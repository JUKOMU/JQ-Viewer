package io.github.jukomu.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.jukomu.data.DownloadStore;
import io.github.jukomu.data.FileStore;
import io.github.jukomu.jmcomic.api.model.JmImage;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RunWith(AndroidJUnit4.class)
public class DownloadServiceCompletedValidationInstrumentedTest {

    private Context context;
    private DownloadStore downloadStore;
    private FileStore fileStore;
    private ExecutorService prepareExecutor;
    private String albumId;
    private String chapterId;
    private String taskId;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        downloadStore = DownloadStore.getInstance(context);
        fileStore = FileStore.getInstance();
        fileStore.init(context, downloadStore, false);
        prepareExecutor = Executors.newSingleThreadExecutor();
        String suffix = Long.toString(System.nanoTime());
        albumId = "validation-album-" + suffix;
        chapterId = "validation-chapter-" + suffix;
        taskId = albumId + "_" + chapterId;
    }

    @After
    public void tearDown() {
        prepareExecutor.shutdownNow();
        fileStore.deleteChapter(albumId, chapterId);
        downloadStore.deleteImages(taskId);
        downloadStore.deleteTask(taskId);
    }

    @Test
    public void completedDownloadWithValidImageCanBeOpened() throws Exception {
        File imageFile = createCompletedTask();
        writeBitmap(imageFile);

        JSONObject photo = createService().getDownloadedPhoto(albumId, chapterId);

        assertEquals(chapterId, photo.getString("id"));
        assertEquals(1, photo.getJSONArray("images").length());
        assertEquals("completed", downloadStore.getTask(taskId).getString("status"));
        assertTrue(imageFile.exists());
    }

    @Test
    public void corruptCompletedDownloadIsDowngradedAndPreparedForRedownload()
        throws Exception {
        File imageFile = createCompletedTask();
        try (FileOutputStream output = new FileOutputStream(imageFile)) {
            output.write(new byte[]{(byte) 0xff, (byte) 0xd8, 1, 2, 3});
        }

        DownloadService.InvalidDownloadedContentException error = assertThrows(
            DownloadService.InvalidDownloadedContentException.class,
            () -> createService().getDownloadedPhoto(albumId, chapterId));

        assertTrue(error.getMessage().contains("1/1 张图片下载失败"));
        JSONObject task = downloadStore.getTask(taskId);
        assertNotNull(task);
        assertEquals("failed", task.getString("status"));
        assertEquals(0, task.getInt("downloadedPages"));
        assertTrue(task.getString("error").contains("文件校验未通过"));
        assertFalse(imageFile.exists());
        assertNull(fileStore.getImageFileByPhotoId(chapterId, 1));
    }

    @Test
    public void startupValidationDowngradesCorruptTaskWithoutDeletingItsFile()
        throws Exception {
        File imageFile = createCompletedTask();
        try (FileOutputStream output = new FileOutputStream(imageFile)) {
            output.write(new byte[]{(byte) 0xff, (byte) 0xd8, 1, 2, 3});
        }

        downloadStore.validateOnStartup(fileStore.getBaseDir());
        fileStore.init(context, downloadStore, false);

        JSONObject task = downloadStore.getTask(taskId);
        assertNotNull(task);
        assertEquals("failed", task.getString("status"));
        assertEquals(0, task.getInt("downloadedPages"));
        assertTrue(imageFile.exists());
        assertNull(fileStore.getImageFileByPhotoId(chapterId, 1));
    }

    @Test
    public void refreshingDownloadMetadataRemovesStalePageMappings() throws Exception {
        JmImage first = new JmImage(chapterId, "scramble", "page-1.png",
            "https://example.invalid/page-1.png", "", 1);
        JmImage stale = new JmImage(chapterId, "scramble", "page-2.png",
            "https://example.invalid/page-2.png", "", 2);
        downloadStore.insertTask(taskId, albumId, chapterId,
            "Album", "Chapter", "");
        downloadStore.insertImages(taskId, Arrays.asList(first, stale));
        assertEquals(2, downloadStore.getImages(taskId).size());

        downloadStore.insertImages(taskId, Collections.singletonList(first));

        assertEquals(1, downloadStore.getImages(taskId).size());
        assertEquals(1, downloadStore.getImages(taskId).get(0).getInt("sortOrder"));
    }

    private DownloadService createService() {
        return new DownloadService(downloadStore, fileStore, null, prepareExecutor,
            null, context);
    }

    private File createCompletedTask() {
        JmImage image = new JmImage(chapterId, "scramble", "page-1.png",
            "https://example.invalid/page-1.png", "", 1);
        downloadStore.insertTask(taskId, albumId, chapterId,
            "Album", "Chapter", "");
        downloadStore.insertImages(taskId, Collections.singletonList(image));
        downloadStore.updateTaskDetail(taskId, 1, "", "[]", 1, false);
        downloadStore.updateCompleted(taskId, 1, 1);
        File chapterDir = fileStore.ensureChapterDir(albumId, chapterId);
        fileStore.refreshMappings(albumId, chapterId, downloadStore);
        return new File(chapterDir, image.getFilename());
    }

    private static void writeBitmap(File target) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(target)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            bitmap.recycle();
        }
    }
}
