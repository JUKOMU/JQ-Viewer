package io.github.jukomu.service;

import android.content.Context;
import android.os.Environment;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import io.github.jukomu.data.FileStore;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PDF 导出服务（单例）。
 * <p>
 * 使用 PdfBox-Android 分块生成 PDF。
 * 后台线程串行执行，通过系统通知报告进度。
 * PDF 前台服务通知承载进行中状态，每任务 notificationId 只用于终态。
 */
public class PdfExportService {

    private static final String TAG = "PdfExportService";
    private static final long FREE_SPACE_MARGIN_BYTES = 16L * 1024L * 1024L;
    private static final long ORIGINAL_OUTPUT_ESTIMATE_NUMERATOR = 110L;
    private static final long OUTPUT_ESTIMATE_DENOMINATOR = 100L;
    private static final int PDF_HEARTBEAT_PAGE_INTERVAL = 25;

    private static PdfExportService instance;
    private final Context context;

    private final ExecutorService executor;

    private final AtomicInteger batchCounter = new AtomicInteger(0);
    private final AtomicInteger notificationCounter = new AtomicInteger(0);
    private final AtomicInteger foregroundSessionCounter = new AtomicInteger(0);
    private int foregroundRevision;
    private final Object activeJobsLock = new Object();
    private final Set<String> activeTaskKeys = new HashSet<>();
    private final Set<String> activeChapterKeys = new HashSet<>();
    private final PdfBoxExportWriter writer;
    private final ForegroundPublisher foregroundPublisher;
    private final WakeLockFactory wakeLockFactory;

    private final PdfExportNotificationHelper notif;

    private PdfExportService(Context context) {
        this(context, createExecutor(), PdfExportForegroundService::update, null);
    }

    PdfExportService(Context context, ExecutorService executor,
                     ForegroundPublisher foregroundPublisher, WakeLockFactory wakeLockFactory) {
        this.context = context.getApplicationContext();
        this.executor = executor;
        this.foregroundPublisher = foregroundPublisher;
        this.wakeLockFactory = wakeLockFactory == null ? this::createAndroidWakeLock : wakeLockFactory;
        this.notif = new PdfExportNotificationHelper(this.context);
        this.writer = new PdfBoxExportWriter(this.context);
    }

