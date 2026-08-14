package io.github.jukomu.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Stores the PDF file library and the persistent single-threaded export queue. */
public class PdfStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "jq_pdf_import.db";
    private static final int DB_VERSION = 9;
    private static final String RESET_REASON = "SCHEMA_REBUILD_V9";

    public static final String SOURCE_IMPORTED = "imported";
    public static final String SOURCE_EXPORTED = "exported";
    public static final String OWNERSHIP_EXTERNAL = "external_reference";
    public static final String OWNERSHIP_APP_CREATED = "app_created";

    static final String TABLE_FILES = "pdf_files";
    static final String TABLE_TASKS = "pdf_export_tasks";
    static final String TABLE_CHAPTERS = "pdf_export_chapters";
    static final String TABLE_VOLUMES = "pdf_export_volumes";
    static final String TABLE_META = "pdf_store_meta";

    private static PdfStore instance;

    public static synchronized PdfStore getInstance(Context context) {
        if (instance == null) instance = new PdfStore(context.getApplicationContext());
        return instance;
    }

    public static synchronized void clearInstanceForTest() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    private PdfStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createSchema(db);
        writeResetMeta(db, false, 0, null);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        dropManagedTables(db);
        createSchema(db);
        writeResetMeta(db, true, oldVersion, RESET_REASON);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException(
            "PDF database downgrade is unsupported: " + oldVersion + " -> " + newVersion);
    }

    private static void dropManagedTables(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CHAPTERS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_VOLUMES);
        db.execSQL("DROP TABLE IF EXISTS pdf_export_path_locks");
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_FILES);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_TASKS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_META);
        db.execSQL("DROP TABLE IF EXISTS imported_pdfs");
        db.execSQL("DROP TABLE IF EXISTS imported_pdfs_new");
        db.execSQL("DROP TABLE IF EXISTS pdf_files_new");
    }

    private static void createSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_FILES + " ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "file_path TEXT NOT NULL UNIQUE,"
            + "file_name TEXT NOT NULL,"
            + "source_type TEXT NOT NULL CHECK(source_type IN ('imported','exported')),"
            + "ownership TEXT NOT NULL CHECK(ownership IN ('external_reference','app_created')),"
            + "chapter_link_status TEXT NOT NULL "
            + "CHECK(chapter_link_status IN ('resolved','unresolved','multi_chapter')),"
            + "album_id TEXT NOT NULL,"
            + "album_title TEXT NOT NULL DEFAULT '',"
            + "cover_url TEXT NOT NULL DEFAULT '',"
            + "authors TEXT NOT NULL DEFAULT '',"
            + "chapter_id TEXT,"
            + "chapter_title TEXT NOT NULL DEFAULT '',"
            + "chapter_sort_order INTEGER NOT NULL DEFAULT 0,"
            + "is_single_episode INTEGER NOT NULL DEFAULT -1 CHECK(is_single_episode IN (-1,0,1)),"
            + "folder_id TEXT,"
            + "file_size INTEGER NOT NULL DEFAULT 0,"
            + "page_count INTEGER NOT NULL DEFAULT 0,"
            + "availability TEXT NOT NULL DEFAULT 'unknown' "
            + "CHECK(availability IN ('unknown','available','missing','inaccessible','invalid')),"
            + "verification_status TEXT NOT NULL DEFAULT 'unverified' "
            + "CHECK(verification_status IN ('unverified','valid','corrupt','page_mismatch')),"
            + "verification_error TEXT,"
            + "created_at INTEGER NOT NULL,"
            + "updated_at INTEGER NOT NULL,"
            + "verified_at INTEGER"
            + ")");

        db.execSQL("CREATE TABLE " + TABLE_TASKS + " ("
            + "export_id TEXT PRIMARY KEY,"
            + "batch_id TEXT NOT NULL,"
            + "mode TEXT NOT NULL CHECK(mode IN ('chapter','merged')),"
            + "album_id TEXT NOT NULL,"
            + "album_title TEXT NOT NULL DEFAULT '',"
            + "cover_url TEXT NOT NULL DEFAULT '',"
            + "authors TEXT NOT NULL DEFAULT '',"
            + "is_single_episode INTEGER NOT NULL DEFAULT -1 CHECK(is_single_episode IN (-1,0,1)),"
            + "chapter_id TEXT,"
            + "display_title TEXT NOT NULL,"
            + "save_path TEXT NOT NULL,"
            + "allow_overwrite INTEGER NOT NULL DEFAULT 0 CHECK(allow_overwrite IN (0,1)),"
            + "use_original INTEGER NOT NULL CHECK(use_original IN (0,1)),"
            + "compression_ratio REAL NOT NULL,"
            + "split_pages INTEGER NOT NULL DEFAULT 0,"
            + "status TEXT NOT NULL,"
            + "phase TEXT NOT NULL,"
            + "current_page INTEGER NOT NULL DEFAULT 0,"
            + "total_pages INTEGER NOT NULL DEFAULT 0,"
            + "current_volume INTEGER NOT NULL DEFAULT 0,"
            + "total_volumes INTEGER NOT NULL DEFAULT 0,"
            + "snapshot_revision INTEGER NOT NULL DEFAULT 0,"
            + "cancel_requested INTEGER NOT NULL DEFAULT 0 CHECK(cancel_requested IN (0,1)),"
            + "error_code TEXT,"
            + "error_message TEXT,"
            + "created_at INTEGER NOT NULL,"
            + "started_at INTEGER,"
            + "updated_at INTEGER NOT NULL,"
            + "completed_at INTEGER"
            + ")");

        db.execSQL("CREATE TABLE " + TABLE_CHAPTERS + " ("
            + "export_id TEXT NOT NULL,"
            + "sequence INTEGER NOT NULL,"
            + "album_id TEXT NOT NULL,"
            + "chapter_id TEXT NOT NULL,"
            + "chapter_title TEXT NOT NULL DEFAULT '',"
            + "sort_order INTEGER NOT NULL DEFAULT 0,"
            + "expected_page_count INTEGER NOT NULL DEFAULT 0,"
            + "PRIMARY KEY(export_id, sequence),"
            + "FOREIGN KEY(export_id) REFERENCES " + TABLE_TASKS
            + "(export_id) ON DELETE CASCADE"
            + ")");

        db.execSQL("CREATE TABLE " + TABLE_VOLUMES + " ("
            + "export_id TEXT NOT NULL,"
            + "volume_index INTEGER NOT NULL,"
            + "start_page INTEGER NOT NULL,"
            + "end_page INTEGER NOT NULL,"
            + "expected_page_count INTEGER NOT NULL,"
            + "actual_page_count INTEGER NOT NULL DEFAULT 0,"
            + "final_path TEXT NOT NULL,"
            + "temp_path TEXT NOT NULL,"
            + "work_dir TEXT NOT NULL,"
            + "status TEXT NOT NULL DEFAULT 'pending',"
            + "file_size INTEGER NOT NULL DEFAULT 0,"
            + "updated_at INTEGER NOT NULL,"
            + "completed_at INTEGER,"
            + "PRIMARY KEY(export_id, volume_index),"
            + "FOREIGN KEY(export_id) REFERENCES " + TABLE_TASKS
            + "(export_id) ON DELETE CASCADE"
            + ")");

        db.execSQL("CREATE TABLE " + TABLE_META + " ("
            + "id INTEGER PRIMARY KEY CHECK(id=1),"
            + "reset_notice_pending INTEGER NOT NULL DEFAULT 0 CHECK(reset_notice_pending IN (0,1)),"
            + "last_reset_at INTEGER,"
            + "reset_from_version INTEGER,"
            + "reset_reason TEXT,"
            + "updated_at INTEGER NOT NULL"
            + ")");

        db.execSQL("CREATE INDEX idx_pdf_files_source_created ON " + TABLE_FILES
            + "(source_type, created_at DESC, id DESC)");
        db.execSQL("CREATE INDEX idx_pdf_files_availability_updated ON " + TABLE_FILES
            + "(availability, updated_at DESC, id DESC)");
        db.execSQL("CREATE INDEX idx_pdf_tasks_status_updated ON " + TABLE_TASKS
            + "(status, updated_at DESC, export_id)");
    }

    private static void writeResetMeta(SQLiteDatabase db, boolean pending,
            int fromVersion, String reason) {
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("id", 1);
        values.put("reset_notice_pending", pending ? 1 : 0);
        if (pending) {
            values.put("last_reset_at", now);
            values.put("reset_from_version", fromVersion);
            values.put("reset_reason", reason);
        }
        values.put("updated_at", now);
        db.insertOrThrow(TABLE_META, null, values);
    }

    public long insertImportedPdf(String filePath, String fileName, String albumId,
            String albumTitle, String coverUrl, String authors, String chapterId,
            String chapterTitle, int chapterSortOrder, int singleEpisode, long createdAt,
            String folderId, long fileSize, int pageCount) throws IOException {
        return upsertFile(normalizeLocator(filePath), fileName, SOURCE_IMPORTED,
            OWNERSHIP_EXTERNAL, albumId, albumTitle, coverUrl, authors, chapterId,
            chapterTitle, chapterSortOrder, singleEpisode, folderId, fileSize, pageCount,
            createdAt, false, false);
    }

    public long registerExportedPdf(String filePath, String fileName, String mode,
            String albumId, String albumTitle, String coverUrl, String authors,
            String chapterId, String chapterTitle, int chapterSortOrder, int singleEpisode,
            long fileSize, int pageCount) throws IOException {
        return upsertFile(normalizeLocator(filePath), fileName, SOURCE_EXPORTED,
            OWNERSHIP_APP_CREATED, albumId, albumTitle, coverUrl, authors,
            "merged".equals(mode) ? null : chapterId, chapterTitle, chapterSortOrder,
            singleEpisode, null, fileSize, pageCount, System.currentTimeMillis(), true,
            "merged".equals(mode));
    }

    private long upsertFile(String locator, String fileName, String sourceType,
            String ownership, String albumId, String albumTitle, String coverUrl,
            String authors, String chapterId, String chapterTitle, int chapterSortOrder,
            int singleEpisode, String folderId, long fileSize, int pageCount,
            long createdAt, boolean replaceMetadata, boolean multiChapter) {
        JSONObject existing = getFileByNormalizedPath(locator);
        if (existing != null && !replaceMetadata) return -1L;
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("file_path", locator);
        values.put("file_name", emptyToFallback(fileName, locatorFileName(locator)));
        values.put("source_type", sourceType);
        values.put("ownership", ownership);
        values.put("chapter_link_status", multiChapter
            ? "multi_chapter" : (emptyToNull(chapterId) == null ? "unresolved" : "resolved"));
        values.put("album_id", albumId == null ? "" : albumId);
        values.put("album_title", albumTitle == null ? "" : albumTitle);
        values.put("cover_url", coverUrl == null ? "" : coverUrl);
        values.put("authors", authors == null ? "" : authors);
        if (emptyToNull(chapterId) == null) values.putNull("chapter_id");
        else values.put("chapter_id", chapterId);
        values.put("chapter_title", chapterTitle == null ? "" : chapterTitle);
        values.put("chapter_sort_order", chapterSortOrder);
        values.put("is_single_episode", singleEpisode);
        if (folderId == null || folderId.isEmpty()) values.putNull("folder_id");
        else values.put("folder_id", folderId);
        values.put("file_size", Math.max(0L, fileSize));
        values.put("page_count", Math.max(0, pageCount));
        values.put("availability", "available");
        values.put("verification_status", "valid");
        values.putNull("verification_error");
        values.put("updated_at", now);
        values.put("verified_at", now);
        if (existing == null) {
            values.put("created_at", createdAt > 0L ? createdAt : now);
            return getWritableDatabase().insertOrThrow(TABLE_FILES, null, values);
        }
        getWritableDatabase().update(TABLE_FILES, values, "id = ?",
            new String[]{String.valueOf(existing.optLong("id"))});
        return existing.optLong("id");
    }

    public JSONArray getAllFiles() {
        return getFilesPage(null, null, null, null, null, 500).optJSONArray("files");
    }

    public JSONObject getFilesPage(String sourceType, String availability,
            String folderId, String query, String cursor, int requestedLimit) {
        int limit = Math.max(1, Math.min(100, requestedLimit));
        CursorPosition position = CursorPosition.parse(cursor);
        List<String> clauses = new ArrayList<>();
        List<String> args = new ArrayList<>();
        if (sourceType != null && !sourceType.isEmpty()) {
            clauses.add("source_type = ?");
            args.add(sourceType);
        }
        if (availability != null && !availability.isEmpty()) {
            if ("problem".equals(availability)) clauses.add("availability <> 'available'");
            else {
                clauses.add("availability = ?");
                args.add(availability);
            }
        }
        if (folderId != null && !folderId.isEmpty()) {
            clauses.add("folder_id = ?");
            args.add(folderId);
        }
        if (query != null && !query.trim().isEmpty()) {
            clauses.add("(album_title LIKE ? OR file_name LIKE ? OR album_id LIKE ?)");
            String pattern = "%" + query.trim() + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        if (position != null) {
            clauses.add("(updated_at < ? OR (updated_at = ? AND id < ?))");
            args.add(String.valueOf(position.timestamp));
            args.add(String.valueOf(position.timestamp));
            args.add(position.id);
        }
        JSONArray files = new JSONArray();
        String nextCursor = null;
        try (Cursor result = getReadableDatabase().query(TABLE_FILES, null,
                joinClauses(clauses), args.toArray(new String[0]), null, null,
                "updated_at DESC, id DESC", String.valueOf(limit + 1))) {
            while (result.moveToNext() && files.length() < limit) files.put(cursorToFileJson(result));
            if (!result.isAfterLast()) {
                JSONObject last = files.optJSONObject(files.length() - 1);
                nextCursor = CursorPosition.encode(last.optLong("updatedAt"),
                    String.valueOf(last.optLong("id")));
            }
        }
        return page("files", files, nextCursor);
    }

    public JSONObject getFile(long id) {
        try (Cursor cursor = getReadableDatabase().query(TABLE_FILES, null, "id = ?",
                new String[]{String.valueOf(id)}, null, null, null, "1")) {
            return cursor.moveToFirst() ? cursorToFileJson(cursor) : null;
        }
    }

    public JSONObject getFileByPath(String filePath) throws IOException {
        return getFileByNormalizedPath(normalizeLocator(filePath));
    }

    private JSONObject getFileByNormalizedPath(String locator) {
        try (Cursor cursor = getReadableDatabase().query(TABLE_FILES, null, "file_path = ?",
                new String[]{locator}, null, null, null, "1")) {
            return cursor.moveToFirst() ? cursorToFileJson(cursor) : null;
        }
    }

    public boolean removeFileFromLibrary(long id) {
        return getWritableDatabase().delete(TABLE_FILES, "id = ?",
            new String[]{String.valueOf(id)}) > 0;
    }

    public JSONObject updateFileVerification(long id, String availability,
            String verificationStatus, String errorMessage, long fileSize, int pageCount) {
        ContentValues values = new ContentValues();
        values.put("availability", availability);
        values.put("verification_status", verificationStatus);
        if (errorMessage == null) values.putNull("verification_error");
        else values.put("verification_error", errorMessage);
        if (fileSize >= 0L) values.put("file_size", fileSize);
        if (pageCount >= 0) values.put("page_count", pageCount);
        long now = System.currentTimeMillis();
        values.put("updated_at", now);
        values.put("verified_at", now);
        getWritableDatabase().update(TABLE_FILES, values, "id = ?",
            new String[]{String.valueOf(id)});
        return getFile(id);
    }

    public JSONObject updateFileMetadata(long id, JSONObject metadata) {
        ContentValues values = new ContentValues();
        putOptionalText(values, metadata, "albumId", "album_id");
        putOptionalText(values, metadata, "albumTitle", "album_title");
        putOptionalText(values, metadata, "coverUrl", "cover_url");
        putOptionalText(values, metadata, "authors", "authors");
        putOptionalText(values, metadata, "chapterId", "chapter_id");
        putOptionalText(values, metadata, "chapterTitle", "chapter_title");
        if (metadata.has("chapterSortOrder")) {
            values.put("chapter_sort_order", metadata.optInt("chapterSortOrder"));
        }
        if (metadata.has("isSingleEpisode")) {
            values.put("is_single_episode", metadata.optBoolean("isSingleEpisode") ? 1 : 0);
        }
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update(TABLE_FILES, values, "id = ?",
            new String[]{String.valueOf(id)});
        return getFile(id);
    }

    public int updateAlbumEpisodeType(String albumId, boolean singleEpisode) {
        ContentValues values = new ContentValues();
        values.put("is_single_episode", singleEpisode ? 1 : 0);
        values.put("updated_at", System.currentTimeMillis());
        return getWritableDatabase().update(TABLE_FILES, values, "album_id = ?",
            new String[]{albumId});
    }

    public void reserveExport(JSONObject task, JSONArray chapters, JSONArray volumes)
            throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            String exportId = task.getString("exportId");
            ContentValues taskValues = new ContentValues();
            taskValues.put("export_id", exportId);
            taskValues.put("batch_id", task.optString("batchId", exportId));
            taskValues.put("mode", task.getString("mode"));
            taskValues.put("album_id", task.getString("albumId"));
            taskValues.put("album_title", task.optString("albumTitle", ""));
            taskValues.put("cover_url", task.optString("coverUrl", ""));
            taskValues.put("authors", task.optString("authors", ""));
            taskValues.put("is_single_episode", task.has("isSingleEpisode")
                ? (task.optBoolean("isSingleEpisode") ? 1 : 0) : -1);
            if (emptyToNull(task.optString("chapterId", null)) == null) {
                taskValues.putNull("chapter_id");
            } else taskValues.put("chapter_id", task.optString("chapterId"));
            taskValues.put("display_title", task.getString("displayTitle"));
            taskValues.put("save_path", normalizeLocator(task.getString("savePath")));
            taskValues.put("allow_overwrite", task.optBoolean("allowOverwrite") ? 1 : 0);
            taskValues.put("use_original", task.optBoolean("useOriginal", true) ? 1 : 0);
            taskValues.put("compression_ratio", task.optDouble("compressionRatio", 1D));
            taskValues.put("split_pages", Math.max(0, task.optInt("splitPages")));
            taskValues.put("status", task.optString("status", "queued"));
            taskValues.put("phase", task.optString("phase", "queued"));
            taskValues.put("total_pages", task.optInt("totalPages"));
            taskValues.put("total_volumes", volumes.length());
            taskValues.put("created_at", task.optLong("createdAt", now));
            taskValues.put("updated_at", now);
            db.insertOrThrow(TABLE_TASKS, null, taskValues);

            for (int index = 0; index < chapters.length(); index++) {
                JSONObject chapter = chapters.getJSONObject(index);
                ContentValues values = new ContentValues();
                values.put("export_id", exportId);
                values.put("sequence", chapter.optInt("sequence", index));
                values.put("album_id", chapter.getString("albumId"));
                values.put("chapter_id", chapter.getString("chapterId"));
                values.put("chapter_title", chapter.optString("chapterTitle", ""));
                values.put("sort_order", chapter.optInt("sortOrder"));
                values.put("expected_page_count", chapter.optInt("expectedPageCount"));
                db.insertOrThrow(TABLE_CHAPTERS, null, values);
            }
            for (int index = 0; index < volumes.length(); index++) {
                JSONObject volume = volumes.getJSONObject(index);
                ContentValues values = new ContentValues();
                values.put("export_id", exportId);
                values.put("volume_index", volume.getInt("volumeIndex"));
                values.put("start_page", volume.getInt("startPage"));
                values.put("end_page", volume.getInt("endPage"));
                values.put("expected_page_count", volume.getInt("expectedPageCount"));
                values.put("final_path", normalizeLocator(volume.getString("finalPath")));
                values.put("temp_path", normalizeLocator(volume.getString("tempPath")));
                values.put("work_dir", normalizeLocator(volume.getString("workDir")));
                values.put("status", "pending");
                values.put("updated_at", now);
                db.insertOrThrow(TABLE_VOLUMES, null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public JSONObject getExportTask(String exportId) {
        try (Cursor cursor = getReadableDatabase().query(TABLE_TASKS, null, "export_id = ?",
                new String[]{exportId}, null, null, null, "1")) {
            return cursor.moveToFirst() ? cursorToTaskJson(cursor) : null;
        }
    }

    public JSONArray getAllExportTasks() {
        JSONArray tasks = new JSONArray();
        try (Cursor cursor = getReadableDatabase().query(TABLE_TASKS, null, null, null,
                null, null, "updated_at DESC, export_id DESC")) {
            while (cursor.moveToNext()) tasks.put(cursorToTaskJson(cursor));
        }
        return tasks;
    }

    public JSONObject getExportTasksPage(String status, String cursor, int requestedLimit) {
        int limit = Math.max(1, Math.min(100, requestedLimit));
        CursorPosition position = CursorPosition.parse(cursor);
        List<String> clauses = new ArrayList<>();
        List<String> args = new ArrayList<>();
        if (status != null && !status.isEmpty()) {
            clauses.add("status = ?");
            args.add(status);
        }
        if (position != null) {
            clauses.add("(updated_at < ? OR (updated_at = ? AND export_id < ?))");
            args.add(String.valueOf(position.timestamp));
            args.add(String.valueOf(position.timestamp));
            args.add(position.id);
        }
        JSONArray tasks = new JSONArray();
        String nextCursor = null;
        try (Cursor result = getReadableDatabase().query(TABLE_TASKS, null,
                joinClauses(clauses), args.toArray(new String[0]), null, null,
                "updated_at DESC, export_id DESC", String.valueOf(limit + 1))) {
            while (result.moveToNext() && tasks.length() < limit) tasks.put(cursorToTaskJson(result));
            if (!result.isAfterLast()) {
                JSONObject last = tasks.optJSONObject(tasks.length() - 1);
                nextCursor = CursorPosition.encode(last.optLong("updatedAt"),
                    last.optString("exportId"));
            }
        }
        return page("tasks", tasks, nextCursor);
    }

    public JSONArray getExportChapters(String exportId) {
        JSONArray chapters = new JSONArray();
        try (Cursor cursor = getReadableDatabase().query(TABLE_CHAPTERS, null,
                "export_id = ?", new String[]{exportId}, null, null, "sequence ASC")) {
            while (cursor.moveToNext()) {
                JSONObject chapter = new JSONObject();
                try {
                    chapter.put("sequence", getInt(cursor, "sequence"));
                    chapter.put("albumId", getString(cursor, "album_id"));
                    chapter.put("chapterId", getString(cursor, "chapter_id"));
                    chapter.put("chapterTitle", getString(cursor, "chapter_title"));
                    chapter.put("sortOrder", getInt(cursor, "sort_order"));
                    chapter.put("expectedPageCount", getInt(cursor, "expected_page_count"));
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
                chapters.put(chapter);
            }
        }
        return chapters;
    }

    public JSONArray getExportVolumes(String exportId) {
        JSONArray volumes = new JSONArray();
        try (Cursor cursor = getReadableDatabase().query(TABLE_VOLUMES, null,
                "export_id = ?", new String[]{exportId}, null, null, "volume_index ASC")) {
            while (cursor.moveToNext()) volumes.put(cursorToVolumeJson(cursor));
        }
        return volumes;
    }

    public JSONObject getExportVolume(String exportId, int volumeIndex) {
        try (Cursor cursor = getReadableDatabase().query(TABLE_VOLUMES, null,
                "export_id = ? AND volume_index = ?",
                new String[]{exportId, String.valueOf(volumeIndex)}, null, null, null, "1")) {
            return cursor.moveToFirst() ? cursorToVolumeJson(cursor) : null;
        }
    }

    public boolean prepareExportRetry(String exportId, boolean allowOverwrite) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues task = new ContentValues();
            task.put("status", "queued");
            task.put("phase", "queued");
            task.put("allow_overwrite", allowOverwrite ? 1 : 0);
            task.put("cancel_requested", 0);
            task.put("current_page", 0);
            task.put("current_volume", 0);
            task.putNull("error_code");
            task.putNull("error_message");
            task.putNull("started_at");
            task.putNull("completed_at");
            task.put("updated_at", System.currentTimeMillis());
            int updated = db.update(TABLE_TASKS, task,
                "export_id = ? AND status IN ('completed','failed','cancelled','partial','interrupted')",
                new String[]{exportId});
            if (updated != 1) return false;
            ContentValues volume = new ContentValues();
            volume.put("status", "pending");
            volume.put("actual_page_count", 0);
            volume.put("file_size", 0);
            volume.putNull("completed_at");
            volume.put("updated_at", System.currentTimeMillis());
            db.update(TABLE_VOLUMES, volume, "export_id = ?", new String[]{exportId});
            incrementSnapshotRevision(db, exportId);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    public boolean claimQueuedExport(String exportId) {
        ContentValues values = new ContentValues();
        long now = System.currentTimeMillis();
        values.put("status", "running");
        values.put("phase", "preparing");
        values.put("started_at", now);
        values.put("updated_at", now);
        int updated = getWritableDatabase().update(TABLE_TASKS, values,
            "export_id = ? AND status = 'queued'", new String[]{exportId});
        if (updated == 1) incrementSnapshotRevision(getWritableDatabase(), exportId);
        return updated == 1;
    }

    public JSONObject requestExportCancel(String exportId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            JSONObject task = getExportTask(exportId);
            if (task == null) return null;
            String status = task.optString("status");
            ContentValues values = new ContentValues();
            if ("queued".equals(status)) {
                values.put("status", "cancelled");
                values.put("phase", "cancelled");
                values.put("completed_at", System.currentTimeMillis());
            } else if ("running".equals(status)) {
                values.put("status", "cancelling");
                values.put("phase", "cancelling");
                values.put("cancel_requested", 1);
            }
            if (values.size() > 0) {
                values.put("updated_at", System.currentTimeMillis());
                db.update(TABLE_TASKS, values, "export_id = ? AND status = ?",
                    new String[]{exportId, status});
                incrementSnapshotRevision(db, exportId);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return getExportTask(exportId);
    }

    public boolean isCancelRequested(String exportId) {
        JSONObject task = getExportTask(exportId);
        return task != null && (task.optBoolean("cancelRequested")
            || "cancelled".equals(task.optString("status"))
            || "cancelling".equals(task.optString("status")));
    }

    public JSONObject updateExportProgress(String exportId, String status, String phase,
            int currentPage, int totalPages, int currentVolume, int totalVolumes,
            String errorCode, String errorMessage) {
        ContentValues values = new ContentValues();
        long now = System.currentTimeMillis();
        values.put("status", status);
        values.put("phase", phase);
        values.put("current_page", currentPage);
        values.put("total_pages", totalPages);
        values.put("current_volume", currentVolume);
        values.put("total_volumes", totalVolumes);
        if (errorCode == null) values.putNull("error_code");
        else values.put("error_code", errorCode);
        if (errorMessage == null) values.putNull("error_message");
        else values.put("error_message", errorMessage);
        if (isTerminalExportStatus(status)) values.put("completed_at", now);
        values.put("updated_at", now);
        String where = "export_id = ?";
        if ("running".equals(status)) where += " AND status = 'running'";
        int updated = getWritableDatabase().update(TABLE_TASKS, values, where,
            new String[]{exportId});
        if (updated == 1) incrementSnapshotRevision(getWritableDatabase(), exportId);
        return getExportTask(exportId);
    }

    public void markVolumeWriting(String exportId, int volumeIndex) {
        ContentValues values = new ContentValues();
        values.put("status", "writing");
        values.put("updated_at", System.currentTimeMillis());
        updateVolumeOrThrow(exportId, volumeIndex, values);
    }

    public void markVolumeOutcome(String exportId, int volumeIndex, String status) {
        if (volumeIndex <= 0) return;
        JSONObject volume = getExportVolume(exportId, volumeIndex);
        if (volume == null || "completed".equals(volume.optString("status"))) return;
        ContentValues values = new ContentValues();
        values.put("status", status);
        values.put("updated_at", System.currentTimeMillis());
        updateVolumeOrThrow(exportId, volumeIndex, values);
    }

    public void completeVolumeAndRegisterFile(String exportId, int volumeIndex,
            String finalPath, long fileSize, int pageCount, String mode, String albumId,
            String albumTitle, String coverUrl, String authors, String chapterId,
            String chapterTitle, int chapterSortOrder, int singleEpisode) throws IOException {
        ContentValues values = new ContentValues();
        long now = System.currentTimeMillis();
        values.put("status", "completed");
        values.put("actual_page_count", pageCount);
        values.put("file_size", fileSize);
        values.put("updated_at", now);
        values.put("completed_at", now);
        updateVolumeOrThrow(exportId, volumeIndex, values);
        registerExportedPdf(finalPath, locatorFileName(finalPath), mode, albumId, albumTitle,
            coverUrl, authors, chapterId, chapterTitle, chapterSortOrder, singleEpisode,
            fileSize, pageCount);
    }

    public int countCompletedVolumes(String exportId) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_VOLUMES
                    + " WHERE export_id = ? AND status = 'completed'",
                new String[]{exportId})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public int markActiveTasksInterrupted() {
        ContentValues values = new ContentValues();
        long now = System.currentTimeMillis();
        values.put("status", "interrupted");
        values.put("phase", "interrupted");
        values.put("error_code", "PROCESS_INTERRUPTED");
        values.put("error_message", "应用上次运行期间中断，可重新导出");
        values.put("completed_at", now);
        values.put("updated_at", now);
        return getWritableDatabase().update(TABLE_TASKS, values,
            "status IN ('queued','running','cancelling')", null);
    }

    public boolean deleteExportTask(String exportId) {
        return getWritableDatabase().delete(TABLE_TASKS,
            "export_id = ? AND status IN ('completed','failed','cancelled','partial','interrupted')",
            new String[]{exportId}) == 1;
    }

    public JSONObject getManagementState() {
        JSONObject result = new JSONObject();
        try {
            result.put("recoveryState", "ready");
            try (Cursor cursor = getReadableDatabase().query(TABLE_META, null, "id = 1",
                    null, null, null, null, "1")) {
                if (cursor.moveToFirst()) {
                    JSONObject reset = new JSONObject();
                    reset.put("pending", getInt(cursor, "reset_notice_pending") == 1);
                    putNullableLong(reset, "resetAt", cursor, "last_reset_at");
                    putNullableInt(reset, "fromVersion", cursor, "reset_from_version");
                    putNullableString(reset, "reason", cursor, "reset_reason");
                    result.put("databaseResetInfo", reset);
                }
            }
        } catch (Exception error) {
            throw new IllegalStateException("无法读取 PDF 管理状态", error);
        }
        return result;
    }

    public boolean acknowledgeDatabaseReset() {
        ContentValues values = new ContentValues();
        values.put("reset_notice_pending", 0);
        values.put("updated_at", System.currentTimeMillis());
        return getWritableDatabase().update(TABLE_META, values,
            "id = 1 AND reset_notice_pending = 1", null) > 0;
    }

    public long countFiles() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_FILES, null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    private void updateVolumeOrThrow(String exportId, int volumeIndex, ContentValues values) {
        int updated = getWritableDatabase().update(TABLE_VOLUMES, values,
            "export_id = ? AND volume_index = ?",
            new String[]{exportId, String.valueOf(volumeIndex)});
        if (updated != 1) {
            throw new IllegalStateException("PDF 分卷记录不存在: " + exportId + "/" + volumeIndex);
        }
    }

    private static void incrementSnapshotRevision(SQLiteDatabase db, String exportId) {
        db.execSQL("UPDATE " + TABLE_TASKS
            + " SET snapshot_revision = snapshot_revision + 1 WHERE export_id = ?",
            new Object[]{exportId});
    }

    public static boolean isTerminalExportStatus(String status) {
        return "completed".equals(status) || "failed".equals(status)
            || "cancelled".equals(status) || "partial".equals(status)
            || "interrupted".equals(status);
    }

    public static String normalizeLocator(String filePath) throws IOException {
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("filePath is required");
        }
        String trimmed = filePath.trim();
        if (trimmed.startsWith("content://")) {
            return Uri.parse(trimmed).normalizeScheme().toString();
        }
        return new File(trimmed).getCanonicalPath();
    }

    private static JSONObject cursorToFileJson(Cursor cursor) {
        JSONObject result = new JSONObject();
        try {
            result.put("id", getLong(cursor, "id"));
            result.put("filePath", getString(cursor, "file_path"));
            result.put("fileName", getString(cursor, "file_name"));
            result.put("sourceType", getString(cursor, "source_type"));
            result.put("ownership", getString(cursor, "ownership"));
            result.put("chapterLinkStatus", getString(cursor, "chapter_link_status"));
            result.put("albumId", getString(cursor, "album_id"));
            result.put("albumTitle", getString(cursor, "album_title"));
            result.put("coverUrl", getString(cursor, "cover_url"));
            result.put("authors", getString(cursor, "authors"));
            putNullableString(result, "chapterId", cursor, "chapter_id");
            result.put("chapterTitle", getString(cursor, "chapter_title"));
            result.put("chapterSortOrder", getInt(cursor, "chapter_sort_order"));
            int singleEpisode = getInt(cursor, "is_single_episode");
            if (singleEpisode >= 0) result.put("isSingleEpisode", singleEpisode == 1);
            putNullableString(result, "folderId", cursor, "folder_id");
            result.put("fileSize", getLong(cursor, "file_size"));
            result.put("pageCount", getInt(cursor, "page_count"));
            result.put("availability", getString(cursor, "availability"));
            result.put("verificationStatus", getString(cursor, "verification_status"));
            putNullableString(result, "verificationError", cursor, "verification_error");
            result.put("createdAt", getLong(cursor, "created_at"));
            result.put("updatedAt", getLong(cursor, "updated_at"));
            putNullableLong(result, "verifiedAt", cursor, "verified_at");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        return result;
    }

    private static JSONObject cursorToTaskJson(Cursor cursor) {
        JSONObject result = new JSONObject();
        try {
            result.put("exportId", getString(cursor, "export_id"));
            result.put("batchId", getString(cursor, "batch_id"));
            result.put("mode", getString(cursor, "mode"));
            result.put("albumId", getString(cursor, "album_id"));
            result.put("albumTitle", getString(cursor, "album_title"));
            result.put("coverUrl", getString(cursor, "cover_url"));
            result.put("authors", getString(cursor, "authors"));
            int singleEpisode = getInt(cursor, "is_single_episode");
            if (singleEpisode >= 0) result.put("isSingleEpisode", singleEpisode == 1);
            putNullableString(result, "chapterId", cursor, "chapter_id");
            result.put("displayTitle", getString(cursor, "display_title"));
            result.put("savePath", getString(cursor, "save_path"));
            result.put("allowOverwrite", getInt(cursor, "allow_overwrite") == 1);
            result.put("useOriginal", getInt(cursor, "use_original") == 1);
            result.put("compressionRatio", cursor.getDouble(
                cursor.getColumnIndexOrThrow("compression_ratio")));
            result.put("splitPages", getInt(cursor, "split_pages"));
            result.put("status", getString(cursor, "status"));
            result.put("phase", getString(cursor, "phase"));
            result.put("currentPage", getInt(cursor, "current_page"));
            result.put("totalPages", getInt(cursor, "total_pages"));
            result.put("currentVolume", getInt(cursor, "current_volume"));
            result.put("totalVolumes", getInt(cursor, "total_volumes"));
            result.put("snapshotRevision", getLong(cursor, "snapshot_revision"));
            result.put("cancelRequested", getInt(cursor, "cancel_requested") == 1);
            putNullableString(result, "errorCode", cursor, "error_code");
            putNullableString(result, "errorMessage", cursor, "error_message");
            result.put("createdAt", getLong(cursor, "created_at"));
            putNullableLong(result, "startedAt", cursor, "started_at");
            result.put("updatedAt", getLong(cursor, "updated_at"));
            putNullableLong(result, "completedAt", cursor, "completed_at");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        return result;
    }

    private static JSONObject cursorToVolumeJson(Cursor cursor) {
        JSONObject result = new JSONObject();
        try {
            result.put("exportId", getString(cursor, "export_id"));
            result.put("volumeIndex", getInt(cursor, "volume_index"));
            result.put("startPage", getInt(cursor, "start_page"));
            result.put("endPage", getInt(cursor, "end_page"));
            result.put("expectedPageCount", getInt(cursor, "expected_page_count"));
            result.put("actualPageCount", getInt(cursor, "actual_page_count"));
            result.put("finalPath", getString(cursor, "final_path"));
            result.put("tempPath", getString(cursor, "temp_path"));
            result.put("workDir", getString(cursor, "work_dir"));
            result.put("status", getString(cursor, "status"));
            result.put("fileSize", getLong(cursor, "file_size"));
            result.put("updatedAt", getLong(cursor, "updated_at"));
            putNullableLong(result, "completedAt", cursor, "completed_at");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        return result;
    }

    private static JSONObject page(String key, JSONArray values, String nextCursor) {
        JSONObject result = new JSONObject();
        try {
            result.put(key, values);
            result.put("nextCursor", nextCursor == null ? JSONObject.NULL : nextCursor);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        return result;
    }

    private static void putOptionalText(ContentValues values, JSONObject source,
            String jsonKey, String column) {
        if (!source.has(jsonKey)) return;
        if (source.isNull(jsonKey)) values.putNull(column);
        else values.put(column, source.optString(jsonKey, ""));
    }

    private static String locatorFileName(String locator) {
        if (!locator.startsWith("content://")) return new File(locator).getName();
        String segment = Uri.parse(locator).getLastPathSegment();
        return segment == null || segment.isEmpty() ? "document.pdf" : segment;
    }

    private static String emptyToFallback(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String joinClauses(List<String> clauses) {
        return clauses.isEmpty() ? null : String.join(" AND ", clauses);
    }

    private static String getString(Cursor cursor, String column) {
        return cursor.getString(cursor.getColumnIndexOrThrow(column));
    }

    private static int getInt(Cursor cursor, String column) {
        return cursor.getInt(cursor.getColumnIndexOrThrow(column));
    }

    private static long getLong(Cursor cursor, String column) {
        return cursor.getLong(cursor.getColumnIndexOrThrow(column));
    }

    private static void putNullableString(JSONObject result, String key,
            Cursor cursor, String column) throws Exception {
        int index = cursor.getColumnIndexOrThrow(column);
        if (!cursor.isNull(index)) result.put(key, cursor.getString(index));
    }

    private static void putNullableInt(JSONObject result, String key,
            Cursor cursor, String column) throws Exception {
        int index = cursor.getColumnIndexOrThrow(column);
        if (!cursor.isNull(index)) result.put(key, cursor.getInt(index));
    }

    private static void putNullableLong(JSONObject result, String key,
            Cursor cursor, String column) throws Exception {
        int index = cursor.getColumnIndexOrThrow(column);
        if (!cursor.isNull(index)) result.put(key, cursor.getLong(index));
    }

    private static final class CursorPosition {
        final long timestamp;
        final String id;

        CursorPosition(long timestamp, String id) {
            this.timestamp = timestamp;
            this.id = id;
        }

        static CursorPosition parse(String cursor) {
            if (cursor == null || cursor.trim().isEmpty()) return null;
            try {
                String value = new String(Base64.decode(cursor,
                    Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.UTF_8);
                int separator = value.indexOf(':');
                if (separator <= 0 || separator == value.length() - 1) {
                    throw new IllegalArgumentException();
                }
                return new CursorPosition(Long.parseLong(value.substring(0, separator)),
                    value.substring(separator + 1));
            } catch (Exception error) {
                throw new IllegalArgumentException("PDF_LIST_CURSOR_INVALID: cursor 无效", error);
            }
        }

        static String encode(long timestamp, String id) {
            String value = timestamp + ":" + id;
            return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        }
    }
}
