package io.github.jukomu.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.jukomu.jmcomic.api.model.JmImage;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class FileStoreDownloadValidationInstrumentedTest {

    @Test
    public void acceptsCompleteDecodableDownloadSet() throws Exception {
        File chapterDir = createChapterDir("valid");
        try {
            writeBitmap(new File(chapterDir, "page-1.png"), Bitmap.CompressFormat.PNG);
            writeBitmap(new File(chapterDir, "page-2.jpg"), Bitmap.CompressFormat.JPEG);

            FileStore.DownloadValidationResult result = FileStore.validateDownloadedImages(
                chapterDir,
                Arrays.asList(image(1, "page-1.png"), image(2, "page-2.jpg")));

            assertTrue(result.isComplete());
            assertEquals(2, result.getExpectedCount());
            assertEquals(2, result.getValidCount());
            assertEquals(0, result.getInvalidContentCount());
            assertEquals(0, result.getMissingCount());
        } finally {
            deleteRecursively(chapterDir);
        }
    }

    @Test
    public void rejectsAndDeletesInvalidDownloadedContent() throws Exception {
        File chapterDir = createChapterDir("invalid");
        File corruptImage = new File(chapterDir, "page-2.jpg");
        try {
            writeBitmap(new File(chapterDir, "page-1.png"), Bitmap.CompressFormat.PNG);
            try (FileOutputStream output = new FileOutputStream(corruptImage)) {
                output.write(new byte[]{(byte) 0xff, (byte) 0xd8, 1, 2, 3});
            }

            List<JmImage> images = Arrays.asList(
                image(1, "page-1.png"),
                image(2, "page-2.jpg"),
                image(3, "page-3.png"));
            FileStore.DownloadValidationResult result =
                FileStore.validateDownloadedImages(chapterDir, images);

            assertFalse(result.isComplete());
            assertEquals(3, result.getExpectedCount());
            assertEquals(1, result.getValidCount());
            assertEquals(1, result.getInvalidContentCount());
            assertEquals(1, result.getMissingCount());
            assertEquals(0, result.getCleanupFailureCount());
            assertFalse(corruptImage.exists());
            assertTrue(result.getFailureMessage().contains("2/3 张图片下载失败"));
        } finally {
            deleteRecursively(chapterDir);
        }
    }

    private static File createChapterDir(String suffix) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getCacheDir(),
            "download-validation-" + suffix + "-" + System.nanoTime());
        if (!directory.mkdirs()) {
            throw new IllegalStateException("Failed to create test directory");
        }
        return directory;
    }

    private static void writeBitmap(File target, Bitmap.CompressFormat format) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(target)) {
            if (!bitmap.compress(format, 100, output)) {
                throw new IllegalStateException("Failed to create image fixture");
            }
        } finally {
            bitmap.recycle();
        }
    }

    private static JmImage image(int sortOrder, String filename) {
        return new JmImage("photo", "scramble", filename,
            "https://example.invalid/" + filename, "", sortOrder);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
