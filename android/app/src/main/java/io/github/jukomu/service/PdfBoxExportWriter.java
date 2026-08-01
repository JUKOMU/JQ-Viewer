package io.github.jukomu.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.File;
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
    private static final Object INITIALIZATION_LOCK = new Object();

    private static volatile boolean initialized;
    private static volatile long initializationDurationNanos = -1L;

    private final Context context;

    PdfBoxExportWriter(Context context) {
        this.context = context.getApplicationContext();
    }

    void writeVolume(List<File> imageFiles, File finalFile, boolean useOriginal,
                     float compressionRatio, ProgressListener listener) throws IOException {
        if (imageFiles.isEmpty()) {
            throw new IOException("没有可写入 PDF 的图片");
        }

        ensureInitialized();
        cleanStaleArtifacts(finalFile);

        File tempFile = getTempFile(finalFile);
        File workDir = getWorkDirectory(finalFile);
        if (!workDir.mkdirs()) {
            throw new IOException("无法创建 PDF 临时目录: " + workDir.getAbsolutePath());
        }

        boolean completed = false;
        Throwable failure = null;
        try {
            List<File> chunks = writeChunks(
                imageFiles,
                workDir,
                useOriginal,
                compressionRatio,
                listener
            );
            listener.onFinalizing();
            mergeChunks(chunks, tempFile, workDir);
            syncFile(tempFile);
            replaceAtomically(tempFile, finalFile);
            completed = true;
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
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

    private List<File> writeChunks(List<File> imageFiles, File workDir, boolean useOriginal,
                                   float compressionRatio, ProgressListener listener) throws IOException {
        List<File> chunks = new ArrayList<>((imageFiles.size() + CHUNK_PAGES - 1) / CHUNK_PAGES);
        for (int start = 0; start < imageFiles.size(); start += CHUNK_PAGES) {
            int end = Math.min(start + CHUNK_PAGES, imageFiles.size());
            File chunk = new File(workDir,
                String.format(Locale.ROOT, "chunk-%05d.pdf", start / CHUNK_PAGES));
            MemoryUsageSetting memory = createMemorySetting(workDir);
            try (PDDocument document = new PDDocument(memory)) {
                for (int index = start; index < end; index++) {
                    addPage(
                        document,
                        imageFiles.get(index),
                        workDir,
                        index,
                        useOriginal,
                        compressionRatio
                    );
                    listener.onPageWritten(index + 1);
                }
                document.save(chunk);
            }
            chunks.add(chunk);
        }
        return chunks;
    }

    private void mergeChunks(List<File> chunks, File tempFile, File workDir) throws IOException {
        if (chunks.size() == 1) {
            replaceAtomically(chunks.get(0), tempFile);
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

    private void addPage(PDDocument document, File source, File workDir, int pageIndex,
                         boolean useOriginal, float compressionRatio) throws IOException {
        MaterializedImage materialized = materializeImage(
            source,
            workDir,
            pageIndex,
            useOriginal,
            compressionRatio
        );
        try {
            PDImageXObject pdfImage;
            try {
                pdfImage = PDImageXObject.createFromFileByExtension(materialized.file, document);
            } catch (IllegalArgumentException error) {
                if (materialized.temporary) {
                    throw error;
                }
                materialized = materializeUnsupportedOriginal(source, workDir, pageIndex);
                pdfImage = PDImageXObject.createFromFileByExtension(materialized.file, document);
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(materialized.file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw new IOException("无法读取图片尺寸: " + source.getName());
            }

            PDPage page = new PDPage(new PDRectangle(bounds.outWidth, bounds.outHeight));
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(pdfImage, 0, 0, bounds.outWidth, bounds.outHeight);
            }
        } finally {
            if (materialized.temporary && materialized.file.exists() && !materialized.file.delete()) {
                Log.w(TAG, "无法立即删除临时图片: " + materialized.file.getAbsolutePath());
            }
        }
    }

    private MaterializedImage materializeImage(File source, File workDir, int pageIndex,
                                               boolean useOriginal, float compressionRatio) throws IOException {
        if (useOriginal) {
            return new MaterializedImage(source, false);
        }

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

    private MaterializedImage materializeUnsupportedOriginal(File source, File workDir,
                                                             int pageIndex) throws IOException {
        Bitmap bitmap = BitmapFactory.decodeFile(source.getAbsolutePath());
        if (bitmap == null) {
            throw new IOException("当前 PDF 引擎不支持图片格式: " + source.getName());
        }
        File output = new File(workDir,
            String.format(Locale.ROOT, "converted-%08d.png", pageIndex));
        try (FileOutputStream stream = new FileOutputStream(output)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw new IOException("无法转换图片格式: " + source.getName());
            }
        } finally {
            bitmap.recycle();
        }
        return new MaterializedImage(output, true);
    }

    private static MemoryUsageSetting createMemorySetting(File workDir) {
        return MemoryUsageSetting.setupTempFileOnly().setTempDir(workDir);
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

    interface ProgressListener {
        void onPageWritten(int currentPage);

        void onFinalizing();
    }

    private static final class MaterializedImage {
        final File file;
        final boolean temporary;

        MaterializedImage(File file, boolean temporary) {
            this.file = file;
            this.temporary = temporary;
        }
    }
}
