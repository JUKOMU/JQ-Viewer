package io.github.jukomu.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Debug;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 使用 PdfBox-Android 按固定页数分块生成单个 PDF 卷。
 */
final class PdfBoxExportWriter {

    private static final String TAG = "PdfBoxExportWriter";
    private static final int CHUNK_PAGES = 100;
    private static final int JPEG_QUALITY = 80;
    private static final int ORIGINAL_JPEG_QUALITY = 100;
    private static final long MERGE_SPACE_MARGIN_BYTES = 16L * 1024L * 1024L;
    private static final Object INITIALIZATION_LOCK = new Object();
    private static final WriteStrategy DEFAULT_WRITE_STRATEGY = WriteStrategy.chunked(
        CHUNK_PAGES,
        MergeMode.OPTIMIZE_RESOURCES
    );

    private static volatile boolean initialized;
    private static volatile long initializationDurationNanos = -1L;

    private final Context context;

    PdfBoxExportWriter(Context context) {
        this.context = context.getApplicationContext();
    }

    WriteReport writeVolume(List<ExportImageDescriptor> images, File finalFile, boolean useOriginal,
                            float compressionRatio, ProgressListener listener) throws IOException {
        return writeVolume(
            images,
            finalFile,
            useOriginal,
            compressionRatio,
            DEFAULT_WRITE_STRATEGY,
            listener
        );
    }

    WriteReport writeVolume(List<ExportImageDescriptor> images, File finalFile, boolean useOriginal,
                            float compressionRatio, WriteStrategy strategy,
                            ProgressListener listener) throws IOException {
        if (images.isEmpty()) {
            throw new IOException("没有可写入 PDF 的图片");
        }
        if (listener == null) {
            throw new IOException("PDF 写入进度监听器不能为空");
        }

        WriteStrategy effectiveStrategy = strategy == null ? DEFAULT_WRITE_STRATEGY : strategy;
        ensureInitialized();
        cleanStaleArtifacts(finalFile);
        BaselineMetrics metrics = new BaselineMetrics(images.size(), useOriginal, effectiveStrategy);

        File tempFile = getTempFile(finalFile);
        File workDir = getWorkDirectory(finalFile);
        if (!workDir.mkdirs()) {
            throw new IOException("无法创建 PDF 临时目录: " + workDir.getAbsolutePath());
        }

        boolean completed = false;
        Throwable failure = null;
        try {
            if (effectiveStrategy.direct) {
                writeDirect(
                    images,
                    tempFile,
                    workDir,
                    useOriginal,
                    compressionRatio,
                    listener,
                    metrics
                );
            } else {
                List<File> chunks = writeChunks(
                    images,
                    workDir,
                    useOriginal,
                    compressionRatio,
                    effectiveStrategy.chunkPages,
                    listener,
                    metrics
                );
                listener.onFinalizing();
                metrics.recordWorkDirPeak(workDir);
                ensureEnoughMergeSpace(workDir, chunks, metrics.chunkBytes);
                long mergeStartedAt = SystemClock.elapsedRealtimeNanos();
                mergeChunks(chunks, tempFile, workDir, effectiveStrategy.mergeMode);
                metrics.mergeNanos += SystemClock.elapsedRealtimeNanos() - mergeStartedAt;
            }
            metrics.recordWorkDirPeak(workDir);
            metrics.tempBytes = tempFile.length();
            long syncStartedAt = SystemClock.elapsedRealtimeNanos();
            syncFile(tempFile);
            metrics.fsyncNanos += SystemClock.elapsedRealtimeNanos() - syncStartedAt;
            long replaceStartedAt = SystemClock.elapsedRealtimeNanos();
            replaceAtomically(tempFile, finalFile);
            metrics.replaceNanos += SystemClock.elapsedRealtimeNanos() - replaceStartedAt;
            metrics.finalBytes = finalFile.length();
            completed = true;
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            metrics.finish(completed);
            Log.i(TAG, metrics.toLogMessage(finalFile));
            IOException cleanupError = null;
            try {
                deleteRecursively(workDir);
            } catch (IOException e) {
                cleanupError = e;
            }
            if (!completed) {
                try {
                    deleteRecursively(tempFile);
                } catch (IOException e) {
                    if (cleanupError == null) {
                        cleanupError = e;
                    } else {
                        cleanupError.addSuppressed(e);
                    }
                }
            }
            if (cleanupError != null) {
                if (failure != null) {
                    failure.addSuppressed(cleanupError);
                } else if (completed) {
                    Log.w(TAG, "PDF 已生成，但临时文件清理失败", cleanupError);
                } else {
                    throw cleanupError;
                }
            }
        }
        return metrics.toReport();
    }

