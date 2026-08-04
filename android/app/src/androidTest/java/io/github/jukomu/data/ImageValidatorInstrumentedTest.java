package io.github.jukomu.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class ImageValidatorInstrumentedTest {

    @Test
    public void acceptsCompleteDecodableDownloadSet() throws Exception {
        File chapterDir = createChapterDir("valid");
        try {
            writeBitmap(new File(chapterDir, "page-1.png"), Bitmap.CompressFormat.PNG);
            writeBitmap(new File(chapterDir, "page-2.jpg"), Bitmap.CompressFormat.JPEG);

            ImageValidator.DownloadValidationResult result =
                ImageValidator.validateDownloadedImages(chapterDir, 2,
                    Arrays.asList("page-1.png", "page-2.jpg"));

            assertTrue(result.isComplete());
            assertEquals(2, result.getExpectedCount());
            assertEquals(2, result.getMappedCount());
            assertEquals(2, result.getValidCount());
            assertEquals(0, result.getInvalidContentCount());
            assertEquals(0, result.getMissingCount());
            assertTrue(result.getInvalidFiles().isEmpty());
        } finally {
            deleteRecursively(chapterDir);
        }
    }

    @Test
    public void rejectsUnexpectedPageMappingsWithoutReportingCompletedProgress()
        throws Exception {
        File chapterDir = createChapterDir("extra-mapping");
        try {
            writeBitmap(new File(chapterDir, "page-1.png"), Bitmap.CompressFormat.PNG);
            writeBitmap(new File(chapterDir, "page-2.png"), Bitmap.CompressFormat.PNG);

            ImageValidator.DownloadValidationResult result =
                ImageValidator.validateDownloadedImages(chapterDir, 1,
                    Arrays.asList("page-1.png", "page-2.png"));

            assertFalse(result.isComplete());
            assertEquals(1, result.getExpectedCount());
            assertEquals(2, result.getMappedCount());
            assertEquals(2, result.getValidCount());
            assertEquals(0, result.getFailedProgressCount());
            assertTrue(result.getFailureMessage(0).contains("页映射 2/1"));
        } finally {
            deleteRecursively(chapterDir);
        }
    }

    @Test
    public void reportsInvalidAndMissingFilesWithoutDeletingThem() throws Exception {
        File chapterDir = createChapterDir("invalid");
        File corruptImage = new File(chapterDir, "page-2.jpg");
        try {
            writeBitmap(new File(chapterDir, "page-1.png"), Bitmap.CompressFormat.PNG);
            try (FileOutputStream output = new FileOutputStream(corruptImage)) {
                output.write(new byte[]{(byte) 0xff, (byte) 0xd8, 1, 2, 3});
            }

            ImageValidator.DownloadValidationResult result =
                ImageValidator.validateDownloadedImages(chapterDir, 3,
                    Arrays.asList("page-1.png", "page-2.jpg", "page-3.png"));

            assertFalse(result.isComplete());
            assertEquals(3, result.getExpectedCount());
            assertEquals(1, result.getValidCount());
            assertEquals(1, result.getInvalidContentCount());
            assertEquals(1, result.getMissingCount());
            assertEquals(Arrays.asList(corruptImage.getCanonicalFile()),
                result.getInvalidFiles());
            assertTrue(corruptImage.exists());
            assertTrue(result.getFailureMessage(0).contains("2/3 张图片下载失败"));
        } finally {
            deleteRecursively(chapterDir);
        }
    }

    @Test
    public void distinguishesMissingEmptyIncompleteAndUndecodableInput() throws Exception {
        File chapterDir = createChapterDir("status");
        File empty = new File(chapterDir, "empty.jpg");
        File incomplete = new File(chapterDir, "incomplete.jpg");
        try {
            assertTrue(empty.createNewFile());
            try (FileOutputStream output = new FileOutputStream(incomplete)) {
                output.write(new byte[]{(byte) 0xff, (byte) 0xd8, 1, 2, 3});
            }

            assertEquals(ImageValidator.Status.MISSING,
                ImageValidator.validate(new File(chapterDir, "missing.jpg")));
            assertEquals(ImageValidator.Status.EMPTY, ImageValidator.validate(empty));
            assertEquals(ImageValidator.Status.INCOMPLETE,
                ImageValidator.validate(incomplete));
            assertEquals(ImageValidator.Status.UNDECODABLE,
                ImageValidator.validate(new byte[]{1, 2, 3, 4}));
        } finally {
            deleteRecursively(chapterDir);
        }
    }

    private static File createChapterDir(String suffix) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getCacheDir(),
            "image-validation-" + suffix + "-" + System.nanoTime());
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
