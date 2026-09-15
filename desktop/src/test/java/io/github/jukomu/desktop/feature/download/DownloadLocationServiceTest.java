package io.github.jukomu.desktop.feature.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.download.model.DownloadRelocationResponse;
import io.github.jukomu.desktop.feature.files.FileReferences;
import io.github.jukomu.desktop.feature.files.FileService;
import io.github.jukomu.desktop.feature.pdf.export.PdfExportStore;
import io.github.jukomu.desktop.feature.settings.SettingsService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadLocationServiceTest {
    @Test
    void migratesBothDirectionsAndRestoresConfiguredRootOnRestart() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.writePrivate("album/chapter/001.jpg", "first");
            fixture.writePrivate("album/chapter/002.jpg", "second");
            fixture.writeTarget("album/chapter/001.jpg", "first");

            DownloadRelocationResponse enabled = fixture.service(fixture.selectedParent).set(true);

            assertEquals(2, enabled.moved());
            assertTrue(enabled.downloadPublic());
            assertEquals(fixture.target.toString(), enabled.displayPath());
            assertEquals(fixture.target, fixture.files.root());
            assertEquals(fixture.target,
                    fixture.settings.downloadRoot(fixture.paths.downloadsDirectory()));
            assertEquals("second", Files.readString(
                    fixture.target.resolve("album/chapter/002.jpg")));
            assertFalse(Files.exists(fixture.paths.downloadsDirectory()));

            DownloadRelocationResponse disabled = fixture.service(fixture.selectedParent).set(false);

            assertEquals(2, disabled.moved());
            assertFalse(disabled.downloadPublic());
            assertEquals(fixture.paths.downloadsDirectory(), fixture.files.root());
            assertEquals("first", Files.readString(
                    fixture.paths.downloadsDirectory().resolve("album/chapter/001.jpg")));
            assertFalse(Files.exists(fixture.target));
        }
    }

    @Test
    void rejectsSwitchWhileDownloadTaskIsActive() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.store.createOrResetTask(
                    "album_chapter", "album", "chapter", "Album", "Chapter", "",
                    "album/chapter", System.currentTimeMillis());

            ApiException failure = assertThrows(
                    ApiException.class, () -> fixture.service(fixture.selectedParent).set(true));

            assertEquals("conflict", failure.code());
            assertFalse(fixture.settings.downloadLocation().downloadPublic());
        }
    }

    @Test
    void leavesStateAndFilesUnchangedWhenFolderSelectionIsCancelled() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.writePrivate("album/chapter/001.jpg", "source");

            ApiException failure = assertThrows(
                    ApiException.class, () -> fixture.service(null).set(true));

            assertEquals("cancelled", failure.code());
            assertEquals(fixture.paths.downloadsDirectory(), fixture.files.root());
            assertTrue(Files.isRegularFile(
                    fixture.paths.downloadsDirectory().resolve("album/chapter/001.jpg")));
            assertFalse(fixture.settings.downloadLocation().downloadPublic());
        }
    }

    @Test
    void rejectsSwitchWhilePdfExportUsesDownloadedFiles() throws Exception {
        try (Fixture fixture = new Fixture()) {
            long now = System.currentTimeMillis();
            fixture.pdfExports.reserve(new PdfExportStore.ReserveTask(
                            "export-1", "batch-1", "chapter", "album", "Album", "", "",
                            false, "chapter", "Chapter",
                            FileReferences.folderRef(fixture.root.resolve("pdf")), "book.pdf", "",
                            false, true, 1D, 0, "queued", "queued", 0,
                            null, null, now),
                    List.of(), List.of());

            ApiException failure = assertThrows(
                    ApiException.class, () -> fixture.service(fixture.selectedParent).set(true));

            assertEquals("conflict", failure.code());
            assertTrue(failure.getMessage().contains("PDF 导出任务"));
            assertFalse(fixture.settings.downloadLocation().downloadPublic());
        }
    }

    @Test
    void refusesToOverwriteMismatchedTargetFiles() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.writePrivate("album/chapter/001.jpg", "source");
            fixture.writeTarget("album/chapter/001.jpg", "different");

            ApiException failure = assertThrows(
                    ApiException.class, () -> fixture.service(fixture.selectedParent).set(true));

            assertEquals("conflict", failure.code());
            assertEquals("different", Files.readString(
                    fixture.target.resolve("album/chapter/001.jpg")));
            assertTrue(Files.isRegularFile(
                    fixture.paths.downloadsDirectory().resolve("album/chapter/001.jpg")));
            assertFalse(fixture.settings.downloadLocation().downloadPublic());
        }
    }

    @Test
    void rollsBackFilesCreatedByFailedCopyWithoutSwitchingRoot() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.writePrivate("album/chapter/001.jpg", "first");
            fixture.writePrivate("album/chapter/002.jpg", "second");
            TestFileOperations operations = new TestFileOperations(2);

            ApiException failure = assertThrows(ApiException.class,
                    () -> fixture.service(fixture.selectedParent, operations).set(true));

            assertEquals("unavailable", failure.code());
            assertEquals(fixture.paths.downloadsDirectory(), fixture.files.root());
            assertTrue(Files.isRegularFile(
                    fixture.paths.downloadsDirectory().resolve("album/chapter/001.jpg")));
            assertTrue(Files.isRegularFile(
                    fixture.paths.downloadsDirectory().resolve("album/chapter/002.jpg")));
            assertFalse(Files.exists(fixture.target.resolve("album/chapter/001.jpg")));
            assertFalse(fixture.settings.downloadLocation().downloadPublic());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Path root = Files.createTempDirectory("jq-viewer-location-");
        private final Paths paths = new Paths(
                root.resolve("program"), root.resolve("home"), Map.of(), "Linux");
        private final Path selectedParent = root.resolve("selected-downloads")
                .toAbsolutePath().normalize();
        private final Path target = selectedParent.resolve(Paths.APPLICATION_NAME);
        private final Database database = new Database(paths);
        private final SettingsService settings;
        private final DownloadStore store;
        private final DownloadFiles files;
        private final PdfExportStore pdfExports;
        private final EventHub events = new EventHub(new ObjectMapper());

        private Fixture() throws Exception {
            paths.ensureDirectories();
            database.open();
            settings = new SettingsService(database);
            store = new DownloadStore(database);
            files = new DownloadFiles(paths);
            pdfExports = new PdfExportStore(database);
        }

        private DownloadLocationService service(Path selected) {
            return service(selected, new TestFileOperations(0));
        }

        private DownloadLocationService service(
                Path selected,
                DownloadLocationService.FileOperations operations
        ) {
            FileService fileService = new FileService(paths, ignored -> selected, ignored -> {
            });
            return new DownloadLocationService(
                    paths, settings, store, files, pdfExports, fileService, events, operations);
        }

        private void writePrivate(String relative, String content) throws IOException {
            write(paths.downloadsDirectory().resolve(relative), content);
        }

        private void writeTarget(String relative, String content) throws IOException {
            write(target.resolve(relative), content);
        }

        private static void write(Path path, String content) throws IOException {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        }

        @Override
        public void close() {
            events.close();
            database.close();
        }
    }

    private static final class TestFileOperations implements DownloadLocationService.FileOperations {
        private final int failOnCopy;
        private int copyCount;

        private TestFileOperations(int failOnCopy) {
            this.failOnCopy = failOnCopy;
        }

        @Override
        public void createDirectories(Path directory) throws IOException {
            Files.createDirectories(directory);
        }

        @Override
        public long availableBytes(Path directory) throws IOException {
            return Files.getFileStore(directory).getUsableSpace();
        }

        @Override
        public void copy(Path source, Path target) throws IOException {
            copyCount++;
            if (failOnCopy > 0 && copyCount == failOnCopy) {
                throw new IOException("simulated copy failure");
            }
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        }

        @Override
        public boolean matches(Path source, Path target) throws IOException {
            return Files.isRegularFile(source)
                    && Files.isRegularFile(target)
                    && Files.mismatch(source, target) == -1;
        }

        @Override
        public void deleteIfExists(Path path) throws IOException {
            Files.deleteIfExists(path);
        }

        @Override
        public void deleteTree(Path root) throws IOException {
            if (!Files.exists(root)) return;
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
