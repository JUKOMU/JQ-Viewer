package io.github.jukomu.data;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class FileStoreMetadataInstrumentedTest {

    private FileStore fileStore;
    private Field baseDirField;
    private File testBaseDir;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        testBaseDir = new File(context.getCacheDir(),
            "file-store-metadata-test-" + System.nanoTime());
        if (!testBaseDir.mkdirs()) {
            throw new IOException("无法创建测试目录");
        }

        fileStore = FileStore.getInstance();
        baseDirField = FileStore.class.getDeclaredField("baseDir");
        baseDirField.setAccessible(true);
        baseDirField.set(fileStore, testBaseDir);
    }

    @After
    public void tearDown() throws Exception {
        baseDirField.set(fileStore, null);
        deleteRecursive(testBaseDir);
    }

    @Test
    public void savesMetadataAtomicallyAndReadsItBack() throws Exception {
        JSONObject metadata = new JSONObject().put("albumId", "album");

        fileStore.saveMeta("album", "chapter", metadata);

        assertEquals("album", fileStore.readMeta("album", "chapter").getString("albumId"));
        assertFalse(new File(fileStore.getChapterDir("album", "chapter"),
            "meta.json.tmp").exists());
    }

    @Test
    public void preservesMetadataReadErrors() throws Exception {
        assertThrows(FileNotFoundException.class,
            () -> fileStore.readMeta("album", "missing"));

        File chapterDir = fileStore.ensureChapterDir("album", "broken");
        Files.write(new File(chapterDir, "meta.json").toPath(),
            "{".getBytes(StandardCharsets.UTF_8));
        assertThrows(org.json.JSONException.class,
            () -> fileStore.readMeta("album", "broken"));
    }

    @Test
    public void expectedImageFileUsesManifestFilenameOnly() throws Exception {
        File chapterDir = fileStore.ensureChapterDir("album", "chapter");
        File image = new File(chapterDir, "page.jpg");
        Files.write(image.toPath(), new byte[]{1});

        assertEquals(image.getCanonicalFile(),
            fileStore.getExpectedImageFile("album", "chapter", "page.jpg"));
        assertNull(fileStore.getExpectedImageFile("album", "chapter", null));
        assertNull(fileStore.getExpectedImageFile("album", "chapter", " "));
        assertNull(fileStore.getExpectedImageFile("album", "chapter", "missing.jpg"));
        assertNull(fileStore.getExpectedImageFile("album", "chapter", "../page.jpg"));
    }

    @Test
    public void saveMetadataFailureIsReported() throws Exception {
        File invalidBase = new File(testBaseDir, "not-a-directory");
        Files.write(invalidBase.toPath(), new byte[]{1});
        baseDirField.set(fileStore, invalidBase);

        assertThrows(IOException.class,
            () -> fileStore.saveMeta("album", "chapter", new JSONObject()));
    }

    @Test
    public void unsafeChapterIdsCannotDeleteOutsideBaseDirectory() throws Exception {
        File outsideDir = new File(testBaseDir.getParentFile(),
            testBaseDir.getName() + "-outside");
        File marker = new File(outsideDir, "marker.txt");
        try {
            assertTrue(outsideDir.mkdirs());
            Files.write(marker.toPath(), new byte[]{1});

            assertThrows(IllegalArgumentException.class,
                () -> fileStore.deleteChapter("..", outsideDir.getName()));
            assertThrows(IllegalArgumentException.class,
                () -> fileStore.deleteChapter("", ""));
            assertTrue(marker.isFile());
        } finally {
            deleteRecursive(outsideDir);
        }
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
