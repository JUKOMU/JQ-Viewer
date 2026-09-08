package io.github.jukomu.feature.pdf.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import androidx.documentfile.provider.DocumentFile;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import io.github.jukomu.feature.download.data.DownloadStore;
import io.github.jukomu.feature.download.storage.FileStore;
import io.github.jukomu.feature.pdf.data.PdfStore;
import io.github.jukomu.feature.pdf.data.PdfRef;
import io.github.jukomu.jmcomic.api.model.JmImage;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PdfExportServiceInstrumentedTest {

    private static final String ALBUM_ID = "900000001";
    private static final long EXPORT_TIMEOUT_MS = 10_000L;
    private static final long LARGE_EXPORT_TIMEOUT_MS = 90_000L;

    private Context context;
    private FileStore fileStore;
    private DownloadStore downloadStore;
    private File albumDirectory;
    private File outputDirectory;

    @Before
    public void setUp() throws IOException {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        fileStore = FileStore.getInstance();
        downloadStore = DownloadStore.getInstance(context);
        fileStore.init(context, downloadStore, false);
        albumDirectory = new File(fileStore.getBaseDir(), ALBUM_ID);
        outputDirectory = new File(context.getCacheDir(), "pdf-phase6-service");
        deleteRecursively(albumDirectory);
        deleteRecursively(outputDirectory);
        assertTrue(outputDirectory.mkdirs());
    }

    @After
    public void tearDown() throws IOException {
        deleteRecursively(albumDirectory);
        deleteRecursively(outputDirectory);
    }

    @Test
    public void exportsMergedChaptersInOrderAndSplitsByTotalPages() throws Exception {
        File chapterTwo = fileStore.ensureChapterDir(ALBUM_ID, "900000002");
        File chapterFive = fileStore.ensureChapterDir(ALBUM_ID, "900000005");
        File unselectedChapter = fileStore.ensureChapterDir(ALBUM_ID, "900000009");
        createImage(chapterTwo, "page-001.jpg", 10, 20, Color.RED);
        createImage(chapterTwo, "page-002.jpg", 20, 30, Color.GREEN);
        createImage(chapterFive, "page-001.jpg", 30, 40, Color.BLUE);
        createImage(chapterFive, "page-002.jpg", 40, 50, Color.YELLOW);
        createImage(unselectedChapter, "page-001.jpg", 50, 60, Color.MAGENTA);
        registerChapter("900000002", 2);
        registerChapter("900000005", 2);

        File output = new File(outputDirectory, "第2话+第5话.pdf");
        PdfExportService.ExportJob job = new PdfExportService.ExportJob();
        job.mode = "merged";
        job.albumId = ALBUM_ID;
        job.chapterTitle = "第2话+第5话";
        job.chapters = Arrays.asList(
            chapter("900000002", "第2话", 2),
            chapter("900000005", "第5话", 5)
        );
        job.targetFolderRef = PdfRef.createPathFolderRef(outputDirectory.getCanonicalPath());
        job.targetName = output.getName();
        job.displayPath = output.getAbsolutePath();
        job.useOriginal = true;
        job.compressionRatio = 1F;
        job.splitPages = 3;

        List<PdfExportService.ExportVolume> volumes =
            PdfExportService.buildVolumes(output, 4, job.splitPages);
        PdfExportService service = PdfExportService.getInstance(context);
        JSONObject submission = service.submitExport(Arrays.asList(job));
        String exportId = submission.getJSONArray("tasks").getJSONObject(0)
            .getString("exportId");
        waitForVolumes(volumes);

        JSONObject completed = waitForTaskTerminal(exportId, EXPORT_TIMEOUT_MS);
        assertEquals("completed", completed.optString("status"));
        assertFalse(output.exists());
        assertPdf(volumes.get(0).file, new int[][]{{10, 20}, {20, 30}, {30, 40}});
        assertPdf(volumes.get(1).file, new int[][]{{40, 50}});
        PdfStore store = PdfStore.getInstance(context);
        for (int index = 0; index < volumes.size(); index++) {
            JSONObject persisted = store.getExportVolume(exportId, index + 1);
            assertEquals("completed", persisted.optString("status"));
            assertNotNull(store.getFileByRef(persisted.getString("outputFileRef")));
        }
        for (PdfExportService.ExportVolume volume : volumes) {
            assertFalse(PdfBoxExportWriter.getTempFile(volume.file).exists());
            assertFalse(PdfBoxExportWriter.getWorkDirectory(volume.file).exists());
        }
    }

    @Test
    public void existingPathOutputIsRejectedBeforePersistingAnExportTask() throws Exception {
        String chapterId = "9" + System.nanoTime();
        File chapterDirectory = fileStore.ensureChapterDir(ALBUM_ID, chapterId);
        createImage(chapterDirectory, "page-0001.jpg", 20, 30, Color.RED);
        registerChapter(chapterId, 1);

        File output = new File(outputDirectory, "existing.pdf");
        assertTrue(output.createNewFile());
        PdfExportService.ExportJob job = new PdfExportService.ExportJob();
        job.mode = "chapter";
        job.albumId = ALBUM_ID;
        job.chapterId = chapterId;
        job.chapterTitle = "已存在文件";
        job.targetFolderRef = PdfRef.createPathFolderRef(outputDirectory.getCanonicalPath());
        job.targetName = output.getName();
        job.displayPath = output.getAbsolutePath();
        job.useOriginal = true;
        job.compressionRatio = 1F;

        JSONObject submission = PdfExportService.getInstance(context)
            .submitExport(Arrays.asList(job));
        JSONObject result = submission.getJSONArray("tasks").getJSONObject(0);

        assertFalse(result.optBoolean("accepted"));
        assertEquals("PDF_OUTPUT_EXISTS", result.getString("errorCode"));
        assertEquals(output.getAbsolutePath(), result.getString("displayPath"));
        assertFalse(result.has("exportId"));
    }

    @Test
    public void safOverwriteRejectsDirectoriesAndFileIntermediatesWithoutDeletingData()
        throws Exception {
        File safRoot = new File(outputDirectory, "saf-root");
        File nested = new File(safRoot, "295852");
        File targetDirectory = new File(nested, "book.pdf");
        File targetContent = new File(targetDirectory, "keep.txt");
        assertTrue(targetDirectory.mkdirs());
        Files.write(targetContent.toPath(), new byte[]{7, 8, 9});

        IOException targetError = assertThrows(IOException.class,
            () -> PdfExportService.validateSafTarget(
                DocumentFile.fromFile(safRoot), "295852/book.pdf", true));
        assertEquals("目标路径指向文件夹", targetError.getMessage());
        assertTrue(targetDirectory.isDirectory());
        assertArrayEquals(new byte[]{7, 8, 9}, Files.readAllBytes(targetContent.toPath()));

        File intermediateFile = new File(safRoot, "not-directory");
        Files.write(intermediateFile.toPath(), new byte[]{1});
        IOException intermediateError = assertThrows(IOException.class,
            () -> PdfExportService.validateSafTarget(
                DocumentFile.fromFile(safRoot), "not-directory/book.pdf", true));
        assertEquals("目标路径中有一段不是目录", intermediateError.getMessage());
        assertTrue(intermediateFile.isFile());
    }

    @Test
    public void safCopyCleansFailedAndCancelledDocumentsAndKeepsSuccessfulCopy()
        throws Exception {
        File source = new File(outputDirectory, "source.bin");
        byte[] sourceBytes = new byte[128 * 1024];
        Arrays.fill(sourceBytes, (byte) 5);
        Files.write(source.toPath(), sourceBytes);

        File failed = new File(outputDirectory, "failed.pdf");
        assertTrue(failed.createNewFile());
        IOException copyFailure = new IOException("copy failed");
        IOException actualFailure = assertThrows(IOException.class,
            () -> PdfExportService.copySafDestination(
                DocumentFile.fromFile(failed),
                new FileInputStream(source),
                new FailingOutputStream(copyFailure),
                () -> {
                }));
        assertSame(copyFailure, actualFailure);
        assertFalse(failed.exists());

        File cancelled = new File(outputDirectory, "cancelled.pdf");
        assertTrue(cancelled.createNewFile());
        AtomicInteger checks = new AtomicInteger();
        PdfExportService.ExportCancelledException cancellation = assertThrows(
            PdfExportService.ExportCancelledException.class,
            () -> PdfExportService.copySafDestination(
                DocumentFile.fromFile(cancelled),
                new FileInputStream(source),
                new FileOutputStream(cancelled),
                () -> {
                    if (checks.incrementAndGet() == 2) {
                        throw new PdfExportService.ExportCancelledException();
                    }
                }));
        assertEquals("PDF 导出已取消", cancellation.getMessage());
        assertFalse(cancelled.exists());

        File successful = new File(outputDirectory, "successful.pdf");
        assertTrue(successful.createNewFile());
        PdfExportService.copySafDestination(
            DocumentFile.fromFile(successful),
            new FileInputStream(source),
            new FileOutputStream(successful),
            () -> {
            });
        assertArrayEquals(sourceBytes, Files.readAllBytes(successful.toPath()));
    }

    @Test
    public void pathCancellationCleanupDeletesOnlyOwnedCurrentVolumeFile() throws Exception {
        File currentVolume = new File(outputDirectory, "current-volume.pdf");
        File otherVolume = new File(outputDirectory, "other-volume.pdf");
        Files.write(currentVolume.toPath(), new byte[]{1, 2, 3});
        Files.write(otherVolume.toPath(), new byte[]{4, 5, 6});
        String outputRef = PdfRef.createPathFileRef(currentVolume.getCanonicalPath());
        IOException cancellation = new IOException("PDF 导出已取消");
        PdfStore store = PdfStore.getInstance(context);
        assertNull(store.getFileByRef(outputRef));

        PdfExportService.cleanupOwnedPathOutput(
            store,
            currentVolume,
            outputRef,
            PdfRef.payload(outputRef),
            currentVolume.length(),
            currentVolume.lastModified(),
            cancellation
        );

        assertFalse(currentVolume.exists());
        assertTrue(otherVolume.exists());
        assertNull(PdfStore.getInstance(context).getFileByRef(outputRef));

        File directory = new File(outputDirectory, "protected-directory");
        File content = new File(directory, "keep.txt");
        assertTrue(directory.mkdirs());
        Files.write(content.toPath(), new byte[]{9});
        String directoryRef = PdfRef.createPathFileRef(directory.getCanonicalPath());
        PdfExportService.cleanupOwnedPathOutput(
            store,
            directory,
            directoryRef,
            directory.getCanonicalPath(),
            directory.length(),
            directory.lastModified(),
            cancellation
        );
        assertTrue(directory.isDirectory());
        assertTrue(content.isFile());
    }

    @Test
    public void registeredPathOutputIsKeptWhenCancellationCleanupRuns() throws Exception {
        File registered = new File(outputDirectory, "registered.pdf");
        Files.write(registered.toPath(), new byte[]{10, 11, 12});
        String outputRef = PdfRef.createPathFileRef(registered.getCanonicalPath());
        PdfStore store = PdfStore.getInstance(context);
        long recordId = store.insertImportedPdf(
            outputRef,
            registered.getCanonicalPath(),
            registered.getName(),
            ALBUM_ID,
            "已登记 PDF",
            "",
            "",
            "chapter-registered",
            "已登记章节",
            1,
            -1,
            System.currentTimeMillis(),
            null,
            registered.length(),
            1
        );

        IOException cancellation = new IOException("PDF 导出已取消");
        PdfExportService.cleanupOwnedPathOutput(
            store,
            registered,
            outputRef,
            PdfRef.payload(outputRef),
            registered.length(),
            registered.lastModified(),
            cancellation
        );

        assertTrue(registered.isFile());
        assertNotNull(store.getFileByRef(outputRef));
        assertArrayEquals(new byte[]{10, 11, 12}, Files.readAllBytes(registered.toPath()));
        assertTrue(store.removeFileFromLibrary(recordId));
    }

    @Test
    public void exportsOneThousandPagesWithoutLeavingArtifacts() throws Exception {
        File firstChapter = fileStore.ensureChapterDir(ALBUM_ID, "900001001");
        File secondChapter = fileStore.ensureChapterDir(ALBUM_ID, "900001002");
        createRepeatedImages(firstChapter, 500, Color.RED);
        createRepeatedImages(secondChapter, 500, Color.BLUE);
        registerChapter("900001001", 500);
        registerChapter("900001002", 500);

        File output = new File(outputDirectory, "large-1000.pdf");
        PdfExportService.ExportJob job = new PdfExportService.ExportJob();
        job.mode = "merged";
        job.albumId = ALBUM_ID;
        job.chapterTitle = "第1-2话";
        job.chapters = Arrays.asList(
            chapter("900001001", "第1话", 1),
            chapter("900001002", "第2话", 2)
        );
        job.targetFolderRef = PdfRef.createPathFolderRef(outputDirectory.getCanonicalPath());
        job.targetName = output.getName();
        job.displayPath = output.getAbsolutePath();
        job.useOriginal = true;
        job.compressionRatio = 1F;
        job.splitPages = 0;

        JSONObject submission = PdfExportService.getInstance(context)
            .submitExport(Arrays.asList(job));
        String exportId = submission.getJSONArray("tasks").getJSONObject(0)
            .getString("exportId");
        JSONObject completed = waitForTaskTerminal(exportId, LARGE_EXPORT_TIMEOUT_MS);

        assertEquals("completed", completed.optString("status"));
        try (PDDocument document = PDDocument.load(output)) {
            assertEquals(1000, document.getNumberOfPages());
            assertPdfBoxPageSize(document, 0, 480, 720);
            assertPdfBoxPageSize(document, 499, 480, 720);
            assertPdfBoxPageSize(document, 500, 480, 720);
            assertPdfBoxPageSize(document, 999, 480, 720);
        }
        assertFalse(PdfBoxExportWriter.getTempFile(output).exists());
        assertFalse(PdfBoxExportWriter.getWorkDirectory(output).exists());
    }

    private PdfExportService.ExportChapter chapter(String chapterId, String title, int sortOrder) {
        PdfExportService.ExportChapter chapter = new PdfExportService.ExportChapter();
        chapter.albumId = ALBUM_ID;
        chapter.chapterId = chapterId;
        chapter.chapterTitle = title;
        chapter.sortOrder = sortOrder;
        return chapter;
    }

    private void registerChapter(String chapterId, int pageCount) throws Exception {
        String taskId = ALBUM_ID + "_" + chapterId;
        downloadStore.insertTask(taskId, ALBUM_ID, chapterId, "测试漫画", "第" + chapterId + "话", "");
        downloadStore.updateTaskDetail(taskId, pageCount, "", "[]", 0, false);
        List<JmImage> images = new ArrayList<>();
        JSONArray metaImages = new JSONArray();
        for (int index = 1; index <= pageCount; index++) {
            String filename = pageCount == 2
                ? String.format(Locale.ROOT, "page-%03d.jpg", index)
                : String.format(Locale.ROOT, "page-%04d.jpg", index);
            images.add(new JmImage(chapterId, "", filename, "", "", index));
            metaImages.put(new JSONObject()
                .put("filename", filename)
                .put("photoId", chapterId)
                .put("sortOrder", index));
        }
        downloadStore.insertImages(taskId, images);
        fileStore.saveMeta(ALBUM_ID, chapterId, new JSONObject()
            .put("albumId", ALBUM_ID)
            .put("chapterId", chapterId)
            .put("totalPages", pageCount)
            .put("images", metaImages));
    }

    private static void createImage(File directory, String name, int width, int height, int color)
        throws IOException {
        File file = new File(directory, name);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        try (FileOutputStream stream = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)) {
                throw new IOException("Unable to write test image: " + file);
            }
        } finally {
            bitmap.recycle();
        }
    }

    private static void createRepeatedImages(File directory, int count, int color)
        throws IOException {
        File source = createSourceImage(directory, color);
        byte[] bytes = Files.readAllBytes(source.toPath());
        for (int index = 1; index <= count; index++) {
            File target = new File(directory, String.format(Locale.ROOT, "page-%04d.jpg", index));
            Files.write(target.toPath(), bytes);
        }
        assertTrue(source.delete());
    }

    private static File createSourceImage(File directory, int color) throws IOException {
        File source = new File(directory, "source.tmp");
        Bitmap bitmap = Bitmap.createBitmap(480, 720, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        try (FileOutputStream stream = new FileOutputStream(source)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)) {
                throw new IOException("Unable to write source image: " + source);
            }
        } finally {
            bitmap.recycle();
        }
        return source;
    }

    private static void waitForVolumes(List<PdfExportService.ExportVolume> volumes)
        throws IOException {
        long deadline = SystemClock.elapsedRealtime() + EXPORT_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean complete = true;
            for (PdfExportService.ExportVolume volume : volumes) {
                if (!isExportComplete(volume.file)) {
                    complete = false;
                    break;
                }
            }
            if (complete) {
                return;
            }
            SystemClock.sleep(50L);
        }
        StringBuilder states = new StringBuilder();
        for (PdfExportService.ExportVolume volume : volumes) {
            if (!isExportComplete(volume.file)) {
                if (states.length() > 0) {
                    states.append("; ");
                }
                states.append(describeExportState(volume.file));
            }
        }
        throw new IOException("Timed out waiting for merged PDF volumes: " + states);
    }

    private JSONObject waitForTaskTerminal(String exportId, long timeoutMs) throws IOException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        PdfStore store = PdfStore.getInstance(context);
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONObject task = store.getExportTask(exportId);
            if (task != null && PdfStore.isTerminalExportStatus(task.optString("status"))) {
                return task;
            }
            SystemClock.sleep(50L);
        }
        throw new IOException("Timed out waiting for PDF task: " + exportId);
    }

    private static boolean isExportComplete(File file) {
        return file.isFile()
            && file.length() > 0L
            && !PdfBoxExportWriter.getTempFile(file).exists()
            && !PdfBoxExportWriter.getWorkDirectory(file).exists();
    }

    private static String describeExportState(File file) {
        File tempFile = PdfBoxExportWriter.getTempFile(file);
        File workDirectory = PdfBoxExportWriter.getWorkDirectory(file);
        return "output=" + file
            + " (exists=" + file.isFile() + ", size=" + file.length() + ")"
            + ", temp=" + tempFile + " (exists=" + tempFile.exists() + ")"
            + ", work=" + workDirectory + " (exists=" + workDirectory.exists() + ")";
    }

    private static void assertPdf(File file, int[][] expectedPageSizes) throws IOException {
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_ONLY
        ); PdfRenderer renderer = new PdfRenderer(descriptor)) {
            assertEquals(expectedPageSizes.length, renderer.getPageCount());
            for (int index = 0; index < expectedPageSizes.length; index++) {
                try (PdfRenderer.Page page = renderer.openPage(index)) {
                    assertEquals(expectedPageSizes[index][0], page.getWidth());
                    assertEquals(expectedPageSizes[index][1], page.getHeight());
                }
            }
        }
    }

    private static void assertPdfBoxPageSize(PDDocument document, int index, int width,
                                             int height) {
        assertEquals(width, document.getPage(index).getMediaBox().getWidth(), 0F);
        assertEquals(height, document.getPage(index).getMediaBox().getHeight(), 0F);
    }

    private static void deleteRecursively(File target) throws IOException {
        if (target == null || !target.exists()) {
            return;
        }
        File[] children = target.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!target.delete() && target.exists()) {
            throw new IOException("Unable to delete test file: " + target);
        }
    }

    private static final class FailingOutputStream extends OutputStream {
        private final IOException failure;

        FailingOutputStream(IOException failure) {
            this.failure = failure;
        }

        @Override
        public void write(int value) throws IOException {
            throw failure;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            throw failure;
        }
    }
}
