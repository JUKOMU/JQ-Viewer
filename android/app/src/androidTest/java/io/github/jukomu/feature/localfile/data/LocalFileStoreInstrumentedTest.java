package io.github.jukomu.feature.localfile.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
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
public class LocalFileStoreInstrumentedTest {

    private static final String DB_NAME = "jq_pdf_import.db";

    private Context context;
    private final List<File> createdFiles = new ArrayList<>();

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LocalFileStore.clearInstanceForTest();
        context.deleteDatabase(DB_NAME);
    }

    @After
    public void tearDown() {
        LocalFileStore.clearInstanceForTest();
        context.deleteDatabase(DB_NAME);
        for (File file : createdFiles) file.delete();
        createdFiles.clear();
    }

    @Test
    public void newDatabaseCreatesVersionElevenGenericSchemaWithoutResetNotice() throws Exception {
        LocalFileStore store = LocalFileStore.getInstance(context);
        SQLiteDatabase database = store.getWritableDatabase();

        assertEquals(11, database.getVersion());
        assertTrue(tableExists(database, LocalFileStore.TABLE_FILES));
        assertTrue(tableExists(database, LocalFileStore.TABLE_FILE_CHAPTERS));
        assertTrue(tableExists(database, LocalFileStore.TABLE_TASKS));
        assertTrue(tableExists(database, LocalFileStore.TABLE_CHAPTERS));
        assertTrue(tableExists(database, LocalFileStore.TABLE_VOLUMES));
        assertTrue(tableExists(database, LocalFileStore.TABLE_META));
        assertEquals(1L, scalarLong(database, "PRAGMA foreign_keys"));
        assertFalse(store.getManagementState().getJSONObject("databaseResetInfo")
            .optBoolean("pending"));
    }

    @Test
    public void versionTenDatabaseMigratesFilesTasksVolumesAndChapterRanges() throws Exception {
        File databaseFile = context.getDatabasePath(DB_NAME);
        File parent = databaseFile.getParentFile();
        assertTrue(parent == null || parent.isDirectory() || parent.mkdirs());
        SQLiteDatabase legacy = SQLiteDatabase.openOrCreateDatabase(databaseFile, null);
        legacy.execSQL("CREATE TABLE pdf_files (id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "file_ref TEXT NOT NULL UNIQUE,display_path TEXT NOT NULL DEFAULT '',file_name TEXT NOT NULL,"
            + "source_type TEXT NOT NULL,ownership TEXT NOT NULL,chapter_link_status TEXT NOT NULL,"
            + "album_id TEXT NOT NULL,album_title TEXT NOT NULL DEFAULT '',cover_url TEXT NOT NULL DEFAULT '',"
            + "authors TEXT NOT NULL DEFAULT '',chapter_id TEXT,chapter_title TEXT NOT NULL DEFAULT '',"
            + "chapter_sort_order INTEGER NOT NULL DEFAULT 0,is_single_episode INTEGER NOT NULL DEFAULT -1,"
            + "folder_id TEXT,file_size INTEGER NOT NULL DEFAULT 0,page_count INTEGER NOT NULL DEFAULT 0,"
            + "availability TEXT NOT NULL DEFAULT 'unknown',verification_status TEXT NOT NULL DEFAULT 'unverified',"
            + "verification_error TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,verified_at INTEGER)");
        legacy.execSQL("CREATE TABLE pdf_export_tasks (export_id TEXT PRIMARY KEY,batch_id TEXT NOT NULL,"
            + "mode TEXT NOT NULL,album_id TEXT NOT NULL,album_title TEXT NOT NULL DEFAULT '',cover_url TEXT NOT NULL DEFAULT '',"
            + "authors TEXT NOT NULL DEFAULT '',is_single_episode INTEGER NOT NULL DEFAULT -1,chapter_id TEXT,"
            + "display_title TEXT NOT NULL,target_folder_ref TEXT NOT NULL,target_name TEXT NOT NULL,display_path TEXT NOT NULL DEFAULT '',"
            + "allow_overwrite INTEGER NOT NULL DEFAULT 0,use_original INTEGER NOT NULL,compression_ratio REAL NOT NULL,"
            + "split_pages INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL,phase TEXT NOT NULL,current_page INTEGER NOT NULL DEFAULT 0,"
            + "total_pages INTEGER NOT NULL DEFAULT 0,current_volume INTEGER NOT NULL DEFAULT 0,total_volumes INTEGER NOT NULL DEFAULT 0,"
            + "snapshot_revision INTEGER NOT NULL DEFAULT 0,cancel_requested INTEGER NOT NULL DEFAULT 0,error_code TEXT,error_message TEXT,"
            + "created_at INTEGER NOT NULL,started_at INTEGER,updated_at INTEGER NOT NULL,completed_at INTEGER)");
        legacy.execSQL("CREATE TABLE pdf_export_chapters (export_id TEXT NOT NULL,sequence INTEGER NOT NULL,"
            + "album_id TEXT NOT NULL,chapter_id TEXT NOT NULL,chapter_title TEXT NOT NULL DEFAULT '',sort_order INTEGER NOT NULL DEFAULT 0,"
            + "expected_page_count INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(export_id,sequence))");
        legacy.execSQL("CREATE TABLE pdf_export_volumes (export_id TEXT NOT NULL,volume_index INTEGER NOT NULL,"
            + "start_page INTEGER NOT NULL,end_page INTEGER NOT NULL,expected_page_count INTEGER NOT NULL,actual_page_count INTEGER NOT NULL DEFAULT 0,"
            + "target_name TEXT NOT NULL,output_file_ref TEXT,display_path TEXT NOT NULL DEFAULT '',temp_path TEXT NOT NULL,work_dir TEXT NOT NULL,"
            + "status TEXT NOT NULL DEFAULT 'pending',file_size INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL,completed_at INTEGER,"
            + "PRIMARY KEY(export_id,volume_index))");
        legacy.execSQL("CREATE TABLE pdf_store_meta (id INTEGER PRIMARY KEY)");
        legacy.execSQL("INSERT INTO pdf_files VALUES "
            + "(1,'file:path:/imports/imported.pdf','Imported','imported.pdf','imported','external_reference','resolved','album-1','Album','','Author',"
            + "'chapter-imported','Imported',1,0,'folder-1',40,4,'available','valid',NULL,10,11,11),"
            + "(2,'file:path:/exports/merged.pdf','Exported','merged.pdf','exported','app_created','multi_chapter','album-1','Album','','Author',"
            + "NULL,'',0,0,NULL,50,5,'available','valid',NULL,20,21,21)");
        legacy.execSQL("INSERT INTO pdf_export_tasks VALUES ('export-1','batch-1','merged','album-1','Album','','Author',0,NULL,"
            + "'Merged','folder:path:/exports','merged.pdf','Exported',0,1,1.0,0,'completed','completed',5,5,1,1,1,0,NULL,NULL,20,20,21,21)");
        legacy.execSQL("INSERT INTO pdf_export_chapters VALUES "
            + "('export-1',0,'album-1','chapter-1','Chapter 1',1,2),('export-1',1,'album-1','chapter-2','Chapter 2',2,3)");
        legacy.execSQL("INSERT INTO pdf_export_volumes VALUES "
            + "('export-1',1,0,5,5,5,'merged.pdf','file:path:/exports/merged.pdf','Exported','temp.pdf','work','completed',50,21,21)");
        legacy.setVersion(10);
        legacy.close();

        LocalFileStore store = LocalFileStore.getInstance(context);
        SQLiteDatabase upgraded = store.getWritableDatabase();
        assertEquals(11, upgraded.getVersion());
        assertEquals(2L, store.countFiles());
        JSONArray importedFiles = store.getAllFiles(null, LocalFileStore.SOURCE_IMPORTED);
        assertEquals(1, importedFiles.length());
        assertEquals("imported.pdf", importedFiles.getJSONObject(0).getString("fileName"));
        assertEquals("pdf", store.getFileByRef("file:path:/imports/imported.pdf").getString("format"));
        assertEquals("pdf", store.getExportTask("export-1").getString("format"));
        JSONArray chapters = store.getFileByRef("file:path:/exports/merged.pdf")
            .getJSONArray("chapters");
        assertEquals(2, chapters.length());
        assertEquals("chapter-1", chapters.getJSONObject(0).getString("chapterId"));
        assertEquals(1, chapters.getJSONObject(0).getInt("startPage"));
        assertEquals(2, chapters.getJSONObject(0).getInt("endPage"));
        assertEquals("chapter-2", chapters.getJSONObject(1).getString("chapterId"));
        assertEquals(3, chapters.getJSONObject(1).getInt("startPage"));
        assertEquals(5, chapters.getJSONObject(1).getInt("endPage"));
        assertFalse(tableExists(upgraded, "pdf_files"));
        assertFalse(tableExists(upgraded, "pdf_export_tasks"));
        JSONObject resetInfo = store.getManagementState().getJSONObject("databaseResetInfo");
        assertFalse(resetInfo.optBoolean("pending"));
        assertEquals(2, resetInfo.optInt("migratedCount"));
    }

    @Test
    public void unsupportedDatabaseVersionIsRejectedWithoutFallback() {
        File databaseFile = context.getDatabasePath(DB_NAME);
        File parent = databaseFile.getParentFile();
        assertTrue(parent == null || parent.isDirectory() || parent.mkdirs());
        SQLiteDatabase legacy = SQLiteDatabase.openOrCreateDatabase(databaseFile, null);
        legacy.setVersion(9);
        legacy.close();

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> LocalFileStore.getInstance(context).getWritableDatabase());

        assertTrue(error.getMessage().contains(
            "Unsupported local file database upgrade: 9 -> 11"));
    }

    @Test
    public void progressRevisionIsAtomicAndStartedAtIsStable() throws Exception {
        LocalFileStore store = LocalFileStore.getInstance(context);
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
        LocalFileStore store = LocalFileStore.getInstance(context);
        reserveTask(store, "export-retry", "queued", 1L, true);
        assertTrue(store.claimQueuedExport("export-retry"));
        JSONObject volume = store.getExportVolume("export-retry", 1);
        store.completeVolumeAndRegisterFile("export-retry", 1,
            LocalFileRef.createPathFileRef(volume.getString("tempPath")),
            volume.getString("displayPath"), volume.getString("targetName"), 100L, 2, "chapter",
            "album-1", "漫画", "", "", "chapter-1", "第一话", 1, -1);
        store.updateExportProgress(
            "export-retry", "completed", "completed", 2, 2, 1, 1, null, null);

        assertTrue(store.prepareExportRetry("export-retry", true));
        assertEquals("queued", store.getExportTask("export-retry").optString("status"));
        assertEquals("pending", store.getExportVolume("export-retry", 1).optString("status"));
    }

    @Test
    public void completedVolumeRegistrationRollsBackAsOneTransaction() throws Exception {
        LocalFileStore store = LocalFileStore.getInstance(context);
        reserveTask(store, "export-atomic", "queued", 1L, true);
        SQLiteDatabase database = store.getWritableDatabase();
        database.execSQL("CREATE TRIGGER fail_export_file_registration "
            + "BEFORE INSERT ON local_files BEGIN "
            + "SELECT RAISE(ABORT, 'forced registration failure'); END");
        JSONObject volume = store.getExportVolume("export-atomic", 1);
        try {
            assertThrows(SQLiteException.class, () -> store.completeVolumeAndRegisterFile(
                "export-atomic", 1,
                LocalFileRef.createPathFileRef(volume.getString("tempPath")),
                volume.getString("displayPath"), volume.getString("targetName"),
                100L, 2, "chapter", "album-1", "漫画", "", "",
                "chapter-1", "第一话", 1, -1));

            JSONObject unchanged = store.getExportVolume("export-atomic", 1);
            assertEquals("pending", unchanged.getString("status"));
            assertTrue(unchanged.isNull("outputFileRef"));
        } finally {
            database.execSQL("DROP TRIGGER fail_export_file_registration");
        }
    }

    @Test
    public void retrySecondVolumeFailureCountsOnlyFirstRewrittenVolume() throws Exception {
        LocalFileStore store = LocalFileStore.getInstance(context);
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
                LocalFileRef.createPathFileRef(oldFinal.getCanonicalPath()), oldFinal.getCanonicalPath(),
                oldFinal.getName(), oldFinal.length(), 1, "chapter",
                "album-1", "漫画", "", "", "chapter-1", "第一话", 1, -1);
        }
        store.updateExportProgress("export-partial-retry", "completed", "completed",
            3, 3, 3, 3, null, null);

        assertTrue(store.prepareExportRetry("export-partial-retry", true));
        assertTrue(store.claimQueuedExport("export-partial-retry"));
        JSONObject first = store.getExportVolume("export-partial-retry", 1);
        store.completeVolumeAndRegisterFile("export-partial-retry", 1,
            LocalFileRef.createPathFileRef(first.getString("tempPath")), first.getString("displayPath"),
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
        LocalFileStore store = LocalFileStore.getInstance(context);
        insertImportedFile(store, "cursor-1.pdf", "folder-a", 1L);
        insertImportedFile(store, "cursor-2.pdf", "folder-a", 2L);
        insertImportedFile(store, "cursor-3.pdf", "folder-b", 3L);

        JSONObject firstFiles = store.getFilesPage(
            null, null, null, null, null, null, "folder-a", null, null, 1);
        JSONObject secondFiles = store.getFilesPage(null, null, null, null, null, null, "folder-a", null,
            firstFiles.getString("nextCursor"), 1);
        assertEquals(1, firstFiles.getJSONArray("files").length());
        assertEquals(1, secondFiles.getJSONArray("files").length());
        assertTrue(secondFiles.isNull("nextCursor"));
        assertThrows(IllegalArgumentException.class,
            () -> store.getFilesPage(
                null, null, null, null, null, null, null, null, "not-a-cursor", 10));

        JSONObject chapterFiles = store.getFilesPage(
            List.of("pdf"), null, null, null, "album-1", "cursor-2.pdf",
            null, null, null, 10);
        assertEquals(1, chapterFiles.getJSONArray("files").length());
        assertEquals("cursor-2.pdf", chapterFiles.getJSONArray("files")
            .getJSONObject(0).getString("chapterId"));

        reserveTask(store, "export-a", "failed", 1L, false);
        reserveTask(store, "export-b", "cancelled", 2L, false);
        reserveTask(store, "export-c", "interrupted", 3L, false);
        JSONObject firstTasks = store.getExportTasksPage(null, null, null, 2);
        JSONObject secondTasks = store.getExportTasksPage(
            null, null, firstTasks.getString("nextCursor"), 2);
        assertEquals(2, firstTasks.getJSONArray("tasks").length());
        assertEquals(1, secondTasks.getJSONArray("tasks").length());
        assertTrue(secondTasks.isNull("nextCursor"));
        assertThrows(IllegalArgumentException.class,
            () -> store.getExportTasksPage(null, null, "invalid", 2));
    }

    @Test
    public void getAllFilesReturnsMoreThanOnePage() throws Exception {
        LocalFileStore store = LocalFileStore.getInstance(context);
        for (int index = 0; index < 105; index++) {
            String name = "library-" + index + ".pdf";
            long id = store.insertImportedFile("pdf",
                LocalFileRef.createPathFileRef(new File(context.getCacheDir(), name).getCanonicalPath()),
                new File(context.getCacheDir(), name).getCanonicalPath(),
                name, "album-1", "漫画", "", "", name, name, 0, -1,
                index, null, 0L, 1);
            assertTrue(id > 0L);
        }

        assertEquals(105, store.getAllFiles().length());
    }

    @Test
    public void onlyTerminalTasksCanBeDeleted() throws Exception {
        LocalFileStore store = LocalFileStore.getInstance(context);
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

    private void insertImportedFile(LocalFileStore store, String name, String folderId,
                                    long createdAt) throws Exception {
        File file = createPdf(name);
        long id = store.insertImportedFile("pdf",
            LocalFileRef.createPathFileRef(file.getCanonicalPath()),
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

    private void reserveTask(LocalFileStore store, String exportId, String status,
                             long createdAt, boolean withVolume) throws Exception {
        reserveTask(store, exportId, status, createdAt, withVolume ? 1 : 0);
    }

    private void reserveTask(LocalFileStore store, String exportId, String status,
                             long createdAt, int volumeCount) throws Exception {
        JSONObject task = new JSONObject();
        task.put("exportId", exportId);
        task.put("batchId", "batch-" + exportId);
        task.put("mode", "chapter");
        task.put("albumId", "album-1");
        task.put("chapterId", "chapter-1");
        task.put("displayTitle", exportId);
        String targetPath = new File(context.getCacheDir(), exportId + ".pdf").getCanonicalPath();
        task.put("targetFolderRef", LocalFileRef.createPathFolderRef(context.getCacheDir().getCanonicalPath()));
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
