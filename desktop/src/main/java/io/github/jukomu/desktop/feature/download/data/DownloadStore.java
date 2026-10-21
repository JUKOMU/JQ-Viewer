package io.github.jukomu.desktop.feature.download.data;

import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.util.JsonUtils;

import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 事务化保存下载任务、图片清单和启动恢复状态。
 */
public final class DownloadStore {
    private static final String ACTIVE_STATUSES = "'queued','downloading','paused','verifying'";

    private final Connection connection;

    public DownloadStore(Database database) {
        this.connection = database.openIsolatedConnection();
    }

    public synchronized StoredDownloadTask findTask(String taskId) {
        try (PreparedStatement statement = connection.prepareStatement(
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
        try (PreparedStatement statement = connection.prepareStatement(
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

    /**
     * 旧版本记录只保存单作者时，补齐作者数组并回写，保证导出快照稳定。
     */
    public synchronized StoredDownloadTask completeMetadata(StoredDownloadTask task) {
        if (task == null) return null;
        String authors = task.authorsJson();
        if (authors != null && !authors.isBlank() && !"[]".equals(authors.trim())) return task;
        String author = task.author() == null ? "" : task.author().trim();
        if (author.isEmpty()) return task;
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE download_tasks SET authors_json=? WHERE task_id=?")) {
            statement.setString(1, JsonUtils.toJsonString(List.of(author), "补全下载任务作者失败"));
            statement.setString(2, task.taskId());
            requireUpdated(statement.executeUpdate(), task.taskId());
            return findTask(task.taskId());
        } catch (SQLException exception) {
            throw failure("补全下载任务元数据失败", exception);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("补全下载任务元数据失败", exception);
        }
    }

    public synchronized List<StoredDownloadTask> listTasks() {
        return list("SELECT * FROM download_tasks ORDER BY created_at DESC");
    }

    public synchronized StoredDownloadTask updateMetadata(String taskId, String albumTitle,
                                                          String chapterTitle, String author, String authorsJson, String tagsJson,
                                                          int chapterSortOrder, boolean singleEpisode) {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE download_tasks SET album_title=?,chapter_title=?,author=?,authors_json=?,"
                + "tags_json=?,chapter_sort_order=?,is_single_episode=? WHERE task_id=?")) {
            statement.setString(1, albumTitle);
            statement.setString(2, chapterTitle);
            statement.setString(3, author == null ? "" : author);
            statement.setString(4, authorsJson);
            statement.setString(5, tagsJson == null || tagsJson.isBlank() ? "[]" : tagsJson);
            statement.setInt(6, chapterSortOrder);
            statement.setInt(7, singleEpisode ? 1 : 0);
            statement.setString(8, taskId);
            requireUpdated(statement.executeUpdate(), taskId);
            return findTask(taskId);
        } catch (SQLException exception) {
            throw failure("补全下载任务元数据失败", exception);
        }
    }

    public synchronized List<StoredDownloadTask> listActiveTasks() {
        return list("SELECT * FROM download_tasks WHERE status IN (" + ACTIVE_STATUSES
            + ") ORDER BY created_at");
    }

    public synchronized TaskPage page(String status, String sort, String direction,
                                      String cursor, int requestedLimit) {
        String normalizedStatus = normalizeStatus(status);
        String normalizedSort = normalizeSort(sort);
        String normalizedDirection = "desc".equalsIgnoreCase(direction) ? "desc" : "asc";
        int limit = Math.max(1, Math.min(50, requestedLimit));
        CursorValue boundary = CursorValue.decode(cursor, normalizedStatus, normalizedSort,
            normalizedDirection);
        String queryDirection = boundary != null && boundary.previous()
            ? flip(normalizedDirection) : normalizedDirection;
        // completed 先按 album 聚合并分页，再展开本页章节，避免分页边界拆开本子。
        if ("completed".equals(normalizedStatus)) {
            return completedPage(normalizedSort, normalizedDirection, queryDirection, boundary, limit);
        }
        String statusSql = "active".equals(normalizedStatus)
            ? "status IN (" + ACTIVE_STATUSES + ")" : "status='failed'";
        String orderValue = orderValue(normalizedSort, "album_title", "author");
        String order = orderClause(orderValue, queryDirection, "task_id", boundary != null && boundary.previous());
        String boundarySql = boundary == null ? "" : " AND " + keysetClause(orderValue,
            queryDirection, "task_id", boundary.previous());
        String sql = "SELECT * FROM download_tasks WHERE " + statusSql + boundarySql
            + " ORDER BY " + order + " LIMIT ?";
        List<StoredDownloadTask> tasks = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindBoundary(statement, boundary, orderValue, queryDirection, 1);
            statement.setInt(index, limit + 1);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) tasks.add(task(rows));
            }
        } catch (SQLException exception) {
            throw failure("读取下载任务分页失败", exception);
        }
        boolean hasNext = tasks.size() > limit;
        if (hasNext) tasks = new ArrayList<>(tasks.subList(0, limit));
        if (boundary != null && boundary.previous()) java.util.Collections.reverse(tasks);
        String next = (boundary != null && boundary.previous() || hasNext) && !tasks.isEmpty()
            ? CursorValue.from(tasks.get(tasks.size() - 1), normalizedSort)
                .encode(false, normalizedStatus, normalizedSort, normalizedDirection) : null;
        String previous = boundary != null && (!boundary.previous() || hasNext) && !tasks.isEmpty()
            ? CursorValue.from(tasks.get(0), normalizedSort)
                .encode(true, normalizedStatus, normalizedSort, normalizedDirection) : null;
        return new TaskPage(tasks, List.of(), next, previous, count(statusSql));
    }

    public synchronized List<StoredDownloadTask> completedChapters(String albumId) {
        return list("SELECT * FROM download_tasks WHERE album_id='" + quote(albumId)
            + "' AND status='completed' ORDER BY chapter_id ASC");
    }

    private Map<String, List<StoredDownloadTask>> completedChaptersByAlbum(List<String> albumIds) {
        Map<String, List<StoredDownloadTask>> result = new LinkedHashMap<>();
        if (albumIds.isEmpty()) return result;
        String placeholders = String.join(",", java.util.Collections.nCopies(albumIds.size(), "?"));
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT * FROM download_tasks WHERE status='completed' AND album_id IN ("
                + placeholders + ") ORDER BY chapter_id ASC")) {
            for (int index = 0; index < albumIds.size(); index++) {
                statement.setString(index + 1, albumIds.get(index));
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    StoredDownloadTask task = task(rows);
                    result.computeIfAbsent(task.albumId(), ignored -> new ArrayList<>()).add(task);
                }
            }
            return result;
        } catch (SQLException exception) {
            throw failure("读取已完成章节失败", exception);
        }
    }

    public synchronized List<String> completedAlbumIds(List<String> albumIds) {
        if (albumIds == null || albumIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(albumIds.size(), "?"));
        List<String> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT DISTINCT album_id FROM download_tasks WHERE status='completed' AND album_id IN ("
                + placeholders + ")")) {
            for (int i = 0; i < albumIds.size(); i++) statement.setString(i + 1, albumIds.get(i));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw failure("读取已完成本子 ID 失败", exception);
        }
    }

    public synchronized List<StoredDownloadTask> taskStatuses(String albumId) {
        return list("SELECT * FROM download_tasks WHERE album_id='" + quote(albumId)
            + "' ORDER BY chapter_id ASC");
    }

    public synchronized List<StoredDownloadTask> activeProgress() {
        return list("SELECT * FROM download_tasks WHERE status IN (" + ACTIVE_STATUSES
            + ") ORDER BY created_at ASC, task_id ASC");
    }

    public synchronized List<String> completedResourceKeys(List<String> albumIds, List<String> chapterIds) {
        if (albumIds == null || chapterIds == null || albumIds.size() != chapterIds.size()) return List.of();
        if (albumIds.isEmpty()) return List.of();
        String predicate = String.join(" OR ", java.util.Collections.nCopies(albumIds.size(),
            "(album_id=? AND chapter_id=?)"));
        Set<String> completed = new java.util.HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT album_id,chapter_id FROM download_tasks WHERE status='completed' AND ("
                + predicate + ")")) {
            for (int index = 0; index < albumIds.size(); index++) {
                statement.setString(index * 2 + 1, albumIds.get(index));
                statement.setString(index * 2 + 2, chapterIds.get(index));
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) completed.add(rows.getString(1) + "|" + rows.getString(2));
            }
        } catch (SQLException exception) {
            throw failure("查询已下载资源键失败", exception);
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < albumIds.size(); i++) {
            String key = albumIds.get(i) + "|" + chapterIds.get(i);
            if (completed.contains(key)) result.add(key);
        }
        return List.copyOf(result);
    }

    public synchronized int countByStatus(String status) {
        if ("completed".equals(status)) {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(DISTINCT album_id) FROM download_tasks WHERE status='completed'" );
                 ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            } catch (SQLException exception) {
                throw failure("统计已完成本子失败", exception);
            }
        }
        return count("active".equals(status) ? "status IN (" + ACTIVE_STATUSES + ")" : "status='failed'");
    }

    public synchronized List<StoredDownloadTask> tasksByStatus(String status) {
        String where = "completed".equals(status) ? "status='completed'" : "failed".equals(status)
            ? "status='failed'" : "status IN (" + ACTIVE_STATUSES + ")";
        return list("SELECT * FROM download_tasks WHERE " + where + " ORDER BY task_id ASC");
    }

    public synchronized DiagnosticSnapshot diagnosticSnapshot(int requestedFailureLimit) {
        int total = 0;
        int active = 0;
        int failed = 0;
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT COUNT(*) AS total,"
                + "SUM(CASE WHEN status IN (" + ACTIVE_STATUSES + ") THEN 1 ELSE 0 END) AS active,"
                + "SUM(CASE WHEN status='failed' THEN 1 ELSE 0 END) AS failed "
                + "FROM download_tasks");
             ResultSet rows = statement.executeQuery()) {
            if (rows.next()) {
                total = rows.getInt("total");
                active = rows.getInt("active");
                failed = rows.getInt("failed");
            }
        } catch (SQLException exception) {
            throw failure("读取下载诊断摘要失败", exception);
        }

        int limit = Math.max(0, Math.min(20, requestedFailureLimit));
        List<DiagnosticFailure> failures = new ArrayList<>();
        if (limit > 0) {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT task_id,album_title,chapter_title,status,error,"
                    + "COALESCE(failed_at,created_at) AS failed_at "
                    + "FROM download_tasks WHERE status='failed' "
                    + "ORDER BY COALESCE(failed_at,created_at) DESC,task_id DESC LIMIT ?")) {
                statement.setInt(1, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String chapterTitle = rows.getString("chapter_title");
                        String albumTitle = rows.getString("album_title");
                        failures.add(new DiagnosticFailure(
                            rows.getString("task_id"),
                            displayTitle(chapterTitle, albumTitle, rows.getString("task_id")),
                            rows.getString("status"),
                            value(rows.getString("error"), "下载失败"),
                            rows.getLong("failed_at")
                        ));
                    }
                }
            } catch (SQLException exception) {
                throw failure("读取下载失败诊断失败", exception);
            }
        }
        return new DiagnosticSnapshot(total, active, failed, List.copyOf(failures));
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
                    + "cover_url=excluded.cover_url, author='', authors_json='[]', tags_json='[]', total_pages=0, "
                    + "downloaded_pages=0, downloaded_bytes=0, first_image_sort_order=NULL, "
                    + "status='queued', error=NULL, total_size=0, chapter_sort_order=0, "
                    + "is_single_episode=NULL, relative_directory=excluded.relative_directory, "
                    + "created_at=excluded.created_at, failed_at=NULL, completed_at=NULL")) {
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
        String authorsJson,
        String tagsJson,
        int chapterSortOrder,
        boolean singleEpisode,
        List<StoredDownloadPage> pages
    ) {
        transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE download_tasks SET total_pages=?, author=?, authors_json=?, tags_json=?, "
                    + "chapter_sort_order=?, is_single_episode=? WHERE task_id=?")) {
                statement.setInt(1, totalPages);
                statement.setString(2, author == null ? "" : author);
                statement.setString(3, authorsJson == null || authorsJson.isBlank() ? "[]" : authorsJson);
                statement.setString(4, tagsJson == null || tagsJson.isBlank() ? "[]" : tagsJson);
                statement.setInt(5, chapterSortOrder);
                statement.setInt(6, singleEpisode ? 1 : 0);
                statement.setString(7, taskId);
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

    public synchronized void saveManifest(
        String taskId, int totalPages, String author, String tagsJson,
        int chapterSortOrder, boolean singleEpisode, List<StoredDownloadPage> pages
    ) {
        saveManifest(taskId, totalPages, author, "[]", tagsJson,
            chapterSortOrder, singleEpisode, pages);
    }

    public synchronized void updateStatus(String taskId, String status, String error) {
        try (PreparedStatement statement = connection.prepareStatement(
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
        try (PreparedStatement statement = connection.prepareStatement(
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
                    + "downloaded_bytes=?, failed_at=NULL, completed_at=? WHERE task_id=?")) {
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
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE download_tasks SET status='failed', downloaded_pages=?, downloaded_bytes=?, "
                + "total_size=?, error=?, failed_at=?, completed_at=NULL WHERE task_id=?")) {
            statement.setInt(1, Math.max(0, downloadedPages));
            statement.setLong(2, Math.max(0, downloadedBytes));
            statement.setLong(3, Math.max(0, totalSize));
            statement.setString(4, error);
            statement.setLong(5, System.currentTimeMillis());
            statement.setString(6, taskId);
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
                    + "failed_at=?, completed_at=NULL, error=? WHERE task_id=?")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, error);
                statement.setString(3, taskId);
                requireUpdated(statement.executeUpdate(), taskId);
            }
            return null;
        }, "恢复中断下载任务失败");
    }

    public synchronized List<StoredDownloadPage> pages(String taskId) {
        List<StoredDownloadPage> pages = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
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
        try (PreparedStatement statement = connection.prepareStatement(
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

    public synchronized int deleteTasksByStatus(List<String> taskIds, String status) {
        return transaction(connection -> {
            int deleted = 0;
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM download_tasks WHERE task_id=? AND status=?")) {
                for (String taskId : taskIds) {
                    deletePages(connection, taskId);
                    statement.setString(1, taskId);
                    statement.setString(2, status);
                    deleted += statement.executeUpdate();
                }
            }
            return deleted;
        }, "批量删除下载任务失败");
    }

    public synchronized void deletePages(String taskId) {
        try {
            deletePages(connection, taskId);
        } catch (SQLException exception) {
            throw failure("删除下载图片记录失败", exception);
        }
    }

    public synchronized int updateAlbumEpisodeType(String albumId, boolean singleEpisode) {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE download_tasks SET is_single_episode=? WHERE album_id=?")) {
            statement.setInt(1, singleEpisode ? 1 : 0);
            statement.setString(2, albumId);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("更新下载章节类型失败", exception);
        }
    }

    private TaskPage completedPage(String sort, String direction, String queryDirection,
                                   CursorValue boundary, int limit) {
        String value = switch (sort) {
            case "title" -> "g.album_title";
            case "id" -> "g.album_id";
            case "author" -> "r.author";
            default -> "g.latest_completed_at";
        };
        String order = orderClause(value, queryDirection, "g.album_id", boundary != null && boundary.previous);
        String boundarySql = boundary == null ? "" : " AND " + keysetClause(value,
            queryDirection, "g.album_id", boundary.previous())
            .replace("task_id", "g.album_id");
        // 代表章节使用最小 chapter_id；author、封面和单章节标记来自同一行。
        String sql = "SELECT g.album_id,g.album_title,g.latest_completed_at,g.total_size,g.rep_chapter_id,"
            + "r.cover_url,r.author,r.authors_json,r.is_single_episode "
            + "FROM (SELECT album_id,MIN(album_title) AS album_title,"
            + "MAX(completed_at) AS latest_completed_at,SUM(total_size) AS total_size,"
            + "MIN(chapter_id) AS rep_chapter_id FROM download_tasks "
            + "WHERE status='completed' GROUP BY album_id) g "
            + "JOIN download_tasks r ON r.album_id=g.album_id AND r.chapter_id=g.rep_chapter_id "
            + "AND r.status='completed' WHERE 1=1" + boundarySql
            + " ORDER BY " + order.replace("task_id", "g.album_id") + " LIMIT ?";
        List<AlbumRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindBoundary(statement, boundary, value, queryDirection, 1);
            statement.setInt(index, limit + 1);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(new AlbumRow(
                    result.getString("album_id"), result.getString("album_title"),
                    result.getLong("latest_completed_at"), result.getLong("total_size"),
                    result.getString("rep_chapter_id"), result.getString("cover_url"),
                    result.getString("author"), result.getString("authors_json"),
                    nullableInt(result, "is_single_episode")));
            }
        } catch (SQLException exception) {
            throw failure("读取已完成本子分页失败", exception);
        }
        boolean hasNext = rows.size() > limit;
        if (hasNext) rows = new ArrayList<>(rows.subList(0, limit));
        if (boundary != null && boundary.previous()) java.util.Collections.reverse(rows);
        Map<String, List<StoredDownloadTask>> chaptersByAlbum = completedChaptersByAlbum(
            rows.stream().map(AlbumRow::albumId).toList());
        List<CompletedAlbum> albums = new ArrayList<>();
        for (AlbumRow row : rows) {
            List<StoredDownloadTask> chapters = chaptersByAlbum.getOrDefault(row.albumId(), List.of());
            albums.add(new CompletedAlbum(row.albumId(), row.albumTitle(), row.coverUrl(),
                row.author(), row.singleEpisode() == null ? null : row.singleEpisode() == 1,
                chapters, row.totalSize(), row.latestCompletedAt()));
        }
        String next = (boundary != null && boundary.previous() || hasNext) && !rows.isEmpty()
            ? rows.get(rows.size() - 1).cursor(sort)
                .encode(false, "completed", sort, direction) : null;
        String previous = boundary != null && (!boundary.previous() || hasNext) && !rows.isEmpty()
            ? rows.get(0).cursor(sort).encode(true, "completed", sort, direction) : null;
        return new TaskPage(List.of(), albums, next, previous, countByStatus("completed"));
    }

    private int count(String where) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM download_tasks WHERE " + where);
             ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getInt(1) : 0;
        } catch (SQLException exception) {
            throw failure("统计下载任务失败", exception);
        }
    }

    private static String normalizeStatus(String status) {
        if ("completed".equals(status) || "failed".equals(status) || "active".equals(status)) return status;
        throw new IllegalArgumentException("status 无效");
    }

    private static String normalizeSort(String sort) {
        if ("title".equals(sort) || "id".equals(sort) || "author".equals(sort) || "time".equals(sort)) return sort;
        throw new IllegalArgumentException("sort 无效");
    }

    private static String orderValue(String sort, String titleColumn, String authorColumn) {
        return switch (sort) {
            case "title" -> titleColumn;
            case "id" -> "album_id";
            case "author" -> authorColumn;
            default -> "created_at";
        };
    }

    private static String orderClause(String value, String direction, String stable, boolean reverseNullRank) {
        String nulls = "CASE WHEN " + value + " IS NULL OR TRIM(" + value + ")='' THEN 1 ELSE 0 END ASC";
        if (reverseNullRank) nulls = nulls.replace(" ASC", " DESC");
        String valueOrder = "desc".equals(direction) ? value + " DESC" : value + " ASC";
        return nulls + "," + valueOrder + "," + stable + ("desc".equals(direction) ? " DESC" : " ASC");
    }

    private static String keysetClause(String value, String direction, String stable,
                                       boolean reverseNullRank) {
        String comparison = "desc".equals(direction) ? "<" : ">";
        String nullComparison = reverseNullRank ? "<" : ">";
        // NULL 与空字符串共享 null-rank；NULL 没有可比较值时，直接使用稳定键继续分页。
        String rank = "CASE WHEN " + value + " IS NULL OR TRIM(" + value + ")='' THEN 1 ELSE 0 END";
        return "(" + rank + " " + nullComparison + " ? OR (" + rank + "=? AND "
            + "((" + rank + "=0 AND " + value + " " + comparison + " ?)"
            + " OR (" + rank + "=1 AND " + stable + " " + comparison + " ?))))";
    }

    private static int bindBoundary(PreparedStatement statement, CursorValue boundary, String value,
                                    String direction, int index) throws SQLException {
        if (boundary == null) return index;
        int nullFlag = boundary.value() == null || boundary.value().isBlank() ? 1 : 0;
        statement.setInt(index++, nullFlag);
        statement.setInt(index++, nullFlag);
        statement.setString(index++, boundary.value());
        statement.setString(index++, boundary.stable());
        return index;
    }

    private static String quote(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private static String flip(String direction) {
        return "desc".equals(direction) ? "asc" : "desc";
    }

    public record TaskPage(List<StoredDownloadTask> tasks, List<CompletedAlbum> albums,
                           String nextCursor, String previousCursor, int totalCount) {
    }

    public record CompletedAlbum(String albumId, String albumTitle, String coverUrl, String author,
                                 Boolean singleEpisode, List<StoredDownloadTask> chapters,
                                 long totalSize, long completedAt) {
    }

    private record AlbumRow(String albumId, String albumTitle, long latestCompletedAt, long totalSize,
                            String repChapterId, String coverUrl, String author, String authorsJson,
                            Integer singleEpisode) {
        CursorValue cursor(String sort) {
            String value = switch (sort) {
                case "title" -> albumTitle;
                case "id" -> albumId;
                case "author" -> author;
                default -> Long.toString(latestCompletedAt);
            };
            return new CursorValue(value, albumId);
        }
    }

    private record CursorValue(String value, String stable, boolean previous) {
        CursorValue(String value, String stable) { this(value, stable, false); }
        String encode(boolean previous, String status, String sort, String direction) {
            String raw = status + "\u001f" + sort + "\u001f" + direction + "\u001f"
                + (previous ? "prev" : "next") + "\u001f" + value + "\u001f" + stable;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
        // cursor 绑定查询条件；prev 查询反转数据库方向后再恢复返回顺序。
        static CursorValue decode(String cursor, String status, String sort, String direction) {
            if (cursor == null || cursor.isBlank()) return null;
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\u001f", -1);
            if (parts.length != 6 || !status.equals(parts[0]) || !sort.equals(parts[1])
                || !direction.equals(parts[2])) throw new IllegalArgumentException("cursor 无效");
            return new CursorValue(parts[4], parts[5], "prev".equals(parts[3]));
        }
        static CursorValue from(StoredDownloadTask task, String sort) {
            String value = switch (sort) {
                case "title" -> task.albumTitle();
                case "id" -> task.albumId();
                case "author" -> task.author();
                default -> Long.toString(task.createdAt());
            };
            return new CursorValue(value, task.taskId());
        }
    }

    private List<StoredDownloadTask> list(String sql) {
        List<StoredDownloadTask> tasks = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) tasks.add(task(rows));
            return List.copyOf(tasks);
        } catch (SQLException exception) {
            throw failure("读取下载任务列表失败", exception);
        }
    }

    private <T> T transaction(SqlOperation<T> operation, String message) {
        Connection connection = this.connection;
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
            rows.getString("author"), rows.getString("authors_json"), rows.getString("tags_json"),
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

    private static String displayTitle(String preferred, String fallback, String id) {
        if (preferred != null && !preferred.isBlank()) return preferred;
        if (fallback != null && !fallback.isBlank()) return fallback;
        return id;
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record DiagnosticSnapshot(
        int total,
        int active,
        int failed,
        List<DiagnosticFailure> recentFailures
    ) {
    }

    public record DiagnosticFailure(
        String id,
        String title,
        String status,
        String reason,
        long updatedAt
    ) {
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run(Connection connection) throws Exception;
    }
}
