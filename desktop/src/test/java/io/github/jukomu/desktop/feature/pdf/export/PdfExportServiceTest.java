package io.github.jukomu.desktop.feature.pdf.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.download.DownloadFiles;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.download.data.StoredDownloadPage;
import io.github.jukomu.desktop.feature.files.FileReferences;
import io.github.jukomu.desktop.feature.pdf.data.LocalFileStore;
import io.github.jukomu.desktop.feature.pdf.model.ExportBatchResponse;
import io.github.jukomu.desktop.feature.pdf.model.ExportTargetRequest;
import io.github.jukomu.desktop.feature.pdf.model.ExportTaskRequest;
import io.github.jukomu.desktop.feature.pdf.model.ExportTaskResponse;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportServiceTest {
    @Test
    void pdfBoxWriterCreatesReadableCompressedPdf() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-pdf-writer-");
        Path image = root.resolve("page.png");
        BufferedImage source = new BufferedImage(40, 60, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, Color.BLUE.getRGB());
        ImageIO.write(source, "png", image.toFile());
        Path output = root.resolve("volume.tmp.pdf");
        AtomicInteger progress = new AtomicInteger();

        new PdfBoxVolumeWriter().write(List.of(image), output, false, 0.6D,
                progress::set);

        assertEquals(1, progress.get());
        assertEquals(1, pageCount(output));
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            assertEquals(24F, document.getPage(0).getMediaBox().getWidth(), 0.01F);
            assertEquals(36F, document.getPage(0).getMediaBox().getHeight(), 0.01F);
        }
    }

    @Test
    void exportsSplitVolumesSeriallyRegistersFilesAndPreservesOutputsOnHistoryDelete()
            throws Exception {
        TrackingWriter writer = new TrackingWriter();
        try (Fixture fixture = fixture(writer)) {
            fixture.addDownload("album-1", "chapter-1", 3);
            fixture.addDownload("album-1", "chapter-2", 1);

            ExportBatchResponse submitted = fixture.service.submit(List.of(
                    fixture.task("chapter-1", "first.pdf", 2, false),
                    fixture.task("chapter-2", "second.pdf", 0, false)
            ));
            assertTrue(submitted.tasks().stream().allMatch(ExportTaskResponse::accepted));
            long firstRevision = submitted.tasks().get(0).snapshotRevision();

            ExportTaskResponse first = fixture.awaitTerminal(submitted.tasks().get(0).exportId());
            ExportTaskResponse second = fixture.awaitTerminal(submitted.tasks().get(1).exportId());
            assertEquals("completed", first.status());
            assertEquals("completed", second.status());
            assertEquals(3, first.currentPage());
            assertEquals(2, first.totalVolumes());
            assertTrue(first.snapshotRevision() > firstRevision);
            assertEquals(1, writer.maxConcurrent.get());

            Path firstVolume = fixture.output.resolve("first_001-002.pdf");
            Path secondVolume = fixture.output.resolve("first_003-003.pdf");
            assertEquals(2, pageCount(firstVolume));
            assertEquals(1, pageCount(secondVolume));
            assertEquals(3, new LocalFileStore(fixture.database).listAll().size());
            assertNotNull(first.outputFileRef());

            assertTrue(fixture.service.deleteTask(first.exportId()));
            assertTrue(Files.isRegularFile(firstVolume));
            assertTrue(Files.isRegularFile(secondVolume));
            assertEquals("completed", second.status());

            ExportBatchResponse conflict = fixture.service.submit(List.of(
                    fixture.task("chapter-2", "second.pdf", 0, false)));
            assertFalse(conflict.tasks().get(0).accepted());
            assertEquals("PDF_OUTPUT_EXISTS", conflict.tasks().get(0).errorCode());
        }
    }

    @Test
    void cancellationAfterOneVolumeProducesPartialAndCleansIncompleteArtifacts() throws Exception {
        BlockingSecondVolumeWriter writer = new BlockingSecondVolumeWriter();
        try (Fixture fixture = fixture(writer)) {
            fixture.addDownload("album-1", "chapter-1", 2);
            ExportTaskResponse queued = fixture.service.submit(List.of(
                    fixture.task("chapter-1", "cancel.pdf", 1, false))).tasks().get(0);

            assertTrue(writer.secondVolume.await(5, TimeUnit.SECONDS));
            ExportTaskResponse cancelling = fixture.service.cancel(queued.exportId());
            assertEquals("cancelling", cancelling.status());
            ExportTaskResponse terminal = fixture.awaitTerminal(queued.exportId());

            assertEquals("partial", terminal.status());
            assertEquals("CANCELLED", terminal.errorCode());
            assertTrue(Files.isRegularFile(fixture.output.resolve("cancel_001-001.pdf")));
            assertFalse(Files.exists(fixture.output.resolve("cancel_002-002.pdf")));
            assertTrue(fixture.store.volumes(queued.exportId()).stream()
                    .map(ExportStore.Volume::tempPath)
                    .noneMatch(path -> Files.exists(Path.of(path))));
        }
    }

    @Test
    void failureAfterOneVolumeCanRetryWithExplicitOverwrite() throws Exception {
        FailSecondVolumeOnceWriter writer = new FailSecondVolumeOnceWriter();
        try (Fixture fixture = fixture(writer)) {
            fixture.addDownload("album-1", "chapter-1", 2);
            ExportTaskResponse queued = fixture.service.submit(List.of(
                    fixture.task("chapter-1", "retry.pdf", 1, false))).tasks().get(0);
            ExportTaskResponse partial = fixture.awaitTerminal(queued.exportId());

            assertEquals("partial", partial.status());
            assertEquals("TEST_WRITE_FAILED", partial.errorCode());
            ExportTaskResponse retried = fixture.service.retry(queued.exportId(), true);
            assertEquals("queued", retried.status());
            ExportTaskResponse completed = fixture.awaitTerminal(queued.exportId());

            assertEquals("completed", completed.status());
            assertEquals(2, completed.currentPage());
            assertEquals(1, pageCount(fixture.output.resolve("retry_001-001.pdf")));
            assertEquals(1, pageCount(fixture.output.resolve("retry_002-002.pdf")));
        }
    }

    @Test
    void firstVolumeFailureProducesFailedTask() throws Exception {
        PdfVolumeWriter writer = (images, temporaryFile, useOriginal, compressionRatio, progress) -> {
            throw new Exception("TEST_WRITE_FAILED: 模拟首卷写入失败");
        };
        try (Fixture fixture = fixture(writer)) {
            fixture.addDownload("album-1", "chapter-1", 1);
            ExportTaskResponse queued = fixture.service.submit(List.of(
                    fixture.task("chapter-1", "failed.pdf", 0, false))).tasks().get(0);

            ExportTaskResponse failed = fixture.awaitTerminal(queued.exportId());

            assertEquals("failed", failed.status());
            assertEquals("TEST_WRITE_FAILED", failed.errorCode());
            assertFalse(Files.exists(fixture.output.resolve("failed.pdf")));
        }
    }

    @Test
    void mergesPersistedChaptersIntoOneMultiChapterLibraryFile() throws Exception {
        try (Fixture fixture = fixture(new TrackingWriter())) {
            fixture.addDownload("album-1", "chapter-1", 2);
            fixture.addDownload("album-1", "chapter-2", 1);
            ExportTaskResponse queued = fixture.service.submit(List.of(
                    fixture.mergedTask("merged.pdf"))).tasks().get(0);

            ExportTaskResponse completed = fixture.awaitTerminal(queued.exportId());

            assertEquals("completed", completed.status());
            assertEquals(3, pageCount(fixture.output.resolve("merged.pdf")));
            var libraryFile = new LocalFileStore(fixture.database).listAll().get(0);
            assertEquals("pdf", libraryFile.format());
            assertEquals("multi_chapter", libraryFile.chapterLinkStatus());
            assertEquals(2, libraryFile.chapters().size());
            assertEquals("chapter-1", libraryFile.chapters().get(0).chapterId());
            assertEquals(1, libraryFile.chapters().get(0).startPage());
            assertEquals(2, libraryFile.chapters().get(0).endPage());
            assertEquals("chapter-2", libraryFile.chapters().get(1).chapterId());
            assertEquals(3, libraryFile.chapters().get(1).startPage());
            assertEquals(3, libraryFile.chapters().get(1).endPage());
        }
    }

    @Test
    void exportsAndRegistersCbzAndMergedZipWithoutChangingImageBytes() throws Exception {
        try (Fixture fixture = fixture(new TrackingWriter())) {
            fixture.addDownload("album-1", "chapter-1", 2);
            fixture.addDownload("album-1", "chapter-2", 1);
            byte[] firstSource = Files.readAllBytes(fixture.files.chapterDirectory("album-1/chapter-1")
                    .resolve("001.png"));

            ExportTaskResponse cbzTask = fixture.service.submit(List.of(
                    fixture.task("cbz", "chapter-1", "first.cbz", 1, false))).tasks().get(0);
            assertEquals("completed", fixture.awaitTerminal(cbzTask.exportId()).status());
            ExportTaskResponse zipTask = fixture.service.submit(List.of(
                    fixture.mergedTask("zip", "merged.zip"))).tasks().get(0);

            assertEquals("completed", fixture.awaitTerminal(zipTask.exportId()).status());
            try (ZipFile firstVolume = new ZipFile(
                    fixture.output.resolve("first_001-001.cbz").toFile())) {
                ZipEntry image = firstVolume.getEntry("0001.png");
                assertEquals(ZipEntry.STORED, image.getMethod());
                assertArrayEquals(firstSource, firstVolume.getInputStream(image).readAllBytes());
                assertNotNull(firstVolume.getEntry("ComicInfo.xml"));
            }
            try (ZipFile merged = new ZipFile(fixture.output.resolve("merged.zip").toFile())) {
                assertNotNull(merged.getEntry("001_Chapter 1/0001.png"));
                assertNotNull(merged.getEntry("001_Chapter 1/0002.png"));
                assertNotNull(merged.getEntry("002_Chapter 2/0001.png"));
                assertNull(merged.getEntry("ComicInfo.xml"));
            }

            var files = new LocalFileStore(fixture.database).listAll();
            assertEquals(3, files.size());
            assertEquals(2, files.stream().filter(file -> "cbz".equals(file.format())).count());
            assertEquals(1, files.stream().filter(file -> "zip".equals(file.format())).count());
            assertEquals(2, files.stream().filter(file -> "zip".equals(file.format()))
                    .findFirst().orElseThrow().chapters().size());

            ExportTaskResponse conflict = fixture.service.submit(List.of(
                    fixture.task("cbz", "chapter-1", "first.cbz", 1, false))).tasks().get(0);
            assertFalse(conflict.accepted());
            assertEquals("CBZ_OUTPUT_EXISTS", conflict.errorCode());

            assertEquals("queued", fixture.service.retry(cbzTask.exportId(), true).status());
            assertEquals("completed", fixture.awaitTerminal(cbzTask.exportId()).status());
            try (ZipFile retried = new ZipFile(
                    fixture.output.resolve("first_001-001.cbz").toFile())) {
                assertArrayEquals(firstSource,
                        retried.getInputStream(retried.getEntry("0001.png")).readAllBytes());
            }
        }
    }

    @Test
    void startupMarksActiveTaskInterruptedAndRemovesKnownTemporaryFile() throws Exception {
        try (Fixture fixture = fixture(new TrackingWriter(), new HoldingExecutorService())) {
            fixture.addDownload("album-1", "chapter-1", 1);
            ExportTaskResponse queued = fixture.service.submit(List.of(
                    fixture.task("chapter-1", "interrupted.pdf", 0, false))).tasks().get(0);
            Path temporary = Path.of(fixture.store.volumes(queued.exportId()).get(0).tempPath());
            Files.writeString(temporary, "partial");

            fixture.service.reconcileOnStartup();

            assertEquals("interrupted", fixture.service.getTask(queued.exportId()).status());
            assertEquals("interrupted", fixture.store.volumes(queued.exportId()).get(0).status());
            assertFalse(Files.exists(temporary));
        }
    }

    @Test
    void cancellingQueuedTaskMarksItsVolumesCancelled() throws Exception {
        try (Fixture fixture = fixture(new TrackingWriter(), new HoldingExecutorService())) {
            fixture.addDownload("album-1", "chapter-1", 1);
            ExportTaskResponse queued = fixture.service.submit(List.of(
                    fixture.task("chapter-1", "queued-cancel.pdf", 0, false))).tasks().get(0);

            ExportTaskResponse cancelled = fixture.service.cancel(queued.exportId());

            assertEquals("cancelled", cancelled.status());
            assertEquals("cancelled", fixture.store.volumes(queued.exportId()).get(0).status());
        }
    }

    @Test
    void runningProgressCannotOverwriteCancellingStatus() throws Exception {
        try (Fixture fixture = fixture(new TrackingWriter(), new HoldingExecutorService())) {
            fixture.addDownload("album-1", "chapter-1", 1);
            ExportTaskResponse queued = fixture.service.submit(List.of(
                    fixture.task("chapter-1", "cancel-race.pdf", 0, false))).tasks().get(0);
            assertNotNull(fixture.store.claim(queued.exportId()));
            ExportTaskResponse cancelling = fixture.store.requestCancel(queued.exportId());

            ExportTaskResponse afterProgress = fixture.store.updateProgress(
                    queued.exportId(), "running", "writing", 1, 1, 1, 1, null, null);

            assertEquals("cancelling", afterProgress.status());
            assertEquals("cancelling", afterProgress.phase());
            assertEquals(cancelling.snapshotRevision(), afterProgress.snapshotRevision());
        }
    }

    private static Fixture fixture(PdfVolumeWriter writer) throws Exception {
        return fixture(writer, Executors.newSingleThreadExecutor());
    }

    private static Fixture fixture(PdfVolumeWriter writer, ExecutorService executor) throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-pdf-export-");
        Paths paths = new Paths(root.resolve("program"), root.resolve("home"), Map.of(), "Linux");
        paths.ensureDirectories();
        Path output = root.resolve("output");
        Files.createDirectories(output);
        Database database = new Database(paths);
        database.open();
        DownloadStore downloads = new DownloadStore(database);
        DownloadFiles files = new DownloadFiles(paths);
        EventHub events = new EventHub(new ObjectMapper());
        ExportStore store = new ExportStore(database);
        ExportService service = new ExportService(
                store, downloads, files, executor, events, writer);
        return new Fixture(paths, output, database, downloads, files, events, store, service, executor);
    }

    private static int pageCount(Path pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            return document.getNumberOfPages();
        }
    }

    private static void writePdf(Path target, int pages) throws Exception {
        Files.createDirectories(target.getParent());
        try (PDDocument document = new PDDocument()) {
            for (int index = 0; index < pages; index++) document.addPage(new PDPage());
            document.save(target.toFile());
        }
    }

    private static class TrackingWriter implements PdfVolumeWriter {
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();

        @Override
        public void write(List<Path> images, Path temporaryFile, boolean useOriginal,
                          double compressionRatio, Progress progress) throws Exception {
            int now = active.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
                writePdf(temporaryFile, images.size());
                for (int index = 1; index <= images.size(); index++) progress.pageWritten(index);
            } finally {
                active.decrementAndGet();
            }
        }
    }

    private static final class BlockingSecondVolumeWriter extends TrackingWriter {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch secondVolume = new CountDownLatch(1);

        @Override
        public void write(List<Path> images, Path temporaryFile, boolean useOriginal,
                          double compressionRatio, Progress progress) throws Exception {
            if (calls.incrementAndGet() == 1) {
                super.write(images, temporaryFile, useOriginal, compressionRatio, progress);
                return;
            }
            secondVolume.countDown();
            while (true) {
                progress.pageWritten(1);
                Thread.sleep(10);
            }
        }
    }

    private static final class FailSecondVolumeOnceWriter extends TrackingWriter {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicBoolean failed = new AtomicBoolean();

        @Override
        public void write(List<Path> images, Path temporaryFile, boolean useOriginal,
                          double compressionRatio, Progress progress) throws Exception {
            int call = calls.incrementAndGet();
            if (call == 2 && failed.compareAndSet(false, true)) {
                throw new Exception("TEST_WRITE_FAILED: 模拟分卷写入失败");
            }
            super.write(images, temporaryFile, useOriginal, compressionRatio, progress);
        }
    }

    private static final class HoldingExecutorService extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            // Intentionally retain queued work to simulate a process exit before execution.
        }
    }

    private record Fixture(
            Paths paths,
            Path output,
            Database database,
            DownloadStore downloads,
            DownloadFiles files,
            EventHub events,
            ExportStore store,
            ExportService service,
            ExecutorService executor
    ) implements AutoCloseable {
        void addDownload(String albumId, String chapterId, int pageCount) throws Exception {
            String taskId = albumId + "_" + chapterId;
            String relativeDirectory = files.relativeDirectory(albumId, chapterId);
            downloads.createOrResetTask(taskId, albumId, chapterId, "Album", "Chapter", "",
                    relativeDirectory, System.currentTimeMillis());
            List<StoredDownloadPage> pages = new java.util.ArrayList<>();
            long totalSize = 0;
            for (int index = 1; index <= pageCount; index++) {
                String filename = String.format("%03d.png", index);
                String relativePath = files.relativeImagePath(relativeDirectory, filename);
                Path imagePath = files.chapterDirectory(relativeDirectory).resolve(filename);
                Files.createDirectories(imagePath.getParent());
                BufferedImage image = new BufferedImage(20, 30, BufferedImage.TYPE_INT_RGB);
                image.setRGB(0, 0, Color.RED.getRGB());
                ImageIO.write(image, "png", imagePath.toFile());
                totalSize += Files.size(imagePath);
                pages.add(new StoredDownloadPage(taskId, index, chapterId, filename,
                        relativePath, "", "", "", false));
            }
            downloads.saveManifest(taskId, pageCount, "Alice", "[]", 1, false, pages);
            downloads.complete(taskId, pageCount, 1, totalSize, System.currentTimeMillis());
        }

        ExportTaskRequest task(
                String chapterId,
                String targetName,
                int splitPages,
                boolean allowOverwrite
        ) {
            return task("pdf", chapterId, targetName, splitPages, allowOverwrite);
        }

        ExportTaskRequest task(
                String format,
                String chapterId,
                String targetName,
                int splitPages,
                boolean allowOverwrite
        ) {
            return new ExportTaskRequest(
                    format, "chapter", "album-1", "Album", "", "Alice", false,
                    chapterId, "Chapter", null,
                    new ExportTargetRequest(FileReferences.folderRef(output), targetName),
                    output.resolve(targetName).toString(), true, 1D, splitPages, allowOverwrite);
        }

        ExportTaskRequest mergedTask(String targetName) {
            return mergedTask("pdf", targetName);
        }

        ExportTaskRequest mergedTask(String format, String targetName) {
            return new ExportTaskRequest(
                    format, "merged", "album-1", "Album", "", "Alice", false,
                    null, "Album", List.of(
                    new io.github.jukomu.desktop.feature.pdf.model.ExportTaskChapterRequest(
                            "album-1", "chapter-1", "Chapter 1", 1),
                    new io.github.jukomu.desktop.feature.pdf.model.ExportTaskChapterRequest(
                            "album-1", "chapter-2", "Chapter 2", 2)),
                    new ExportTargetRequest(FileReferences.folderRef(output), targetName),
                    output.resolve(targetName).toString(), true, 1D, 0, false);
        }

        ExportTaskResponse awaitTerminal(String exportId) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            ExportTaskResponse current;
            do {
                current = service.getTask(exportId);
                if (ExportStore.isTerminal(current.status())) return current;
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("PDF 导出任务未结束: " + current.status());
        }

        @Override
        public void close() {
            service.close();
            executor.shutdownNow();
            events.close();
            database.close();
        }
    }
}
