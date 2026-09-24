package io.github.jukomu.desktop.feature.localfile.management;

import io.github.jukomu.desktop.feature.files.FileReferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ZipFileValidatorTest {
    @TempDir
    Path directory;

    @Test
    void verifiesNestedImageBytesAndPageCount() throws Exception {
        Path archive = directory.resolve("volume.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("001_First/0001.jpg"));
            output.write(new byte[]{1, 2, 3});
            output.closeEntry();
            output.putNextEntry(new ZipEntry("002_Second/0001.png"));
            output.write(new byte[]{4, 5, 6});
            output.closeEntry();
        }
        String ref = FileReferences.fileRef(archive);
        assertEquals(2, ZipFileValidator.validate(ref, 2).pageCount());
        assertEquals("ZIP_PAGE_MISMATCH",
                assertThrows(ZipFileValidator.ValidationException.class,
                        () -> ZipFileValidator.validate(ref, 3)).code());

        Files.write(archive, new byte[]{1, 2, 3});
        assertEquals("ZIP_INVALID",
                assertThrows(ZipFileValidator.ValidationException.class,
                        () -> ZipFileValidator.validate(ref, 2)).code());
    }

    @Test
    void reportsMissingArchive() {
        String ref = FileReferences.fileRef(directory.resolve("missing.zip"));
        assertEquals("ZIP_MISSING",
                assertThrows(ZipFileValidator.ValidationException.class,
                        () -> ZipFileValidator.validate(ref, 1)).code());
    }
}
