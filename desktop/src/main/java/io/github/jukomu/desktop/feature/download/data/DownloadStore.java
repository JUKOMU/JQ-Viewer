package io.github.jukomu.desktop.feature.download.data;

import io.github.jukomu.desktop.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/** 事务化保存下载任务、图片清单和启动恢复状态。 */
public final class DownloadStore {
    private static final String ACTIVE_STATUSES = "'queued','downloading','paused','verifying'";

    private final Database database;

    public DownloadStore(Database database) {
        this.database = database;
    }

    public synchronized StoredDownloadTask findTask(String taskId) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT * FROM download_tasks WHERE task_id = ?")) {
            statement.setString(1, taskId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? task(rows) : null;
            }
        } catch (SQLException exception) {
            throw failure("读取下载任务失败", exception);
        }
    }

    public synchronized StoredDownloadTask findTask(String albumId, String chapterId) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT * FROM download_tasks WHERE album_id = ? AND chapter_id = ? "
                        + "ORDER BY created_at DESC LIMIT 1")) {
            statement.setString(1, albumId);
            statement.setString(2, chapterId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? task(rows) : null;
            }
        } catch (SQLException exception) {
            throw failure("读取下载章节失败", exception);
        }
    }

    public synchronized List<StoredDownloadTask> listTasks() {
        return list("SELECT * FROM download_tasks ORDER BY created_at DESC");
    }

    public synchronized List<StoredDownloadTask> listActiveTasks() {
        return list("SELECT * FROM download_tasks WHERE status IN (" + ACTIVE_STATUSES
                + ") ORDER BY created_at");
    }

    public synchronized void createOrResetTask(
            String taskId,
            String albumId,
            String chapterId,
            String albumTitle,
            String chapterTitle,
            String coverUrl,
            String relativeDirectory,
            long createdAt
    ) {
        transaction(connection -> {
            deletePages(connection, taskId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO download_tasks(task_id, album_id, chapter_id, album_title, "
                            + "chapter_title, cover_url, status, relative_directory, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'queued', ?, ?) "
                            + "ON CONFLICT(task_id) DO UPDATE SET "
                            + "album_id=excluded.album_id, chapter_id=excluded.chapter_id, "
                            + "album_title=excluded.album_title, chapter_title=excluded.chapter_title, "
                            + "cover_url=excluded.cover_url, author='', tags_json='[]', total_pages=0, "
                            + "downloaded_pages=0, downloaded_bytes=0, first_image_sort_order=NULL, "
                            + "status='queued', error=NULL, total_size=0, chapter_sort_order=0, "
                            + "is_single_episode=NULL, relative_directory=excluded.relative_directory, "
                            + "created_at=excluded.created_at, completed_at=NULL")) {
                statement.setString(1, taskId);
                statement.setString(2, albumId);
                statement.setString(3, chapterId);
                statement.setString(4, albumTitle);
                statement.setString(5, chapterTitle);
                statement.setString(6, coverUrl);
                statement.setString(7, relativeDirectory);
                statement.setLong(8, createdAt);
                statement.executeUpdate();
            }
            return null;
        }, "创建下载任务失败");
    }

    public synchronized void saveManifest(
            String taskId,
            int totalPages,
            String author,
            String tagsJson,
            int chapterSortOrder,
            boolean singleEpisode,
            List<StoredDownloadPage> pages
    ) {
        transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE download_tasks SET total_pages=?, author=?, tags_json=?, "
                            + "chapter_sort_order=?, is_single_episode=? WHERE task_id=?")) {
                statement.setInt(1, totalPages);
                statement.setString(2, author);
                statement.setString(3, tagsJson);
                statement.setInt(4, chapterSortOrder);
                statement.setInt(5, singleEpisode ? 1 : 0);
                statement.setString(6, taskId);
                requireUpdated(statement.executeUpdate(), taskId);
            }
            deletePages(connection, taskId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO download_pages(task_id, sort_order, photo_id, filename, "
                            + "relative_path, source_url, scramble_id, query_params, completed) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)")) {
                for (StoredDownloadPage page : pages) {
                    statement.setString(1, taskId);
                    statement.setInt(2, page.sortOrder());
                    statement.setString(3, page.photoId());
                    statement.setString(4, page.filename());
                    statement.setString(5, page.relativePath());
                    statement.setString(6, page.sourceUrl());
                    statement.setString(7, page.scrambleId());
                    statement.setString(8, page.queryParams());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        }, "保存下载图片清单失败");
    }

    public synchronized void updateStatus(String taskId, String status, String error) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "UPDATE download_tasks SET status=?, error=? WHERE task_id=?")) {
            statement.setString(1, status);
            if (error == null) statement.setNull(2, Types.VARCHAR);
            else statement.setString(2, error);
            statement.setString(3, taskId);
            requireUpdated(statement.executeUpdate(), taskId);
        } catch (SQLException exception) {
            throw failure("更新下载状态失败", exception);
        }
    }

    public synchronized void updateProgress(String taskId, int pages, long bytes) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "UPDATE download_tasks SET downloaded_pages=?, downloaded_bytes=? WHERE task_id=?")) {
            statement.setInt(1, pages);
            statement.setLong(2, Math.max(0, bytes));
            statement.setString(3, taskId);
            requireUpdated(statement.executeUpdate(), taskId);
        } catch (SQLException exception) {
            throw failure("更新下载进度失败", exception);
        }
    }

    public synchronized void complete(String taskId, int totalPages, int firstSortOrder,
                                      long totalSize, long completedAt) {
        transaction(connection -> {
            try (PreparedStatement pages = connection.prepareStatement(
                    "UPDATE download_pages SET completed=1 WHERE task_id=?")) {
                pages.setString(1, taskId);
                pages.executeUpdate();
            }
            try (PreparedStatement task = connection.prepareStatement(
                    "UPDATE download_tasks SET status='completed', error=NULL, "
                            + "downloaded_pages=?, first_image_sort_order=?, total_size=?, "
                            + "downloaded_bytes=?, completed_at=? WHERE task_id=?")) {
                task.setInt(1, totalPages);
                task.setInt(2, firstSortOrder);
                task.setLong(3, totalSize);
                task.setLong(4, totalSize);
                task.setLong(5, completedAt);
                task.setString(6, taskId);
                requireUpdated(task.executeUpdate(), taskId);
            }
            return null;
        }, "完成下载任务失败");
    }

    public synchronized void fail(String taskId, int downloadedPages, long downloadedBytes,
                                  long totalSize, String error) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "UPDATE download_tasks SET status='failed', downloaded_pages=?, downloaded_bytes=?, "
                        + "total_size=?, error=?, completed_at=NULL WHERE task_id=?")) {
            statement.setInt(1, Math.max(0, downloadedPages));
            statement.setLong(2, Math.max(0, downloadedBytes));
            statement.setLong(3, Math.max(0, totalSize));
            statement.setString(4, error);
            statement.setString(5, taskId);
            requireUpdated(statement.executeUpdate(), taskId);
        } catch (SQLException exception) {
            throw failure("记录下载失败状态失败", exception);
        }
    }

    public synchronized void interrupt(String taskId, String error) {
        transaction(connection -> {
            deletePages(connection, taskId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE download_tasks SET status='failed', downloaded_pages=0, "
                            + "downloaded_bytes=0, total_size=0, first_image_sort_order=NULL, "
                            + "completed_at=NULL, error=? WHERE task_id=?")) {
                statement.setString(1, error);
                statement.setString(2, taskId);
                requireUpdated(statement.executeUpdate(), taskId);
            }
            return null;
        }, "恢复中断下载任务失败");
    }

    public synchronized List<StoredDownloadPage> pages(String taskId) {
        List<StoredDownloadPage> pages = new ArrayList<>();
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT * FROM download_pages WHERE task_id=? ORDER BY sort_order")) {
            statement.setString(1, taskId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) pages.add(page(rows));
            }
            return List.copyOf(pages);
        } catch (SQLException exception) {
            throw failure("读取下载图片清单失败", exception);
        }
    }

    public synchronized StoredDownloadPage findCompletedPage(String photoId, int sortOrder) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT p.* FROM download_pages p JOIN download_tasks t ON t.task_id=p.task_id "
                        + "WHERE p.photo_id=? AND p.sort_order=? AND p.completed=1 "
                        + "AND t.status='completed' ORDER BY t.completed_at DESC LIMIT 1")) {
            statement.setString(1, photoId);
            statement.setInt(2, sortOrder);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? page(rows) : null;
            }
        } catch (SQLException exception) {
            throw failure("读取离线图片记录失败", exception);
        }
    }

    public synchronized void deleteTask(String taskId) {
        transaction(connection -> {
            deletePages(connection, taskId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM download_tasks WHERE task_id=?")) {
                statement.setString(1, taskId);
                statement.executeUpdate();
            }
            return null;
        }, "删除下载任务失败");
    }

    public synchronized void deletePages(String taskId) {
        try {
            deletePages(database.connection(), taskId);
        } catch (SQLException exception) {
            throw failure("删除下载图片记录失败", exception);
        }
    }

    private List<StoredDownloadTask> list(String sql) {
        List<StoredDownloadTask> tasks = new ArrayList<>();
        try (PreparedStatement statement = database.connection().prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) tasks.add(task(rows));
            return List.copyOf(tasks);
        } catch (SQLException exception) {
            throw failure("读取下载任务列表失败", exception);
        }
    }

    private <T> T transaction(SqlOperation<T> operation, String message) {
        Connection connection = database.connection();
        boolean autoCommit;
        try {
            autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = operation.run(connection);
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

    private static StoredDownloadTask task(ResultSet rows) throws SQLException {
        Integer firstSortOrder = nullableInt(rows, "first_image_sort_order");
        Integer single = nullableInt(rows, "is_single_episode");
        Long completedAt = nullableLong(rows, "completed_at");
        return new StoredDownloadTask(
                rows.getString("task_id"), rows.getString("album_id"),
                rows.getString("chapter_id"), rows.getString("album_title"),
                rows.getString("chapter_title"), rows.getString("cover_url"),
                rows.getString("author"), rows.getString("tags_json"),
                rows.getInt("total_pages"), rows.getInt("downloaded_pages"),
                rows.getLong("downloaded_bytes"), firstSortOrder,
                rows.getString("status"), rows.getString("error"),
                rows.getLong("total_size"), rows.getInt("chapter_sort_order"),
                single == null ? null : single == 1, rows.getString("relative_directory"),
                rows.getLong("created_at"), completedAt
        );
    }

    private static StoredDownloadPage page(ResultSet rows) throws SQLException {
        return new StoredDownloadPage(
                rows.getString("task_id"), rows.getInt("sort_order"),
                rows.getString("photo_id"), rows.getString("filename"),
                rows.getString("relative_path"), rows.getString("source_url"),
                rows.getString("scramble_id"), rows.getString("query_params"),
                rows.getInt("completed") == 1
        );
    }

    private static Integer nullableInt(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static void deletePages(Connection connection, String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM download_pages WHERE task_id=?")) {
            statement.setString(1, taskId);
            statement.executeUpdate();
        }
    }

    private static void requireUpdated(int count, String taskId) {
        if (count != 1) throw new IllegalStateException("下载任务不存在: " + taskId);
    }

    private static IllegalStateException failure(String message, SQLException exception) {
        return new IllegalStateException(message, exception);
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run(Connection connection) throws Exception;
    }
}
