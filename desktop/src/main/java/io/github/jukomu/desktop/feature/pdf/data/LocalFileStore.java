package io.github.jukomu.desktop.feature.pdf.data;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.data.Database;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** 持久化 Desktop 本地文件库，并提供稳定的分页顺序。 */
public final class LocalFileStore {
    public static final String SOURCE_IMPORTED = "imported";
    public static final String SOURCE_EXPORTED = "exported";
    public static final String OWNERSHIP_EXTERNAL = "external_reference";
    public static final String OWNERSHIP_APP_CREATED = "app_created";

    private final Connection connection;

    public LocalFileStore(Database database) {
        this.connection = database.openIsolatedConnection();
    }

    public synchronized InsertResult insertImported(
            String format,
            String fileRef,
            String displayPath,
            String fileName,
            String albumId,
            String albumTitle,
            String coverUrl,
            String authors,
            String chapterId,
            String chapterTitle,
            int chapterSortOrder,
            Boolean singleEpisode,
            String folderId,
            long fileSize,
            int pageCount,
            long now
    ) {
        StoredLocalFile existing = findByRef(fileRef);
        if (existing != null) return new InsertResult(existing, false);

        String sql = "INSERT INTO local_files(format, file_ref, display_path, file_name, source_type, "
                + "ownership, chapter_link_status, album_id, album_title, cover_url, authors, "
                + "is_single_episode, folder_id, "
                + "file_size, page_count, availability, verification_status, verification_error, "
                + "created_at, updated_at, verified_at) "
                + "VALUES (?, ?, ?, ?, 'imported', 'external_reference', ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + "'available', 'valid', NULL, ?, ?, ?)";
        try {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, format);
                statement.setString(2, fileRef);
                statement.setString(3, value(displayPath));
                statement.setString(4, value(fileName));
                statement.setString(5, value(chapterId).isBlank() ? "unresolved" : "resolved");
                statement.setString(6, value(albumId));
                statement.setString(7, value(albumTitle));
                statement.setString(8, value(coverUrl));
                statement.setString(9, value(authors));
                statement.setInt(10, singleEpisode == null ? -1 : singleEpisode ? 1 : 0);
                nullableText(statement, 11, folderId);
                statement.setLong(12, Math.max(0L, fileSize));
                statement.setInt(13, Math.max(0, pageCount));
                statement.setLong(14, now);
                statement.setLong(15, now);
                statement.setLong(16, now);
                statement.executeUpdate();
            }
            StoredLocalFile inserted = findByRef(fileRef);
            if (inserted == null) throw new SQLException("inserted local file is missing");
            if (chapterId != null && !chapterId.isBlank()) {
                insertChapter(inserted.id(), 0, albumId, chapterId, chapterTitle,
                        chapterSortOrder, 1, pageCount, pageCount);
            }
            connection.commit();
            connection.setAutoCommit(autoCommit);
        } catch (SQLException exception) {
            try {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // The original failure is more useful.
            }
            if (isUniqueConflict(exception)) {
                StoredLocalFile duplicate = findByRef(fileRef);
                if (duplicate != null) return new InsertResult(duplicate, false);
            }
            throw failure("导入本地文件记录失败", exception);
        }
        StoredLocalFile inserted = findByRef(fileRef);
        if (inserted == null) throw new IllegalStateException("导入 PDF 后无法读取记录");
        return new InsertResult(inserted, true);
    }

    public synchronized StoredLocalFile find(long id) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM local_files WHERE id=?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? file(rows) : null;
            }
        } catch (SQLException exception) {
            throw failure("读取 PDF 记录失败", exception);
        }
    }

    public synchronized StoredLocalFile findByRef(String fileRef) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM local_files WHERE file_ref=?")) {
            statement.setString(1, fileRef);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? file(rows) : null;
            }
        } catch (SQLException exception) {
            throw failure("读取 PDF 引用失败", exception);
        }
    }

    public synchronized Page list(
            String format,
            String sourceType,
            String availability,
            String folderId,
            String query,
            String cursor,
            int requestedLimit
    ) {
        int limit = Math.max(1, Math.min(100, requestedLimit));
        CursorPosition position = CursorPosition.parse(cursor);
        List<String> clauses = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        if (format != null && !format.isBlank()) {
            clauses.add("format=?");
            arguments.add(format);
        }
        if (sourceType != null && !sourceType.isBlank()) {
            clauses.add("source_type=?");
            arguments.add(sourceType);
        }
        if (availability != null && !availability.isBlank()) {
            if ("problem".equals(availability)) {
                clauses.add("availability<>'available'");
            } else {
                clauses.add("availability=?");
                arguments.add(availability);
            }
        }
        if (folderId != null && !folderId.isBlank()) {
            clauses.add("folder_id=?");
            arguments.add(folderId);
        }
        if (query != null && !query.isBlank()) {
            clauses.add("(album_title LIKE ? OR file_name LIKE ? OR album_id LIKE ?)");
            String pattern = "%" + query.trim() + "%";
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
        }
        if (position != null) {
            clauses.add("(updated_at<? OR (updated_at=? AND id<?))");
            arguments.add(position.updatedAt());
            arguments.add(position.updatedAt());
            arguments.add(position.id());
        }

        String sql = "SELECT * FROM local_files"
                + (clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses))
                + " ORDER BY updated_at DESC, id DESC LIMIT ?";
        List<StoredLocalFile> files = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (Object argument : arguments) {
                if (argument instanceof Long number) statement.setLong(index++, number);
                else statement.setString(index++, String.valueOf(argument));
            }
            statement.setInt(index, limit + 1);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) files.add(file(rows));
            }
        } catch (SQLException exception) {
            throw failure("读取 PDF 文件库失败", exception);
        }

        String nextCursor = null;
        if (files.size() > limit) {
            files.remove(files.size() - 1);
            StoredLocalFile last = files.get(files.size() - 1);
            nextCursor = CursorPosition.encode(last.updatedAt(), last.id());
        }
        return new Page(List.copyOf(files), nextCursor);
    }

    public synchronized List<StoredLocalFile> listAll() {
        return listAll(null);
    }

    public synchronized List<StoredLocalFile> listAll(String sourceType) {
        List<StoredLocalFile> files = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM local_files"
                        + (sourceType == null || sourceType.isBlank()
                        ? "" : " WHERE source_type=?")
                        + " ORDER BY album_id, id")) {
            if (sourceType != null && !sourceType.isBlank()) statement.setString(1, sourceType);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) files.add(file(rows));
                return List.copyOf(files);
            }
        } catch (SQLException exception) {
            throw failure("读取全部 PDF 记录失败", exception);
        }
    }

    public synchronized StoredLocalFile updateVerification(
            long id,
            String availability,
            String verificationStatus,
            String error,
            Long fileSize,
            Integer pageCount,
            long verifiedAt
    ) {
        String sql = "UPDATE local_files SET availability=?, verification_status=?, "
                + "verification_error=?, file_size=COALESCE(?, file_size), "
                + "page_count=COALESCE(?, page_count), updated_at=?, verified_at=? WHERE id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, availability);
            statement.setString(2, verificationStatus);
            nullableText(statement, 3, error);
            if (fileSize == null) statement.setNull(4, Types.BIGINT);
            else statement.setLong(4, Math.max(0L, fileSize));
            if (pageCount == null) statement.setNull(5, Types.INTEGER);
            else statement.setInt(5, Math.max(0, pageCount));
            statement.setLong(6, verifiedAt);
            statement.setLong(7, verifiedAt);
            statement.setLong(8, id);
            if (statement.executeUpdate() != 1) return null;
            return find(id);
        } catch (SQLException exception) {
            throw failure("更新 PDF 校验状态失败", exception);
        }
    }

    public synchronized boolean remove(long id) {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM local_files WHERE id=?")) {
            statement.setLong(1, id);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw failure("移除 PDF 记录失败", exception);
        }
    }

    public synchronized int updateAlbumEpisodeType(String albumId, boolean singleEpisode) {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE local_files SET is_single_episode=?, updated_at=? WHERE album_id=?")) {
            statement.setInt(1, singleEpisode ? 1 : 0);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, albumId);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("更新 PDF 章节类型失败", exception);
        }
    }

    private StoredLocalFile file(ResultSet rows) throws SQLException {
        int singleEpisode = rows.getInt("is_single_episode");
        Boolean single = singleEpisode < 0 ? null : singleEpisode == 1;
        long verifiedAt = rows.getLong("verified_at");
        Long verified = rows.wasNull() ? null : verifiedAt;
        long id = rows.getLong("id");
        List<StoredLocalFileChapter> chapters = chapters(id);
        StoredLocalFileChapter first = chapters.isEmpty() ? null : chapters.get(0);
        return new StoredLocalFile(
                id, rows.getString("format"), rows.getString("file_ref"), rows.getString("display_path"),
                rows.getString("file_name"), rows.getString("source_type"),
                rows.getString("ownership"), rows.getString("chapter_link_status"),
                rows.getString("album_id"), rows.getString("album_title"),
                rows.getString("cover_url"), rows.getString("authors"),
                first == null ? null : first.chapterId(),
                first == null ? "" : first.chapterTitle(),
                first == null ? 0 : first.sortOrder(), single, rows.getString("folder_id"),
                rows.getLong("file_size"), rows.getInt("page_count"),
                rows.getString("availability"), rows.getString("verification_status"),
                rows.getString("verification_error"), rows.getLong("created_at"),
                rows.getLong("updated_at"), verified, chapters
        );
    }

    private List<StoredLocalFileChapter> chapters(long fileId) throws SQLException {
        List<StoredLocalFileChapter> chapters = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM local_file_chapters WHERE file_id=? ORDER BY sequence")) {
            statement.setLong(1, fileId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    chapters.add(new StoredLocalFileChapter(
                            rows.getInt("sequence"), rows.getString("album_id"),
                            rows.getString("chapter_id"), rows.getString("chapter_title"),
                            rows.getInt("sort_order"), rows.getInt("start_page"),
                            rows.getInt("end_page"), rows.getInt("page_count")));
                }
            }
        }
        return List.copyOf(chapters);
    }

    private void insertChapter(long fileId, int sequence, String albumId, String chapterId,
                               String chapterTitle, int sortOrder, int startPage,
                               int endPage, int pageCount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO local_file_chapters(file_id,sequence,album_id,chapter_id,chapter_title,"
                        + "sort_order,start_page,end_page,page_count) VALUES (?,?,?,?,?,?,?,?,?)")) {
            statement.setLong(1, fileId);
            statement.setInt(2, sequence);
            statement.setString(3, value(albumId));
            statement.setString(4, chapterId);
            statement.setString(5, value(chapterTitle));
            statement.setInt(6, sortOrder);
            statement.setInt(7, Math.max(1, startPage));
            statement.setInt(8, Math.max(0, endPage));
            statement.setInt(9, Math.max(0, pageCount));
            statement.executeUpdate();
        }
    }

    private static void nullableText(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null || value.isBlank()) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static boolean isUniqueConflict(SQLException exception) {
        return exception.getMessage() != null
                && exception.getMessage().toLowerCase().contains("unique constraint failed");
    }

    private static IllegalStateException failure(String message, SQLException exception) {
        return new IllegalStateException(message, exception);
    }

    public record InsertResult(StoredLocalFile file, boolean inserted) {
    }

    public record Page(List<StoredLocalFile> files, String nextCursor) {
    }

    private record CursorPosition(long updatedAt, long id) {
        private static CursorPosition parse(String cursor) {
            if (cursor == null || cursor.isBlank()) return null;
            try {
                String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int separator = value.indexOf(':');
                if (separator <= 0 || separator == value.length() - 1) throw new IllegalArgumentException();
                return new CursorPosition(
                        Long.parseLong(value.substring(0, separator)),
                        Long.parseLong(value.substring(separator + 1))
                );
            } catch (RuntimeException exception) {
                throw ApiException.invalidRequest("cursor无效");
            }
        }

        private static String encode(long updatedAt, long id) {
            String value = updatedAt + ":" + id;
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
