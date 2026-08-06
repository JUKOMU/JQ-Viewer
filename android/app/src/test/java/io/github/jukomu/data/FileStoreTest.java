package io.github.jukomu.data;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class FileStoreTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void readsExactFileLength() throws Exception {
        File image = temporaryFolder.newFile("page.jpg");
        byte[] expected = {1, 2, 3, 4, 5};
        Files.write(image.toPath(), expected);

        assertArrayEquals(expected, FileStore.getInstance().readImageBytes(image));
    }

    @Test
    public void rejectsInvalidImageLengthsBeforeAllocation() throws Exception {
        assertThrows(IOException.class, () -> FileStore.checkedImageLength(0L));
        assertThrows(IOException.class,
            () -> FileStore.checkedImageLength((long) Integer.MAX_VALUE + 1L));
        assertEquals(1, FileStore.checkedImageLength(1L));
    }

    @Test
    public void resolvesOnlyFilesInsideChapterDirectory() throws Exception {
        File chapterDir = temporaryFolder.newFolder("album", "chapter");
        File valid = new File(chapterDir, "page.jpg");
        Files.write(valid.toPath(), new byte[]{1});
        File outside = temporaryFolder.newFile("outside.jpg");

        assertEquals(valid.getCanonicalFile(),
            FileStore.resolveImageFile(chapterDir, "page.jpg"));
        assertNull(FileStore.resolveImageFile(chapterDir, "../../outside.jpg"));
        assertNull(FileStore.resolveImageFile(chapterDir, outside.getAbsolutePath()));
    }

    @Test
    public void validatesChapterIdsAsSinglePathSegments() {
        FileStore.validateChapterIds("album", "chapter");

        assertThrows(IllegalArgumentException.class,
            () -> FileStore.validateChapterIds(null, "chapter"));
        assertThrows(IllegalArgumentException.class,
            () -> FileStore.validateChapterIds("", "chapter"));
        assertThrows(IllegalArgumentException.class,
            () -> FileStore.validateChapterIds("album", " "));
        assertThrows(IllegalArgumentException.class,
            () -> FileStore.validateChapterIds("..", "chapter"));
        assertThrows(IllegalArgumentException.class,
            () -> FileStore.validateChapterIds("album/child", "chapter"));
        assertThrows(IllegalArgumentException.class,
            () -> FileStore.validateChapterIds("album", "parent\\child"));
    }
}
