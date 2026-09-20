package io.github.jukomu.desktop.feature.pdf.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.download.DownloadFiles;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.download.data.StoredDownloadPage;
import io.github.jukomu.desktop.feature.files.FileReferences;
import io.github.jukomu.desktop.feature.pdf.data.PdfStore;
import io.github.jukomu.desktop.feature.pdf.model.PdfExportBatchResponse;
import io.github.jukomu.desktop.feature.pdf.model.PdfExportTargetRequest;
import io.github.jukomu.desktop.feature.pdf.model.PdfExportTaskRequest;
import io.github.jukomu.desktop.feature.pdf.model.PdfExportTaskResponse;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfExportServiceTest {
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

            PdfExportBatchResponse submitted = fixture.service.submit(List.of(
                    fixture.task("chapter-1", "first.pdf", 2, false),
                    fixture.task("chapter-2", "second.pdf", 0, false)
            ));
            assertTrue(submitted.tasks().stream().allMatch(PdfExportTaskResponse::accepted));
            long firstRevision = submitted.tasks().get(0).snapshotRevision();

            PdfExportTaskResponse first = fixture.awaitTerminal(submitted.tasks().get(0).exportId());
            PdfExportTaskResponse second = fixture.awaitTerminal(submitted.tasks().get(1).exportId());
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
            assertEquals(3, new PdfStore(fixture.database).listAll().size());
            assertNotNull(first.outputFileRef());

            assertTrue(fixture.service.deleteTask(first.exportId()));
            assertTrue(Files.isRegularFile(firstVolume));
            assertTrue(Files.isRegularFile(secondVolume));
            assertEquals("completed", second.status());

            PdfExportBatchResponse conflict = fixture.service.submit(List.of(
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
            PdfExportTaskResponse queued = fixture.service.submit(List.of(
                    fixture.task("chapter-1", "cancel.pdf", 1, false))).tasks().get(0);

            assertTrue(writer.secondVolume.await(5, TimeUnit.SECONDS));
            PdfExportTaskResponse cancelling = fixture.service.cancel(queued.exportId());
            assertEquals("cancelling", cancelling.status());
            PdfExportTaskResponse terminal = fixture.awaitTerminal(queued.exportId());

            assertEquals("partial", terminal.status());
            assertEquals("CANCELLED", terminal.errorCode());
            assertTrue(Files.isRegularFile(fixture.output.resolve("cancel_001-001.pdf")));
            assertFalse(Files.exists(fixture.output.resolve("cancel_002-002.pdf")));
            assertTrue(fixture.store.volumes(queued.exportId()).stream()
                    .map(PdfExportStore.Volume::tempPath)
                    .noneMatch(path -> Files.exists(Path.of(path))));
        }
    }

    @Test
    void failureAfterOneVolumeCanRetryWithExplicitOverwrite() throws Exception {
        FailSecondVolumeOnceWriter writer = new FailSecondVolumeOnceWriter();
        try (Fixture fixture = fixture(writer)) {
            fixture.addDownload("album-1", "chapter-1", 2);
            PdfExportTaskResponse queued = fixture.service.submit(List.of(
                    fixture.task("chapter-1", "retry.pdf", 1, false))).tasks().get(0);
            PdfExportTaskResponse partial = fixture.awaitTerminal(queued.exportId());

            assertEquals("partial", partial.status());
            assertEquals("TEST_WRITE_FAILED", partial.errorCode());
            PdfExportTaskResponse retried = fixture.service.retry(queued.exportId(), true);
            assertEquals("queued", retried.status());
            PdfExportTaskResponse completed = fixture.awaitTerminal(queued.exportId());

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
            PdfExportTaskResponse queued = fixture.service.submit(List.of(
                    fixture.task("chapter-1", "failed.pdf", 0, false))).tasks().get(0);

            PdfExportTaskResponse failed = fixture.awaitTerminal(queued.exportId());

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
            PdfExportTaskResponse queued = fixture.service.submit(List.of(
                    fixture.mergedTask("merged.pdf"))).tasks().get(0);

            PdfExportTaskResponse completed = fixture.awaitTerminal(queued.exportId());

            assertEquals("completed", completed.status());
            assertEquals(3, pageCount(fixture.output.resolve("merged.pdf")));
            var libraryFile = new PdfStore(fixture.database).listAll().get(0);
            assertEquals("multi_chapter", libraryFile.chapterLinkStatus());
            assertNull(libraryFile.chapterId());
        }
    }

    @Test
    void startupMarksActiveTaskInterruptedAndRemovesKnownTemporaryFile() throws Exception {
        try (Fixture fixture = fixture(new TrackingWriter(), new HoldingExecutorService())) {
            fixture.addDownload("album-1", "chapter-1", 1);
            PdfExportTaskResponse queued = fixture.service.submit(List.of(
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
            PdfExportTaskResponse queued = fixture.service.submit(List.of(
                    fixture.task("chapter-1", "queued-cancel.pdf", 0, false))).tasks().get(0);

            PdfExportTaskResponse cancelled = fixture.service.cancel(queued.exportId());

            assertEquals("cancelled", cancelled.status());
            assertEquals("cancelled", fixture.store.volumes(queued.exportId()).get(0).status());
        }
    }

    @Test
    void runningProgressCannotOverwriteCancellingStatus() throws Exception {
        try (Fixture fixture = fixture(new TrackingWriter(), new HoldingExecutorService())) {
            fixture.addDownload("album-1", "chapter-1", 1);
            PdfExportTaskResponse queued = fixture.service.submit(List.of(
                    fixture.task("chapter-1", "cancel-race.pdf", 0, false))).tasks().get(0);
            assertNotNull(fixture.store.claim(queued.exportId()));
            PdfExportTaskResponse cancelling = fixture.store.requestCancel(queued.exportId());

            PdfExportTaskResponse afterProgress = fixture.store.updateProgress(
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
        PdfExportStore store = new PdfExportStore(database);
        PdfExportService service = new PdfExportService(
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
            PdfExportStore store,
            PdfExportService service,
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

        PdfExportTaskRequest task(
                String chapterId,
                String targetName,
                int splitPages,
                boolean allowOverwrite
        ) {
            return new PdfExportTaskRequest(
                    "chapter", "album-1", "Album", "", "Alice", false,
                    chapterId, "Chapter", null,
                    new PdfExportTargetRequest(FileReferences.folderRef(output), targetName),
                    output.resolve(targetName).toString(), true, 1D, splitPages, allowOverwrite);
        }

        PdfExportTaskRequest mergedTask(String targetName) {
            return new PdfExportTaskRequest(
                    "merged", "album-1", "Album", "", "Alice", false,
                    null, "Album", List.of(
                    new io.github.jukomu.desktop.feature.pdf.model.PdfExportChapterRequest(
                            "album-1", "chapter-1", "Chapter 1", 1),
                    new io.github.jukomu.desktop.feature.pdf.model.PdfExportChapterRequest(
                            "album-1", "chapter-2", "Chapter 2", 2)),
                    new PdfExportTargetRequest(FileReferences.folderRef(output), targetName),
                    output.resolve(targetName).toString(), true, 1D, 0, false);
        }

        PdfExportTaskResponse awaitTerminal(String exportId) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            PdfExportTaskResponse current;
            do {
                current = service.getTask(exportId);
                if (PdfExportStore.isTerminal(current.status())) return current;
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
