package io.github.jukomu.desktop.feature.pdf;

import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.files.FileReferences;
import io.github.jukomu.desktop.feature.files.FileService;
import io.github.jukomu.desktop.feature.pdf.data.LocalFileStore;
import io.github.jukomu.desktop.feature.pdf.management.LocalFileManagementService;
import io.github.jukomu.desktop.feature.pdf.model.ImportLocalFileItemRequest;
import io.github.jukomu.desktop.feature.pdf.model.ImportLocalFilesResponse;
import io.github.jukomu.desktop.feature.pdf.model.LocalFileResponse;
import io.github.jukomu.desktop.feature.pdf.render.PdfDocumentService;
import io.github.jukomu.desktop.feature.pdf.render.PdfPageCache;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileManagementServiceTest {
    @Test
    void scansOnlyDirectChildrenAndImportsWithoutCopying() throws Exception {
        Fixture fixture = fixture();
        try (Database ignored = fixture.database()) {
            Path root = fixture.paths().pdfDirectory();
            Files.createDirectories(root.resolve("nested"));
            Path direct = writePdf(root.resolve("direct.pdf"), 2);
            writePdf(root.resolve("nested/ignored.pdf"), 1);

            var scanned = fixture.files().scanImportableFiles(
                    FileReferences.folderRef(root), List.of("pdf"));
            assertEquals(List.of("direct.pdf"),
                    scanned.files().stream().map(file -> file.fileName()).toList());

            ImportLocalFilesResponse imported = fixture.service().importLocalFiles(List.of(item(direct)));
            ImportLocalFilesResponse duplicate = fixture.service().importLocalFiles(List.of(item(direct)));
            LocalFileResponse record = fixture.service().getImportedLocalFiles().files().get(0);

            assertEquals(1, imported.imported());
            assertEquals(1, duplicate.duplicateCount());
            assertEquals("imported", record.sourceType());
            assertEquals("external_reference", record.ownership());
            assertEquals("resolved", record.chapterLinkStatus());
            assertEquals(2, record.pageCount());
            assertTrue(Files.isRegularFile(direct));

            assertTrue(fixture.service().removeFromLibrary(record.id()).success());
            assertTrue(Files.isRegularFile(direct));
            assertTrue(fixture.service().getImportedLocalFiles().files().isEmpty());
        }
    }

    @Test
    void verifiesMissingFilesAndOnlyRemovesRecordAfterPhysicalDelete() throws Exception {
        Fixture fixture = fixture();
        try (Database ignored = fixture.database()) {
            Path pdf = writePdf(fixture.paths().pdfDirectory().resolve("delete.pdf"), 1);
            fixture.service().importLocalFiles(List.of(item(pdf)));
            LocalFileResponse first = fixture.service().getImportedLocalFiles().files().get(0);

            Files.delete(pdf);
            LocalFileResponse missing = fixture.service().verifyFile(first.id());
            assertEquals("missing", missing.availability());
            assertEquals("unverified", missing.verificationStatus());
            assertEquals("already_missing", fixture.service().deleteFile(first.id()).result());
            assertTrue(fixture.service().getImportedLocalFiles().files().isEmpty());

            Path second = writePdf(fixture.paths().pdfDirectory().resolve("delete-actual.pdf"), 1);
            fixture.service().importLocalFiles(List.of(item(second)));
            long secondId = fixture.service().getImportedLocalFiles().files().get(0).id();
            assertEquals("deleted", fixture.service().deleteFile(secondId).result());
            assertFalse(Files.exists(second));
            assertTrue(fixture.service().getImportedLocalFiles().files().isEmpty());
        }
    }

    @Test
    void countsExpectedFileValidationFailuresAsImportErrors() throws Exception {
        Fixture fixture = fixture();
        try (Database ignored = fixture.database()) {
            Path missing = fixture.paths().pdfDirectory().resolve("missing.pdf");

            ImportLocalFilesResponse response = fixture.service().importLocalFiles(List.of(item(missing)));

            assertEquals(0, response.imported());
            assertEquals(1, response.skipped());
            assertEquals(1, response.errorCount());
            assertTrue(response.results().isEmpty());
        }
    }

    @Test
    void propagatesDatabaseFailuresDuringImport() throws Exception {
        Fixture fixture = fixture();
        Path pdf = writePdf(fixture.paths().pdfDirectory().resolve("database-failure.pdf"), 1);
        fixture.database().close();

        assertThrows(IllegalStateException.class,
                () -> fixture.service().importLocalFiles(List.of(item(pdf))));
    }

    private static Fixture fixture() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-pdf-library-");
        Paths paths = new Paths(root.resolve("program"), root.resolve("home"), Map.of(), "Linux");
        paths.ensureDirectories();
        Database database = new Database(paths);
        database.open();
        FileService files = new FileService(paths, ignored -> null, ignored -> {
        });
        LocalFileManagementService service = new LocalFileManagementService(
                new LocalFileStore(database),
                new DownloadStore(database),
                files,
                new PdfDocumentService(new PdfPageCache(paths.cacheDirectory()))
        );
        return new Fixture(paths, database, files, service);
    }

    private static ImportLocalFileItemRequest item(Path pdf) {
        return new ImportLocalFileItemRequest(
                "pdf", FileReferences.fileRef(pdf), pdf.toString(), pdf.getFileName().toString(),
                "album-1", "Album", "", "Alice", "chapter-1", "Chapter 1",
                1, false, "folder-1"
        );
    }

    private static Path writePdf(Path path, int pages) throws Exception {
        Files.createDirectories(path.getParent());
        try (PDDocument document = new PDDocument()) {
            for (int index = 0; index < pages; index++) document.addPage(new PDPage());
            document.save(path.toFile());
        }
        return path;
    }

    private record Fixture(
            Paths paths,
            Database database,
            FileService files,
            LocalFileManagementService service
    ) {
    }
}
