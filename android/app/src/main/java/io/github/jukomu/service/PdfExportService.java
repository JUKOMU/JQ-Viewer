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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PDF 导出服务（单例）。
 * <p>
 * 使用 Android 内置 {@link PdfDocument} API 生成 PDF。
 * 后台线程串行执行，通过系统通知报告进度。
 * notificationId 全程显式传递，无共享可变状态，线程安全。
 */
public class PdfExportService {

    private static final String TAG = "PdfExportService";
    private static final int NOTIFY_ID_BASE = 2000;
    private static final long FREE_SPACE_MARGIN_BYTES = 16L * 1024L * 1024L;

    private static PdfExportService instance;
    private final Context context;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pdf-export");
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });

    private final AtomicInteger batchCounter = new AtomicInteger(0);
    private final Object activeJobsLock = new Object();
    private final Set<String> activeTaskKeys = new HashSet<>();
    private final Set<String> activeChapterKeys = new HashSet<>();

    private PdfExportNotificationHelper notif;

    private PdfExportService(Context context) {
        this.context = context.getApplicationContext();
        this.notif = new PdfExportNotificationHelper(this.context);
    }

    public static synchronized PdfExportService getInstance(Context context) {
        if (instance == null) {
            instance = new PdfExportService(context.getApplicationContext());
        }
        return instance;
    }

    // ---- 导出任务数据结构 ----

    public static class ExportJob {
        public String mode;
        public String albumId;
        public String chapterId;
        public String chapterTitle;
        public List<ExportChapter> chapters;
        public String savePath;
        public boolean useOriginal;
        public float compressionRatio; // 0.1~1.0
        public int splitPages;         // 0=不分卷, >0=每卷页数
    }

    public static class ExportChapter {
        public String albumId;
        public String chapterId;
        public String chapterTitle;
        public int sortOrder;
    }

    // ---- 提交任务 ----

    /** 提交批量导出任务。对每个 job 立即显示排队通知，冲突任务自动跳过。 */
    public void submitExport(List<ExportJob> jobs) {
        final int batchId = batchCounter.incrementAndGet();
        List<ExportJob> accepted = new ArrayList<>();

        for (int i = 0; i < jobs.size(); i++) {
            ExportJob job = jobs.get(i);
            PdfExportJobValidator.validate(job);
            if (!acquireJobLocks(job)) {
                continue;
            }
            accepted.add(job);

            int nid = NOTIFY_ID_BASE + batchId * 1000 + accepted.size() - 1;
            notif.showQueued(nid, job.chapterTitle);
        }

        if (!accepted.isEmpty()) {
            updateForegroundService();
            executor.submit(() -> executeBatch(batchId, accepted));
        }
    }

    // ---- 批量执行 ----

    private void executeBatch(int batchId, List<ExportJob> jobs) {
        int success = 0;
        int fail = 0;

        for (int i = 0; i < jobs.size(); i++) {
            ExportJob job = jobs.get(i);
            int nid = NOTIFY_ID_BASE + batchId * 1000 + i;

            try {
                try {
                    notif.showPreparing(nid, job.chapterTitle);
                    ExportPreflight preflight = preflight(job);
                    if ("merged".equals(job.mode)) {
                        throw new IOException("当前版本暂不支持合并 PDF 写入");
                    }
                    exportSingle(job, preflight, nid);
                    success++;
                } catch (Exception e) {
                    fail++;
                    ExportFailure failure = describeExportFailure(e, job);
                    Log.e(TAG, failure.debugMessage, e);
                    notif.showError(nid, job.chapterTitle, failure.userMessage);
                } catch (Throwable t) {
                    fail++;
                    Log.e(TAG, "PDF export crashed: " + job.chapterTitle, t);
                    notif.showError(nid, job.chapterTitle, "内部错误: " + t.getClass().getSimpleName());
                }
            } finally {
                releaseJobLocks(job);
                updateForegroundService();
            }
        }

        Log.i(TAG, "Batch " + batchId + " done: " + success + " success, " + fail + " fail");
    }

    // ---- 单章节导出 ----

    private void exportSingle(ExportJob job, ExportPreflight preflight, int baseNid) throws IOException {
        String basePath = job.savePath;
        String baseWithoutExt = basePath.endsWith(".pdf")
            ? basePath.substring(0, basePath.length() - 4) : basePath;

        File pdfFile = preflight.pdfFile;
        File[] imageFiles = preflight.chapters.get(0).imageFiles;

        int total = imageFiles.length;
        Log.i(TAG, "Exporting PDF: " + job.chapterTitle + " (" + total + " pages)");

        int pagesPerVolume = job.splitPages > 0 ? job.splitPages : Integer.MAX_VALUE;
        int volumeCount = (total + pagesPerVolume - 1) / pagesPerVolume;
        if (pagesPerVolume > total) volumeCount = 1;

        for (int vol = 0; vol < volumeCount; vol++) {
            int volStart = vol * pagesPerVolume;
            int volEnd = Math.min(volStart + pagesPerVolume, total);

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

            // 每卷独立 notificationId，互不覆盖
            int volNid = baseNid + vol;

            writeSingleVolume(job, imageFiles, volStart, volEnd, volTitle, volFile, total, volNid);
        }
    }

    private void writeSingleVolume(ExportJob job, File[] imageFiles,
            int start, int end, String volTitle, File volFile, int chapterTotal, int nid) throws IOException {

        PdfDocument document = new PdfDocument();
        try {
            for (int i = start; i < end; i++) {
                if (i == start || i == end - 1 || (i - start) % 10 == 0) {
                    notif.showProgress(nid, job.chapterTitle, i + 1, chapterTotal);
                }

                byte[] imageBytes = readFileBytes(imageFiles[i]);
                if (imageBytes == null || imageBytes.length == 0) {
                    throw new IOException("图片文件为空: " + imageFiles[i].getName());
                }

                Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                imageBytes = null;

                if (bitmap == null) {
                    throw new IOException("无法解码图片: " + imageFiles[i].getName());
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
                    if (drawBitmap != null) drawBitmap.recycle();
                    if (bitmap != null && bitmap != drawBitmap && !bitmap.isRecycled()) bitmap.recycle();
                }
            }

            notif.showWriting(nid, volTitle);
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}

            FileOutputStream fos = new FileOutputStream(volFile);
            try {
                document.writeTo(fos);
            } finally {
                fos.close();
            }
            Log.i(TAG, "PDF saved: " + volFile.getAbsolutePath() + " (" + volFile.length() + " bytes)");

            notif.showComplete(nid, volTitle, volFile.getName(), volFile.getAbsolutePath());

        } finally {
            document.close();
        }
    }

    // ---- 任务锁与预检 ----

    private boolean acquireJobLocks(ExportJob job) {
        String taskKey = PdfExportJobValidator.taskKey(job);
        List<String> chapterKeys = PdfExportJobValidator.chapterResourceKeys(job);
        synchronized (activeJobsLock) {
            if (activeTaskKeys.contains(taskKey)) return false;
            for (String chapterKey : chapterKeys) {
                if (activeChapterKeys.contains(chapterKey)) return false;
            }
            activeTaskKeys.add(taskKey);
            activeChapterKeys.addAll(chapterKeys);
            return true;
        }
    }

    private void releaseJobLocks(ExportJob job) {
        synchronized (activeJobsLock) {
            activeTaskKeys.remove(PdfExportJobValidator.taskKey(job));
            activeChapterKeys.removeAll(PdfExportJobValidator.chapterResourceKeys(job));
        }
    }

    private ExportPreflight preflight(ExportJob job) throws IOException {
        FileStore fileStore = FileStore.getInstance();
        List<ExportChapter> exportChapters = new ArrayList<>();
        if ("merged".equals(job.mode)) {
            exportChapters.addAll(job.chapters);
        } else {
            ExportChapter chapter = new ExportChapter();
            chapter.albumId = job.albumId;
            chapter.chapterId = job.chapterId;
            chapter.chapterTitle = job.chapterTitle;
            exportChapters.add(chapter);
        }

        List<ChapterPreflight> chapterResults = new ArrayList<>();
        long totalImageBytes = 0L;
        long totalPages = 0L;
        for (ExportChapter chapter : exportChapters) {
            File chapterDir = fileStore.getChapterDir(chapter.albumId, chapter.chapterId);
            String label = chapter.chapterTitle == null || chapter.chapterTitle.trim().isEmpty()
                ? chapter.chapterId : chapter.chapterTitle;
            if (!chapterDir.isDirectory()) {
                throw new IOException("章节“" + label + "”的下载目录不存在");
            }

            File[] imageFiles = fileStore.listImageFiles(chapter.albumId, chapter.chapterId);
            if (imageFiles == null || imageFiles.length == 0) {
                throw new IOException("章节“" + label + "”没有可导出的图片");
            }
            Arrays.sort(imageFiles, (a, b) -> a.getName().compareTo(b.getName()));
            for (File imageFile : imageFiles) {
                if (!imageFile.isFile()) {
                    throw new IOException("章节“" + label + "”包含无效图片项: " + imageFile.getName());
                }
                if (!imageFile.canRead()) {
                    throw new IOException("章节“" + label + "”的图片不可读: " + imageFile.getName());
                }
                long fileSize = imageFile.length();
                if (fileSize <= 0L) {
                    throw new IOException("章节“" + label + "”包含空图片: " + imageFile.getName());
                }
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(imageFile.getAbsolutePath(), bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw new IOException("章节“" + label + "”包含无法解码的图片: "
                        + imageFile.getName());
                }
                totalImageBytes = saturatingAdd(totalImageBytes, fileSize);
            }
            totalPages = saturatingAdd(totalPages, imageFiles.length);
            chapterResults.add(new ChapterPreflight(imageFiles));
        }

        File pdfFile = resolveAbsolutePath(job.savePath);
        File parentDir = pdfFile.getParentFile();
        if (parentDir == null) {
            throw new IOException("目标路径不可用: " + job.savePath);
        }
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("无法创建目录: " + parentDir.getAbsolutePath());
        }
        if (!parentDir.isDirectory()) {
            throw new IOException("目标路径中有一段不是目录: " + parentDir.getAbsolutePath());
        }
        if (!parentDir.canWrite()) {
            throw new IOException("目标目录不可写: " + parentDir.getAbsolutePath());
        }
        if (pdfFile.exists() && !pdfFile.isFile()) {
            throw new IOException("目标路径指向文件夹: " + pdfFile.getAbsolutePath());
        }
        if (pdfFile.isFile() && !pdfFile.canWrite()) {
            throw new IOException("目标文件不可写: " + pdfFile.getAbsolutePath());
        }

        File tempFile = new File(pdfFile.getAbsolutePath() + ".tmp");
        if (tempFile.exists() && !tempFile.isFile()) {
            throw new IOException("临时文件路径不可用: " + tempFile.getAbsolutePath());
        }
        if (tempFile.isFile() && !tempFile.canWrite()) {
            throw new IOException("临时文件不可写: " + tempFile.getAbsolutePath());
        }

        long requiredBytes = saturatingAdd(totalImageBytes, FREE_SPACE_MARGIN_BYTES);
        long reusableBytes = pdfFile.isFile() ? pdfFile.length() : 0L;
        long availableBytes = saturatingAdd(parentDir.getUsableSpace(), reusableBytes);
        if (parentDir.getUsableSpace() > 0L && availableBytes < requiredBytes) {
            throw new IOException("存储空间不足，预计至少需要 " + formatMegabytes(requiredBytes) + " MB 可用空间");
        }

        Log.i(TAG, "PDF preflight passed: mode=" + job.mode
            + ", chapters=" + chapterResults.size()
            + ", pages=" + totalPages
            + ", imageBytes=" + totalImageBytes
            + ", usableBytes=" + parentDir.getUsableSpace());
        return new ExportPreflight(pdfFile, chapterResults);
    }

    // ---- 工具方法 ----

    private File resolveAbsolutePath(String path) {
        if (path.startsWith("/")) return new File(path);
        return new File(Environment.getExternalStorageDirectory(), path);
    }

    private void updateForegroundService() {
        int activeCount;
        synchronized (activeJobsLock) {
            activeCount = activeTaskKeys.size();
        }
        PdfExportForegroundService.update(context, activeCount);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static long formatMegabytes(long bytes) {
        long megabyte = 1024L * 1024L;
        return Math.max(1L, ((bytes - 1L) / megabyte) + 1L);
    }

    private static ExportFailure describeExportFailure(Throwable error, ExportJob job) {
        String rawMessage = findErrorMessage(error);
        String normalized = rawMessage.toLowerCase(Locale.ROOT);
        String userMessage;

        if (containsAny(normalized, "enametoolong", "file name too long", "filename too long")) {
            userMessage = "文件名或路径过长，请缩短导出模板中的标题、作者或标签后重试";
        } else if (containsAny(normalized, "enospc", "no space left")) {
            userMessage = "存储空间不足，请清理空间后重试";
        } else if (containsAny(normalized, "eacces", "permission denied", "operation not permitted")) {
            userMessage = "没有写入权限，请更换导出目录或重新授权";
        } else if (containsAny(normalized, "erofs", "read-only file system", "read only file system")) {
            userMessage = "目标目录不可写，请更换导出目录";
        } else if (containsAny(normalized, "enotdir", "not a directory")) {
            userMessage = "目标路径中有一段不是目录，请检查导出路径";
        } else if (containsAny(normalized, "eisdir", "is a directory")) {
            userMessage = "目标路径指向文件夹，请检查导出文件名";
        } else if (containsAny(normalized, "enoent", "no such file", "无法创建目录")) {
            userMessage = "目标路径不可用，请重新选择导出目录";
        } else if (!rawMessage.isEmpty()) {
            userMessage = "导出失败：" + rawMessage;
        } else {
            userMessage = "导出失败：未知错误";
        }

        String path = job != null && job.savePath != null ? job.savePath : "";
        String title = job != null && job.chapterTitle != null ? job.chapterTitle : "";
        String debugMessage = "PDF export failed: " + title
            + (path.isEmpty() ? "" : ", path=" + path)
            + (rawMessage.isEmpty() ? "" : ", error=" + rawMessage);
        return new ExportFailure(userMessage, debugMessage);
    }

    private static String findErrorMessage(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && !message.trim().isEmpty()) {
                return message.trim();
            }
            current = current.getCause();
        }
        return "";
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private static final class ExportFailure {
        final String userMessage;
        final String debugMessage;

        ExportFailure(String userMessage, String debugMessage) {
            this.userMessage = userMessage;
            this.debugMessage = debugMessage;
        }
    }

    private static final class ChapterPreflight {
        final File[] imageFiles;

        ChapterPreflight(File[] imageFiles) {
            this.imageFiles = imageFiles;
        }
    }

    private static final class ExportPreflight {
        final File pdfFile;
        final List<ChapterPreflight> chapters;

        ExportPreflight(File pdfFile, List<ChapterPreflight> chapters) {
            this.pdfFile = pdfFile;
            this.chapters = chapters;
        }
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
