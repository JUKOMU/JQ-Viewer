package io.github.jukomu.feature.pdf.export;

import android.content.Context;
import android.os.Environment;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import io.github.jukomu.feature.download.data.DownloadStore;
import io.github.jukomu.feature.download.storage.FileStore;
import io.github.jukomu.feature.download.validation.ChapterManifestValidator;
import io.github.jukomu.feature.pdf.data.PdfStore;
import io.github.jukomu.feature.pdf.management.PdfFileValidator;
import io.github.jukomu.feature.pdf.notification.PdfExportNotificationHelper;
import io.github.jukomu.platform.notification.NotificationIds;

import org.json.JSONArray;
import org.json.JSONObject;

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
    private final AtomicBoolean startupReconciled = new AtomicBoolean(false);
    private int foregroundRevision;
    private final Object activeJobsLock = new Object();
    private final Set<String> activeTaskKeys = new HashSet<>();
    private final Set<String> activeChapterKeys = new HashSet<>();
    private final PdfBoxExportWriter writer;
    private final PdfStore pdfStore;
    private final ForegroundPublisher foregroundPublisher;
    private final WakeLockFactory wakeLockFactory;
    private volatile PdfExportEventSink eventSink = snapshot -> {
    };

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
        this.pdfStore = PdfStore.getInstance(this.context);
    }

    static ExecutorService createExecutor() {
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

    /**
     * Binds the current bridge event consumer; a null consumer disables publication.
     */
    public synchronized void attachEventSink(PdfExportEventSink sink) {
        eventSink = sink == null ? snapshot -> {
        } : sink;
    }

    /**
     * Detaches only the consumer that currently owns the binding.
     */
    public synchronized void detachEventSink(PdfExportEventSink expected) {
        if (eventSink == expected) {
            eventSink = snapshot -> {
            };
        }
    }

    /**
     * Marks unfinished persisted work as interrupted and removes its known temporary artifacts once per process.
     */
    public void reconcileOnStartup() {
        if (!startupReconciled.compareAndSet(false, true)) return;
        pdfStore.markActiveTasksInterrupted();
        JSONArray tasks = pdfStore.getAllExportTasks();
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task != null && "interrupted".equals(task.optString("status"))) {
                cleanupKnownArtifactsQuietly(task.optString("exportId"));
            }
        }
    }

    public JSONObject getManagementState() {
        return pdfStore.getManagementState();
    }

    public JSONObject getExportTasksPage(String status, String cursor, int limit) {
        return pdfStore.getExportTasksPage(status, cursor, limit);
    }

    public JSONObject getExportTask(String exportId) {
        return pdfStore.getExportTask(exportId);
    }

    public JSONObject cancelExport(String exportId) {
        return pdfStore.requestExportCancel(exportId);
    }

    public synchronized JSONObject retryExport(String exportId, boolean allowOverwrite) throws Exception {
        JSONObject task = pdfStore.getExportTask(exportId);
        if (task == null) throw new IllegalArgumentException("PDF 导出任务不存在");
        JSONArray persistedChapters = pdfStore.getExportChapters(exportId);
        JSONArray persistedVolumes = pdfStore.getExportVolumes(exportId);
        ExportJob job = jobFromSnapshot(task, persistedChapters);
        job.allowOverwrite = allowOverwrite;
        ExportPreflight preflight = preflight(job);
        ensureRetryLayoutUnchanged(persistedChapters, persistedVolumes, preflight);
        job.exportId = exportId;
        if (!acquireJobLocks(job)) throw new IllegalStateException("相同章节已有任务正在运行");
        if (!pdfStore.prepareExportRetry(exportId, allowOverwrite)) {
            releaseJobLocksAndUpdate(job);
            throw new IllegalStateException("当前任务状态不能重试");
        }
        QueuedExportJob queued = new QueuedExportJob(job, preflight,
            NotificationIds.pdfTask(notificationCounter.getAndIncrement()));
        updateForegroundQueued(Collections.singletonList(queued));
        executor.submit(() -> executeBatch(batchCounter.incrementAndGet(),
            Collections.singletonList(queued)));
        return pdfStore.getExportTask(exportId);
    }

    public synchronized boolean deleteExportTask(String exportId) throws IOException {
        JSONObject task = pdfStore.getExportTask(exportId);
        if (task == null) return false;
        if (!PdfStore.isTerminalExportStatus(task.optString("status"))) {
            throw new IllegalStateException("活动 PDF 导出任务不能删除");
        }
        cleanupKnownArtifacts(exportId, true);
        if (!pdfStore.deleteExportTask(exportId)) {
            throw new IllegalStateException("活动 PDF 导出任务不能删除");
        }
        return true;
    }

    private void reserveExport(ExportJob job, ExportPreflight preflight, String batchId,
                               String status, String errorCode, String errorMessage) throws Exception {
        JSONObject task = new JSONObject();
        task.put("exportId", job.exportId);
        task.put("batchId", batchId);
        task.put("mode", job.mode);
        task.put("albumId", job.albumId);
        task.put("albumTitle", nullToEmpty(job.albumTitle));
        task.put("coverUrl", nullToEmpty(job.coverUrl));
        task.put("authors", nullToEmpty(job.authors));
        if (job.singleEpisode >= 0) task.put("isSingleEpisode", job.singleEpisode == 1);
        if (!"merged".equals(job.mode)) task.put("chapterId", job.chapterId);
        task.put("displayTitle", job.chapterTitle);
        task.put("savePath", job.savePath);
        task.put("allowOverwrite", job.allowOverwrite);
        task.put("useOriginal", job.useOriginal);
        task.put("compressionRatio", job.compressionRatio);
        task.put("splitPages", job.splitPages);
        task.put("status", status);
        task.put("phase", status);
        task.put("totalPages", preflight == null ? 0 : preflight.totalPages);
        task.put("createdAt", System.currentTimeMillis());
        if (errorCode != null) task.put("errorCode", errorCode);
        if (errorMessage != null) task.put("errorMessage", errorMessage);

        JSONArray chapters = new JSONArray();
        List<ExportChapter> requested = requestedChapters(job);
        for (int index = 0; index < requested.size(); index++) {
            ExportChapter chapter = requested.get(index);
            JSONObject value = new JSONObject();
            value.put("sequence", index);
            value.put("albumId", chapter.albumId);
            value.put("chapterId", chapter.chapterId);
            value.put("chapterTitle", nullToEmpty(chapter.chapterTitle));
            value.put("sortOrder", chapter.sortOrder);
            int pages = preflight == null ? 0 : preflight.chapters.get(index).images.size();
            value.put("expectedPageCount", pages);
            chapters.put(value);
        }

        JSONArray volumes = new JSONArray();
        if (preflight != null) {
            for (int index = 0; index < preflight.volumes.size(); index++) {
                ExportVolume volume = preflight.volumes.get(index);
                JSONObject value = new JSONObject();
                value.put("volumeIndex", index + 1);
                value.put("startPage", volume.start);
                value.put("endPage", volume.end);
                value.put("expectedPageCount", volume.end - volume.start);
                value.put("finalPath", volume.file.getAbsolutePath());
                value.put("tempPath", PdfBoxExportWriter.getTempFile(volume.file).getAbsolutePath());
                value.put("workDir", PdfBoxExportWriter.getWorkDirectory(volume.file).getAbsolutePath());
                volumes.put(value);
            }
        }
        pdfStore.reserveExport(task, chapters, volumes);
        if ("failed".equals(status)) {
            updateProgress(job.exportId, "failed", "failed", 0, 0, 0, 0,
                errorCode, errorMessage);
        }
    }

    private static ExportJob jobFromSnapshot(JSONObject task, JSONArray chapters) {
        ExportJob job = new ExportJob();
        job.exportId = task.optString("exportId");
        job.mode = task.optString("mode");
        job.albumId = task.optString("albumId");
        job.albumTitle = task.optString("albumTitle");
        job.coverUrl = task.optString("coverUrl");
        job.authors = task.optString("authors");
        job.singleEpisode = task.has("isSingleEpisode")
            ? (task.optBoolean("isSingleEpisode") ? 1 : 0) : -1;
        job.chapterId = task.optString("chapterId");
        job.chapterTitle = task.optString("displayTitle");
        job.savePath = task.optString("savePath");
        job.useOriginal = task.optBoolean("useOriginal", true);
        job.compressionRatio = (float) task.optDouble("compressionRatio", 1D);
        job.splitPages = task.optInt("splitPages");
        job.chapters = new ArrayList<>();
        for (int index = 0; index < chapters.length(); index++) {
            JSONObject value = chapters.optJSONObject(index);
            if (value == null) continue;
            ExportChapter chapter = new ExportChapter();
            chapter.albumId = value.optString("albumId");
            chapter.chapterId = value.optString("chapterId");
            chapter.chapterTitle = value.optString("chapterTitle");
            chapter.sortOrder = value.optInt("sortOrder");
            job.chapters.add(chapter);
        }
        return job;
    }

    private void cleanupKnownArtifacts(String exportId, boolean strict) throws IOException {
        JSONArray volumes = pdfStore.getExportVolumes(exportId);
        IOException failure = null;
        for (int index = 0; index < volumes.length(); index++) {
            try {
                PdfArtifactCleaner.cleanupKnownVolume(volumes.getJSONObject(index));
            } catch (Exception error) {
                if (failure == null) failure = new IOException("PDF 临时产物清理失败", error);
                else failure.addSuppressed(error);
            }
        }
        if (strict && failure != null) throw failure;
        if (!strict && failure != null) Log.w(TAG, "PDF 临时产物清理失败: " + exportId, failure);
    }

    private void cleanupKnownArtifactsQuietly(String exportId) {
        try {
            cleanupKnownArtifacts(exportId, false);
        } catch (IOException ignored) {
            // Non-strict cleanup already logs each failure.
        }
    }

    private JSONObject updateProgress(String exportId, String status, String phase,
                                      int currentPage, int totalPages, int currentVolume, int totalVolumes,
                                      String errorCode, String errorMessage) {
        JSONObject snapshot = pdfStore.updateExportProgress(exportId, status, phase,
            currentPage, totalPages, currentVolume, totalVolumes, errorCode, errorMessage);
        if (snapshot != null) {
            try {
                eventSink.onExportProgress(snapshot);
            } catch (RuntimeException error) {
                Log.w(TAG, "发布 PDF 导出进度失败", error);
            }
        }
        return snapshot;
    }

    private void updateTerminalProgress(ExportJob job, ExportPreflight preflight, String status,
                                        int completedVolumes, String errorCode, String errorMessage) {
        JSONObject current = pdfStore.getExportTask(job.exportId);
        int currentPage = current == null ? 0 : current.optInt("currentPage");
        int totalPages = current == null
            ? preflight.totalPages
            : current.optInt("totalPages", preflight.totalPages);
        updateProgress(job.exportId, status, status, currentPage, totalPages, completedVolumes,
            preflight.volumes.size(), errorCode, errorMessage);
    }

    private static List<ExportChapter> requestedChapters(ExportJob job) {
        if ("merged".equals(job.mode)) return job.chapters;
        ExportChapter chapter = new ExportChapter();
        chapter.albumId = job.albumId;
        chapter.chapterId = job.chapterId;
        chapter.chapterTitle = job.chapterTitle;
        return Collections.singletonList(chapter);
    }

    private static String errorCode(Throwable error) {
        String message = findErrorMessage(error);
        int separator = message.indexOf(':');
        return separator > 0 ? message.substring(0, separator) : "PDF_EXPORT_FAILED";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // ---- 导出任务数据结构 ----

    public static class ExportJob {
        public String exportId;
        public String mode;
        public String albumId;
        public String albumTitle;
        public String coverUrl;
        public String authors;
        public int singleEpisode = -1;
        public String chapterId;
        public String chapterTitle;
        public List<ExportChapter> chapters;
        public String savePath;
        public boolean useOriginal;
        public float compressionRatio; // 0.1~1.0
        public int splitPages;         // 0=不分卷, >0=每卷页数
        public boolean allowOverwrite;
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
    public JSONObject submitExport(List<ExportJob> jobs) throws Exception {
        final int batchId = batchCounter.incrementAndGet();
        List<QueuedExportJob> accepted = new ArrayList<>();
        JSONArray results = new JSONArray();
        AtomicBoolean workerStarted = new AtomicBoolean(false);

        try {
            for (ExportJob job : jobs) {
                PdfExportJobValidator.validate(job);
                if (!acquireJobLocks(job)) {
                    results.put(new JSONObject()
                        .put("accepted", false)
                        .put("errorCode", "TASK_CONFLICT")
                        .put("errorMessage", "相同章节已有 PDF 导出任务正在排队或运行"));
                    continue;
                }
                job.exportId = UUID.randomUUID().toString();
                publishPdfForeground(0, job, "排队中", 0, 0, 0, 0);
                try {
                    ExportPreflight preflight = preflight(job);
                    reserveExport(job, preflight, String.valueOf(batchId), "queued", null, null);
                    int notificationId = NotificationIds.pdfTask(
                        notificationCounter.getAndIncrement());
                    accepted.add(new QueuedExportJob(job, preflight, notificationId));
                    JSONObject task = pdfStore.getExportTask(job.exportId);
                    task.put("accepted", true);
                    results.put(task);
                } catch (Exception error) {
                    ExportFailure failure = describeExportFailure(error, job);
                    reserveExport(job, null, String.valueOf(batchId), "failed",
                        errorCode(error), failure.userMessage);
                    releaseJobLocksAndUpdate(job);
                    JSONObject task = pdfStore.getExportTask(job.exportId);
                    task.put("accepted", false);
                    results.put(task);
                }
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
        try {
            return new JSONObject().put("tasks", results);
        } catch (Exception error) {
            throw new IllegalStateException(error);
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
                    ExportPreflight preflight = queuedJob.preflight;
                    if (!pdfStore.claimQueuedExport(job.exportId)) {
                        continue;
                    }
                    publishPdfForeground(sessionId, job, "准备写入", 0, preflight.totalPages, 1,
                        preflight.volumes.size());
                    exportJob(job, preflight, notificationId, sessionId);
                    updateProgress(job.exportId, "completed", "completed", preflight.totalPages,
                        preflight.totalPages, preflight.volumes.size(), preflight.volumes.size(),
                        null, null);
                    success++;
                } catch (ExportCancelledException | ExportCancelledRuntimeException error) {
                    int completed = pdfStore.countCompletedVolumes(job.exportId);
                    String status = completed > 0 ? "partial" : "cancelled";
                    cleanupKnownArtifactsQuietly(job.exportId);
                    updateTerminalProgress(job, queuedJob.preflight, status, completed,
                        "CANCELLED", "PDF 导出已取消");
                } catch (Exception e) {
                    fail++;
                    ExportFailure failure = describeExportFailure(e, job);
                    Log.e(TAG, failure.debugMessage, e);
                    int completed = pdfStore.countCompletedVolumes(job.exportId);
                    String status = completed > 0 ? "partial" : "failed";
                    cleanupKnownArtifactsQuietly(job.exportId);
                    updateTerminalProgress(job, queuedJob.preflight, status, completed,
                        errorCode(e), failure.userMessage);
                    notif.showError(notificationId, job.chapterTitle, failure.userMessage);
                } catch (Throwable t) {
                    fail++;
                    Log.e(TAG, "PDF export crashed: " + job.chapterTitle, t);
                    int completed = pdfStore.countCompletedVolumes(job.exportId);
                    cleanupKnownArtifactsQuietly(job.exportId);
                    updateTerminalProgress(job, queuedJob.preflight, "failed", completed,
                        "INTERNAL_ERROR",
                        "内部错误: " + t.getClass().getSimpleName());
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
            if (pdfStore.isCancelRequested(job.exportId)) throw new ExportCancelledException();
            ExportVolume volume = volumes.get(volumeIndex);
            final int volumeNumber = volumeIndex + 1;
            final int volumeCount = volumes.size();
            long volumeStartedAt = SystemClock.elapsedRealtimeNanos();
            Log.i(TAG, "Volume " + (volumeIndex + 1) + "/" + volumes.size()
                + ": " + volume.file.getName());

            List<PdfBoxExportWriter.ExportImageDescriptor> volumeImages =
                images.subList(volume.start, volume.end);
            pdfStore.markVolumeWriting(job.exportId, volumeNumber);
            updateProgress(job.exportId, "running", "writing", volume.start, total,
                volumeNumber, volumeCount, null, null);
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
                        if (pdfStore.isCancelRequested(job.exportId)) {
                            throw new ExportCancelledRuntimeException();
                        }
                        updateProgress(job.exportId, "running", "writing", taskPage, total,
                            volumeNumber, volumeCount, null, null);
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
            PdfFileValidator.Report report;
            try {
                report = PdfFileValidator.validate(
                    context, volume.file.getAbsolutePath(), volume.end - volume.start);
            } catch (PdfFileValidator.ValidationException error) {
                throw new IOException(error.code + ": " + error.getMessage(), error);
            }
            pdfStore.completeVolumeAndRegisterFile(job.exportId, volumeNumber,
                volume.file.getAbsolutePath(), report.fileSize, report.pageCount, job.mode,
                job.albumId, job.albumTitle, job.coverUrl, job.authors, job.chapterId,
                job.chapterTitle, 0, job.singleEpisode);
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
            String label = chapter.chapterTitle == null || chapter.chapterTitle.trim().isEmpty()
                ? chapter.chapterId : chapter.chapterTitle;
            ChapterManifestValidator.Report manifest;
            try {
                manifest = ChapterManifestValidator.validate(fileStore,
                    DownloadStore.getInstance(context), chapter.albumId, chapter.chapterId);
            } catch (ChapterManifestValidator.ValidationException error) {
                throw new IOException(error.code + ": 章节“" + label + "”" + error.getMessage(),
                    error);
            }
            List<PdfBoxExportWriter.ExportImageDescriptor> descriptors =
                new ArrayList<>(manifest.expectedFiles.size());
            for (File imageFile : manifest.expectedFiles) {
                long boundsStartedAt = SystemClock.elapsedRealtimeNanos();
                PdfBoxExportWriter.ExportImageDescriptor descriptor =
                    PdfBoxExportWriter.inspectImage(imageFile);
                boundsDecodeNanos = saturatingAdd(
                    boundsDecodeNanos,
                    SystemClock.elapsedRealtimeNanos() - boundsStartedAt
                );
                descriptors.add(descriptor);
                totalImageBytes = saturatingAdd(totalImageBytes, imageFile.length());
            }
            totalPages = saturatingAdd(totalPages, manifest.totalPages);
            chapterResults.add(new ChapterPreflight(chapter, descriptors));
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
        ensureOverwriteAllowed(volumes, job.allowOverwrite);
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

    static void ensureOverwriteAllowed(List<ExportVolume> volumes, boolean allowOverwrite)
        throws IOException {
        if (allowOverwrite) return;
        for (ExportVolume volume : volumes) {
            if (volume.file.exists()) {
                throw new IOException("PDF_OUTPUT_EXISTS: 目标文件已存在，请确认覆盖后重试: "
                    + volume.file.getAbsolutePath());
            }
        }
    }

    private static void ensureRetryLayoutUnchanged(JSONArray persistedChapters,
                                                   JSONArray persistedVolumes, ExportPreflight preflight) throws IOException {
        List<Integer> persistedChapterPageCounts = new ArrayList<>(persistedChapters.length());
        for (int index = 0; index < persistedChapters.length(); index++) {
            JSONObject chapter = persistedChapters.optJSONObject(index);
            if (chapter == null || chapter.optInt("expectedPageCount", -1) < 0) {
                throw retryLayoutChanged();
            }
            persistedChapterPageCounts.add(chapter.optInt("expectedPageCount"));
        }
        List<ExportVolume> persistedVolumeLayouts = new ArrayList<>(persistedVolumes.length());
        for (int index = 0; index < persistedVolumes.length(); index++) {
            JSONObject volume = persistedVolumes.optJSONObject(index);
            if (volume == null || volume.optInt("volumeIndex", -1) != index + 1
                || volume.optString("finalPath").isEmpty()) {
                throw retryLayoutChanged();
            }
            int startPage = volume.optInt("startPage", -1);
            int endPage = volume.optInt("endPage", -1);
            if (startPage < 0 || endPage < startPage
                || volume.optInt("expectedPageCount", -1) != endPage - startPage) {
                throw retryLayoutChanged();
            }
            persistedVolumeLayouts.add(new ExportVolume(
                startPage,
                endPage,
                new File(volume.optString("finalPath"))));
        }
        List<Integer> chapterPageCounts = new ArrayList<>(preflight.chapters.size());
        for (ChapterPreflight chapter : preflight.chapters) {
            chapterPageCounts.add(chapter.images.size());
        }
        ensureRetryLayoutUnchanged(persistedChapterPageCounts, persistedVolumeLayouts,
            chapterPageCounts, preflight.volumes);
    }

    static void ensureRetryLayoutUnchanged(List<Integer> persistedChapterPageCounts,
                                           List<ExportVolume> persistedVolumes, List<Integer> currentChapterPageCounts,
                                           List<ExportVolume> currentVolumes) throws IOException {
        if (!persistedChapterPageCounts.equals(currentChapterPageCounts)
            || persistedVolumes.size() != currentVolumes.size()) {
            throw retryLayoutChanged();
        }
        for (int index = 0; index < persistedVolumes.size(); index++) {
            ExportVolume persisted = persistedVolumes.get(index);
            ExportVolume current = currentVolumes.get(index);
            if (persisted.start != current.start
                || persisted.end != current.end
                || !PdfStore.normalizeLocator(persisted.file.getAbsolutePath())
                .equals(PdfStore.normalizeLocator(current.file.getAbsolutePath()))) {
                throw retryLayoutChanged();
            }
        }
    }

    private static IOException retryLayoutChanged() {
        return new IOException("PDF_RETRY_LAYOUT_CHANGED: 章节页数或分卷布局已变化，"
            + "请重新创建导出任务");
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
        final ExportPreflight preflight;
        final int notificationId;

        QueuedExportJob(ExportJob job, ExportPreflight preflight, int notificationId) {
            this.job = job;
            this.preflight = preflight;
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
        final ExportChapter chapter;
        final List<PdfBoxExportWriter.ExportImageDescriptor> images;

        ChapterPreflight(ExportChapter chapter,
                         List<PdfBoxExportWriter.ExportImageDescriptor> images) {
            this.chapter = chapter;
            this.images = images;
        }
    }

    private static final class ExportCancelledException extends IOException {
        ExportCancelledException() {
            super("PDF 导出已取消");
        }
    }

    private static final class ExportCancelledRuntimeException extends RuntimeException {
        ExportCancelledRuntimeException() {
            super("PDF 导出已取消");
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
