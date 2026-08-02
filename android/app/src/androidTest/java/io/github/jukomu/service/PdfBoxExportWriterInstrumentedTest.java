package io.github.jukomu.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;

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
    public void writesJpegAndPngAsDctImagesInOrder() throws Exception {
        File testDir = freshTestDirectory("pdf-phase4-sample");
        File jpeg = createImage(testDir, "page-001.jpg", 40, 60, Color.RED,
            Bitmap.CompressFormat.JPEG);
        File png = createImage(testDir, "page-002.jpg", 30, 50, Color.GREEN,
            Bitmap.CompressFormat.PNG);
        File output = new File(testDir, "phase4-sample.pdf");
        AtomicInteger progress = new AtomicInteger();

        createWriter().writeVolume(
            describe(jpeg, png),
            output,
            true,
            1F,
            listener(progress)
        );

        assertPdf(output, 2, new int[][]{{40, 60}, {30, 50}});
        assertDctImages(output, 2, false);
        assertEquals(2, progress.get());
        assertFalse(PdfBoxExportWriter.getTempFile(output).exists());
        assertFalse(PdfBoxExportWriter.getWorkDirectory(output).exists());
        assertTrue(PdfBoxExportWriter.getInitializationDurationNanos() >= 0L);
    }

    @Test
    public void directEmbedsJpegContentWithoutExtension() throws Exception {
        File testDir = freshTestDirectory("pdf-phase1-jpeg-no-extension");
        File jpeg = createImage(testDir, "page-001", 24, 36, Color.RED,
            Bitmap.CompressFormat.JPEG);
        File output = new File(testDir, "jpeg-no-extension.pdf");

        createWriter().writeVolume(describe(jpeg), output, true, 1F,
            listener(new AtomicInteger()));

        assertPdf(output, 1, new int[][]{{24, 36}});
        assertDctImages(output, 1, false);
    }

    @Test
    public void transcodesWebpContentWithJpegExtension() throws Exception {
        File testDir = freshTestDirectory("pdf-phase1-webp-wrong-extension");
        File webp = createImage(testDir, "page-001.jpg", 32, 48, Color.BLUE,
            Bitmap.CompressFormat.WEBP);
        File output = new File(testDir, "webp-wrong-extension.pdf");

        createWriter().writeVolume(describe(webp), output, true, 1F,
            listener(new AtomicInteger()));

        assertPdf(output, 1, new int[][]{{32, 48}});
        assertDctImages(output, 1, false);
    }

    @Test
    public void flattensTransparentPngOnWhiteWithoutSoftMask() throws Exception {
        File testDir = freshTestDirectory("pdf-phase1-alpha");
        File png = createTransparentPng(testDir, "transparent.png");
        File output = new File(testDir, "transparent.pdf");

        createWriter().writeVolume(describe(png), output, true, 1F,
            listener(new AtomicInteger()));

        assertPdf(output, 1, new int[][]{{20, 20}});
        assertDctImages(output, 1, false);
        assertRenderedPixel(output, 1, 1, Color.WHITE);
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

        PdfBoxExportWriter.WriteReport report = createWriter().writeVolume(
            describe(images),
            output,
            false,
            0.5F,
            PdfBoxExportWriter.WriteStrategy.chunked(
                100,
                PdfBoxExportWriter.MergeMode.OPTIMIZE_RESOURCES
            ),
            listener(new AtomicInteger())
        );

        assertPdf(output, 205, new int[][]{{20, 30}});
        assertEquals(3, report.chunkCount);
        assertFalse(PdfBoxExportWriter.getTempFile(output).exists());
        assertFalse(PdfBoxExportWriter.getWorkDirectory(output).exists());
    }

    @Test
    public void directStrategyWritesOriginalPages() throws Exception {
        File testDir = freshTestDirectory("pdf-phase2-direct");
        File first = createImage(testDir, "page-001.jpg", 40, 60, Color.RED,
            Bitmap.CompressFormat.JPEG);
        File second = createImage(testDir, "page-002.webp", 30, 50, Color.BLUE,
            Bitmap.CompressFormat.WEBP);
        File output = new File(testDir, "direct.pdf");
        AtomicInteger progress = new AtomicInteger();

        PdfBoxExportWriter.WriteReport report = createWriter().writeVolume(
            describe(first, second),
            output,
            true,
            1F,
            PdfBoxExportWriter.WriteStrategy.direct(),
            listener(progress)
        );

        assertPdf(output, 2, new int[][]{{40, 60}, {30, 50}});
        assertDctImages(output, 2, false);
        assertEquals(2, progress.get());
        assertTrue(report.completed);
        assertTrue(report.direct);
        assertEquals(0, report.chunkCount);
        assertEquals(2, report.pagesWritten);
        assertFalse(PdfBoxExportWriter.getTempFile(output).exists());
        assertFalse(PdfBoxExportWriter.getWorkDirectory(output).exists());
    }

    @Test
    public void legacyMergeStrategyWritesChunkedOutput() throws Exception {
        File testDir = freshTestDirectory("pdf-phase2-legacy-merge");
        File source = createImage(testDir, "source.jpg", 40, 60, Color.GREEN,
            Bitmap.CompressFormat.JPEG);
        File output = new File(testDir, "legacy-merge.pdf");
        List<File> images = new ArrayList<>(5);
        for (int index = 0; index < 5; index++) {
            images.add(source);
        }

        PdfBoxExportWriter.WriteReport report = createWriter().writeVolume(
            describe(images),
            output,
            true,
            1F,
            PdfBoxExportWriter.WriteStrategy.chunked(2, PdfBoxExportWriter.MergeMode.LEGACY),
            listener(new AtomicInteger())
        );

        assertPdf(output, 5, new int[][]{{40, 60}});
        assertTrue(report.completed);
        assertFalse(report.direct);
        assertEquals(3, report.chunkCount);
        assertEquals(PdfBoxExportWriter.MergeMode.LEGACY, report.mergeMode);
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
        List<PdfBoxExportWriter.ExportImageDescriptor> descriptors = new ArrayList<>();
        descriptors.add(PdfBoxExportWriter.inspectImage(valid));
        descriptors.add(new PdfBoxExportWriter.ExportImageDescriptor(
            missing,
            "image/jpeg",
            40,
            60,
            1L,
            true
        ));

        assertThrows(IOException.class, () -> createWriter().writeVolume(
            descriptors,
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

    private static List<PdfBoxExportWriter.ExportImageDescriptor> describe(File... files)
            throws IOException {
        return describe(Arrays.asList(files));
    }

    private static List<PdfBoxExportWriter.ExportImageDescriptor> describe(List<File> files)
            throws IOException {
        List<PdfBoxExportWriter.ExportImageDescriptor> descriptors =
            new ArrayList<>(files.size());
        for (File file : files) {
            descriptors.add(PdfBoxExportWriter.inspectImage(file));
        }
        return descriptors;
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

    private static File createTransparentPng(File directory, String name) throws IOException {
        File file = new File(directory, name);
        Bitmap bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.TRANSPARENT);
        for (int y = 8; y < 12; y++) {
            for (int x = 8; x < 12; x++) {
                bitmap.setPixel(x, y, Color.RED);
            }
        }
        try (FileOutputStream stream = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
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

    private static void assertDctImages(File file, int expectedImages, boolean expectSoftMask)
            throws IOException {
        int imageCount = 0;
        try (PDDocument document = PDDocument.load(file)) {
            for (PDPage page : document.getPages()) {
                PDResources resources = page.getResources();
                for (COSName name : resources.getXObjectNames()) {
                    PDXObject xObject = resources.getXObject(name);
                    if (xObject instanceof PDImageXObject) {
                        imageCount++;
                        PDImageXObject image = (PDImageXObject) xObject;
                        assertTrue(image.getStream().getFilters().contains(COSName.DCT_DECODE));
                        assertEquals(expectSoftMask, image.getCOSObject().containsKey(COSName.SMASK));
                    }
                }
            }
        }
        assertEquals(expectedImages, imageCount);
    }

    private static void assertRenderedPixel(File file, int x, int y, int expectedColor)
            throws IOException {
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_ONLY
            ); PdfRenderer renderer = new PdfRenderer(descriptor);
             PdfRenderer.Page page = renderer.openPage(0)) {
            Bitmap bitmap = Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.ARGB_8888);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            try {
                assertCloseColor(expectedColor, bitmap.getPixel(x, y));
            } finally {
                bitmap.recycle();
            }
        }
    }

    private static void assertCloseColor(int expectedColor, int actualColor) {
        assertTrue(Math.abs(Color.red(expectedColor) - Color.red(actualColor)) <= 12);
        assertTrue(Math.abs(Color.green(expectedColor) - Color.green(actualColor)) <= 12);
        assertTrue(Math.abs(Color.blue(expectedColor) - Color.blue(actualColor)) <= 12);
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
