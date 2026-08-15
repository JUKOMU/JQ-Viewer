package io.github.jukomu.bridge;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import com.getcapacitor.JSObject;
import io.github.jukomu.bridge.handler.DownloadPluginHandler;
import io.github.jukomu.feature.download.DownloadService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

public class DownloadPluginContractInstrumentedTest {

    private JmcomicPlugin plugin;
    private FakeDownloadService downloadService;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        plugin = new JmcomicPlugin();
        downloadService = new FakeDownloadService(context);
        injectDownloadHandler(plugin, downloadService);
    }

    @Test
    public void requiredIdentifiersUseCurrentMessages() {
        RecordingPluginCall download = call("downloadChapter", "albumId", "1");
        RecordingPluginCall cancel = call("cancelDownload");
        RecordingPluginCall pause = call("pauseDownload");
        RecordingPluginCall resume = call("resumeDownload");
        RecordingPluginCall delete = call("deleteDownloaded", "chapterId", "2");
        RecordingPluginCall photo = call("getDownloadedPhoto", "albumId", "1");

        plugin.downloadChapter(download);
        plugin.cancelDownload(cancel);
        plugin.pauseDownload(pause);
        plugin.resumeDownload(resume);
        plugin.deleteDownloaded(delete);
        plugin.getDownloadedPhoto(photo);

        assertRejected(download, "albumId and chapterId are required");
        assertRejected(cancel, "taskId is required");
        assertRejected(pause, "taskId is required");
        assertRejected(resume, "taskId is required");
        assertRejected(delete, "albumId and chapterId are required");
        assertRejected(photo, "albumId and chapterId are required");
    }

    @Test
    public void downloadUsesEmptyDefaultsAndReturnsTaskId() {
        RecordingPluginCall call = call(
            "downloadChapter", "albumId", "", "chapterId", "");

        plugin.downloadChapter(call);

        assertEquals("_", call.resolvedData.getString("taskId"));
        assertEquals("", downloadService.albumId);
        assertEquals("", downloadService.chapterId);
        assertEquals("", downloadService.albumTitle);
        assertEquals("", downloadService.chapterTitle);
        assertEquals("", downloadService.coverUrl);
        assertSynchronous(call);
    }

    @Test
    public void taskListSkipsInvalidEntriesAndReturnsSpaceStats() throws Exception {
        JSONObject task = new JSONObject();
        task.put("taskId", "a_b");
        downloadService.tasks = new JSONArray().put(task).put("invalid");
        downloadService.usedBytes = 123L;
        downloadService.availableBytes = 456L;
        RecordingPluginCall call = call("getDownloadTasks");

        plugin.getDownloadTasks(call);

        JSONArray tasks = call.resolvedData.getJSONArray("tasks");
        assertEquals(1, tasks.length());
        assertEquals("a_b", tasks.getJSONObject(0).getString("taskId"));
        assertEquals(123L, call.resolvedData.getLong("usedBytes"));
        assertEquals(456L, call.resolvedData.getLong("availableBytes"));
        assertSynchronous(call);
    }

    @Test
    public void taskCommandsAcceptEmptyIdsAndReturnSuccess() {
        RecordingPluginCall cancel = call("cancelDownload", "taskId", "");
        RecordingPluginCall pause = call("pauseDownload", "taskId", "pause-id");
        RecordingPluginCall resume = call("resumeDownload", "taskId", "resume-id");
        RecordingPluginCall delete = call(
            "deleteDownloaded", "albumId", "album", "chapterId", "chapter");

        plugin.cancelDownload(cancel);
        assertEquals("", downloadService.cancelledTaskId);
        plugin.pauseDownload(pause);
        assertEquals("pause-id", downloadService.pausedTaskId);
        plugin.resumeDownload(resume);
        assertEquals("resume-id", downloadService.resumedTaskId);
        plugin.deleteDownloaded(delete);

        assertEquals("album", downloadService.deletedAlbumId);
        assertEquals("chapter", downloadService.deletedChapterId);
        assertSuccess(cancel, pause, resume, delete);
    }

    @Test
    public void stateErrorsRejectWithoutExceptionObjects() {
        RecordingPluginCall download = call(
            "downloadChapter", "albumId", "1", "chapterId", "2");
        RecordingPluginCall pause = call("pauseDownload", "taskId", "task");
        RecordingPluginCall resume = call("resumeDownload", "taskId", "task");
        RecordingPluginCall photo = call(
            "getDownloadedPhoto", "albumId", "1", "chapterId", "2");

        downloadService.failure = new IllegalStateException("already queued");
        plugin.downloadChapter(download);
        downloadService.failure = new IllegalArgumentException("Task not found");
        plugin.pauseDownload(pause);
        downloadService.failure = new IllegalStateException("not paused");
        plugin.resumeDownload(resume);
        downloadService.failure = new IllegalStateException("not completed");
        plugin.getDownloadedPhoto(photo);

        assertRejectedWithoutException(download, "already queued");
        assertRejectedWithoutException(pause, "Task not found");
        assertRejectedWithoutException(resume, "not paused");
        assertRejectedWithoutException(photo, "not completed");
    }

    @Test
    public void otherFailuresKeepExceptionObjects() {
        UnsupportedOperationException failure = new UnsupportedOperationException("failed");
        downloadService.failure = failure;
        RecordingPluginCall list = call("getDownloadTasks");

        plugin.getDownloadTasks(list);

        assertEquals("failed", list.rejectionMessage);
        assertSame(failure, list.rejectionException);
        assertEquals(1, list.completionCount);

        RecordingPluginCall cancel = call("cancelDownload", "taskId", "task");
        plugin.cancelDownload(cancel);
        assertEquals("failed", cancel.rejectionMessage);
        assertSame(failure, cancel.rejectionException);
    }

    @Test
    public void downloadedPhotoReturnsServiceJson() throws Exception {
        downloadService.photo = new JSONObject()
            .put("id", "chapter")
            .put("albumId", "album")
            .put("images", new JSONArray());
        RecordingPluginCall call = call(
            "getDownloadedPhoto", "albumId", "album", "chapterId", "chapter");

        plugin.getDownloadedPhoto(call);

        assertEquals("chapter", call.resolvedData.getString("id"));
        assertEquals("album", call.resolvedData.getString("albumId"));
        assertEquals(0, call.resolvedData.getJSONArray("images").length());
        assertEquals("album", downloadService.photoAlbumId);
        assertEquals("chapter", downloadService.photoChapterId);
        assertSynchronous(call);
    }

    private static RecordingPluginCall call(String methodName, Object... values) {
        JSObject data = new JSObject();
        for (int index = 0; index < values.length; index += 2) {
            data.put((String) values[index], values[index + 1]);
        }
        return new RecordingPluginCall(methodName, data);
    }

    private static void assertSuccess(RecordingPluginCall... calls) {
        for (RecordingPluginCall call : calls) {
            assertTrue(call.resolvedData.getBool("success"));
            assertSynchronous(call);
        }
    }

    private static void assertSynchronous(RecordingPluginCall... calls) {
        for (RecordingPluginCall call : calls) {
            assertEquals(1, call.completionCount);
            assertFalse(call.isKeptAlive());
            assertNull(call.rejectionMessage);
        }
    }

    private static void assertRejected(RecordingPluginCall call, String message) {
        assertEquals(message, call.rejectionMessage);
        assertEquals(1, call.completionCount);
        assertNull(call.resolvedData);
        assertFalse(call.isKeptAlive());
    }

    private static void assertRejectedWithoutException(RecordingPluginCall call,
                                                       String message) {
        assertRejected(call, message);
        assertNull(call.rejectionException);
    }

    private static void injectDownloadHandler(JmcomicPlugin plugin,
                                              DownloadService service) throws Exception {
        Field field = JmcomicPlugin.class.getDeclaredField("downloadHandler");
        field.setAccessible(true);
        field.set(plugin, new DownloadPluginHandler(service));
    }

    private static final class FakeDownloadService extends DownloadService {

        private JSONArray tasks = new JSONArray();
        private JSONObject photo = new JSONObject();
        private long usedBytes;
        private long availableBytes;
        private RuntimeException failure;
        private String albumId;
        private String chapterId;
        private String albumTitle;
        private String chapterTitle;
        private String coverUrl;
        private String cancelledTaskId;
        private String pausedTaskId;
        private String resumedTaskId;
        private String deletedAlbumId;
        private String deletedChapterId;
        private String photoAlbumId;
        private String photoChapterId;

        private FakeDownloadService(Context context) {
            super(null, null, null, null, null, context);
        }

        @Override
        public String downloadChapter(String newAlbumId, String newChapterId,
                                      String newAlbumTitle, String newChapterTitle,
                                      String newCoverUrl) {
            failIfNeeded();
            albumId = newAlbumId;
            chapterId = newChapterId;
            albumTitle = newAlbumTitle;
            chapterTitle = newChapterTitle;
            coverUrl = newCoverUrl;
            return newAlbumId + "_" + newChapterId;
        }

        @Override
        public JSONArray getDownloadTasks() {
            failIfNeeded();
            return tasks;
        }

        @Override
        public long getUsedBytes() {
            return usedBytes;
        }

        @Override
        public long getAvailableBytes() {
            return availableBytes;
        }

        @Override
        public void cancelDownload(String taskId) {
            failIfNeeded();
            cancelledTaskId = taskId;
        }

        @Override
        public void pauseDownload(String taskId) {
            failIfNeeded();
            pausedTaskId = taskId;
        }

        @Override
        public void resumeDownload(String taskId) {
            failIfNeeded();
            resumedTaskId = taskId;
        }

        @Override
        public void deleteDownloaded(String newAlbumId, String newChapterId) {
            failIfNeeded();
            deletedAlbumId = newAlbumId;
            deletedChapterId = newChapterId;
        }

        @Override
        public JSONObject getDownloadedPhoto(String newAlbumId, String newChapterId) {
            failIfNeeded();
            photoAlbumId = newAlbumId;
            photoChapterId = newChapterId;
            return photo;
        }

        private void failIfNeeded() {
            if (failure != null) {
                throw failure;
            }
        }
    }
}
