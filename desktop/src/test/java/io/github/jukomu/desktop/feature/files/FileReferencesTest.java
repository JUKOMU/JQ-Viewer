package io.github.jukomu.desktop.feature.files;

import io.github.jukomu.desktop.bridge.ApiException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileReferencesTest {
    @Test
    void roundTripsAbsoluteFileAndFolderReferences() {
        Path folder = Path.of("build", "files").toAbsolutePath().normalize();
        Path file = folder.resolve("sample.pdf");

        assertEquals(folder, FileReferences.parseFolder(FileReferences.folderRef(folder)));
        assertEquals(file, FileReferences.parseFile(FileReferences.fileRef(file)));
    }

    @Test
    void exportTargetCannotEscapeSelectedFolder() {
        Path folder = Path.of("build", "exports").toAbsolutePath().normalize();
        String reference = FileReferences.folderRef(folder);

        assertEquals(folder.resolve("album/chapter.pdf"),
                ExportTargetResolver.resolve(reference, "album/chapter.pdf"));
        assertThrows(ApiException.class,
                () -> ExportTargetResolver.resolve(reference, "../outside.pdf"));
        assertThrows(ApiException.class,
                () -> ExportTargetResolver.resolve(reference, folder.resolve("absolute.pdf").toString()));
    }

    @Test
    void exportTargetCannotEscapeThroughSymbolicLink() throws Exception {
        Path folder = Files.createTempDirectory("jq-viewer-export-root-");
        Path outside = Files.createTempDirectory("jq-viewer-export-outside-");
        Path link = folder.resolve("outside-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "当前文件系统不支持创建符号链接");
        }

        assertThrows(ApiException.class, () -> ExportTargetResolver.resolve(
                FileReferences.folderRef(folder), "outside-link/chapter.pdf"));
    }
}
