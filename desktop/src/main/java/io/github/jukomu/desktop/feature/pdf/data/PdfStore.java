package io.github.jukomu.desktop.feature.pdf.data;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.data.Database;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** 持久化 Desktop PDF 文件库，并提供稳定的分页顺序。 */
public final class PdfStore {
    public static final String SOURCE_IMPORTED = "imported";
    public static final String SOURCE_EXPORTED = "exported";
    public static final String OWNERSHIP_EXTERNAL = "external_reference";
    public static final String OWNERSHIP_APP_CREATED = "app_created";

    private final Database database;

    public PdfStore(Database database) {
        this.database = database;
    }

    public synchronized InsertResult insertImported(
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
        StoredPdfFile existing = findByRef(fileRef);
        if (existing != null) return new InsertResult(existing, false);

        String sql = "INSERT INTO pdf_files(file_ref, display_path, file_name, source_type, "
                + "ownership, chapter_link_status, album_id, album_title, cover_url, authors, "
                + "chapter_id, chapter_title, chapter_sort_order, is_single_episode, folder_id, "
                + "file_size, page_count, availability, verification_status, verification_error, "
                + "created_at, updated_at, verified_at) "
                + "VALUES (?, ?, ?, 'imported', 'external_reference', ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + "?, ?, ?, 'available', 'valid', NULL, ?, ?, ?)";
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            statement.setString(1, fileRef);
            statement.setString(2, value(displayPath));
            statement.setString(3, value(fileName));
            statement.setString(4, value(chapterId).isBlank() ? "unresolved" : "resolved");
            statement.setString(5, value(albumId));
            statement.setString(6, value(albumTitle));
            statement.setString(7, value(coverUrl));
            statement.setString(8, value(authors));
            nullableText(statement, 9, chapterId);
            statement.setString(10, value(chapterTitle));
            statement.setInt(11, chapterSortOrder);
            statement.setInt(12, singleEpisode == null ? -1 : singleEpisode ? 1 : 0);
            nullableText(statement, 13, folderId);
            statement.setLong(14, Math.max(0L, fileSize));
            statement.setInt(15, Math.max(0, pageCount));
            statement.setLong(16, now);
            statement.setLong(17, now);
            statement.setLong(18, now);
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (isUniqueConflict(exception)) {
                StoredPdfFile duplicate = findByRef(fileRef);
                if (duplicate != null) return new InsertResult(duplicate, false);
            }
            throw failure("导入 PDF 记录失败", exception);
        }
        StoredPdfFile inserted = findByRef(fileRef);
        if (inserted == null) throw new IllegalStateException("导入 PDF 后无法读取记录");
        return new InsertResult(inserted, true);
    }

    public synchronized StoredPdfFile find(long id) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT * FROM pdf_files WHERE id=?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? file(rows) : null;
            }
        } catch (SQLException exception) {
            throw failure("读取 PDF 记录失败", exception);
        }
    }

    public synchronized StoredPdfFile findByRef(String fileRef) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT * FROM pdf_files WHERE file_ref=?")) {
            statement.setString(1, fileRef);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? file(rows) : null;
            }
        } catch (SQLException exception) {
            throw failure("读取 PDF 引用失败", exception);
        }
    }

    public synchronized Page list(
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

        String sql = "SELECT * FROM pdf_files"
                + (clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses))
                + " ORDER BY updated_at DESC, id DESC LIMIT ?";
        List<StoredPdfFile> files = new ArrayList<>();
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
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
            StoredPdfFile last = files.get(files.size() - 1);
            nextCursor = CursorPosition.encode(last.updatedAt(), last.id());
        }
        return new Page(List.copyOf(files), nextCursor);
    }

    public synchronized List<StoredPdfFile> listAll() {
        List<StoredPdfFile> files = new ArrayList<>();
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT * FROM pdf_files ORDER BY album_id, chapter_sort_order, id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) files.add(file(rows));
            return List.copyOf(files);
        } catch (SQLException exception) {
            throw failure("读取全部 PDF 记录失败", exception);
        }
    }

    public synchronized StoredPdfFile updateVerification(
            long id,
            String availability,
            String verificationStatus,
            String error,
            Long fileSize,
            Integer pageCount,
            long verifiedAt
    ) {
        String sql = "UPDATE pdf_files SET availability=?, verification_status=?, "
                + "verification_error=?, file_size=COALESCE(?, file_size), "
                + "page_count=COALESCE(?, page_count), updated_at=?, verified_at=? WHERE id=?";
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
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
        try (PreparedStatement statement = database.connection().prepareStatement(
                "DELETE FROM pdf_files WHERE id=?")) {
            statement.setLong(1, id);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw failure("移除 PDF 记录失败", exception);
        }
    }

    public synchronized int updateAlbumEpisodeType(String albumId, boolean singleEpisode) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "UPDATE pdf_files SET is_single_episode=?, updated_at=? WHERE album_id=?")) {
            statement.setInt(1, singleEpisode ? 1 : 0);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, albumId);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("更新 PDF 章节类型失败", exception);
        }
    }

    private static StoredPdfFile file(ResultSet rows) throws SQLException {
        int singleEpisode = rows.getInt("is_single_episode");
        Boolean single = singleEpisode < 0 ? null : singleEpisode == 1;
        long verifiedAt = rows.getLong("verified_at");
        Long verified = rows.wasNull() ? null : verifiedAt;
        return new StoredPdfFile(
                rows.getLong("id"), rows.getString("file_ref"), rows.getString("display_path"),
                rows.getString("file_name"), rows.getString("source_type"),
                rows.getString("ownership"), rows.getString("chapter_link_status"),
                rows.getString("album_id"), rows.getString("album_title"),
                rows.getString("cover_url"), rows.getString("authors"),
                rows.getString("chapter_id"), rows.getString("chapter_title"),
                rows.getInt("chapter_sort_order"), single, rows.getString("folder_id"),
                rows.getLong("file_size"), rows.getInt("page_count"),
                rows.getString("availability"), rows.getString("verification_status"),
                rows.getString("verification_error"), rows.getLong("created_at"),
                rows.getLong("updated_at"), verified
        );
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

    public record InsertResult(StoredPdfFile file, boolean inserted) {
    }

    public record Page(List<StoredPdfFile> files, String nextCursor) {
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