    private static ExecutorService createExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "pdf-export");
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        });
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
     * 提交批量导出任务。冲突任务自动跳过，排队状态由固定 PDF 前台通知展示。
     */
    public void submitExport(List<ExportJob> jobs) {
        final int batchId = batchCounter.incrementAndGet();
        List<QueuedExportJob> accepted = new ArrayList<>();
        AtomicBoolean workerStarted = new AtomicBoolean(false);

        try {
            for (ExportJob job : jobs) {
                PdfExportJobValidator.validate(job);
                if (!acquireJobLocks(job)) {
                    continue;
                }

                int notificationId = NotificationIds.pdfTask(notificationCounter.getAndIncrement());
                accepted.add(new QueuedExportJob(job, notificationId));
            }

            if (!accepted.isEmpty()) {
                updateForegroundQueued(accepted);
                executor.submit(() -> {
                    workerStarted.set(true);
                    executeBatch(batchId, accepted);
                });
            }
        } catch (RuntimeException | Error failure) {
            if (!workerStarted.get()) {
                rollbackAcceptedJobs(accepted, failure);
            }
            throw failure;
        }
    }

    // ---- 批量执行 ----

    private void executeBatch(int batchId, List<QueuedExportJob> queuedJobs) {
        int success = 0;
        int fail = 0;
        WakeLockHandle wakeLock = null;
        int releasedJobs = 0;

        try {
            wakeLock = acquirePdfWakeLock(batchId, queuedJobs.size());
            for (QueuedExportJob queuedJob : queuedJobs) {
                ExportJob job = queuedJob.job;
                int notificationId = queuedJob.notificationId;
                int sessionId = foregroundSessionCounter.incrementAndGet();

                try {
                    publishPdfForeground(sessionId, job, "准备导出", 0, 0, 0, 0);
                    ExportPreflight preflight = preflight(job);
                    publishPdfForeground(sessionId, job, "准备写入", 0, preflight.totalPages, 1,
                        preflight.volumes.size());
                    exportJob(job, preflight, notificationId, sessionId);
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
                } finally {
                    releaseJobLocksAndUpdate(job);
                    releasedJobs++;
                }
            }
        } finally {
            try {
                if (releasedJobs < queuedJobs.size()) {
                    releaseQueuedJobLocksAndUpdate(queuedJobs.subList(releasedJobs, queuedJobs.size()));
                }
            } finally {
                releasePdfWakeLock(wakeLock, batchId);
            }
        }

        Log.i(TAG, "Batch " + batchId + " done: " + success + " success, " + fail + " fail");
    }

    // ---- PDF 导出 ----

    private void exportJob(ExportJob job, ExportPreflight preflight, int baseNotificationId,
                           int sessionId)
        throws IOException {
        long exportStartedAt = SystemClock.elapsedRealtimeNanos();
        List<PdfBoxExportWriter.ExportImageDescriptor> images =
            flattenImageDescriptors(preflight.chapters, preflight.totalPages);
        int total = images.size();
        Log.i(TAG, "Exporting PDF: " + job.chapterTitle
            + " (" + total + " pages, imageBytes=" + preflight.totalImageBytes
            + ", requiredBytes=" + preflight.requiredBytes
            + ", preflightMs=" + formatMillis(preflight.preflightDurationNanos) + ")");

        List<ExportVolume> volumes = preflight.volumes;
        PdfBoxExportWriter.cleanStaleArtifacts(preflight.pdfFile);
        for (ExportVolume volume : volumes) {
            validateOutputFile(volume.file);
            PdfBoxExportWriter.cleanStaleArtifacts(volume.file);
        }

        long totalOutputBytes = 0L;
        for (int volumeIndex = 0; volumeIndex < volumes.size(); volumeIndex++) {
            ExportVolume volume = volumes.get(volumeIndex);
            final int volumeNumber = volumeIndex + 1;
            final int volumeCount = volumes.size();
            long volumeStartedAt = SystemClock.elapsedRealtimeNanos();
            Log.i(TAG, "Volume " + (volumeIndex + 1) + "/" + volumes.size()
                + ": " + volume.file.getName());

            List<PdfBoxExportWriter.ExportImageDescriptor> volumeImages =
                images.subList(volume.start, volume.end);
            ensureUsableSpace(
                volume.file.getParentFile(),
                estimateRequiredBytesForVolume(volumeImages, job.useOriginal)
            );
            writer.writeVolume(
                volumeImages,
                volume.file,
                job.useOriginal,
                job.compressionRatio,
                new PdfBoxExportWriter.ProgressListener() {
                    @Override
                    public void onPageWritten(int currentPage) {
                        int taskPage = volume.start + currentPage;
                        logPdfHeartbeat(job, sessionId, taskPage, total, volumeNumber, volumeCount);
                        publishPdfForeground(sessionId, job, "正在导出", taskPage, total,
                            volumeNumber, volumeCount);
                    }

                    @Override
                    public void onFinalizing() {
                        publishPdfForeground(sessionId, job, "写入文件", volume.end, total,
                            volumeNumber, volumeCount);
                    }
                }
            );
            Log.i(TAG, "PDF saved: " + volume.file.getAbsolutePath()
                + " (" + volume.file.length() + " bytes, volumeMs="
                + formatMillis(SystemClock.elapsedRealtimeNanos() - volumeStartedAt) + ")");
            totalOutputBytes = saturatingAdd(totalOutputBytes, volume.file.length());
        }
        ExportVolume firstVolume = volumes.get(0);
        String detail = volumes.size() > 1
            ? "共 " + volumes.size() + " 个分卷，位于同一目录"
            : null;
        notif.showComplete(
            baseNotificationId,
            job.chapterTitle,
            firstVolume.file.getName(),
            firstVolume.file.getAbsolutePath(),
            detail
        );
        Log.i(TAG, "PDF export baseline: title=" + job.chapterTitle
            + ", mode=" + job.mode
            + ", useOriginal=" + job.useOriginal
            + ", pages=" + total
            + ", volumes=" + volumes.size()
            + ", imageBytes=" + preflight.totalImageBytes
            + ", outputBytes=" + totalOutputBytes
            + ", outputInputRatio=" + formatRatio(totalOutputBytes, preflight.totalImageBytes)
            + ", totalMs=" + formatMillis(SystemClock.elapsedRealtimeNanos() - exportStartedAt));
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

    private void releaseJobLocksAndUpdate(ExportJob job) {
        synchronized (activeJobsLock) {
            releaseJobLocksLocked(job);
            publishForegroundSummaryLocked();
        }
    }

    private ExportPreflight preflight(ExportJob job) throws IOException {
        long preflightStartedAt = SystemClock.elapsedRealtimeNanos();
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
        long boundsDecodeNanos = 0L;
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
            List<PdfBoxExportWriter.ExportImageDescriptor> descriptors =
                new ArrayList<>(imageFiles.length);
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
                long boundsStartedAt = SystemClock.elapsedRealtimeNanos();
                PdfBoxExportWriter.ExportImageDescriptor descriptor =
                    PdfBoxExportWriter.inspectImage(imageFile);
                boundsDecodeNanos = saturatingAdd(
                    boundsDecodeNanos,
                    SystemClock.elapsedRealtimeNanos() - boundsStartedAt
                );
                descriptors.add(descriptor);
                totalImageBytes = saturatingAdd(totalImageBytes, fileSize);
            }
            totalPages = saturatingAdd(totalPages, imageFiles.length);
            chapterResults.add(new ChapterPreflight(descriptors));
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

        long preflightDurationNanos = SystemClock.elapsedRealtimeNanos() - preflightStartedAt;
        if (totalPages > Integer.MAX_VALUE) {
            throw new IOException("导出页数过多，超出当前 PDF 引擎支持范围");
        }
        int totalPageCount = (int) totalPages;
        List<PdfBoxExportWriter.ExportImageDescriptor> imageDescriptors =
            flattenImageDescriptors(chapterResults, totalPageCount);
        List<ExportVolume> volumes = buildVolumes(pdfFile, totalPageCount, job.splitPages);
        long requiredBytes = estimateRequiredBytesForExport(
            volumes,
            imageDescriptors,
            job.useOriginal
        );
        long availableBytes = parentDir.getUsableSpace();
        ensureUsableSpace(parentDir, requiredBytes);
        Log.i(TAG, "PDF preflight passed: mode=" + job.mode
            + ", chapters=" + chapterResults.size()
            + ", pages=" + totalPages
            + ", imageBytes=" + totalImageBytes
            + ", requiredBytes=" + requiredBytes
            + ", usableBytes=" + availableBytes
            + ", boundsDecodeMs=" + formatMillis(boundsDecodeNanos)
            + ", preflightMs=" + formatMillis(preflightDurationNanos));
        return new ExportPreflight(
            pdfFile,
            chapterResults,
            volumes,
            totalPageCount,
            totalImageBytes,
            requiredBytes,
            preflightDurationNanos
        );
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

    private static List<PdfBoxExportWriter.ExportImageDescriptor> flattenImageDescriptors(
        List<ChapterPreflight> chapters, int totalPages) {
        List<PdfBoxExportWriter.ExportImageDescriptor> imageFiles = new ArrayList<>(totalPages);
        for (ChapterPreflight chapter : chapters) {
            imageFiles.addAll(chapter.images);
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

    private void updateForegroundQueued(List<QueuedExportJob> queuedJobs) {
        synchronized (activeJobsLock) {
            int activeCount = activeTaskKeys.size();
            ExportJob firstJob = queuedJobs.get(0).job;
            publishForegroundLocked(
                0,
                activeCount,
                Math.max(0, activeCount - 1),
                firstJob.chapterTitle,
                "排队中",
                0,
                0,
                0,
                0
            );
        }
    }

    private void publishPdfForeground(int sessionId, ExportJob job, String phase, int currentPage,
                                      int totalPages, int volumeIndex, int totalVolumes) {
        synchronized (activeJobsLock) {
            int activeCount = activeTaskKeys.size();
            publishForegroundLocked(
                sessionId,
                activeCount,
                Math.max(0, activeCount - 1),
                job.chapterTitle,
                phase,
                currentPage,
                totalPages,
                volumeIndex,
                totalVolumes
            );
        }
    }

    int getActiveJobCount() {
        synchronized (activeJobsLock) {
            return activeTaskKeys.size();
        }
    }

    WakeLockHandle acquirePdfWakeLock(int batchId, int jobCount) {
        WakeLockHandle wakeLock = null;
        try {
            wakeLock = wakeLockFactory.create();
            if (wakeLock == null) {
                Log.w(TAG, "PDF wake lock unavailable: PowerManager service missing");
                return null;
            }
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            Log.i(TAG, "PDF wake lock acquired: batch=" + batchId + ", jobs=" + jobCount);
            return wakeLock;
        } catch (RuntimeException e) {
            releasePdfWakeLock(wakeLock, batchId);
            Log.w(TAG, "PDF wake lock acquire failed; export continues without wake lock", e);
            return null;
        }
    }

    private void releasePdfWakeLock(WakeLockHandle wakeLock, int batchId) {
        if (wakeLock == null) {
            return;
        }
        try {
            if (wakeLock.isHeld()) {
                wakeLock.release();
                Log.i(TAG, "PDF wake lock released: batch=" + batchId);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "PDF wake lock release failed", e);
        }
    }

    private WakeLockHandle createAndroidWakeLock() {
        Object service = context.getSystemService(Context.POWER_SERVICE);
        if (!(service instanceof PowerManager)) {
            return null;
        }
        PowerManager.WakeLock wakeLock = ((PowerManager) service).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            context.getPackageName() + ":pdf-export"
        );
        return new AndroidWakeLockHandle(wakeLock);
    }

    private void rollbackAcceptedJobs(List<QueuedExportJob> accepted, Throwable failure) {
        if (accepted.isEmpty()) {
            return;
        }
        try {
            releaseQueuedJobLocksAndUpdate(accepted);
        } catch (RuntimeException | Error rollbackFailure) {
            if (rollbackFailure != failure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

    private void releaseQueuedJobLocksAndUpdate(List<QueuedExportJob> queuedJobs) {
        synchronized (activeJobsLock) {
            for (QueuedExportJob queuedJob : queuedJobs) {
                releaseJobLocksLocked(queuedJob.job);
            }
            publishForegroundSummaryLocked();
        }
    }

    private void releaseJobLocksLocked(ExportJob job) {
        activeTaskKeys.remove(PdfExportJobValidator.taskKey(job));
        activeChapterKeys.removeAll(PdfExportJobValidator.chapterResourceKeys(job));
    }

    private void publishForegroundSummaryLocked() {
        int activeCount = activeTaskKeys.size();
        publishForegroundLocked(
            0,
            activeCount,
            activeCount,
            "PDF 导出",
            activeCount > 0 ? "排队中" : "已结束",
            0,
            0,
            0,
            0
        );
    }

    private void publishForegroundLocked(int sessionId, int activeCount, int queueRemaining,
                                         String title, String phase, int currentPage,
                                         int totalPages, int volumeIndex, int totalVolumes) {
        foregroundPublisher.publish(
            context,
            new PdfExportForegroundService.Snapshot(
                sessionId,
                ++foregroundRevision,
                activeCount,
                queueRemaining,
                title,
                phase,
                currentPage,
                totalPages,
                volumeIndex,
                totalVolumes
            )
        );
    }

    private static void logPdfHeartbeat(ExportJob job, int sessionId, int currentPage,
                                        int totalPages, int volumeNumber, int volumeCount) {
        if (currentPage == 1 || currentPage == totalPages
            || currentPage % PDF_HEARTBEAT_PAGE_INTERVAL == 0) {
            Log.i(TAG, "PDF export heartbeat: session=" + sessionId
                + ", title=" + job.chapterTitle
                + ", page=" + currentPage + "/" + totalPages
                + ", volume=" + volumeNumber + "/" + volumeCount);
        }
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    static long estimateRequiredBytesForExport(
        List<ExportVolume> volumes,
        List<PdfBoxExportWriter.ExportImageDescriptor> images,
        boolean useOriginal) {
        long requiredBytes = 0L;
        long retainedDeltaBefore = 0L;
        for (ExportVolume volume : volumes) {
            long estimatedFinalBytes = estimateFinalPdfBytes(
                sumImageBytes(images, volume.start, volume.end),
                useOriginal
            );
            long candidate = saturatingAddSigned(
                retainedDeltaBefore,
                estimatePeakWorkingBytes(estimatedFinalBytes)
            );
            requiredBytes = Math.max(requiredBytes, Math.max(0L, candidate));
            long existingFinalBytes = volume.file.isFile() ? volume.file.length() : 0L;
            retainedDeltaBefore = saturatingAddSigned(
                retainedDeltaBefore,
                saturatingSubtract(estimatedFinalBytes, existingFinalBytes)
            );
        }
        return saturatingAdd(requiredBytes, FREE_SPACE_MARGIN_BYTES);
    }

    static long estimateRequiredBytesForVolume(
        List<PdfBoxExportWriter.ExportImageDescriptor> images,
        boolean useOriginal) {
        return saturatingAdd(
            estimatePeakWorkingBytes(estimateFinalPdfBytes(sumImageBytes(images), useOriginal)),
            FREE_SPACE_MARGIN_BYTES
        );
    }

    private static long estimateFinalPdfBytes(long inputBytes, boolean useOriginal) {
        if (!useOriginal) {
            return inputBytes;
        }
        return saturatingDivideCeiling(
            saturatingMultiply(inputBytes, ORIGINAL_OUTPUT_ESTIMATE_NUMERATOR),
            OUTPUT_ESTIMATE_DENOMINATOR
        );
    }

    private static long estimatePeakWorkingBytes(long estimatedFinalBytes) {
        return saturatingAdd(estimatedFinalBytes, estimatedFinalBytes);
    }

    private static long sumImageBytes(List<PdfBoxExportWriter.ExportImageDescriptor> images) {
        return sumImageBytes(images, 0, images.size());
    }

    private static long sumImageBytes(List<PdfBoxExportWriter.ExportImageDescriptor> images,
                                      int start, int end) {
        long total = 0L;
        for (int index = start; index < end; index++) {
            total = saturatingAdd(total, images.get(index).fileBytes);
        }
        return total;
    }

    private static void ensureUsableSpace(File directory, long requiredBytes) throws IOException {
        long availableBytes = directory == null ? 0L : directory.getUsableSpace();
        if (availableBytes <= 0L) {
            throw new IOException("无法确认目标目录可用空间或可用空间为 0");
        }
        if (availableBytes < requiredBytes) {
            throw new IOException("存储空间不足，预计至少需要 " + formatMegabytes(requiredBytes) + " MB 可用空间");
        }
    }

    private static long saturatingMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static long saturatingDivideCeiling(long value, long divisor) {
        if (value <= 0L) {
            return 0L;
        }
        return ((value - 1L) / divisor) + 1L;
    }

    private static long saturatingSubtract(long left, long right) {
        if (right > 0L && left < Long.MIN_VALUE + right) {
            return Long.MIN_VALUE;
        }
        return left - right;
    }

    private static long saturatingAddSigned(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        if (right < 0L && left < Long.MIN_VALUE - right) {
            return Long.MIN_VALUE;
        }
        return left + right;
    }

    private static long formatMegabytes(long bytes) {
        long megabyte = 1024L * 1024L;
        return Math.max(1L, ((bytes - 1L) / megabyte) + 1L);
    }

    private static String formatMillis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000D);
    }

    private static String formatRatio(long outputBytes, long inputBytes) {
        if (inputBytes <= 0L) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.3f", outputBytes / (double) inputBytes);
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

    interface ForegroundPublisher {
        void publish(Context context, PdfExportForegroundService.Snapshot snapshot);
    }

    interface WakeLockFactory {
        WakeLockHandle create();
    }

    interface WakeLockHandle {
        void setReferenceCounted(boolean value);

        void acquire();

        boolean isHeld();

        void release();
    }

    private static final class AndroidWakeLockHandle implements WakeLockHandle {
        private final PowerManager.WakeLock wakeLock;

        AndroidWakeLockHandle(PowerManager.WakeLock wakeLock) {
            this.wakeLock = wakeLock;
        }

        @Override
        public void setReferenceCounted(boolean value) {
            wakeLock.setReferenceCounted(value);
        }

        @Override
        public void acquire() {
            wakeLock.acquire();
        }

        @Override
        public boolean isHeld() {
            return wakeLock.isHeld();
        }

        @Override
        public void release() {
            wakeLock.release();
        }
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
        final List<PdfBoxExportWriter.ExportImageDescriptor> images;

        ChapterPreflight(List<PdfBoxExportWriter.ExportImageDescriptor> images) {
            this.images = images;
        }
    }

    private static final class ExportPreflight {
        final File pdfFile;
        final List<ChapterPreflight> chapters;
        final List<ExportVolume> volumes;
        final int totalPages;
        final long totalImageBytes;
        final long requiredBytes;
        final long preflightDurationNanos;

        ExportPreflight(File pdfFile, List<ChapterPreflight> chapters, List<ExportVolume> volumes,
                        int totalPages, long totalImageBytes, long requiredBytes,
                        long preflightDurationNanos) {
            this.pdfFile = pdfFile;
            this.chapters = chapters;
            this.volumes = volumes;
            this.totalPages = totalPages;
            this.totalImageBytes = totalImageBytes;
            this.requiredBytes = requiredBytes;
            this.preflightDurationNanos = preflightDurationNanos;
        }
    }
}
