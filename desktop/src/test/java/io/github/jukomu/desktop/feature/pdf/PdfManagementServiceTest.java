package io.github.jukomu.desktop.feature.pdf;

import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.cbz.CbzDocumentService;
import io.github.jukomu.desktop.feature.files.FileReferences;
import io.github.jukomu.desktop.feature.files.FileService;
import io.github.jukomu.desktop.feature.localfile.data.LocalFileStore;
import io.github.jukomu.desktop.feature.localfile.management.LocalFileManagementService;
import io.github.jukomu.desktop.feature.localfile.model.ImportLocalFileItemRequest;
import io.github.jukomu.desktop.feature.localfile.model.ImportLocalFilesResponse;
import io.github.jukomu.desktop.feature.localfile.model.LocalFileResponse;
import io.github.jukomu.desktop.feature.pdf.render.PdfDocumentService;
import io.github.jukomu.desktop.feature.pdf.render.PdfPageCache;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

            var chapterPage = fixture.service().getFiles(
                    List.of("pdf"), null, null, null,
                    "album-1", "chapter-1", null, null, null, 10);
            assertEquals(1, chapterPage.files().size());
            assertEquals(record.id(), chapterPage.files().getFirst().id());

            var filePage = fixture.service().getFiles(
                    null, null, null, record.id(),
                    null, null, null, null, null, 10);
            assertEquals(1, filePage.files().size());
            assertEquals(record.id(), filePage.files().getFirst().id());

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

    @Test
    void scansImportsAndVerifiesCbzWithoutTreatingZipAsImportable() throws Exception {
        Fixture fixture = fixture();
        try (Database ignored = fixture.database()) {
            Path root = fixture.paths().pdfDirectory();
            Path cbz = root.resolve("123 第2话.cbz");
            writeCbz(cbz, "001.jpg", "page");
            writeCbz(root.resolve("ignored.zip"), "001.jpg", "page");

            var scanned = fixture.files().scanImportableFiles(
                    FileReferences.folderRef(root), List.of("pdf", "cbz"));
            assertEquals(List.of("123 第2话.cbz"),
                    scanned.files().stream().map(file -> file.fileName()).toList());
            assertEquals("cbz", scanned.files().getFirst().format());

            ImportLocalFileItemRequest item = new ImportLocalFileItemRequest(
                    "cbz", FileReferences.fileRef(cbz), cbz.toString(), cbz.getFileName().toString(),
                    "123", "Album", "", "Alice", "chapter-2", "Chapter 2",
                    2, false, null);
            ImportLocalFilesResponse imported = fixture.service().importLocalFiles(List.of(item));
            LocalFileResponse record = fixture.service().getImportedLocalFiles().files().getFirst();

            assertEquals(1, imported.imported());
            assertEquals("cbz", record.format());
            assertEquals(1, record.pageCount());
            assertEquals("valid", fixture.service().verifyFile(record.id()).verificationStatus());
        }
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
                new PdfDocumentService(new PdfPageCache(paths.cacheDirectory())),
                new CbzDocumentService()
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

    private static void writeCbz(Path path, String entryName, String content) throws Exception {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry(entryName));
            output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private record Fixture(
            Paths paths,
            Database database,
            FileService files,
            LocalFileManagementService service
    ) {
    }
}
