package io.github.jukomu.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.jukomu.data.DownloadStore;
import io.github.jukomu.data.FileStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
public class PdfExportServiceInstrumentedTest {

    private static final String ALBUM_ID = "900000001";
    private static final long EXPORT_TIMEOUT_MS = 10_000L;
    private static final long LARGE_EXPORT_TIMEOUT_MS = 90_000L;

    private Context context;
    private FileStore fileStore;
    private File albumDirectory;
    private File outputDirectory;

    @Before
    public void setUp() throws IOException {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        fileStore = FileStore.getInstance();
        fileStore.init(context, DownloadStore.getInstance(context), false);
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

        File output = new File(outputDirectory, "第2话+第5话.pdf");
        PdfExportService.ExportJob job = new PdfExportService.ExportJob();
        job.mode = "merged";
        job.albumId = ALBUM_ID;
        job.chapterTitle = "第2话+第5话";
        job.chapters = Arrays.asList(
            chapter("900000002", "第2话", 2),
            chapter("900000005", "第5话", 5)
        );
        job.savePath = output.getAbsolutePath();
        job.useOriginal = true;
        job.compressionRatio = 1F;
        job.splitPages = 3;

        List<PdfExportService.ExportVolume> volumes =
            PdfExportService.buildVolumes(output, 4, job.splitPages);
        PdfExportService.getInstance(context).submitExport(Arrays.asList(job));
        waitForVolumes(volumes);

        assertFalse(output.exists());
        assertPdf(volumes.get(0).file, new int[][]{{10, 20}, {20, 30}, {30, 40}});
        assertPdf(volumes.get(1).file, new int[][]{{40, 50}});
        for (PdfExportService.ExportVolume volume : volumes) {
            assertFalse(PdfBoxExportWriter.getTempFile(volume.file).exists());
            assertFalse(PdfBoxExportWriter.getWorkDirectory(volume.file).exists());
        }
    }

    @Test
    public void exportsOneThousandPagesWithoutLeavingArtifacts() throws Exception {
        File firstChapter = fileStore.ensureChapterDir(ALBUM_ID, "900001001");
        File secondChapter = fileStore.ensureChapterDir(ALBUM_ID, "900001002");
        createRepeatedImages(firstChapter, 500, Color.RED);
        createRepeatedImages(secondChapter, 500, Color.BLUE);

        File output = new File(outputDirectory, "large-1000.pdf");
        PdfExportService.ExportJob job = new PdfExportService.ExportJob();
        job.mode = "merged";
        job.albumId = ALBUM_ID;
        job.chapterTitle = "第1-2话";
        job.chapters = Arrays.asList(
            chapter("900001001", "第1话", 1),
            chapter("900001002", "第2话", 2)
        );
        job.savePath = output.getAbsolutePath();
        job.useOriginal = true;
        job.compressionRatio = 1F;
        job.splitPages = 0;

        PdfExportService.getInstance(context).submitExport(Arrays.asList(job));
        waitForFile(output, LARGE_EXPORT_TIMEOUT_MS);

        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                output,
                ParcelFileDescriptor.MODE_READ_ONLY
            ); PdfRenderer renderer = new PdfRenderer(descriptor)) {
            assertEquals(1000, renderer.getPageCount());
            assertPageSize(renderer, 0, 480, 720);
            assertPageSize(renderer, 499, 480, 720);
            assertPageSize(renderer, 500, 480, 720);
            assertPageSize(renderer, 999, 480, 720);
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

    private static void waitForFile(File file, long timeoutMs) throws IOException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isExportComplete(file)) {
                return;
            }
            SystemClock.sleep(50L);
        }
        throw new IOException("Timed out waiting for PDF output: " + describeExportState(file));
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

    private static void assertPageSize(PdfRenderer renderer, int index, int width, int height) {
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            assertEquals(width, page.getWidth());
            assertEquals(height, page.getHeight());
        }
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
}