    static void cleanStaleArtifacts(File finalFile) throws IOException {
        deleteRecursively(getTempFile(finalFile));
        deleteRecursively(getWorkDirectory(finalFile));
    }

    static File getTempFile(File finalFile) {
        return new File(finalFile.getAbsolutePath() + ".tmp");
    }

    static File getWorkDirectory(File finalFile) {
        File parent = finalFile.getParentFile();
        return new File(parent, "." + finalFile.getName() + ".jqpdf-work");
    }

    static long getInitializationDurationNanos() {
        return initializationDurationNanos;
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (INITIALIZATION_LOCK) {
            if (initialized) {
                return;
            }
            long startedAt = SystemClock.elapsedRealtimeNanos();
            PDFBoxResourceLoader.init(context);
            initializationDurationNanos = SystemClock.elapsedRealtimeNanos() - startedAt;
            initialized = true;
            Log.i(TAG, String.format(
                Locale.ROOT,
                "PdfBox initialized in %.3f ms",
                initializationDurationNanos / 1_000_000D
            ));
        }
    }

    private void writeDirect(List<ExportImageDescriptor> images, File tempFile, File workDir,
                             boolean useOriginal, float compressionRatio,
                             ProgressListener listener, BaselineMetrics metrics) throws IOException {
        long directStartedAt = SystemClock.elapsedRealtimeNanos();
        try (PDDocument document = new PDDocument(createMemorySetting(workDir))) {
            for (int index = 0; index < images.size(); index++) {
                addPage(
                    document,
                    images.get(index),
                    workDir,
                    index,
                    useOriginal,
                    compressionRatio,
                    metrics
                );
                listener.onPageWritten(index + 1);
            }
            listener.onFinalizing();
            long saveStartedAt = SystemClock.elapsedRealtimeNanos();
            document.save(tempFile);
            metrics.saveNanos += SystemClock.elapsedRealtimeNanos() - saveStartedAt;
            metrics.recordWorkDirPeak(workDir);
        }
        metrics.directWriteNanos += SystemClock.elapsedRealtimeNanos() - directStartedAt;
    }

    private List<File> writeChunks(List<ExportImageDescriptor> images, File workDir, boolean useOriginal,
                                   float compressionRatio, int chunkPages,
                                   ProgressListener listener, BaselineMetrics metrics) throws IOException {
        List<File> chunks = new ArrayList<>((images.size() + chunkPages - 1) / chunkPages);
        for (int start = 0; start < images.size(); start += chunkPages) {
            int end = Math.min(start + chunkPages, images.size());
            File chunk = new File(workDir,
                String.format(Locale.ROOT, "chunk-%05d.pdf", start / chunkPages));
            MemoryUsageSetting memory = createMemorySetting(workDir);
            long chunkStartedAt = SystemClock.elapsedRealtimeNanos();
            try (PDDocument document = new PDDocument(memory)) {
                for (int index = start; index < end; index++) {
                    addPage(
                        document,
                        images.get(index),
                        workDir,
                        index,
                        useOriginal,
                        compressionRatio,
                        metrics
                    );
                    listener.onPageWritten(index + 1);
                }
                long saveStartedAt = SystemClock.elapsedRealtimeNanos();
                document.save(chunk);
                metrics.saveNanos += SystemClock.elapsedRealtimeNanos() - saveStartedAt;
                metrics.recordWorkDirPeak(workDir);
            }
            chunks.add(chunk);
            metrics.chunkCount++;
            metrics.chunkBytes = saturatingAdd(metrics.chunkBytes, chunk.length());
            metrics.chunkNanos += SystemClock.elapsedRealtimeNanos() - chunkStartedAt;
            metrics.recordWorkDirPeak(workDir);
        }
        return chunks;
    }

