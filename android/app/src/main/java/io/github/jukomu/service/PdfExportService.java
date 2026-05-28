package io.github.jukomu.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;
import android.os.Environment;
import android.util.Log;
import io.github.jukomu.data.FileStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PDF 导出服务（单例）。
 * <p>
 * 使用 Android 内置 {@link PdfDocument} API 生成 PDF。
 * 后台线程串行执行，通过系统通知报告进度。
 */
public class PdfExportService {

    private static final String TAG = "PdfExportService";
    private static final int NOTIFY_ID_BASE = 2000;

    private static PdfExportService instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pdf-export");
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });

    private final AtomicInteger batchCounter = new AtomicInteger(0);

    private PdfExportNotificationHelper notificationHelper;

    private PdfExportService(Context context) {
        this.notificationHelper = new PdfExportNotificationHelper(context.getApplicationContext());
    }

    public static synchronized PdfExportService getInstance(Context context) {
        if (instance == null) {
            instance = new PdfExportService(context.getApplicationContext());
        }
        return instance;
    }

    // ---- 导出任务数据结构 ----

    public static class ExportJob {
        public String albumId;
        public String chapterId;
        public String chapterTitle;
        public String savePath;
        public boolean useOriginal;
        public float compressionRatio; // 0.1~1.0
        public int splitPages;         // 0=不分卷, >0=每卷页数
    }

    // ---- 提交任务 ----

    public void submitExport(List<ExportJob> jobs) {
        final int batchId = batchCounter.incrementAndGet();
        executor.submit(() -> executeBatch(batchId, jobs));
    }

    // ---- 批量执行 ----

    private void executeBatch(int batchId, List<ExportJob> jobs) {
        int success = 0;
        int fail = 0;

        for (int i = 0; i < jobs.size(); i++) {
            ExportJob job = jobs.get(i);
            int notificationId = NOTIFY_ID_BASE + batchId * 1000 + i;
            notificationHelper.assignNotificationId(notificationId);

            notificationHelper.showPreparing(job.chapterTitle);

            try {
                exportSingle(job);
                success++;
            } catch (Exception e) {
                fail++;
                Log.e(TAG, "PDF export failed: " + job.chapterTitle, e);
                notificationHelper.showError(job.chapterTitle,
                    e.getMessage() != null ? e.getMessage() : "未知错误");
            } catch (Throwable t) {
                fail++;
                Log.e(TAG, "PDF export crashed: " + job.chapterTitle, t);
                notificationHelper.showError(job.chapterTitle, "内部错误: " + t.getClass().getSimpleName());
            }
        }

        Log.i(TAG, "Batch " + batchId + " done: " + success + " success, " + fail + " fail");
    }

    // ---- 单章节导出 ----

    private void exportSingle(ExportJob job) throws IOException {
        FileStore fileStore = FileStore.getInstance();

        // 解析基础路径
        String basePath = job.savePath;
        String baseWithoutExt = basePath.endsWith(".pdf")
            ? basePath.substring(0, basePath.length() - 4) : basePath;

        File pdfFile = resolveAbsolutePath(basePath);
        File parentDir = pdfFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new IOException("无法创建目录: " + parentDir.getAbsolutePath());
            }
        }

        File[] imageFiles = fileStore.listImageFiles(job.albumId, job.chapterId);
        if (imageFiles == null || imageFiles.length == 0) {
            throw new IOException("未找到章节图片文件");
        }

        java.util.Arrays.sort(imageFiles, (a, b) -> a.getName().compareTo(b.getName()));

        int total = imageFiles.length;
        Log.i(TAG, "Exporting PDF: " + job.chapterTitle + " (" + total + " pages)");

        int pagesPerVolume = job.splitPages > 0 ? job.splitPages : Integer.MAX_VALUE;
        int volumeCount = (total + pagesPerVolume - 1) / pagesPerVolume;
        if (pagesPerVolume > total) volumeCount = 1;
        String volumeTitle = volumeCount > 1
            ? job.chapterTitle + " (卷" + volumeCount + "/" + volumeCount + ")"
            : job.chapterTitle;

        for (int vol = 0; vol < volumeCount; vol++) {
            int volStart = vol * pagesPerVolume;
            int volEnd = Math.min(volStart + pagesPerVolume, total);
            int volPages = volEnd - volStart;

            File volFile;
            if (volumeCount > 1) {
                String volName = String.format("%s_%03d-%03d.pdf",
                    baseWithoutExt, volStart + 1, volEnd);
                volFile = resolveAbsolutePath(volName);
            } else {
                volFile = pdfFile;
            }

            String volTitle = volumeCount > 1
                ? job.chapterTitle + " (" + (volStart + 1) + "-" + volEnd + ")"
                : job.chapterTitle;

            Log.i(TAG, "Volume " + (vol + 1) + "/" + volumeCount + ": " + volFile.getName());

            // 每卷用独立 notificationId，避免覆盖，用户可独立点击打开各卷 PDF
            int volNotifyId = notificationHelper.getBaseNotificationId() + vol;
            notificationHelper.assignNotificationId(volNotifyId);

            writeSingleVolume(job, imageFiles, volStart, volEnd, volTitle, volFile, total);
        }
    }

    private void writeSingleVolume(ExportJob job, File[] imageFiles,
            int start, int end, String volTitle, File volFile, int chapterTotal) throws IOException {

        PdfDocument document = new PdfDocument();
        try {
            for (int i = start; i < end; i++) {
                // 每 10 页刷新一次进度文字，避免高频 notify 被限流
                if (i == start || i == end - 1 || (i - start) % 10 == 0) {
                    notificationHelper.showProgress(job.chapterTitle, i + 1, chapterTotal);
                }

                byte[] imageBytes = readFileBytes(imageFiles[i]);
                if (imageBytes == null || imageBytes.length == 0) {
                    Log.w(TAG, "跳过空文件: " + imageFiles[i].getName());
                    continue;
                }

                Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                imageBytes = null;

                if (bitmap == null) {
                    Log.w(TAG, "无法解码图片: " + imageFiles[i].getName());
                    continue;
                }

                Bitmap drawBitmap = bitmap;
                try {
                    if (!job.useOriginal && job.compressionRatio > 0f && job.compressionRatio < 1f) {
                        int newW = Math.max(1, Math.round(bitmap.getWidth() * job.compressionRatio));
                        int newH = Math.max(1, Math.round(bitmap.getHeight() * job.compressionRatio));
                        drawBitmap = Bitmap.createScaledBitmap(bitmap, newW, newH, true);
                        if (drawBitmap != bitmap) {
                            bitmap.recycle();
                        }
                    }

                    PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                        drawBitmap.getWidth(), drawBitmap.getHeight(), (i - start) + 1).create();
                    PdfDocument.Page page = document.startPage(pageInfo);
                    Canvas canvas = page.getCanvas();
                    canvas.drawBitmap(drawBitmap, 0, 0, null);
                    document.finishPage(page);
                } finally {
                    if (drawBitmap != null) {
                        drawBitmap.recycle();
                    }
                    if (bitmap != null && bitmap != drawBitmap && !bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                }
            }

            notificationHelper.showWriting(volTitle);
            // 确保"正在写入"通知已被系统接收，不被后续写盘操作阻塞
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}

            FileOutputStream fos = new FileOutputStream(volFile);
            try {
                document.writeTo(fos);
            } finally {
                fos.close();
            }
            Log.i(TAG, "PDF saved: " + volFile.getAbsolutePath() + " (" + volFile.length() + " bytes)");

            notificationHelper.showComplete(volTitle, volFile.getName(), volFile.getAbsolutePath());

        } finally {
            document.close();
        }
    }

    // ---- 工具方法 ----

    private File resolveAbsolutePath(String path) {
        if (path.startsWith("/")) {
            return new File(path);
        }
        File externalRoot = Environment.getExternalStorageDirectory();
        return new File(externalRoot, path);
    }

    private static byte[] readFileBytes(File file) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        try {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) {
                bos.write(buf, 0, len);
            }
            return bos.toByteArray();
        } finally {
            fis.close();
        }
    }
}
