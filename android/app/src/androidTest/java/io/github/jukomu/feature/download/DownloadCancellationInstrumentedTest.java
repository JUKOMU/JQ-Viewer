package io.github.jukomu.feature.download;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.jukomu.feature.download.data.DownloadStore;
import io.github.jukomu.feature.download.storage.FileStore;
import io.github.jukomu.jmcomic.api.download.enums.TaskState;
import io.github.jukomu.jmcomic.api.download.task.BaseDownloadTask;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class DownloadCancellationInstrumentedTest {
    @Test
    public void lateCancelledCallbackDoesNotRestoreRemovedTask() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File databaseFile = new File(context.getCacheDir(), "download-cancel-" + UUID.randomUUID() + ".db");
        File downloadDirectory = new File(context.getCacheDir(), "download-cancel-files-" + UUID.randomUUID());
        Context isolated = new ContextWrapper(context) {
            @Override public File getDatabasePath(String name) { return databaseFile; }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                SQLiteDatabase.CursorFactory factory) {
                return SQLiteDatabase.openOrCreateDatabase(databaseFile.getPath(), factory);
            }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
                return SQLiteDatabase.openOrCreateDatabase(databaseFile.getPath(), factory, handler);
            }
        };
        Constructor<DownloadStore> storeConstructor = DownloadStore.class.getDeclaredConstructor(Context.class);
        storeConstructor.setAccessible(true);
        DownloadStore store = storeConstructor.newInstance(isolated);
        Constructor<FileStore> filesConstructor = FileStore.class.getDeclaredConstructor();
        filesConstructor.setAccessible(true);
        FileStore files = filesConstructor.newInstance();
        files.init(isolated, store, false);
        files.switchBaseDir(downloadDirectory);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        String taskId = "900000090_900000091";
        DownloadService service = new DownloadService(store, files, () -> null,
            executor, null, context);
        try {
            store.insertTask(taskId, "900000090", "900000091", "Album", "Chapter", "");
            store.updateStatus(taskId, DownloadService.STATUS_DOWNLOADING);
            files.ensureChapterDir("900000090", "900000091");
            service.taskIdMap.put(taskId, "library-task");

            service.cancelDownload(taskId);
            new DownloadObserver(taskId, "900000090", "900000091", 1, store, files, service)
                .onStateChanged(new CancelledTask(), TaskState.CANCELLED);

            assertNull(store.getTask(taskId));
            assertFalse(new File(downloadDirectory, "900000090/900000091").exists());
        } finally {
            executor.shutdownNow();
            store.deleteImages(taskId);
            store.deleteTask(taskId);
            store.close();
            deleteRecursively(downloadDirectory);
            databaseFile.delete();
        }
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private static final class CancelledTask extends BaseDownloadTask {
        @Override public void start() { }
        @Override public void pause() { }
        @Override public void resume() { }
        @Override public void cancel() { }
    }
}