    private void mergeChunks(List<File> chunks, File tempFile, File workDir, MergeMode mergeMode)
            throws IOException {
        if (chunks.size() == 1) {
            replaceAtomically(chunks.get(0), tempFile);
            return;
        }

        PDFMergerUtility merger = new PDFMergerUtility();
        merger.setDestinationFileName(tempFile.getAbsolutePath());
        merger.setDocumentMergeMode(mergeMode.toPdfBoxMode());
        for (File chunk : chunks) {
            merger.addSource(chunk);
        }
        merger.mergeDocuments(createMemorySetting(workDir));
    }

    private void addPage(PDDocument document, ExportImageDescriptor descriptor, File workDir, int pageIndex,
                         boolean useOriginal, float compressionRatio,
                         BaselineMetrics metrics) throws IOException {
        PdfPageImage pageImage = useOriginal
            ? createOriginalPageImage(document, descriptor, metrics)
            : createCompressedPageImage(document, descriptor, workDir, pageIndex, compressionRatio, metrics);

        long pageStartedAt = SystemClock.elapsedRealtimeNanos();
        PDPage page = new PDPage(new PDRectangle(pageImage.width, pageImage.height));
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.drawImage(pageImage.image, 0, 0, pageImage.width, pageImage.height);
        }
        metrics.addPageNanos += SystemClock.elapsedRealtimeNanos() - pageStartedAt;
        metrics.pagesWritten++;
    }

    private PdfPageImage createOriginalPageImage(PDDocument document, ExportImageDescriptor descriptor,
                                                BaselineMetrics metrics) throws IOException {
        File source = descriptor.file;
        if (descriptor.directJpeg) {
            long createStartedAt = SystemClock.elapsedRealtimeNanos();
            try (FileInputStream stream = new FileInputStream(source)) {
                PDImageXObject pdfImage = JPEGFactory.createFromStream(document, stream);
                metrics.createImageNanos += SystemClock.elapsedRealtimeNanos() - createStartedAt;
                metrics.originalDirectJpegCount++;
                return new PdfPageImage(pdfImage, descriptor.width, descriptor.height);
            }
        }

        long decodeStartedAt = SystemClock.elapsedRealtimeNanos();
        Bitmap bitmap = BitmapFactory.decodeFile(source.getAbsolutePath());
        metrics.decodeNanos += SystemClock.elapsedRealtimeNanos() - decodeStartedAt;
        if (bitmap == null) {
            throw new IOException("无法解码图片: " + source.getName());
        }

        Bitmap jpegBitmap = bitmap;
        try {
            if (bitmap.hasAlpha()) {
                long alphaStartedAt = SystemClock.elapsedRealtimeNanos();
                jpegBitmap = flattenAlphaOnWhite(bitmap);
                metrics.alphaFlattenCount++;
                metrics.alphaFlattenNanos += SystemClock.elapsedRealtimeNanos() - alphaStartedAt;
            } else {
                jpegBitmap.setHasAlpha(false);
            }

            ByteArrayOutputStream jpegBytes = new ByteArrayOutputStream(
                initialJpegBufferSize(descriptor.fileBytes)
            );
            long encodeStartedAt = SystemClock.elapsedRealtimeNanos();
            if (!jpegBitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    ORIGINAL_JPEG_QUALITY,
                    jpegBytes
                )) {
                throw new IOException("无法转换图片格式: " + source.getName());
            }
            metrics.originalJpegEncodeNanos += SystemClock.elapsedRealtimeNanos() - encodeStartedAt;
            byte[] encoded = jpegBytes.toByteArray();
            metrics.originalTranscodedJpegBytes = saturatingAdd(
                metrics.originalTranscodedJpegBytes,
                encoded.length
            );

            long createStartedAt = SystemClock.elapsedRealtimeNanos();
            PDImageXObject pdfImage = JPEGFactory.createFromByteArray(document, encoded);
            metrics.createImageNanos += SystemClock.elapsedRealtimeNanos() - createStartedAt;
            metrics.originalTranscodedJpegCount++;
            return new PdfPageImage(pdfImage, descriptor.width, descriptor.height);
        } finally {
            if (jpegBitmap != bitmap && !jpegBitmap.isRecycled()) {
                jpegBitmap.recycle();
            }
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private PdfPageImage createCompressedPageImage(PDDocument document,
                                                   ExportImageDescriptor descriptor,
                                                   File workDir, int pageIndex,
                                                   float compressionRatio, BaselineMetrics metrics)
            throws IOException {
        File source = descriptor.file;
        long materializeStartedAt = SystemClock.elapsedRealtimeNanos();
        MaterializedImage materialized = materializeCompressedImage(
            source,
            workDir,
            pageIndex,
            compressionRatio
        );
        metrics.materializeNanos += SystemClock.elapsedRealtimeNanos() - materializeStartedAt;
        try {
            long createStartedAt = SystemClock.elapsedRealtimeNanos();
            PDImageXObject pdfImage = PDImageXObject.createFromFileByExtension(
                materialized.file,
                document
            );
            metrics.createImageNanos += SystemClock.elapsedRealtimeNanos() - createStartedAt;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            long boundsStartedAt = SystemClock.elapsedRealtimeNanos();
            BitmapFactory.decodeFile(materialized.file.getAbsolutePath(), bounds);
            metrics.boundsDecodeNanos += SystemClock.elapsedRealtimeNanos() - boundsStartedAt;
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw new IOException("无法读取图片尺寸: " + source.getName());
            }
            return new PdfPageImage(pdfImage, bounds.outWidth, bounds.outHeight);
        } finally {
            if (materialized.temporary && materialized.file.exists() && !materialized.file.delete()) {
                Log.w(TAG, "无法立即删除临时图片: " + materialized.file.getAbsolutePath());
            }
        }
    }

    private MaterializedImage materializeCompressedImage(File source, File workDir, int pageIndex,
                                                        float compressionRatio) throws IOException {
        Bitmap bitmap = BitmapFactory.decodeFile(source.getAbsolutePath());
        if (bitmap == null) {
            throw new IOException("无法解码图片: " + source.getName());
        }
        Bitmap scaled = bitmap;
        File output = new File(workDir,
            String.format(Locale.ROOT, "scaled-%08d.jpg", pageIndex));
        try {
            int width = Math.max(1, Math.round(bitmap.getWidth() * compressionRatio));
            int height = Math.max(1, Math.round(bitmap.getHeight() * compressionRatio));
            scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
            try (FileOutputStream stream = new FileOutputStream(output)) {
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                    throw new IOException("无法压缩图片: " + source.getName());
                }
            }
            return new MaterializedImage(output, true);
        } finally {
            if (scaled != bitmap && !scaled.isRecycled()) {
                scaled.recycle();
            }
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    static ExportImageDescriptor inspectImage(File source) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("无法读取图片尺寸: " + source.getName());
        }
        if (bounds.outMimeType == null || bounds.outMimeType.trim().isEmpty()) {
            throw new IOException("无法确认图片格式: " + source.getName());
        }

        String mimeType = bounds.outMimeType.toLowerCase(Locale.ROOT);
        if (!isSupportedOriginalMimeType(mimeType)) {
            throw new IOException("当前 PDF 导出不支持图片格式: " + source.getName());
        }
        return new ExportImageDescriptor(
            source,
            mimeType,
            bounds.outWidth,
            bounds.outHeight,
            source.length(),
            isJpegMimeType(mimeType)
        );
    }

    private static boolean isJpegMimeType(String mimeType) {
        return "image/jpeg".equals(mimeType) || "image/jpg".equals(mimeType);
    }

    private static boolean isSupportedOriginalMimeType(String mimeType) {
        return isJpegMimeType(mimeType)
            || "image/webp".equals(mimeType)
            || "image/png".equals(mimeType)
            || "image/gif".equals(mimeType)
            || "image/bmp".equals(mimeType)
            || "image/x-ms-bmp".equals(mimeType);
    }

    private static Bitmap flattenAlphaOnWhite(Bitmap bitmap) {
        Bitmap flattened = Bitmap.createBitmap(
            bitmap.getWidth(),
            bitmap.getHeight(),
            Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(flattened);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(bitmap, 0F, 0F, null);
        flattened.setHasAlpha(false);
        return flattened;
    }

    private static int initialJpegBufferSize(long sourceBytes) {
        return Math.max(4096, (int) Math.min(Integer.MAX_VALUE, sourceBytes));
    }

    private static MemoryUsageSetting createMemorySetting(File workDir) {
        return MemoryUsageSetting.setupTempFileOnly().setTempDir(workDir);
    }

    private static void ensureEnoughMergeSpace(File workDir, List<File> chunks,
                                               long chunkBytes) throws IOException {
        if (chunks.size() <= 1) {
            return;
        }
        long requiredBytes = saturatingAdd(chunkBytes, MERGE_SPACE_MARGIN_BYTES);
        long usableBytes = workDir.getUsableSpace();
        if (usableBytes <= 0L) {
            throw new IOException("无法确认 PDF 临时目录可用空间: " + workDir.getAbsolutePath());
        }
        if (usableBytes < requiredBytes) {
            throw new IOException("存储空间不足，预计至少需要 "
                + formatMegabytes(requiredBytes) + " MB 可用空间");
        }
    }

    private static void syncFile(File file) throws IOException {
        if (!file.isFile() || file.length() <= 0L) {
            throw new IOException("PDF 临时文件为空: " + file.getAbsolutePath());
        }
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw")) {
            randomAccessFile.getFD().sync();
        }
    }

    private static void replaceAtomically(File source, File target) throws IOException {
        try {
            Os.rename(source.getAbsolutePath(), target.getAbsolutePath());
        } catch (ErrnoException e) {
            throw new IOException("无法原子替换 PDF 文件: " + target.getAbsolutePath(), e);
        }
    }

    private static void deleteRecursively(File target) throws IOException {
        if (!target.exists()) {
            return;
        }
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children == null) {
                throw new IOException("无法读取临时目录: " + target.getAbsolutePath());
            }
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!target.delete() && target.exists()) {
            throw new IOException("无法删除临时文件: " + target.getAbsolutePath());
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

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000D);
    }

    private static long formatMegabytes(long bytes) {
        long megabyte = 1024L * 1024L;
        return Math.max(1L, ((bytes - 1L) / megabyte) + 1L);
    }

    interface ProgressListener {
        void onPageWritten(int currentPage);

        void onFinalizing();
    }

    enum MergeMode {
        OPTIMIZE_RESOURCES,
        LEGACY;

        PDFMergerUtility.DocumentMergeMode toPdfBoxMode() {
            if (this == OPTIMIZE_RESOURCES) {
                return PDFMergerUtility.DocumentMergeMode.OPTIMIZE_RESOURCES_MODE;
            }
            return PDFMergerUtility.DocumentMergeMode.PDFBOX_LEGACY_MODE;
        }
    }

    static final class WriteStrategy {
        final boolean direct;
        final int chunkPages;
        final MergeMode mergeMode;

        private WriteStrategy(boolean direct, int chunkPages, MergeMode mergeMode) {
            this.direct = direct;
            this.chunkPages = chunkPages;
            this.mergeMode = mergeMode == null ? MergeMode.OPTIMIZE_RESOURCES : mergeMode;
        }

        static WriteStrategy direct() {
            return new WriteStrategy(true, 0, MergeMode.OPTIMIZE_RESOURCES);
        }

        static WriteStrategy chunked(int chunkPages, MergeMode mergeMode) {
            if (chunkPages <= 0) {
                throw new IllegalArgumentException("chunkPages must be positive");
            }
            return new WriteStrategy(false, chunkPages, mergeMode);
        }

        String label() {
            if (direct) {
                return "direct";
            }
            return "chunked-" + chunkPages + "-" + mergeMode.name().toLowerCase(Locale.ROOT);
        }
    }

    static final class WriteReport {
        final boolean completed;
        final boolean useOriginal;
        final boolean direct;
        final int totalPages;
        final int pagesWritten;
        final int chunkCount;
        final int chunkPages;
        final MergeMode mergeMode;
        final int unsupportedOriginalFallbackCount;
        final int originalDirectJpegCount;
        final int originalTranscodedJpegCount;
        final int alphaFlattenCount;
        final long totalNanos;
        final long directWriteNanos;
        final long materializeNanos;
        final long unsupportedOriginalFallbackNanos;
        final long unsupportedOriginalFallbackBytes;
        final long decodeNanos;
        final long alphaFlattenNanos;
        final long originalJpegEncodeNanos;
        final long originalTranscodedJpegBytes;
        final long createImageNanos;
        final long boundsDecodeNanos;
        final long addPageNanos;
        final long saveNanos;
        final long chunkNanos;
        final long mergeNanos;
        final long fsyncNanos;
        final long replaceNanos;
        final long chunkBytes;
        final long workDirPeakBytes;
        final long tempBytes;
        final long finalBytes;
        final long javaHeapStartBytes;
        final long javaHeapEndBytes;
        final long nativePssStartBytes;
        final long nativePssEndBytes;

        private WriteReport(BaselineMetrics metrics) {
            this.completed = metrics.completed;
            this.useOriginal = metrics.useOriginal;
            this.direct = metrics.strategy.direct;
            this.totalPages = metrics.totalPages;
            this.pagesWritten = metrics.pagesWritten;
            this.chunkCount = metrics.chunkCount;
            this.chunkPages = metrics.strategy.chunkPages;
            this.mergeMode = metrics.strategy.mergeMode;
            this.unsupportedOriginalFallbackCount = metrics.unsupportedOriginalFallbackCount;
            this.originalDirectJpegCount = metrics.originalDirectJpegCount;
            this.originalTranscodedJpegCount = metrics.originalTranscodedJpegCount;
            this.alphaFlattenCount = metrics.alphaFlattenCount;
            this.totalNanos = metrics.finishedAtNanos - metrics.startedAtNanos;
            this.directWriteNanos = metrics.directWriteNanos;
            this.materializeNanos = metrics.materializeNanos;
            this.unsupportedOriginalFallbackNanos = metrics.unsupportedOriginalFallbackNanos;
            this.unsupportedOriginalFallbackBytes = metrics.unsupportedOriginalFallbackBytes;
            this.decodeNanos = metrics.decodeNanos;
            this.alphaFlattenNanos = metrics.alphaFlattenNanos;
            this.originalJpegEncodeNanos = metrics.originalJpegEncodeNanos;
            this.originalTranscodedJpegBytes = metrics.originalTranscodedJpegBytes;
            this.createImageNanos = metrics.createImageNanos;
            this.boundsDecodeNanos = metrics.boundsDecodeNanos;
            this.addPageNanos = metrics.addPageNanos;
            this.saveNanos = metrics.saveNanos;
            this.chunkNanos = metrics.chunkNanos;
            this.mergeNanos = metrics.mergeNanos;
            this.fsyncNanos = metrics.fsyncNanos;
            this.replaceNanos = metrics.replaceNanos;
            this.chunkBytes = metrics.chunkBytes;
            this.workDirPeakBytes = metrics.workDirPeakBytes;
            this.tempBytes = metrics.tempBytes;
            this.finalBytes = metrics.finalBytes;
            this.javaHeapStartBytes = metrics.startJavaHeapBytes;
            this.javaHeapEndBytes = metrics.endJavaHeapBytes;
            this.nativePssStartBytes = metrics.startNativePssBytes;
            this.nativePssEndBytes = metrics.endNativePssBytes;
        }
    }

    private static final class MaterializedImage {
        final File file;
        final boolean temporary;

        MaterializedImage(File file, boolean temporary) {
            this.file = file;
            this.temporary = temporary;
        }
    }

    static final class ExportImageDescriptor {
        final File file;
        final String mimeType;
        final int width;
        final int height;
        final long fileBytes;
        final boolean directJpeg;

        ExportImageDescriptor(File file, String mimeType, int width, int height, long fileBytes,
                              boolean directJpeg) {
            this.file = file;
            this.mimeType = mimeType;
            this.width = width;
            this.height = height;
            this.fileBytes = fileBytes;
            this.directJpeg = directJpeg;
        }
    }

    private static final class PdfPageImage {
        final PDImageXObject image;
        final int width;
        final int height;

        PdfPageImage(PDImageXObject image, int width, int height) {
            this.image = image;
            this.width = width;
            this.height = height;
        }
    }

    private static final class BaselineMetrics {
        final long startedAtNanos = SystemClock.elapsedRealtimeNanos();
        final long startJavaHeapBytes = javaHeapBytes();
        final long startNativePssBytes = nativePssBytes();
        final int totalPages;
        final boolean useOriginal;
        final WriteStrategy strategy;

        int pagesWritten;
        int chunkCount;
        int unsupportedOriginalFallbackCount;
        int originalDirectJpegCount;
        int originalTranscodedJpegCount;
        int alphaFlattenCount;
        long materializeNanos;
        long unsupportedOriginalFallbackNanos;
        long unsupportedOriginalFallbackBytes;
        long decodeNanos;
        long alphaFlattenNanos;
        long originalJpegEncodeNanos;
        long originalTranscodedJpegBytes;
        long createImageNanos;
        long boundsDecodeNanos;
        long addPageNanos;
        long saveNanos;
        long directWriteNanos;
        long chunkNanos;
        long mergeNanos;
        long fsyncNanos;
        long replaceNanos;
        long chunkBytes;
        long workDirPeakBytes;
        long tempBytes;
        long finalBytes;
        long endJavaHeapBytes;
        long endNativePssBytes;
        long finishedAtNanos;
        boolean completed;

        BaselineMetrics(int totalPages, boolean useOriginal, WriteStrategy strategy) {
            this.totalPages = totalPages;
            this.useOriginal = useOriginal;
            this.strategy = strategy;
        }

        void recordWorkDirPeak(File workDir) {
            workDirPeakBytes = Math.max(workDirPeakBytes, directorySize(workDir));
        }

        void finish(boolean completed) {
            this.completed = completed;
            finishedAtNanos = SystemClock.elapsedRealtimeNanos();
            endJavaHeapBytes = javaHeapBytes();
            endNativePssBytes = nativePssBytes();
        }

        String toLogMessage(File finalFile) {
            return "PDF writer baseline: completed=" + completed
                + ", file=" + finalFile.getName()
                + ", useOriginal=" + useOriginal
                + ", strategy=" + strategy.label()
                + ", direct=" + strategy.direct
                + ", chunkPages=" + strategy.chunkPages
                + ", mergeMode=" + strategy.mergeMode
                + ", pages=" + pagesWritten + "/" + totalPages
                + ", chunks=" + chunkCount
                + ", totalMs=" + millis(finishedAtNanos - startedAtNanos)
                + ", directWriteMs=" + millis(directWriteNanos)
                + ", materializeMs=" + millis(materializeNanos)
                + ", unsupportedOriginalFallbackCount=" + unsupportedOriginalFallbackCount
                + ", unsupportedOriginalFallbackMs=" + millis(unsupportedOriginalFallbackNanos)
                + ", unsupportedOriginalFallbackBytes=" + unsupportedOriginalFallbackBytes
                + ", originalDirectJpegCount=" + originalDirectJpegCount
                + ", originalTranscodedJpegCount=" + originalTranscodedJpegCount
                + ", originalTranscodedJpegBytes=" + originalTranscodedJpegBytes
                + ", decodeMs=" + millis(decodeNanos)
                + ", alphaFlattenCount=" + alphaFlattenCount
                + ", alphaFlattenMs=" + millis(alphaFlattenNanos)
                + ", originalJpegEncodeMs=" + millis(originalJpegEncodeNanos)
                + ", createImageMs=" + millis(createImageNanos)
                + ", boundsDecodeMs=" + millis(boundsDecodeNanos)
                + ", addPageMs=" + millis(addPageNanos)
                + ", saveMs=" + millis(saveNanos)
                + ", chunkMs=" + millis(chunkNanos)
                + ", mergeMs=" + millis(mergeNanos)
                + ", fsyncMs=" + millis(fsyncNanos)
                + ", replaceMs=" + millis(replaceNanos)
                + ", chunkBytes=" + chunkBytes
                + ", workDirPeakBytes=" + workDirPeakBytes
                + ", tempBytes=" + tempBytes
                + ", finalBytes=" + finalBytes
                + ", javaHeapStartBytes=" + startJavaHeapBytes
                + ", javaHeapEndBytes=" + endJavaHeapBytes
                + ", nativePssStartBytes=" + startNativePssBytes
                + ", nativePssEndBytes=" + endNativePssBytes;
        }

        WriteReport toReport() {
            return new WriteReport(this);
        }
    }
}
