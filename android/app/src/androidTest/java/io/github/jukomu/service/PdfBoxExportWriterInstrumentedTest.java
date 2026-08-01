package io.github.jukomu.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class PdfBoxExportWriterInstrumentedTest {

    @Test
    public void writesJpegAndPngInOrder() throws Exception {
        File testDir = freshTestDirectory("pdf-phase4-sample");
        File jpeg = createImage(testDir, "page-001.jpg", 40, 60, Color.RED,
            Bitmap.CompressFormat.JPEG);
        File png = createImage(testDir, "page-002.png", 30, 50, Color.GREEN,
            Bitmap.CompressFormat.PNG);
        File output = new File(testDir, "phase4-sample.pdf");
        AtomicInteger progress = new AtomicInteger();

        createWriter().writeVolume(
            Arrays.asList(jpeg, png),
            output,
            true,
            1F,
            listener(progress)
        );

        assertPdf(output, 2, new int[][]{{40, 60}, {30, 50}});
        assertEquals(2, progress.get());
        assertFalse(PdfBoxExportWriter.getTempFile(output).exists());
        assertFalse(PdfBoxExportWriter.getWorkDirectory(output).exists());
        assertTrue(PdfBoxExportWriter.getInitializationDurationNanos() >= 0L);
    }

    @Test
    public void writesMoreThanTwoChunksAndCompressesPages() throws Exception {
        File testDir = freshTestDirectory("pdf-phase4-chunked");
        File source = createImage(testDir, "source.jpg", 40, 60, Color.BLUE,
            Bitmap.CompressFormat.JPEG);
        File output = new File(testDir, "chunked.pdf");
        List<File> images = new ArrayList<>(205);
        for (int index = 0; index < 205; index++) {
            images.add(source);
        }

        createWriter().writeVolume(images, output, false, 0.5F,
            listener(new AtomicInteger()));

        assertPdf(output, 205, new int[][]{{20, 30}});
        assertFalse(PdfBoxExportWriter.getTempFile(output).exists());
        assertFalse(PdfBoxExportWriter.getWorkDirectory(output).exists());
    }

    @Test
    public void preservesExistingFinalFileWhenWritingFails() throws Exception {
        File testDir = freshTestDirectory("pdf-phase4-failure");
        File valid = createImage(testDir, "valid.jpg", 40, 60, Color.YELLOW,
            Bitmap.CompressFormat.JPEG);
        File missing = new File(testDir, "missing.jpg");
        File output = new File(testDir, "existing.pdf");
        byte[] original = new byte[]{1, 2, 3, 4};
        Files.write(output.toPath(), original);

        assertThrows(IOException.class, () -> createWriter().writeVolume(
            Arrays.asList(valid, missing),
            output,
            true,
            1F,
            listener(new AtomicInteger())
        ));

        assertArrayEquals(original, Files.readAllBytes(output.toPath()));
        assertFalse(PdfBoxExportWriter.getTempFile(output).exists());
        assertFalse(PdfBoxExportWriter.getWorkDirectory(output).exists());
    }

    private PdfBoxExportWriter createWriter() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return new PdfBoxExportWriter(context);
    }

    private File freshTestDirectory(String name) throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getCacheDir(), name);
        deleteRecursively(directory);
        if (!directory.mkdirs()) {
            throw new IOException("Unable to create test directory: " + directory);
        }
        return directory;
    }

    private static File createImage(File directory, String name, int width, int height, int color,
            Bitmap.CompressFormat format) throws IOException {
        File file = new File(directory, name);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        try (FileOutputStream stream = new FileOutputStream(file)) {
            if (!bitmap.compress(format, 90, stream)) {
                throw new IOException("Unable to write test image: " + file);
            }
        } finally {
            bitmap.recycle();
        }
        return file;
    }

    private static PdfBoxExportWriter.ProgressListener listener(AtomicInteger progress) {
        return new PdfBoxExportWriter.ProgressListener() {
            @Override
            public void onPageWritten(int currentPage) {
                progress.set(currentPage);
            }

            @Override
            public void onFinalizing() {
                // No-op for writer integration tests.
            }
        };
    }

    private static void assertPdf(File file, int expectedPages, int[][] expectedPageSizes)
            throws IOException {
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_ONLY
            ); PdfRenderer renderer = new PdfRenderer(descriptor)) {
            assertEquals(expectedPages, renderer.getPageCount());
            for (int index = 0; index < expectedPageSizes.length; index++) {
                try (PdfRenderer.Page page = renderer.openPage(index)) {
                    assertEquals(expectedPageSizes[index][0], page.getWidth());
                    assertEquals(expectedPageSizes[index][1], page.getHeight());
                }
            }
        }
    }

    private static void deleteRecursively(File target) throws IOException {
        if (!target.exists()) {
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
