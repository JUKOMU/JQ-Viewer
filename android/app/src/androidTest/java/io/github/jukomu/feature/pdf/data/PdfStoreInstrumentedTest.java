package io.github.jukomu.feature.pdf.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.pdf.PdfDocument;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PdfStoreInstrumentedTest {

    private static final String DB_NAME = "jq_pdf_import.db";

    private Context context;
    private final List<File> createdFiles = new ArrayList<>();

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PdfStore.clearInstanceForTest();
        context.deleteDatabase(DB_NAME);
    }

    @After
    public void tearDown() {
        PdfStore.clearInstanceForTest();
        context.deleteDatabase(DB_NAME);
        for (File file : createdFiles) file.delete();
        createdFiles.clear();
    }

    @Test
    public void newDatabaseCreatesVersionTenRefSchemaWithoutResetNotice() throws Exception {
        PdfStore store = PdfStore.getInstance(context);
        SQLiteDatabase database = store.getWritableDatabase();

        assertEquals(10, database.getVersion());
        assertTrue(tableExists(database, PdfStore.TABLE_FILES));
        assertTrue(tableExists(database, PdfStore.TABLE_TASKS));
        assertTrue(tableExists(database, PdfStore.TABLE_CHAPTERS));
        assertTrue(tableExists(database, PdfStore.TABLE_VOLUMES));
        assertTrue(tableExists(database, PdfStore.TABLE_META));
        assertEquals(1L, scalarLong(database, "PRAGMA foreign_keys"));
        assertFalse(store.getManagementState().getJSONObject("databaseResetInfo")
            .optBoolean("pending"));
    }

    @Test
    public void versionNineDatabaseMigratesLegacyImportedPdfWithoutReadingIt() throws Exception {
        File referencedPdf = new File(context.getCacheDir(), "legacy-pdf-store-test.pdf");
        createdFiles.add(referencedPdf);
        try (FileOutputStream output = new FileOutputStream(referencedPdf)) {
            output.write(new byte[]{1, 2, 3});
        }

        File databaseFile = context.getDatabasePath(DB_NAME);
        File parent = databaseFile.getParentFile();
        assertTrue(parent == null || parent.isDirectory() || parent.mkdirs());
        SQLiteDatabase legacy = SQLiteDatabase.openOrCreateDatabase(databaseFile, null);
        legacy.execSQL("CREATE TABLE imported_pdfs (id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "file_path TEXT NOT NULL UNIQUE,file_name TEXT NOT NULL,album_id TEXT NOT NULL,"
            + "created_at INTEGER NOT NULL)");
        legacy.execSQL("INSERT INTO imported_pdfs "
                + "(file_path,file_name,album_id,created_at) VALUES (?,?,?,?)",
            new Object[]{referencedPdf.getAbsolutePath(), referencedPdf.getName(), "1", 1L});
        legacy.setVersion(9);
        legacy.close();

        PdfStore store = PdfStore.getInstance(context);
        SQLiteDatabase upgraded = store.getWritableDatabase();

        assertEquals(10, upgraded.getVersion());
        assertFalse(tableExists(upgraded, "imported_pdfs"));
        assertEquals(1L, store.countFiles());
        assertTrue(referencedPdf.exists());
        JSONObject resetInfo = store.getManagementState().getJSONObject("databaseResetInfo");
        assertTrue(resetInfo.optBoolean("pending"));
        assertEquals(9, resetInfo.optInt("fromVersion"));
        assertEquals("SCHEMA_MIGRATION_V10", resetInfo.optString("reason"));
        assertEquals(1, resetInfo.optInt("migratedCount"));
        assertTrue(store.acknowledgeDatabaseReset());
        assertFalse(store.getManagementState().getJSONObject("databaseResetInfo")
            .optBoolean("pending"));
    }

    @Test
    public void migrationKeepsFirstPdfRecordSkipsBadLocatorAndClearsExportHistory() throws Exception {
        File databaseFile = context.getDatabasePath(DB_NAME);
        File parent = databaseFile.getParentFile();
        assertTrue(parent == null || parent.isDirectory() || parent.mkdirs());
        SQLiteDatabase legacy = SQLiteDatabase.openOrCreateDatabase(databaseFile, null);
        legacy.execSQL("CREATE TABLE pdf_files (id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "file_path TEXT NOT NULL UNIQUE,file_name TEXT NOT NULL,"
            + "source_type TEXT NOT NULL,ownership TEXT NOT NULL,"
            + "chapter_link_status TEXT NOT NULL,album_id TEXT NOT NULL,"
            + "created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
        legacy.execSQL("INSERT INTO pdf_files "
                + "(file_path,file_name,source_type,ownership,chapter_link_status,album_id,created_at,updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?)",
            new Object[]{"/storage/emulated/0/book.pdf", "book.pdf", "imported",
                "external_reference", "resolved", "1", 1L, 1L});
        legacy.execSQL("INSERT INTO pdf_files "
                + "(file_path,file_name,source_type,ownership,chapter_link_status,album_id,created_at,updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?)",
            new Object[]{"content://provider/document/1", "saf.pdf", "imported",
                "external_reference", "resolved", "1", 2L, 2L});
        legacy.execSQL("CREATE TABLE imported_pdfs (id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "file_path TEXT NOT NULL UNIQUE,file_name TEXT NOT NULL,album_id TEXT NOT NULL,"
            + "created_at INTEGER NOT NULL)");
        legacy.execSQL("INSERT INTO imported_pdfs (file_path,file_name,album_id,created_at)"
                + " VALUES (?,?,?,?)",
            new Object[]{"/storage/emulated/0/book.pdf", "duplicate.pdf", "1", 3L});
        legacy.execSQL("INSERT INTO imported_pdfs (file_path,file_name,album_id,created_at)"
                + " VALUES (?,?,?,?)",
            new Object[]{"relative.pdf", "bad.pdf", "1", 4L});
        legacy.setVersion(9);
        legacy.close();

        PdfStore store = PdfStore.getInstance(context);
        SQLiteDatabase upgraded = store.getWritableDatabase();
        assertEquals(10, upgraded.getVersion());
        assertEquals(2L, store.countFiles());
        assertNotNull(store.getFileByRef("file:path:/storage/emulated/0/book.pdf"));
        assertNotNull(store.getFileByRef("file:saf:content://provider/document/1"));
        JSONObject resetInfo = store.getManagementState().getJSONObject("databaseResetInfo");
        assertEquals(2, resetInfo.optInt("migratedCount"));
        assertEquals(2, resetInfo.optInt("skippedCount"));
        assertEquals("unknown",
            store.getFileByRef("file:path:/storage/emulated/0/book.pdf")
                .getString("availability"));
        assertEquals("unverified",
            store.getFileByRef("file:path:/storage/emulated/0/book.pdf")
                .getString("verificationStatus"));
        assertEquals("unknown",
            store.getFileByRef("file:saf:content://provider/document/1")
                .getString("availability"));
        assertEquals("unverified",
            store.getFileByRef("file:saf:content://provider/document/1")
                .getString("verificationStatus"));
        assertTrue(resetInfo.optBoolean("exportHistoryCleared"));
        assertTrue(resetInfo.optBoolean("exportFolderReset"));
    }

    @Test
    public void progressRevisionIsAtomicAndStartedAtIsStable() throws Exception {
        PdfStore store = PdfStore.getInstance(context);
        reserveTask(store, "export-progress", "queued", 1L, true);

        assertTrue(store.claimQueuedExport("export-progress"));
        JSONObject first = store.updateExportProgress(
            "export-progress", "running", "writing", 1, 2, 1, 1, null, null);
        JSONObject second = store.updateExportProgress(
            "export-progress", "running", "writing", 2, 2, 1, 1, null, null);

        assertTrue(first.optLong("startedAt") > 0L);
        assertEquals(first.optLong("startedAt"), second.optLong("startedAt"));
        assertEquals(first.optLong("snapshotRevision") + 1L,
            second.optLong("snapshotRevision"));
    }

    @Test
    public void retryResetsEveryVolumeInsteadOfReusingCompletedVolume() throws Exception {
        PdfStore store = PdfStore.getInstance(context);
        reserveTask(store, "export-retry", "queued", 1L, true);
        assertTrue(store.claimQueuedExport("export-retry"));
        JSONObject volume = store.getExportVolume("export-retry", 1);
        store.completeVolumeAndRegisterFile("export-retry", 1,
            PdfRef.createPathFileRef(volume.getString("tempPath")),
            volume.getString("displayPath"), volume.getString("targetName"), 100L, 2, "chapter",
            "album-1", "漫画", "", "", "chapter-1", "第一话", 1, -1);
        store.updateExportProgress(
            "export-retry", "completed", "completed", 2, 2, 1, 1, null, null);

        assertTrue(store.prepareExportRetry("export-retry", true));
        assertEquals("queued", store.getExportTask("export-retry").optString("status"));
        assertEquals("pending", store.getExportVolume("export-retry", 1).optString("status"));
    }

    @Test
    public void retrySecondVolumeFailureCountsOnlyFirstRewrittenVolume() throws Exception {
        PdfStore store = PdfStore.getInstance(context);
        reserveTask(store, "export-partial-retry", "queued", 1L, 3);
        assertTrue(store.claimQueuedExport("export-partial-retry"));
        for (int index = 1; index <= 3; index++) {
            JSONObject volume = store.getExportVolume("export-partial-retry", index);
            File oldFinal = new File(volume.getString("tempPath"));
            createdFiles.add(oldFinal);
            try (FileOutputStream output = new FileOutputStream(oldFinal)) {
                output.write(index);
            }
            store.completeVolumeAndRegisterFile("export-partial-retry", index,
                PdfRef.createPathFileRef(oldFinal.getCanonicalPath()), oldFinal.getCanonicalPath(),
                oldFinal.getName(), oldFinal.length(), 1, "chapter",
                "album-1", "漫画", "", "", "chapter-1", "第一话", 1, -1);
        }
        store.updateExportProgress("export-partial-retry", "completed", "completed",
            3, 3, 3, 3, null, null);

        assertTrue(store.prepareExportRetry("export-partial-retry", true));
        assertTrue(store.claimQueuedExport("export-partial-retry"));
        JSONObject first = store.getExportVolume("export-partial-retry", 1);
        store.completeVolumeAndRegisterFile("export-partial-retry", 1,
            PdfRef.createPathFileRef(first.getString("tempPath")), first.getString("displayPath"),
            first.getString("targetName"), 2L, 1, "chapter",
            "album-1", "漫画", "", "", "chapter-1", "第一话", 1, -1);
        store.markVolumeOutcome("export-partial-retry", 2, "failed");
        store.updateExportProgress("export-partial-retry", "partial", "partial",
            1, 3, 1, 3, "PDF_EXPORT_FAILED", "第二卷失败");

        assertEquals(1, store.countCompletedVolumes("export-partial-retry"));
        assertEquals("completed", store.getExportVolume("export-partial-retry", 1)
            .optString("status"));
        assertEquals("failed", store.getExportVolume("export-partial-retry", 2)
            .optString("status"));
        assertEquals("pending", store.getExportVolume("export-partial-retry", 3)
            .optString("status"));
        assertTrue(new File(store.getExportVolume("export-partial-retry", 3)
            .getString("tempPath")).exists());
    }

    @Test
    public void fileAndTaskCursorPaginationRejectInvalidCursor() throws Exception {
        PdfStore store = PdfStore.getInstance(context);
        insertImportedPdf(store, "cursor-1.pdf", "folder-a", 1L);
        insertImportedPdf(store, "cursor-2.pdf", "folder-a", 2L);
        insertImportedPdf(store, "cursor-3.pdf", "folder-b", 3L);

        JSONObject firstFiles = store.getFilesPage(null, null, "folder-a", null, null, 1);
        JSONObject secondFiles = store.getFilesPage(null, null, "folder-a", null,
            firstFiles.getString("nextCursor"), 1);
        assertEquals(1, firstFiles.getJSONArray("files").length());
        assertEquals(1, secondFiles.getJSONArray("files").length());
        assertTrue(secondFiles.isNull("nextCursor"));
        assertThrows(IllegalArgumentException.class,
            () -> store.getFilesPage(null, null, null, null, "not-a-cursor", 10));

        reserveTask(store, "export-a", "failed", 1L, false);
        reserveTask(store, "export-b", "cancelled", 2L, false);
        reserveTask(store, "export-c", "interrupted", 3L, false);
        JSONObject firstTasks = store.getExportTasksPage(null, null, 2);
        JSONObject secondTasks = store.getExportTasksPage(
            null, firstTasks.getString("nextCursor"), 2);
        assertEquals(2, firstTasks.getJSONArray("tasks").length());
        assertEquals(1, secondTasks.getJSONArray("tasks").length());
        assertTrue(secondTasks.isNull("nextCursor"));
        assertThrows(IllegalArgumentException.class,
            () -> store.getExportTasksPage(null, "invalid", 2));
    }

    @Test
    public void getAllFilesReturnsMoreThanOnePage() throws Exception {
        PdfStore store = PdfStore.getInstance(context);
        for (int index = 0; index < 105; index++) {
            String name = "library-" + index + ".pdf";
            long id = store.insertImportedPdf(
                PdfRef.createPathFileRef(new File(context.getCacheDir(), name).getCanonicalPath()),
                new File(context.getCacheDir(), name).getCanonicalPath(),
                name, "album-1", "漫画", "", "", name, name, 0, -1,
                index, null, 0L, 1);
            assertTrue(id > 0L);
        }

        assertEquals(105, store.getAllFiles().length());
    }

    @Test
    public void onlyTerminalTasksCanBeDeleted() throws Exception {
        PdfStore store = PdfStore.getInstance(context);
        reserveTask(store, "queued", "queued", 1L, false);
        reserveTask(store, "running", "running", 2L, false);
        reserveTask(store, "cancelling", "cancelling", 3L, false);
        reserveTask(store, "completed", "completed", 4L, false);
        reserveTask(store, "failed", "failed", 5L, false);
        reserveTask(store, "cancelled", "cancelled", 6L, false);
        reserveTask(store, "partial", "partial", 7L, false);
        reserveTask(store, "interrupted", "interrupted", 8L, false);

        assertFalse(store.deleteExportTask("queued"));
        assertFalse(store.deleteExportTask("running"));
        assertFalse(store.deleteExportTask("cancelling"));
        for (String terminal : new String[]{"completed", "failed", "cancelled", "partial", "interrupted"}) {
            assertTrue(store.deleteExportTask(terminal));
        }
        assertNotNull(store.getExportTask("queued"));
    }

    private void insertImportedPdf(PdfStore store, String name, String folderId,
                                   long createdAt) throws Exception {
        File file = createPdf(name);
        long id = store.insertImportedPdf(PdfRef.createPathFileRef(file.getCanonicalPath()),
            file.getCanonicalPath(), name, "album-1",
            "漫画", "", "", name, name, 0, -1, createdAt, folderId,
            file.length(), 1);
        assertTrue(id > 0L);
    }

    private File createPdf(String name) throws Exception {
        File file = new File(context.getCacheDir(), name);
        createdFiles.add(file);
        PdfDocument document = new PdfDocument();
        try {
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(10, 10, 1).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            document.finishPage(page);
            try (FileOutputStream output = new FileOutputStream(file)) {
                document.writeTo(output);
            }
        } finally {
            document.close();
        }
        return file;
    }

    private void reserveTask(PdfStore store, String exportId, String status,
                             long createdAt, boolean withVolume) throws Exception {
        reserveTask(store, exportId, status, createdAt, withVolume ? 1 : 0);
    }

    private void reserveTask(PdfStore store, String exportId, String status,
                             long createdAt, int volumeCount) throws Exception {
        JSONObject task = new JSONObject();
        task.put("exportId", exportId);
        task.put("batchId", "batch-" + exportId);
        task.put("mode", "chapter");
        task.put("albumId", "album-1");
        task.put("chapterId", "chapter-1");
        task.put("displayTitle", exportId);
        String targetPath = new File(context.getCacheDir(), exportId + ".pdf").getCanonicalPath();
        task.put("targetFolderRef", PdfRef.createPathFolderRef(context.getCacheDir().getCanonicalPath()));
        task.put("targetName", exportId + ".pdf");
        task.put("displayPath", targetPath);
        task.put("status", status);
        task.put("phase", status);
        task.put("createdAt", createdAt);
        JSONArray volumes = new JSONArray();
        for (int index = 1; index <= volumeCount; index++) {
            JSONObject volume = new JSONObject();
            String finalPath = task.getString("displayPath").replace(".pdf", "-" + index + ".pdf");
            volume.put("volumeIndex", index);
            volume.put("startPage", index - 1);
            volume.put("endPage", index);
            volume.put("expectedPageCount", 1);
            volume.put("targetName", new File(finalPath).getName());
            volume.put("displayPath", finalPath);
            volume.put("tempPath", finalPath + ".tmp");
            volume.put("workDir", finalPath + ".work");
            volumes.put(volume);
        }
        store.reserveExport(task, new JSONArray(), volumes);
    }

    private static boolean tableExists(SQLiteDatabase database, String table) {
        try (Cursor cursor = database.rawQuery(
            "SELECT COUNT(name) FROM sqlite_master WHERE type='table' AND name=?",
            new String[]{table})) {
            return cursor.moveToFirst() && cursor.getInt(0) == 1;
        }
    }

    private static long scalarLong(SQLiteDatabase database, String sql) {
        try (Cursor cursor = database.rawQuery(sql, null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1L;
        }
    }
}
