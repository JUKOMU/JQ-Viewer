package io.github.jukomu.desktop.feature.pdf.export;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.feature.pdf.model.PdfExportTaskResponse;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** 持久化 Desktop PDF 导出队列、章节输入、分卷结果和文件库登记。 */
public final class PdfExportStore {
    private static final String ACTIVE_STATUSES = "'queued','running','cancelling'";
    private static final String TERMINAL_STATUSES =
            "'completed','failed','cancelled','partial','interrupted'";

    private final Database database;

    public PdfExportStore(Database database) {
        this.database = database;
    }

    public synchronized void reserve(ReserveTask task, List<Chapter> chapters, List<Volume> volumes) {
        transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pdf_export_tasks(export_id,batch_id,mode,album_id,album_title,"
                            + "cover_url,authors,is_single_episode,chapter_id,display_title,"
                            + "target_folder_ref,target_name,display_path,allow_overwrite,use_original,"
                            + "compression_ratio,split_pages,status,phase,total_pages,total_volumes,"
                            + "snapshot_revision,cancel_requested,error_code,error_message,created_at,"
                            + "updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,0,?,?,?,?)")) {
                int index = 1;
                statement.setString(index++, task.exportId());
                statement.setString(index++, task.batchId());
                statement.setString(index++, task.mode());
                statement.setString(index++, task.albumId());
                statement.setString(index++, value(task.albumTitle()));
                statement.setString(index++, value(task.coverUrl()));
                statement.setString(index++, value(task.authors()));
                statement.setInt(index++, task.singleEpisode() == null ? -1
                        : task.singleEpisode() ? 1 : 0);
                nullableText(statement, index++, task.chapterId());
                statement.setString(index++, task.displayTitle());
                statement.setString(index++, task.targetFolderRef());
                statement.setString(index++, task.targetName());
                statement.setString(index++, value(task.displayPath()));
                statement.setInt(index++, task.allowOverwrite() ? 1 : 0);
                statement.setInt(index++, task.useOriginal() ? 1 : 0);
                statement.setDouble(index++, task.compressionRatio());
                statement.setInt(index++, task.splitPages());
                statement.setString(index++, task.status());
                statement.setString(index++, task.phase());
                statement.setInt(index++, task.totalPages());
                statement.setInt(index++, volumes.size());
                nullableText(statement, index++, task.errorCode());
                nullableText(statement, index++, task.errorMessage());
                statement.setLong(index++, task.createdAt());
                statement.setLong(index, task.createdAt());
                statement.executeUpdate();
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pdf_export_chapters(export_id,sequence,album_id,chapter_id,"
                            + "chapter_title,sort_order,expected_page_count) VALUES (?,?,?,?,?,?,?)")) {
                for (Chapter chapter : chapters) {
                    statement.setString(1, task.exportId());
                    statement.setInt(2, chapter.sequence());
                    statement.setString(3, chapter.albumId());
                    statement.setString(4, chapter.chapterId());
                    statement.setString(5, value(chapter.chapterTitle()));
                    statement.setInt(6, chapter.sortOrder());
                    statement.setInt(7, chapter.expectedPageCount());
                    statement.addBatch();
                }
                statement.executeBatch();
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pdf_export_volumes(export_id,volume_index,start_page,end_page,"
                            + "expected_page_count,target_name,display_path,temp_path,status,updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,'pending',?)")) {
                for (Volume volume : volumes) {
                    statement.setString(1, task.exportId());
                    statement.setInt(2, volume.volumeIndex());
                    statement.setInt(3, volume.startPage());
                    statement.setInt(4, volume.endPage());
                    statement.setInt(5, volume.expectedPageCount());
                    statement.setString(6, volume.targetName());
                    statement.setString(7, volume.displayPath());
                    statement.setString(8, volume.tempPath());
                    statement.setLong(9, task.createdAt());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        }, "保存 PDF 导出任务失败");
    }

    public synchronized PdfExportTaskResponse find(String exportId) {
        try (PreparedStatement statement = database.connection().prepareStatement(taskSelect()
                + " WHERE t.export_id=?")) {
            statement.setString(1, exportId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? task(rows) : null;
            }
        } catch (SQLException exception) {
            throw failure("读取 PDF 导出任务失败", exception);
        }
    }

    public synchronized Page list(String status, String cursor, int requestedLimit) {
        int limit = Math.max(1, Math.min(100, requestedLimit));
        CursorPosition position = CursorPosition.parse(cursor);
        List<String> clauses = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            clauses.add("t.status=?");
            arguments.add(status);
        }
        if (position != null) {
            clauses.add("(t.updated_at<? OR (t.updated_at=? AND t.export_id<?))");
            arguments.add(position.updatedAt());
            arguments.add(position.updatedAt());
            arguments.add(position.exportId());
        }
        String sql = taskSelect()
                + (clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses))
                + " ORDER BY t.updated_at DESC,t.export_id DESC LIMIT ?";
        List<PdfExportTaskResponse> tasks = new ArrayList<>();
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            int index = 1;
            for (Object argument : arguments) {
                if (argument instanceof Long number) statement.setLong(index++, number);
                else statement.setString(index++, String.valueOf(argument));
            }
            statement.setInt(index, limit + 1);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) tasks.add(task(rows));
            }
        } catch (SQLException exception) {
            throw failure("读取 PDF 导出任务列表失败", exception);
        }
        String nextCursor = null;
        if (tasks.size() > limit) {
            tasks.remove(tasks.size() - 1);
            PdfExportTaskResponse last = tasks.get(tasks.size() - 1);
            nextCursor = CursorPosition.encode(last.updatedAt(), last.exportId());
        }
        return new Page(List.copyOf(tasks), nextCursor);
    }

    public synchronized DiagnosticSnapshot diagnosticSnapshot(int requestedFailureLimit) {
        int total = 0;
        int active = 0;
        int failed = 0;
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT COUNT(*) AS total,"
                        + "SUM(CASE WHEN status IN (" + ACTIVE_STATUSES + ") THEN 1 ELSE 0 END) AS active,"
                        + "SUM(CASE WHEN status IN ('failed','partial','interrupted') THEN 1 ELSE 0 END) AS failed "
                        + "FROM pdf_export_tasks");
             ResultSet rows = statement.executeQuery()) {
            if (rows.next()) {
                total = rows.getInt("total");
                active = rows.getInt("active");
                failed = rows.getInt("failed");
            }
        } catch (SQLException exception) {
            throw failure("读取 PDF 导出诊断摘要失败", exception);
        }

        int limit = Math.max(0, Math.min(20, requestedFailureLimit));
        List<DiagnosticFailure> failures = new ArrayList<>();
        if (limit > 0) {
            try (PreparedStatement statement = database.connection().prepareStatement(
                    "SELECT export_id,display_title,status,error_message,updated_at "
                            + "FROM pdf_export_tasks "
                            + "WHERE status IN ('failed','partial','interrupted') "
                            + "ORDER BY updated_at DESC,export_id DESC LIMIT ?")) {
                statement.setInt(1, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String id = rows.getString("export_id");
                        failures.add(new DiagnosticFailure(
                                id,
                                value(rows.getString("display_title"), id),
                                rows.getString("status"),
                                value(rows.getString("error_message"), "PDF 导出失败"),
                                rows.getLong("updated_at")
                        ));
                    }
                }
            } catch (SQLException exception) {
                throw failure("读取 PDF 导出失败诊断失败", exception);
            }
        }
        return new DiagnosticSnapshot(total, active, failed, List.copyOf(failures));
    }

    public synchronized List<Chapter> chapters(String exportId) {
        List<Chapter> chapters = new ArrayList<>();
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT * FROM pdf_export_chapters WHERE export_id=? ORDER BY sequence")) {
            statement.setString(1, exportId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) chapters.add(new Chapter(
                        rows.getInt("sequence"), rows.getString("album_id"),
                        rows.getString("chapter_id"), rows.getString("chapter_title"),
                        rows.getInt("sort_order"), rows.getInt("expected_page_count")
                ));
            }
            return List.copyOf(chapters);
        } catch (SQLException exception) {
            throw failure("读取 PDF 导出章节失败", exception);
        }
    }

    public synchronized List<Volume> volumes(String exportId) {
        List<Volume> volumes = new ArrayList<>();
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT * FROM pdf_export_volumes WHERE export_id=? ORDER BY volume_index")) {
            statement.setString(1, exportId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) volumes.add(volume(rows));
            }
            return List.copyOf(volumes);
        } catch (SQLException exception) {
            throw failure("读取 PDF 导出分卷失败", exception);
        }
    }

    public synchronized boolean hasActiveChapterConflict(List<Chapter> chapters) {
        String sql = "SELECT 1 FROM pdf_export_chapters c JOIN pdf_export_tasks t "
                + "ON t.export_id=c.export_id WHERE t.status IN (" + ACTIVE_STATUSES + ") "
                + "AND c.album_id=? AND c.chapter_id=? LIMIT 1";
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            for (Chapter chapter : chapters) {
                statement.setString(1, chapter.albumId());
                statement.setString(2, chapter.chapterId());
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) return true;
                }
            }
            return false;
        } catch (SQLException exception) {
            throw failure("检查 PDF 导出任务冲突失败", exception);
        }
    }

    public synchronized PdfExportTaskResponse claim(String exportId) {
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = database.connection().prepareStatement(
                "UPDATE pdf_export_tasks SET status='running',phase='preparing',started_at=?,"
                        + "updated_at=?,snapshot_revision=snapshot_revision+1 "
                        + "WHERE export_id=? AND status='queued'")) {
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.setString(3, exportId);
            return statement.executeUpdate() == 1 ? find(exportId) : null;
        } catch (SQLException exception) {
            throw failure("领取 PDF 导出任务失败", exception);
        }
    }

    public synchronized PdfExportTaskResponse requestCancel(String exportId) {
        PdfExportTaskResponse current = find(exportId);
        if (current == null) return null;
        long now = System.currentTimeMillis();
        String sql;
        if ("queued".equals(current.status())) {
            sql = "UPDATE pdf_export_tasks SET status='cancelled',phase='cancelled',"
                    + "cancel_requested=1,error_code='CANCELLED',error_message='PDF 导出已取消',"
                    + "completed_at=?,updated_at=?,snapshot_revision=snapshot_revision+1 "
                    + "WHERE export_id=? AND status='queued'";
        } else if ("running".equals(current.status())) {
            sql = "UPDATE pdf_export_tasks SET status='cancelling',phase='cancelling',"
                    + "cancel_requested=1,updated_at=?,snapshot_revision=snapshot_revision+1 "
                    + "WHERE export_id=? AND status='running'";
        } else {
            return current;
        }
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            int index = 1;
            if ("queued".equals(current.status())) statement.setLong(index++, now);
            statement.setLong(index++, now);
            statement.setString(index, exportId);
            statement.executeUpdate();
            return find(exportId);
        } catch (SQLException exception) {
            throw failure("取消 PDF 导出任务失败", exception);
        }
    }

    public synchronized boolean isCancellationRequested(String exportId) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT cancel_requested,status FROM pdf_export_tasks WHERE export_id=?")) {
            statement.setString(1, exportId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return true;
                String status = rows.getString("status");
                return rows.getInt("cancel_requested") == 1
                        || "cancelled".equals(status) || "cancelling".equals(status);
            }
        } catch (SQLException exception) {
            throw failure("读取 PDF 导出取消状态失败", exception);
        }
    }

    public synchronized PdfExportTaskResponse updateProgress(
            String exportId,
            String status,
            String phase,
            int currentPage,
            int totalPages,
            int currentVolume,
            int totalVolumes,
            String errorCode,
            String errorMessage
    ) {
        long now = System.currentTimeMillis();
        String sql = "UPDATE pdf_export_tasks SET status=?,phase=?,current_page=?,total_pages=?,"
                + "current_volume=?,total_volumes=?,error_code=?,error_message=?,updated_at=?,"
                + (isTerminal(status) ? "completed_at=?," : "")
                + "snapshot_revision=snapshot_revision+1 WHERE export_id=?"
                + ("running".equals(status) ? " AND status='running'" : "");
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, status);
            statement.setString(index++, phase);
            statement.setInt(index++, Math.max(0, currentPage));
            statement.setInt(index++, Math.max(0, totalPages));
            statement.setInt(index++, Math.max(0, currentVolume));
            statement.setInt(index++, Math.max(0, totalVolumes));
            nullableText(statement, index++, errorCode);
            nullableText(statement, index++, errorMessage);
            statement.setLong(index++, now);
            if (isTerminal(status)) statement.setLong(index++, now);
            statement.setString(index, exportId);
            statement.executeUpdate();
            return find(exportId);
        } catch (SQLException exception) {
            throw failure("更新 PDF 导出进度失败", exception);
        }
    }

    public synchronized void markVolumeStatus(String exportId, int volumeIndex, String status) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "UPDATE pdf_export_volumes SET status=?,updated_at=? "
                        + "WHERE export_id=? AND volume_index=? AND status<>'completed'")) {
            statement.setString(1, status);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, exportId);
            statement.setInt(4, volumeIndex);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("更新 PDF 导出分卷失败", exception);
        }
    }

    public synchronized void completeVolumeAndRegisterFile(
            String exportId,
            int volumeIndex,
            String outputFileRef,
            String displayPath,
            String fileName,
            long fileSize,
            int pageCount
    ) {
        transaction(connection -> {
            PdfExportTaskResponse task = find(exportId);
            List<Chapter> chapters = chapters(exportId);
            if (task == null || chapters.isEmpty()) {
                throw new IllegalStateException("PDF 导出任务数据不完整: " + exportId);
            }
            long now = System.currentTimeMillis();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE pdf_export_volumes SET status='completed',actual_page_count=?,"
                            + "file_size=?,output_file_ref=?,display_path=?,updated_at=?,completed_at=? "
                            + "WHERE export_id=? AND volume_index=?")) {
                statement.setInt(1, pageCount);
                statement.setLong(2, fileSize);
                statement.setString(3, outputFileRef);
                statement.setString(4, displayPath);
                statement.setLong(5, now);
                statement.setLong(6, now);
                statement.setString(7, exportId);
                statement.setInt(8, volumeIndex);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("PDF 导出分卷不存在: " + exportId + "/" + volumeIndex);
                }
            }
            Chapter first = chapters.get(0);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pdf_files(file_ref,display_path,file_name,source_type,ownership,"
                            + "chapter_link_status,album_id,album_title,cover_url,authors,chapter_id,"
                            + "chapter_title,chapter_sort_order,is_single_episode,folder_id,file_size,"
                            + "page_count,availability,verification_status,verification_error,created_at,"
                            + "updated_at,verified_at) VALUES (?,?,?,'exported','app_created',?,?,?,?,?,?,?,"
                            + "?,?,NULL,?,?,'available','valid',NULL,?,?,?) "
                            + "ON CONFLICT(file_ref) DO UPDATE SET display_path=excluded.display_path,"
                            + "file_name=excluded.file_name,source_type='exported',ownership='app_created',"
                            + "chapter_link_status=excluded.chapter_link_status,album_id=excluded.album_id,"
                            + "album_title=excluded.album_title,cover_url=excluded.cover_url,authors=excluded.authors,"
                            + "chapter_id=excluded.chapter_id,chapter_title=excluded.chapter_title,"
                            + "chapter_sort_order=excluded.chapter_sort_order,is_single_episode=excluded.is_single_episode,"
                            + "file_size=excluded.file_size,page_count=excluded.page_count,availability='available',"
                            + "verification_status='valid',verification_error=NULL,updated_at=excluded.updated_at,"
                            + "verified_at=excluded.verified_at")) {
                int index = 1;
                statement.setString(index++, outputFileRef);
                statement.setString(index++, displayPath);
                statement.setString(index++, fileName);
                statement.setString(index++, "merged".equals(task.mode()) ? "multi_chapter" : "resolved");
                statement.setString(index++, task.albumId());
                statement.setString(index++, value(task.albumTitle()));
                statement.setString(index++, value(task.coverUrl()));
                statement.setString(index++, value(task.authors()));
                nullableText(statement, index++, "merged".equals(task.mode()) ? null : first.chapterId());
                statement.setString(index++, value(first.chapterTitle()));
                statement.setInt(index++, first.sortOrder());
                statement.setInt(index++, task.isSingleEpisode() == null ? -1
                        : task.isSingleEpisode() ? 1 : 0);
                statement.setLong(index++, Math.max(0, fileSize));
                statement.setInt(index++, Math.max(0, pageCount));
                statement.setLong(index++, now);
                statement.setLong(index++, now);
                statement.setLong(index, now);
                statement.executeUpdate();
            }
            return null;
        }, "登记 PDF 导出结果失败");
    }

    public synchronized int completedVolumeCount(String exportId) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT COUNT(*) FROM pdf_export_volumes WHERE export_id=? AND status='completed'")) {
            statement.setString(1, exportId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            throw failure("统计 PDF 完成分卷失败", exception);
        }
    }

    public synchronized boolean prepareRetry(String exportId, boolean allowOverwrite) {
        return transaction(connection -> {
            long now = System.currentTimeMillis();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE pdf_export_tasks SET status='queued',phase='queued',allow_overwrite=?,"
                            + "cancel_requested=0,current_page=0,current_volume=0,error_code=NULL,"
                            + "error_message=NULL,started_at=NULL,completed_at=NULL,updated_at=?,"
                            + "snapshot_revision=snapshot_revision+1 WHERE export_id=? "
                            + "AND status IN (" + TERMINAL_STATUSES + ")")) {
                statement.setInt(1, allowOverwrite ? 1 : 0);
                statement.setLong(2, now);
                statement.setString(3, exportId);
                if (statement.executeUpdate() != 1) return false;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE pdf_export_volumes SET status='pending',actual_page_count=0,file_size=0,"
                            + "completed_at=NULL,updated_at=? WHERE export_id=?")) {
                statement.setLong(1, now);
                statement.setString(2, exportId);
                statement.executeUpdate();
            }
            return true;
        }, "准备 PDF 导出重试失败");
    }

    public synchronized List<String> markActiveInterrupted() {
        List<String> tempPaths = new ArrayList<>();
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT v.temp_path FROM pdf_export_volumes v JOIN pdf_export_tasks t "
                        + "ON t.export_id=v.export_id WHERE t.status IN (" + ACTIVE_STATUSES + ")")) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) tempPaths.add(rows.getString(1));
            }
        } catch (SQLException exception) {
            throw failure("读取中断 PDF 临时文件失败", exception);
        }
        long now = System.currentTimeMillis();
        transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE pdf_export_volumes SET status='interrupted',updated_at=? "
                            + "WHERE status<>'completed' AND export_id IN (SELECT export_id "
                            + "FROM pdf_export_tasks WHERE status IN (" + ACTIVE_STATUSES + "))")) {
                statement.setLong(1, now);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE pdf_export_tasks SET status='interrupted',phase='interrupted',"
                            + "error_code='PROCESS_INTERRUPTED',error_message='应用上次运行期间中断，可重新导出',"
                            + "completed_at=?,updated_at=?,snapshot_revision=snapshot_revision+1 "
                            + "WHERE status IN (" + ACTIVE_STATUSES + ")")) {
                statement.setLong(1, now);
                statement.setLong(2, now);
                statement.executeUpdate();
            }
            return null;
        }, "恢复中断 PDF 导出任务失败");
        return List.copyOf(tempPaths);
    }

    public synchronized List<String> activeExportIds() {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT export_id FROM pdf_export_tasks WHERE status IN (" + ACTIVE_STATUSES + ")");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) ids.add(rows.getString(1));
            return List.copyOf(ids);
        } catch (SQLException exception) {
            throw failure("读取活动 PDF 导出任务失败", exception);
        }
    }

    public synchronized boolean delete(String exportId) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "DELETE FROM pdf_export_tasks WHERE export_id=? AND status IN ("
                        + TERMINAL_STATUSES + ")")) {
            statement.setString(1, exportId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw failure("删除 PDF 导出任务失败", exception);
        }
    }

    public static boolean isTerminal(String status) {
        return "completed".equals(status) || "failed".equals(status)
                || "cancelled".equals(status) || "partial".equals(status)
                || "interrupted".equals(status);
    }

    private static String taskSelect() {
        return "SELECT t.*,(SELECT v.output_file_ref FROM pdf_export_volumes v "
                + "WHERE v.export_id=t.export_id AND v.status='completed' "
                + "ORDER BY v.volume_index LIMIT 1) AS output_file_ref,"
                + "COALESCE((SELECT v.display_path FROM pdf_export_volumes v "
                + "WHERE v.export_id=t.export_id AND v.status='completed' "
                + "ORDER BY v.volume_index LIMIT 1),t.display_path) AS response_display_path "
                + "FROM pdf_export_tasks t";
    }

    private static PdfExportTaskResponse task(ResultSet rows) throws SQLException {
        int singleEpisode = rows.getInt("is_single_episode");
        Boolean single = singleEpisode < 0 ? null : singleEpisode == 1;
        return new PdfExportTaskResponse(
                null, rows.getString("export_id"), rows.getString("batch_id"),
                rows.getString("mode"), rows.getString("album_id"),
                rows.getString("album_title"), rows.getString("cover_url"),
                rows.getString("authors"), single, rows.getString("chapter_id"),
                rows.getString("display_title"), rows.getString("target_folder_ref"),
                rows.getString("target_name"), rows.getString("output_file_ref"),
                rows.getString("response_display_path"), rows.getInt("allow_overwrite") == 1,
                rows.getInt("use_original") == 1, rows.getDouble("compression_ratio"),
                rows.getInt("split_pages"), rows.getString("status"), rows.getString("phase"),
                rows.getInt("current_page"), rows.getInt("total_pages"),
                rows.getInt("current_volume"), rows.getInt("total_volumes"),
                rows.getLong("snapshot_revision"), rows.getInt("cancel_requested") == 1,
                rows.getString("error_code"), rows.getString("error_message"),
                rows.getLong("created_at"), nullableLong(rows, "started_at"),
                rows.getLong("updated_at"), nullableLong(rows, "completed_at")
        );
    }

    private static Volume volume(ResultSet rows) throws SQLException {
        return new Volume(
                rows.getInt("volume_index"), rows.getInt("start_page"),
                rows.getInt("end_page"), rows.getInt("expected_page_count"),
                rows.getString("target_name"), rows.getString("display_path"),
                rows.getString("temp_path"), rows.getString("output_file_ref"),
                rows.getString("status"), rows.getInt("actual_page_count"),
                rows.getLong("file_size")
        );
    }

    private <T> T transaction(SqlOperation<T> operation, String message) {
        Connection connection = database.connection();
        try {
            boolean autoCommit = connection.getAutoCommit();
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

    private static void nullableText(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null || value.isBlank()) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static IllegalStateException failure(String message, SQLException exception) {
        return new IllegalStateException(message, exception);
    }

    public record ReserveTask(
            String exportId,
            String batchId,
            String mode,
            String albumId,
            String albumTitle,
            String coverUrl,
            String authors,
            Boolean singleEpisode,
            String chapterId,
            String displayTitle,
            String targetFolderRef,
            String targetName,
            String displayPath,
            boolean allowOverwrite,
            boolean useOriginal,
            double compressionRatio,
            int splitPages,
            String status,
            String phase,
            int totalPages,
            String errorCode,
            String errorMessage,
            long createdAt
    ) {
    }

    public record Chapter(
            int sequence,
            String albumId,
            String chapterId,
            String chapterTitle,
            int sortOrder,
            int expectedPageCount
    ) {
    }

    public record Volume(
            int volumeIndex,
            int startPage,
            int endPage,
            int expectedPageCount,
            String targetName,
            String displayPath,
            String tempPath,
            String outputFileRef,
            String status,
            int actualPageCount,
            long fileSize
    ) {
        public Volume(int volumeIndex, int startPage, int endPage, int expectedPageCount,
                      String targetName, String displayPath, String tempPath) {
            this(volumeIndex, startPage, endPage, expectedPageCount, targetName,
                    displayPath, tempPath, null, "pending", 0, 0);
        }
    }

    public record Page(List<PdfExportTaskResponse> tasks, String nextCursor) {
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

    private record CursorPosition(long updatedAt, String exportId) {
        static CursorPosition parse(String cursor) {
            if (cursor == null || cursor.isBlank()) return null;
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(cursor),
                        StandardCharsets.UTF_8);
                int separator = decoded.indexOf(':');
                if (separator <= 0 || separator == decoded.length() - 1) throw new IllegalArgumentException();
                return new CursorPosition(Long.parseLong(decoded.substring(0, separator)),
                        decoded.substring(separator + 1));
            } catch (RuntimeException exception) {
                throw ApiException.invalidRequest("cursor无效");
            }
        }

        static String encode(long updatedAt, String exportId) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (updatedAt + ":" + exportId).getBytes(StandardCharsets.UTF_8));
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run(Connection connection) throws Exception;
    }
}
