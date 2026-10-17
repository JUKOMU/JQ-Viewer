package io.github.jukomu.feature.download.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.jukomu.feature.download.DownloadService;
import io.github.jukomu.feature.download.storage.FileStore;
import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

public class DownloadMetadataInstrumentedTest {
    @Test
    public void versionFourUpgradePreservesExistingDownloadRows() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Constructor<DownloadStore> constructor = DownloadStore.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        DownloadStore store = constructor.newInstance(context);
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE download_tasks(task_id TEXT PRIMARY KEY,author TEXT,tags TEXT,status TEXT)");
            db.execSQL("INSERT INTO download_tasks VALUES ('old','Alice','[\"tag\"]','completed')");
            store.onUpgrade(db, 4, 5);
            try (Cursor cursor = db.rawQuery("SELECT author,authors_json,tags,status FROM download_tasks", null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals("Alice", cursor.getString(0));
                assertEquals("[]", cursor.getString(1));
                assertEquals("[\"tag\"]", cursor.getString(2));
                assertEquals("completed", cursor.getString(3));
            }
        }
    }

    @Test
    public void savesMultipleAuthorsAndBackfillsLegacyAuthorWithoutChangingFailedRows() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DownloadStore store = DownloadStore.getInstance(context);
        String taskId = "900000090_900000091";
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DownloadService service = new DownloadService(store, FileStore.getInstance(), () -> null,
            executor, null, context);
        try {
            store.deleteTask(taskId);
            store.insertTask(taskId, "900000090", "900000091", "Album", "Chapter", "");
            store.updateTaskDetail(taskId, 1, "Alice", "[\"Alice\",\"Bob\"]", "[\"tag\"]", 2, false);
            assertEquals(2, store.getTask(taskId).getJSONArray("authors").length());
            store.updateTaskDetail(taskId, 1, "Alice", "[]", "[\"tag\"]", 2, false);
            JSONObject completed = service.prepareExportMetadata(taskId);
            assertEquals("[\"Alice\"]", completed.getJSONArray("authors").toString());
            store.getWritableDatabase().execSQL("UPDATE download_tasks SET album_title='',authors_json='[]' WHERE task_id=?", new Object[]{taskId});
            try {
                service.prepareExportMetadata(taskId);
                fail("Missing titles must reject metadata completion");
            } catch (IllegalStateException expected) {
                assertEquals("", store.getTask(taskId).optString("albumTitle"));
                assertEquals("[]", store.getTask(taskId).getJSONArray("authors").toString());
            }
        } finally {
            executor.shutdownNow();
            store.deleteImages(taskId);
            store.deleteTask(taskId);
        }
    }
}
