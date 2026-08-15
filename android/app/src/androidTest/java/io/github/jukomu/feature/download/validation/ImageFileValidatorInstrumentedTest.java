package io.github.jukomu.feature.download.validation;

import android.graphics.Bitmap;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ImageFileValidatorInstrumentedTest {

    private File testDir;

    @Before
    public void setUp() {
        File cacheDir = InstrumentationRegistry.getInstrumentation()
            .getTargetContext().getCacheDir();
        testDir = new File(cacheDir, "image-validator-test-" + System.nanoTime());
        if (!testDir.mkdirs()) {
            throw new IllegalStateException("无法创建测试目录");
        }
    }

    @After
    public void tearDown() {
        deleteRecursive(testDir);
    }

    @Test
    public void acceptsDecodedJpegWithFullAndQuickValidation() throws Exception {
        File image = new File(testDir, "page.jpg");
        Bitmap bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(image)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output));
        } finally {
            bitmap.recycle();
        }

        assertTrue(ImageFileValidator.validateQuick(image));
        assertTrue(ImageFileValidator.validateFull(image));
    }

    @Test
    public void rejectsMissingEmptyDirectoryAndRandomFiles() throws Exception {
        assertFalse(ImageFileValidator.validateFull(null));
        assertFalse(ImageFileValidator.validateQuick(new File(testDir, "missing.jpg")));

        File empty = new File(testDir, "empty.jpg");
        Files.createFile(empty.toPath());
        assertFalse(ImageFileValidator.validateFull(empty));

        File directory = new File(testDir, "directory.jpg");
        assertTrue(directory.mkdirs());
        assertFalse(ImageFileValidator.validateFull(directory));

        File random = new File(testDir, "random.jpg");
        Files.write(random.toPath(), new byte[]{1, 2, 3, 4});
        assertFalse(ImageFileValidator.validateQuick(random));
        assertFalse(ImageFileValidator.validateFull(random));
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        file.delete();
    }
}
