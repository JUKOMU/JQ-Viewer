package io.github.jukomu.desktop.feature.files;

import io.github.jukomu.desktop.bridge.ApiException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
