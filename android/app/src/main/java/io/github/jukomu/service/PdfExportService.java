package io.github.jukomu.service;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.util.Log;
import io.github.jukomu.data.FileStore;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PDF 导出服务（单例）。
 * <p>
 * 使用 PdfBox-Android 分块生成 PDF。
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
    private final AtomicInteger notificationCounter = new AtomicInteger(NOTIFY_ID_BASE);
    private final Object activeJobsLock = new Object();
    private final Set<String> activeTaskKeys = new HashSet<>();
    private final Set<String> activeChapterKeys = new HashSet<>();
    private final PdfBoxExportWriter writer;

    private final PdfExportNotificationHelper notif;

    private PdfExportService(Context context) {
        this.context = context.getApplicationContext();
        this.notif = new PdfExportNotificationHelper(this.context);
        this.writer = new PdfBoxExportWriter(this.context);
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

    /**
     * 提交批量导出任务。对每个 job 立即显示排队通知，冲突任务自动跳过。
     */
    public void submitExport(List<ExportJob> jobs) {
        final int batchId = batchCounter.incrementAndGet();
        List<QueuedExportJob> accepted = new ArrayList<>();

        for (ExportJob job : jobs) {
            PdfExportJobValidator.validate(job);
            if (!acquireJobLocks(job)) {
                continue;
            }

            int notificationId = notificationCounter.getAndIncrement();
            accepted.add(new QueuedExportJob(job, notificationId));
            notif.showQueued(notificationId, job.chapterTitle);
        }

        if (!accepted.isEmpty()) {
            updateForegroundService();
            executor.submit(() -> executeBatch(batchId, accepted));
        }
    }

    // ---- 批量执行 ----

    private void executeBatch(int batchId, List<QueuedExportJob> queuedJobs) {
        int success = 0;
        int fail = 0;

        for (QueuedExportJob queuedJob : queuedJobs) {
            ExportJob job = queuedJob.job;
            int notificationId = queuedJob.notificationId;

            try {
                try {
                    notif.showPreparing(notificationId, job.chapterTitle);
                    ExportPreflight preflight = preflight(job);
                    exportJob(job, preflight, notificationId);
                    success++;
                } catch (Exception e) {
                    fail++;
                    ExportFailure failure = describeExportFailure(e, job);
                    Log.e(TAG, failure.debugMessage, e);
                    notif.showError(notificationId, job.chapterTitle, failure.userMessage);
                } catch (Throwable t) {
                    fail++;
                    Log.e(TAG, "PDF export crashed: " + job.chapterTitle, t);
                    notif.showError(notificationId, job.chapterTitle,
                        "内部错误: " + t.getClass().getSimpleName());
                }
            } finally {
                releaseJobLocks(job);
                updateForegroundService();
            }
        }

        Log.i(TAG, "Batch " + batchId + " done: " + success + " success, " + fail + " fail");
    }

    // ---- PDF 导出 ----

    private void exportJob(ExportJob job, ExportPreflight preflight, int baseNotificationId)
        throws IOException {
        List<File> imageFiles = flattenImageFiles(preflight.chapters, preflight.totalPages);
        int total = imageFiles.size();
        Log.i(TAG, "Exporting PDF: " + job.chapterTitle + " (" + total + " pages)");

        List<ExportVolume> volumes = buildVolumes(preflight.pdfFile, total, job.splitPages);
        PdfBoxExportWriter.cleanStaleArtifacts(preflight.pdfFile);
        for (ExportVolume volume : volumes) {
            validateOutputFile(volume.file);
            PdfBoxExportWriter.cleanStaleArtifacts(volume.file);
        }

        for (int volumeIndex = 0; volumeIndex < volumes.size(); volumeIndex++) {
            ExportVolume volume = volumes.get(volumeIndex);
            String volumeTitle = volumes.size() > 1
                ? job.chapterTitle + " (" + (volume.start + 1) + "-" + volume.end + ")"
                : job.chapterTitle;
            int notificationId = baseNotificationId;
            Log.i(TAG, "Volume " + (volumeIndex + 1) + "/" + volumes.size()
                + ": " + volume.file.getName());

            List<File> volumeImages = imageFiles.subList(volume.start, volume.end);
            writer.writeVolume(
                volumeImages,
                volume.file,
                job.useOriginal,
                job.compressionRatio,
                new PdfBoxExportWriter.ProgressListener() {
                    @Override
                    public void onPageWritten(int currentPage) {
                        int taskPage = volume.start + currentPage;
                        if (taskPage == 1 || taskPage == total || (taskPage - 1) % 10 == 0) {
                            notif.showProgress(
                                notificationId,
                                job.chapterTitle,
                                taskPage,
                                total
                            );
                        }
                    }

                    @Override
                    public void onFinalizing() {
                        notif.showWriting(notificationId, volumeTitle);
                    }
                }
            );
            Log.i(TAG, "PDF saved: " + volume.file.getAbsolutePath()
                + " (" + volume.file.length() + " bytes)");
            notif.showComplete(
                notificationId,
                volumeTitle,
                volume.file.getName(),
                volume.file.getAbsolutePath()
            );
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

        long requiredBytes = saturatingAdd(
            saturatingAdd(totalImageBytes, totalImageBytes),
            FREE_SPACE_MARGIN_BYTES
        );
        long availableBytes = parentDir.getUsableSpace();
        if (parentDir.getUsableSpace() > 0L && availableBytes < requiredBytes) {
            throw new IOException("存储空间不足，预计至少需要 " + formatMegabytes(requiredBytes) + " MB 可用空间");
        }

        Log.i(TAG, "PDF preflight passed: mode=" + job.mode
            + ", chapters=" + chapterResults.size()
            + ", pages=" + totalPages
            + ", imageBytes=" + totalImageBytes
            + ", usableBytes=" + parentDir.getUsableSpace());
        if (totalPages > Integer.MAX_VALUE) {
            throw new IOException("导出页数过多，超出当前 PDF 引擎支持范围");
        }
        return new ExportPreflight(pdfFile, chapterResults, (int) totalPages);
    }

    // ---- 工具方法 ----

    static List<ExportVolume> buildVolumes(File pdfFile, int totalPages, int splitPages) {
        int pagesPerVolume = splitPages > 0 ? splitPages : totalPages;
        int volumeCount = (totalPages + pagesPerVolume - 1) / pagesPerVolume;
        List<ExportVolume> volumes = new ArrayList<>(volumeCount);
        String basePath = pdfFile.getAbsolutePath();
        String baseWithoutExtension = basePath.endsWith(".pdf")
            ? basePath.substring(0, basePath.length() - 4) : basePath;

        for (int index = 0; index < volumeCount; index++) {
            int start = index * pagesPerVolume;
            int end = Math.min(start + pagesPerVolume, totalPages);
            File volumeFile = volumeCount > 1
                ? new File(String.format(
                Locale.ROOT,
                "%s_%03d-%03d.pdf",
                baseWithoutExtension,
                start + 1,
                end
            ))
                : pdfFile;
            volumes.add(new ExportVolume(start, end, volumeFile));
        }
        return volumes;
    }

    private static List<File> flattenImageFiles(List<ChapterPreflight> chapters, int totalPages) {
        List<File> imageFiles = new ArrayList<>(totalPages);
        for (ChapterPreflight chapter : chapters) {
            imageFiles.addAll(Arrays.asList(chapter.imageFiles));
        }
        return imageFiles;
    }

    private static void validateOutputFile(File outputFile) throws IOException {
        if (outputFile.exists() && !outputFile.isFile()) {
            throw new IOException("目标路径指向文件夹: " + outputFile.getAbsolutePath());
        }
        if (outputFile.isFile() && !outputFile.canWrite()) {
            throw new IOException("目标文件不可写: " + outputFile.getAbsolutePath());
        }
    }

    private File resolveAbsolutePath(String path) {
        if (path.startsWith("/")) {
            return new File(path);
        }
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
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
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
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    static final class ExportVolume {
        final int start;
        final int end;
        final File file;

        ExportVolume(int start, int end, File file) {
            this.start = start;
            this.end = end;
            this.file = file;
        }
    }

    private static final class QueuedExportJob {
        final ExportJob job;
        final int notificationId;

        QueuedExportJob(ExportJob job, int notificationId) {
            this.job = job;
            this.notificationId = notificationId;
        }
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
        final int totalPages;

        ExportPreflight(File pdfFile, List<ChapterPreflight> chapters, int totalPages) {
            this.pdfFile = pdfFile;
            this.chapters = chapters;
            this.totalPages = totalPages;
        }
    }
}
