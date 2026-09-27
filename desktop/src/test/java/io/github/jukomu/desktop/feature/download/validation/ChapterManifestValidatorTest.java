package io.github.jukomu.desktop.feature.download.validation;

import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.download.DownloadFiles;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.download.data.StoredDownloadPage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChapterManifestValidatorTest {
    @Test
    void validatesManifestFilesAndReturnsOrderedReport() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.addChapter(2);

            ChapterManifestValidator.Report report = fixture.validate();

            assertEquals(2, report.totalPages());
            assertEquals(2, report.expectedFiles().size());
            assertEquals(1, report.firstSortOrder());
            assertTrue(report.totalSize() > 0);
            assertEquals("001.png", report.expectedFiles().get(0).getFileName().toString());
        }
    }

    @Test
    void rejectsMissingAndExtraImages() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.addChapter(2);
            Files.delete(fixture.image("001.png"));

            ChapterManifestValidator.ValidationException missing = assertThrows(
                ChapterManifestValidator.ValidationException.class, fixture::validate);
            assertEquals("IMAGE_MISSING", missing.code());

            fixture.writeImage("001.png");
            fixture.writeImage("extra.png");
            ChapterManifestValidator.ValidationException extra = assertThrows(
                ChapterManifestValidator.ValidationException.class, fixture::validate);
            assertEquals("IMAGE_EXTRA", extra.code());
        }
    }

    @Test
    void rejectsCorruptImagesAndDuplicateManifestEntries() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.addChapter(1);
            Files.write(fixture.image("001.png"), new byte[]{1, 2, 3, 4});

            ChapterManifestValidator.ValidationException corrupt = assertThrows(
                ChapterManifestValidator.ValidationException.class, fixture::validate);
            assertEquals("IMAGE_CORRUPT", corrupt.code());
            assertEquals(0, corrupt.verifiedPages());

            fixture.addDuplicateFilenameManifest();
            ChapterManifestValidator.ValidationException mismatch = assertThrows(
                ChapterManifestValidator.ValidationException.class, fixture::validate);
            assertEquals("IMAGE_MANIFEST_MISMATCH", mismatch.code());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Database database;
        private final DownloadStore downloads;
        private final DownloadFiles files;

        private Fixture() throws Exception {
            Path root = Files.createTempDirectory("jq-viewer-manifest-");
            Paths paths = new Paths(root.resolve("program"), root.resolve("home"), Map.of(), "Linux");
            paths.ensureDirectories();
            database = new Database(paths);
            database.open();
            downloads = new DownloadStore(database);
            files = new DownloadFiles(paths);
        }

        private void addChapter(int count) throws Exception {
            String taskId = "album-1_chapter-1";
            String relativeDirectory = files.relativeDirectory("album-1", "chapter-1");
            downloads.createOrResetTask(taskId, "album-1", "chapter-1",
                "Album", "Chapter", "", relativeDirectory, System.currentTimeMillis());
            List<StoredDownloadPage> pages = new ArrayList<>();
            long totalSize = 0L;
            for (int index = 1; index <= count; index++) {
                String filename = String.format("%03d.png", index);
                Path image = files.chapterDirectory(relativeDirectory).resolve(filename);
                Files.createDirectories(image.getParent());
                writeImage(image);
                totalSize += Files.size(image);
                pages.add(new StoredDownloadPage(taskId, index, "photo-" + index, filename,
                    files.relativeImagePath(relativeDirectory, filename), "", "", "", false));
            }
            downloads.saveManifest(taskId, count, "Alice", "[]", 1, false, pages);
            downloads.complete(taskId, count, 1, totalSize, System.currentTimeMillis());
        }

        private void addDuplicateFilenameManifest() {
            String taskId = "album-1_chapter-1";
            String relativeDirectory = files.relativeDirectory("album-1", "chapter-1");
            List<StoredDownloadPage> pages = List.of(
                new StoredDownloadPage(taskId, 1, "photo-1", "001.png",
                    files.relativeImagePath(relativeDirectory, "001.png"), "", "", "", false),
                new StoredDownloadPage(taskId, 2, "photo-2", "001.png",
                    files.relativeImagePath(relativeDirectory, "001.png"), "", "", "", false)
            );
            downloads.saveManifest(taskId, 2, "Alice", "[]", 1, false, pages);
        }

        private ChapterManifestValidator.Report validate()
            throws ChapterManifestValidator.ValidationException {
            return ChapterManifestValidator.validate(downloads, files, "album-1", "chapter-1");
        }

        private Path image(String filename) {
            return files.chapterDirectory("album-1/chapter-1").resolve(filename);
        }

        private void writeImage(String filename) throws Exception {
            writeImage(image(filename));
        }

        private static void writeImage(Path path) throws Exception {
            BufferedImage image = new BufferedImage(8, 12, BufferedImage.TYPE_INT_RGB);
            assertTrue(ImageIO.write(image, "png", path.toFile()));
        }

        @Override
        public void close() {
            database.close();
        }
    }
}
