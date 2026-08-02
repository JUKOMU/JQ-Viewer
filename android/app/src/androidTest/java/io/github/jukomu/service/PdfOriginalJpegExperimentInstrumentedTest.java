package io.github.jukomu.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Debug;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class PdfOriginalJpegExperimentInstrumentedTest {

    private static final String TAG = "PdfOriginalJpeg0B";
    private static final String DEFAULT_SOURCE_ROOT =
        "/data/data/io.github.jukomu/files/downloads/1064000";
    private static final int CHUNK_PAGES = 100;
    private static final int ORIGINAL_JPEG_QUALITY = 100;

    @Test
    public void measuresOriginalImagesAsJpegQuality100() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PDFBoxResourceLoader.init(context);

        String sourceRootArg = InstrumentationRegistry.getArguments().getString("sourceRoot");
        File sourceRoot = new File(sourceRootArg == null || sourceRootArg.trim().isEmpty()
            ? DEFAULT_SOURCE_ROOT : sourceRootArg.trim());
        List<File> images = collectImages(sourceRoot);
        if (images.isEmpty()) {
            throw new IOException("No experiment images found: " + sourceRoot.getAbsolutePath());
        }

        File outputDir = new File(context.getExternalFilesDir(null), "pdf-phase0b");
        deleteRecursively(outputDir);
        if (!outputDir.mkdirs()) {
            throw new IOException("Unable to create experiment directory: " + outputDir);
        }
        File workDir = new File(outputDir, "work");
        if (!workDir.mkdirs()) {
            throw new IOException("Unable to create experiment work directory: " + workDir);
        }
        File finalPdf = new File(outputDir, "phase0b-original-jpeg-quality100.pdf");
        File tempPdf = new File(outputDir, finalPdf.getName() + ".tmp");
        File json = new File(outputDir, "phase0b-result.json");

        ExperimentMetrics metrics = new ExperimentMetrics(sourceRoot, images.size());
        boolean completed = false;
        try {
            List<File> chunks = writeChunks(images, workDir, metrics);
            metrics.recordWorkDirPeak(workDir);
            long mergeStartedAt = SystemClock.elapsedRealtimeNanos();
            mergeChunks(chunks, tempPdf, workDir);
            metrics.mergeNanos += SystemClock.elapsedRealtimeNanos() - mergeStartedAt;
            metrics.finalTempPeakBytes = tempPdf.length();
            metrics.recordWorkDirPeak(workDir);
            if (tempPdf.exists() && !tempPdf.renameTo(finalPdf)) {
                throw new IOException("Unable to move experiment PDF: " + finalPdf);
            }
            metrics.finalPdfBytes = finalPdf.length();
            assertPdf(finalPdf, images.size(), metrics.firstPageWidth, metrics.firstPageHeight);
            completed = true;
        } finally {
            metrics.finish(completed);
            writeJson(json, metrics, finalPdf);
            Log.i(TAG, "phase0b result json=" + json.getAbsolutePath());
            Log.i(TAG, metrics.toLogMessage(finalPdf));
            deleteRecursively(workDir);
            if (!completed) {
                deleteRecursively(tempPdf);
                deleteRecursively(finalPdf);
            }
        }
    }

    private static List<File> writeChunks(List<File> images, File workDir, ExperimentMetrics metrics)
            throws IOException {
        List<File> chunks = new ArrayList<>((images.size() + CHUNK_PAGES - 1) / CHUNK_PAGES);
        for (int start = 0; start < images.size(); start += CHUNK_PAGES) {
            int end = Math.min(start + CHUNK_PAGES, images.size());
            File chunk = new File(workDir,
                String.format(Locale.ROOT, "chunk-%05d.pdf", start / CHUNK_PAGES));
            long chunkStartedAt = SystemClock.elapsedRealtimeNanos();
            try (PDDocument document = new PDDocument(createMemorySetting(workDir))) {
                for (int index = start; index < end; index++) {
                    addPage(document, images.get(index), metrics);
                    metrics.pagesWritten++;
                    metrics.recordMemoryPeak();
                }
                long saveStartedAt = SystemClock.elapsedRealtimeNanos();
                document.save(chunk);
                metrics.saveNanos += SystemClock.elapsedRealtimeNanos() - saveStartedAt;
            }
            chunks.add(chunk);
            metrics.chunkCount++;
            metrics.chunkBytesSum = saturatingAdd(metrics.chunkBytesSum, chunk.length());
            metrics.chunkNanos += SystemClock.elapsedRealtimeNanos() - chunkStartedAt;
            metrics.recordWorkDirPeak(workDir);
        }
        return chunks;
    }

    private static void addPage(PDDocument document, File source, ExperimentMetrics metrics)
            throws IOException {
        long inputBytes = source.length();
        metrics.inputBytes = saturatingAdd(metrics.inputBytes, inputBytes);

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        long boundsStartedAt = SystemClock.elapsedRealtimeNanos();
        BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
        metrics.boundsDecodeNanos += SystemClock.elapsedRealtimeNanos() - boundsStartedAt;
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Unable to read image bounds: " + source.getAbsolutePath());
        }
        if (metrics.firstPageWidth == 0) {
            metrics.firstPageWidth = bounds.outWidth;
            metrics.firstPageHeight = bounds.outHeight;
        }
        metrics.pixelCount = saturatingAdd(
            metrics.pixelCount,
            saturatingMultiply(bounds.outWidth, bounds.outHeight)
        );

        long decodeStartedAt = SystemClock.elapsedRealtimeNanos();
        Bitmap decoded = BitmapFactory.decodeFile(source.getAbsolutePath());
        metrics.decodeNanos += SystemClock.elapsedRealtimeNanos() - decodeStartedAt;
        if (decoded == null) {
            throw new IOException("Unable to decode image: " + source.getAbsolutePath());
        }

        Bitmap jpegBitmap = decoded;
        try {
            if (decoded.hasAlpha()) {
                long alphaStartedAt = SystemClock.elapsedRealtimeNanos();
                jpegBitmap = Bitmap.createBitmap(
                    decoded.getWidth(),
                    decoded.getHeight(),
                    Bitmap.Config.ARGB_8888
                );
                Canvas canvas = new Canvas(jpegBitmap);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(decoded, 0F, 0F, null);
                jpegBitmap.setHasAlpha(false);
                metrics.alphaFlattenCount++;
                metrics.alphaFlattenNanos += SystemClock.elapsedRealtimeNanos() - alphaStartedAt;
            } else {
                jpegBitmap.setHasAlpha(false);
            }

            ByteArrayOutputStream jpegBytes = new ByteArrayOutputStream(
                Math.max(4096, (int) Math.min(Integer.MAX_VALUE, inputBytes))
            );
            long encodeStartedAt = SystemClock.elapsedRealtimeNanos();
            if (!jpegBitmap.compress(Bitmap.CompressFormat.JPEG, ORIGINAL_JPEG_QUALITY, jpegBytes)) {
                throw new IOException("Unable to encode JPEG: " + source.getAbsolutePath());
            }
            metrics.jpegEncodeNanos += SystemClock.elapsedRealtimeNanos() - encodeStartedAt;
            byte[] encoded = jpegBytes.toByteArray();
            metrics.encodedJpegBytes = saturatingAdd(metrics.encodedJpegBytes, encoded.length);

            long createStartedAt = SystemClock.elapsedRealtimeNanos();
            PDImageXObject pdfImage = JPEGFactory.createFromByteArray(document, encoded);
            metrics.createImageNanos += SystemClock.elapsedRealtimeNanos() - createStartedAt;

            long pageStartedAt = SystemClock.elapsedRealtimeNanos();
            PDPage page = new PDPage(new PDRectangle(jpegBitmap.getWidth(), jpegBitmap.getHeight()));
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(pdfImage, 0, 0, jpegBitmap.getWidth(), jpegBitmap.getHeight());
            }
            metrics.addPageNanos += SystemClock.elapsedRealtimeNanos() - pageStartedAt;
        } finally {
            if (jpegBitmap != decoded && !jpegBitmap.isRecycled()) {
                jpegBitmap.recycle();
            }
            if (!decoded.isRecycled()) {
                decoded.recycle();
            }
        }
    }

    private static void mergeChunks(List<File> chunks, File tempFile, File workDir) throws IOException {
        if (chunks.size() == 1) {
            if (!chunks.get(0).renameTo(tempFile)) {
                throw new IOException("Unable to move single experiment chunk: " + tempFile);
            }
            return;
        }

        PDFMergerUtility merger = new PDFMergerUtility();
        merger.setDestinationFileName(tempFile.getAbsolutePath());
        merger.setDocumentMergeMode(PDFMergerUtility.DocumentMergeMode.OPTIMIZE_RESOURCES_MODE);
        for (File chunk : chunks) {
            merger.addSource(chunk);
        }
        merger.mergeDocuments(createMemorySetting(workDir));
    }

    private static List<File> collectImages(File root) throws IOException {
        if (!root.isDirectory()) {
            throw new IOException("Experiment source root is not a directory: " + root);
        }
        List<File> images = new ArrayList<>();
        collectImages(root, images);
        Collections.sort(images, (left, right) -> left.getAbsolutePath().compareTo(right.getAbsolutePath()));
        return images;
    }

    private static void collectImages(File directory, List<File> images) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectImages(child, images);
            } else if (isImageFile(child)) {
                images.add(child);
            }
        }
    }

    private static boolean isImageFile(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg")
            || name.endsWith(".jpeg")
            || name.endsWith(".png")
            || name.endsWith(".webp")
            || name.endsWith(".bmp")
            || name.endsWith(".gif");
    }

    private static MemoryUsageSetting createMemorySetting(File workDir) {
        return MemoryUsageSetting.setupTempFileOnly().setTempDir(workDir);
    }

    private static void assertPdf(File file, int expectedPages, int firstWidth, int firstHeight)
            throws IOException {
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_ONLY
            ); PdfRenderer renderer = new PdfRenderer(descriptor)) {
            assertEquals(expectedPages, renderer.getPageCount());
            try (PdfRenderer.Page page = renderer.openPage(0)) {
                assertEquals(firstWidth, page.getWidth());
                assertEquals(firstHeight, page.getHeight());
            }
            assertTrue(file.length() > 0L);
        }
    }

    private static void writeJson(File json, ExperimentMetrics metrics, File finalPdf)
            throws IOException {
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(json),
                StandardCharsets.UTF_8
            )) {
            writer.write(metrics.toJson(finalPdf));
        }
    }

    private static void deleteRecursively(File target) throws IOException {
        if (target == null || !target.exists()) {
            return;
        }
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!target.delete() && target.exists()) {
            throw new IOException("Unable to delete experiment file: " + target);
        }
    }

    private static long directorySize(File target) {
        if (target == null || !target.exists()) {
            return 0L;
        }
        if (target.isFile()) {
            return target.length();
        }
        long total = 0L;
        File[] children = target.listFiles();
        if (children == null) {
            return 0L;
        }
        for (File child : children) {
            total = saturatingAdd(total, directorySize(child));
        }
        return total;
    }

    private static long javaHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long nativePssBytes() {
        Debug.MemoryInfo memoryInfo = new Debug.MemoryInfo();
        Debug.getMemoryInfo(memoryInfo);
        return memoryInfo.getTotalPss() * 1024L;
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long saturatingMultiply(int left, int right) {
        if (left <= 0 || right <= 0) {
            return 0L;
        }
        long value = (long) left * (long) right;
        return value < 0L ? Long.MAX_VALUE : value;
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000D);
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static final class ExperimentMetrics {
        final String sourceRoot;
        final int totalPages;
        final long startedAtNanos = SystemClock.elapsedRealtimeNanos();
        final long javaHeapStartBytes = javaHeapBytes();
        final long nativePssStartBytes = nativePssBytes();

        boolean completed;
        int pagesWritten;
        int firstPageWidth;
        int firstPageHeight;
        int chunkCount;
        int alphaFlattenCount;
        long inputBytes;
        long pixelCount;
        long encodedJpegBytes;
        long chunkBytesSum;
        long finalTempPeakBytes;
        long finalPdfBytes;
        long workDirPeakBytes;
        long javaHeapPeakBytes;
        long nativePssPeakBytes;
        long javaHeapEndBytes;
        long nativePssEndBytes;
        long boundsDecodeNanos;
        long decodeNanos;
        long alphaFlattenNanos;
        long jpegEncodeNanos;
        long createImageNanos;
        long addPageNanos;
        long saveNanos;
        long chunkNanos;
        long mergeNanos;
        long finishedAtNanos;

        ExperimentMetrics(File sourceRoot, int totalPages) {
            this.sourceRoot = sourceRoot.getAbsolutePath();
            this.totalPages = totalPages;
            this.javaHeapPeakBytes = javaHeapStartBytes;
            this.nativePssPeakBytes = nativePssStartBytes;
        }

        void recordMemoryPeak() {
            javaHeapPeakBytes = Math.max(javaHeapPeakBytes, javaHeapBytes());
            nativePssPeakBytes = Math.max(nativePssPeakBytes, nativePssBytes());
        }

        void recordWorkDirPeak(File workDir) {
            workDirPeakBytes = Math.max(workDirPeakBytes, directorySize(workDir));
        }

        void finish(boolean completed) {
            this.completed = completed;
            finishedAtNanos = SystemClock.elapsedRealtimeNanos();
            javaHeapEndBytes = javaHeapBytes();
            nativePssEndBytes = nativePssBytes();
            recordMemoryPeak();
        }

        long peakAdditionalBytes() {
            return Math.max(workDirPeakBytes, saturatingAdd(chunkBytesSum, finalTempPeakBytes));
        }

        String toLogMessage(File finalPdf) {
            return "phase0b baseline: completed=" + completed
                + ", sourceRoot=" + sourceRoot
                + ", file=" + finalPdf.getAbsolutePath()
                + ", pages=" + pagesWritten + "/" + totalPages
                + ", chunks=" + chunkCount
                + ", inputBytes=" + inputBytes
                + ", pixelCount=" + pixelCount
                + ", encodedJpegBytes=" + encodedJpegBytes
                + ", chunkBytesSum=" + chunkBytesSum
                + ", finalTempPeakBytes=" + finalTempPeakBytes
                + ", finalPdfBytes=" + finalPdfBytes
                + ", peakAdditionalBytes=" + peakAdditionalBytes()
                + ", workDirPeakBytes=" + workDirPeakBytes
                + ", javaHeapPeakBytes=" + javaHeapPeakBytes
                + ", nativePssPeakBytes=" + nativePssPeakBytes
                + ", totalMs=" + millis(finishedAtNanos - startedAtNanos)
                + ", boundsDecodeMs=" + millis(boundsDecodeNanos)
                + ", decodeMs=" + millis(decodeNanos)
                + ", alphaFlattenCount=" + alphaFlattenCount
                + ", alphaFlattenMs=" + millis(alphaFlattenNanos)
                + ", jpegEncodeMs=" + millis(jpegEncodeNanos)
                + ", createImageMs=" + millis(createImageNanos)
                + ", addPageMs=" + millis(addPageNanos)
                + ", saveMs=" + millis(saveNanos)
                + ", chunkMs=" + millis(chunkNanos)
                + ", mergeMs=" + millis(mergeNanos)
                + ", javaHeapStartBytes=" + javaHeapStartBytes
                + ", javaHeapEndBytes=" + javaHeapEndBytes
                + ", nativePssStartBytes=" + nativePssStartBytes
                + ", nativePssEndBytes=" + nativePssEndBytes;
        }

        String toJson(File finalPdf) {
            return "{\n"
                + "  \"stage\": \"0B\",\n"
                + "  \"completed\": " + completed + ",\n"
                + "  \"sourceRoot\": " + jsonString(sourceRoot) + ",\n"
                + "  \"outputPdf\": " + jsonString(finalPdf.getAbsolutePath()) + ",\n"
                + "  \"totalPages\": " + totalPages + ",\n"
                + "  \"pagesWritten\": " + pagesWritten + ",\n"
                + "  \"chunkCount\": " + chunkCount + ",\n"
                + "  \"inputBytes\": " + inputBytes + ",\n"
                + "  \"pixelCount\": " + pixelCount + ",\n"
                + "  \"encodedJpegBytes\": " + encodedJpegBytes + ",\n"
                + "  \"chunkBytesSum\": " + chunkBytesSum + ",\n"
                + "  \"pdfBoxScratchPeakBytes\": 0,\n"
                + "  \"pdfBoxScratchPeakNote\": \"not separately observable; covered by workDirPeakBytes\",\n"
                + "  \"finalTempPeakBytes\": " + finalTempPeakBytes + ",\n"
                + "  \"finalPdfBytes\": " + finalPdfBytes + ",\n"
                + "  \"peakAdditionalBytes\": " + peakAdditionalBytes() + ",\n"
                + "  \"workDirPeakBytes\": " + workDirPeakBytes + ",\n"
                + "  \"javaHeapPeakBytes\": " + javaHeapPeakBytes + ",\n"
                + "  \"nativePssPeakBytes\": " + nativePssPeakBytes + ",\n"
                + "  \"totalMs\": " + millis(finishedAtNanos - startedAtNanos) + ",\n"
                + "  \"boundsDecodeMs\": " + millis(boundsDecodeNanos) + ",\n"
                + "  \"decodeMs\": " + millis(decodeNanos) + ",\n"
                + "  \"alphaFlattenCount\": " + alphaFlattenCount + ",\n"
                + "  \"alphaFlattenMs\": " + millis(alphaFlattenNanos) + ",\n"
                + "  \"jpegEncodeMs\": " + millis(jpegEncodeNanos) + ",\n"
                + "  \"createImageMs\": " + millis(createImageNanos) + ",\n"
                + "  \"addPageMs\": " + millis(addPageNanos) + ",\n"
                + "  \"saveMs\": " + millis(saveNanos) + ",\n"
                + "  \"chunkMs\": " + millis(chunkNanos) + ",\n"
                + "  \"mergeMs\": " + millis(mergeNanos) + ",\n"
                + "  \"javaHeapStartBytes\": " + javaHeapStartBytes + ",\n"
                + "  \"javaHeapEndBytes\": " + javaHeapEndBytes + ",\n"
                + "  \"nativePssStartBytes\": " + nativePssStartBytes + ",\n"
                + "  \"nativePssEndBytes\": " + nativePssEndBytes + "\n"
                + "}\n";
        }
    }
}
