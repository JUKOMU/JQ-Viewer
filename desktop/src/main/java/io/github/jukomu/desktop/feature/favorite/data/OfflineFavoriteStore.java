package io.github.jukomu.desktop.feature.favorite.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.feature.favorite.model.OfflineFavoriteFolder;
import io.github.jukomu.desktop.feature.favorite.model.OfflineFavoriteItem;
import io.github.jukomu.desktop.feature.favorite.model.OfflineFavoritePageResponse;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 持久化 Desktop 离线收藏夹、条目和操作备份。 */
public final class OfflineFavoriteStore {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<OfflineFavoriteItem>> ITEM_LIST = new TypeReference<>() {
    };

    private final Database database;
    private final ObjectMapper mapper;

    public OfflineFavoriteStore(Database database, ObjectMapper mapper) {
        this.database = database;
        this.mapper = mapper;
    }

    public synchronized List<OfflineFavoriteFolder> folders() {
        List<OfflineFavoriteFolder> folders = new ArrayList<>();
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT f.folder_id, f.name, COUNT(i.id) "
                        + "FROM offline_folders f "
                        + "LEFT JOIN offline_favorites i ON i.folder_id = f.folder_id "
                        + "GROUP BY f.folder_id, f.name, f.created_at "
                        + "ORDER BY f.created_at ASC, f.folder_id ASC");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                folders.add(new OfflineFavoriteFolder(
                        rows.getString(1), rows.getString(2), rows.getLong(3)));
            }
            return List.copyOf(folders);
        } catch (SQLException exception) {
            throw failure("读取离线收藏夹失败", exception);
        }
    }

    public synchronized String createFolder(String name) {
        String folderId = newFolderId();
        try (PreparedStatement statement = database.connection().prepareStatement(
                "INSERT INTO offline_folders(folder_id, name, created_at) VALUES (?, ?, ?)")) {
            statement.setString(1, folderId);
            statement.setString(2, name);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
            return folderId;
        } catch (SQLException exception) {
            throw failure("创建离线收藏夹失败", exception);
        }
    }

    public synchronized boolean renameFolder(String folderId, String name) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "UPDATE offline_folders SET name = ? WHERE folder_id = ?")) {
            statement.setString(1, name);
            statement.setString(2, folderId);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw failure("重命名离线收藏夹失败", exception);
        }
    }

    public synchronized boolean deleteFolder(String folderId) {
        return transaction("删除离线收藏夹失败", connection -> {
            try (PreparedStatement items = connection.prepareStatement(
                    "DELETE FROM offline_favorites WHERE folder_id = ?");
                 PreparedStatement folder = connection.prepareStatement(
                         "DELETE FROM offline_folders WHERE folder_id = ?")) {
                items.setString(1, folderId);
                items.executeUpdate();
                folder.setString(1, folderId);
                return folder.executeUpdate() > 0;
            }
        });
    }

    public synchronized boolean addItem(String folderId, OfflineFavoriteItem item) {
        if (!folderExists(database.connection(), folderId)) return false;
        StoredItem stored = stored(item);
        try (PreparedStatement statement = database.connection().prepareStatement(
                "INSERT OR IGNORE INTO offline_favorites("
                        + "folder_id, album_id, title, cover_url, authors_json, tags_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            bindItem(statement, folderId, stored);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw failure("添加离线收藏失败", exception);
        }
    }

    public synchronized boolean removeItem(String folderId, String albumId) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "DELETE FROM offline_favorites WHERE folder_id = ? AND album_id = ?")) {
            statement.setString(1, folderId);
            statement.setString(2, albumId);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw failure("移除离线收藏失败", exception);
        }
    }

    public synchronized OfflineFavoritePageResponse page(
            String folderId,
            String keyword,
            int requestedPage,
            int pageSize
    ) {
        String normalizedKeyword = keyword == null || keyword.isEmpty() ? null : keyword;
        String where = normalizedKeyword == null
                ? "folder_id = ?"
                : "folder_id = ? AND title LIKE ?";
        long totalItems = count("SELECT COUNT(*) FROM offline_favorites WHERE " + where,
                folderId, normalizedKeyword);
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / pageSize));
        int currentPage = Math.min(Math.max(1, requestedPage), totalPages);
        int offset = (currentPage - 1) * pageSize;
        List<OfflineFavoriteItem> content = new ArrayList<>();
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT album_id, title, cover_url, authors_json, tags_json "
                        + "FROM offline_favorites WHERE " + where
                        + " ORDER BY id ASC LIMIT ? OFFSET ?")) {
            int index = bindFolderAndKeyword(statement, folderId, normalizedKeyword);
            statement.setInt(index++, pageSize);
            statement.setInt(index, offset);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) content.add(readItem(rows));
            }
            return new OfflineFavoritePageResponse(
                    totalItems, totalPages, currentPage, List.copyOf(content));
        } catch (SQLException exception) {
            throw failure("读取离线收藏分页失败", exception);
        }
    }

    public synchronized List<OfflineFavoriteItem> allItems(String folderId) {
        return items("SELECT album_id, title, cover_url, authors_json, tags_json "
                + "FROM offline_favorites WHERE folder_id = ? ORDER BY id ASC", folderId);
    }

    public synchronized long totalCount() {
        return count("SELECT COUNT(DISTINCT album_id) FROM offline_favorites", null, null);
    }

    public synchronized List<OfflineFavoriteItem> allItemsMerged() {
        List<OfflineFavoriteItem> items = new ArrayList<>();
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT album_id, title, cover_url, authors_json, tags_json "
                        + "FROM offline_favorites WHERE id IN ("
                        + "SELECT MIN(id) FROM offline_favorites GROUP BY album_id) "
                        + "ORDER BY id ASC");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) items.add(readItem(rows));
            return List.copyOf(items);
        } catch (SQLException exception) {
            throw failure("读取合并后的离线收藏失败", exception);
        }
    }

    public synchronized boolean moveAllItems(String sourceId, String targetId) {
        return transaction("移动离线收藏失败", connection -> {
            if (sourceId.equals(targetId)
                    || !folderExists(connection, sourceId)
                    || !folderExists(connection, targetId)) {
                return false;
            }
            try (PreparedStatement copy = connection.prepareStatement(
                    "INSERT OR IGNORE INTO offline_favorites("
                            + "folder_id, album_id, title, cover_url, authors_json, tags_json) "
                            + "SELECT ?, album_id, title, cover_url, authors_json, tags_json "
                            + "FROM offline_favorites WHERE folder_id = ? ORDER BY id ASC");
                 PreparedStatement delete = connection.prepareStatement(
                         "DELETE FROM offline_favorites WHERE folder_id = ?")) {
                copy.setString(1, targetId);
                copy.setString(2, sourceId);
                copy.executeUpdate();
                delete.setString(1, sourceId);
                delete.executeUpdate();
                return true;
            }
        });
    }

    public synchronized String copyFolder(String sourceId, String targetName) {
        return transaction("复制离线收藏夹失败", connection -> {
            if (!folderExists(connection, sourceId)) return "";
            String folderId = newFolderId();
            try (PreparedStatement folder = connection.prepareStatement(
                    "INSERT INTO offline_folders(folder_id, name, created_at) VALUES (?, ?, ?)");
                 PreparedStatement items = connection.prepareStatement(
                         "INSERT INTO offline_favorites("
                                 + "folder_id, album_id, title, cover_url, authors_json, tags_json) "
                                 + "SELECT ?, album_id, title, cover_url, authors_json, tags_json "
                                 + "FROM offline_favorites WHERE folder_id = ? ORDER BY id ASC")) {
                folder.setString(1, folderId);
                folder.setString(2, targetName);
                folder.setLong(3, System.currentTimeMillis());
                folder.executeUpdate();
                items.setString(1, folderId);
                items.setString(2, sourceId);
                items.executeUpdate();
                return folderId;
            }
        });
    }

    public synchronized int addItemsBatch(String folderId, List<OfflineFavoriteItem> items) {
        List<StoredItem> storedItems = items.stream().map(this::stored).toList();
        return transaction("批量添加离线收藏失败", connection -> {
            if (!folderExists(connection, folderId)) return 0;
            int added = 0;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR IGNORE INTO offline_favorites("
                            + "folder_id, album_id, title, cover_url, authors_json, tags_json) "
                            + "VALUES (?, ?, ?, ?, ?, ?)")) {
                for (StoredItem item : storedItems) {
                    bindItem(statement, folderId, item);
                    added += statement.executeUpdate();
                }
            }
            return added;
        });
    }

    public synchronized boolean mergeAllToFolder(String targetId) {
        return transaction("合并离线收藏夹失败", connection -> {
            if (!folderExists(connection, targetId)) return false;
            try (PreparedStatement merge = connection.prepareStatement(
                    "INSERT OR IGNORE INTO offline_favorites("
                            + "folder_id, album_id, title, cover_url, authors_json, tags_json) "
                            + "SELECT ?, item.album_id, item.title, item.cover_url, "
                            + "item.authors_json, item.tags_json "
                            + "FROM offline_favorites item WHERE item.id IN ("
                            + "SELECT MIN(id) FROM offline_favorites GROUP BY album_id)");
                 PreparedStatement clear = connection.prepareStatement(
                         "DELETE FROM offline_favorites WHERE folder_id != ?")) {
                merge.setString(1, targetId);
                merge.executeUpdate();
                clear.setString(1, targetId);
                clear.executeUpdate();
                return true;
            }
        });
    }

    public synchronized void saveBackup(String key, List<OfflineFavoriteItem> items) {
        String itemsJson = writeJson(items);
        try (PreparedStatement statement = database.connection().prepareStatement(
                "INSERT INTO offline_backups(backup_key, items_json, created_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT(backup_key) DO UPDATE SET "
                        + "items_json = excluded.items_json, created_at = excluded.created_at")) {
            statement.setString(1, key);
            statement.setString(2, itemsJson);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("保存离线收藏备份失败", exception);
        }
    }

    public synchronized List<OfflineFavoriteItem> loadBackup(String key) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT items_json FROM offline_backups WHERE backup_key = ?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return List.copyOf(mapper.readValue(rows.getString(1), ITEM_LIST));
            }
        } catch (SQLException | IOException exception) {
            throw failure("读取离线收藏备份失败", exception);
        }
    }

    public synchronized boolean deleteBackup(String key) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "DELETE FROM offline_backups WHERE backup_key = ?")) {
            statement.setString(1, key);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw failure("删除离线收藏备份失败", exception);
        }
    }

    public synchronized List<String> backupKeys() {
        List<String> keys = new ArrayList<>();
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT backup_key FROM offline_backups "
                        + "ORDER BY created_at DESC, backup_key DESC");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) keys.add(rows.getString(1));
            return List.copyOf(keys);
        } catch (SQLException exception) {
            throw failure("读取离线收藏备份列表失败", exception);
        }
    }

    private List<OfflineFavoriteItem> items(String sql, String folderId) {
        List<OfflineFavoriteItem> items = new ArrayList<>();
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            statement.setString(1, folderId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) items.add(readItem(rows));
            }
            return List.copyOf(items);
        } catch (SQLException exception) {
            throw failure("读取离线收藏失败", exception);
        }
    }

    private long count(String sql, String folderId, String keyword) {
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            if (folderId != null) bindFolderAndKeyword(statement, folderId, keyword);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        } catch (SQLException exception) {
            throw failure("读取离线收藏数量失败", exception);
        }
    }

    private static int bindFolderAndKeyword(
            PreparedStatement statement,
            String folderId,
            String keyword
    ) throws SQLException {
        statement.setString(1, folderId);
        if (keyword != null) {
            statement.setString(2, "%" + keyword + "%");
            return 3;
        }
        return 2;
    }

    private static boolean folderExists(Connection connection, String folderId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM offline_folders WHERE folder_id = ?")) {
            statement.setString(1, folderId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException exception) {
            throw failure("检查离线收藏夹失败", exception);
        }
    }

    private OfflineFavoriteItem readItem(ResultSet rows) throws SQLException {
        try {
            return new OfflineFavoriteItem(
                    rows.getString(1),
                    rows.getString(2),
                    rows.getString(3),
                    List.copyOf(mapper.readValue(rows.getString(4), STRING_LIST)),
                    List.copyOf(mapper.readValue(rows.getString(5), STRING_LIST))
            );
        } catch (IOException exception) {
            throw failure("离线收藏条目数据损坏", exception);
        }
    }

    private StoredItem stored(OfflineFavoriteItem item) {
        return new StoredItem(
                item.id(), item.title(), item.coverUrl(),
                writeJson(item.authors()), writeJson(item.tags()));
    }

    private static void bindItem(
            PreparedStatement statement,
            String folderId,
            StoredItem item
    ) throws SQLException {
        statement.setString(1, folderId);
        statement.setString(2, item.id());
        statement.setString(3, item.title());
        statement.setString(4, item.coverUrl());
        statement.setString(5, item.authorsJson());
        statement.setString(6, item.tagsJson());
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw failure("序列化离线收藏数据失败", exception);
        }
    }

    private <T> T transaction(String message, SqlTask<T> task) {
        Connection connection = database.connection();
        try {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = task.run(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof SQLException sqlException) throw sqlException;
                if (exception instanceof RuntimeException runtimeException) throw runtimeException;
                throw new SQLException(exception);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException exception) {
            throw failure(message, exception);
        }
    }

    private static String newFolderId() {
        return "offline_" + UUID.randomUUID();
    }

    private static IllegalStateException failure(String message, Exception exception) {
        return new IllegalStateException(message, exception);
    }

    @FunctionalInterface
    private interface SqlTask<T> {
        T run(Connection connection) throws SQLException;
    }

    private record StoredItem(
            String id,
            String title,
            String coverUrl,
            String authorsJson,
            String tagsJson
    ) {
    }
}
