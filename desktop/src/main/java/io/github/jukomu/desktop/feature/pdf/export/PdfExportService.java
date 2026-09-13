package io.github.jukomu.desktop.feature.pdf.export;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.desktop.feature.download.DownloadFiles;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.download.data.StoredDownloadPage;
import io.github.jukomu.desktop.feature.download.data.StoredDownloadTask;
import io.github.jukomu.desktop.feature.files.ExportTargetResolver;
import io.github.jukomu.desktop.feature.files.FileReferences;
import io.github.jukomu.desktop.feature.pdf.management.PdfFileValidator;
import io.github.jukomu.desktop.feature.pdf.model.PdfExportBatchResponse;
import io.github.jukomu.desktop.feature.pdf.model.PdfExportChapterRequest;
import io.github.jukomu.desktop.feature.pdf.model.PdfExportTaskRequest;
import io.github.jukomu.desktop.feature.pdf.model.PdfExportTaskResponse;
import io.github.jukomu.desktop.feature.pdf.model.PdfExportTasksResponse;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Desktop 持久化单线程 PDF 导出队列。 */
public final class PdfExportService implements AutoCloseable {
    private final PdfExportStore store;
    private final DownloadStore downloads;
    private final DownloadFiles downloadFiles;
    private final ExecutorService executor;
    private final EventHub events;
    private final PdfVolumeWriter writer;
    private final AtomicBoolean startupReconciled = new AtomicBoolean();
    private boolean closed;

    public PdfExportService(
            PdfExportStore store,
            DownloadStore downloads,
            DownloadFiles downloadFiles,
            ExecutorService executor,
            EventHub events
    ) {
        this(store, downloads, downloadFiles, executor, events, new PdfBoxVolumeWriter());
    }

    PdfExportService(
            PdfExportStore store,
            DownloadStore downloads,
            DownloadFiles downloadFiles,
            ExecutorService executor,
            EventHub events,
            PdfVolumeWriter writer
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.downloads = Objects.requireNonNull(downloads, "downloads");
        this.downloadFiles = Objects.requireNonNull(downloadFiles, "downloadFiles");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    /** 将上次进程遗留的活动任务标为 interrupted，并清理已知临时文件。 */
    public void reconcileOnStartup() {
        if (!startupReconciled.compareAndSet(false, true)) return;
        for (String tempPath : store.markActiveInterrupted()) deleteQuietly(Path.of(tempPath));
    }

    public synchronized PdfExportBatchResponse submit(List<PdfExportTaskRequest> requestedTasks) {
        requireOpen();
        if (requestedTasks == null || requestedTasks.isEmpty()) {
            throw ApiException.invalidRequest("tasks不能为空");
        }
        String batchId = UUID.randomUUID().toString();
        List<PdfExportTaskResponse> results = new ArrayList<>();
        List<Plan> acceptedPlans = new ArrayList<>();
        for (PdfExportTaskRequest request : requestedTasks) {
            NormalizedTask task = normalize(request);
            String exportId = UUID.randomUUID().toString();
            try {
                Plan plan = preflight(exportId, batchId, task, task.allowOverwrite());
                if (store.hasActiveChapterConflict(plan.chapters())) {
                    results.add(PdfExportTaskResponse.rejected(
                            "TASK_CONFLICT", "相同章节已有 PDF 导出任务正在排队或运行",
                            task.displayPath()));
                    continue;
                }
                reserve(plan, "queued", "queued", null, null);
                PdfExportTaskResponse snapshot = requireTask(exportId).withAccepted(true);
                results.add(snapshot);
                acceptedPlans.add(plan);
                publish(snapshot);
            } catch (OutputExistsException exception) {
                results.add(PdfExportTaskResponse.rejected(
                        exception.code(), exception.getMessage(), task.displayPath()));
            } catch (ExportException exception) {
                Plan failed = failedPlan(exportId, batchId, task);
                reserve(failed, "failed", "failed", exception.code(), exception.getMessage());
                PdfExportTaskResponse snapshot = requireTask(exportId).withAccepted(false);
                results.add(snapshot);
                publish(snapshot);
            }
        }
        for (Plan plan : acceptedPlans) schedule(plan);
        return new PdfExportBatchResponse(List.copyOf(results));
    }

    public PdfExportTasksResponse getTasks(String status, String cursor, int limit) {
        PdfExportStore.Page page = store.list(status, cursor, limit);
        return new PdfExportTasksResponse(page.tasks(), page.nextCursor());
    }

    public PdfExportTaskResponse getTask(String exportId) {
        return requireTask(requireText(exportId, "exportId"));
    }

    public PdfExportTaskResponse cancel(String exportId) {
        PdfExportTaskResponse task = store.requestCancel(requireText(exportId, "exportId"));
        if (task == null) throw ApiException.notFound("PDF 导出任务不存在");
        if ("cancelled".equals(task.status())) cleanupQueuedCancellation(task.exportId());
        publish(task);
        return task;
    }

    public synchronized PdfExportTaskResponse retry(String exportId, boolean allowOverwrite) {
        requireOpen();
        PdfExportTaskResponse persisted = requireTask(requireText(exportId, "exportId"));
        if (!PdfExportStore.isTerminal(persisted.status())) {
            throw ApiException.conflict("当前 PDF 导出任务不能重试");
        }
        NormalizedTask task = taskFromPersisted(persisted, store.chapters(exportId));
        final Plan plan;
        try {
            plan = preflight(exportId, persisted.batchId(), task, allowOverwrite);
            ensureRetryLayoutUnchanged(store.chapters(exportId), store.volumes(exportId), plan);
        } catch (OutputExistsException exception) {
            throw ApiException.conflict(exception.getMessage());
        } catch (ExportException exception) {
            throw new ApiException("internal", 409, exception.getMessage());
        }
        if (store.hasActiveChapterConflict(plan.chapters())) {
            throw ApiException.conflict("相同章节已有 PDF 导出任务正在排队或运行");
        }
        if (!store.prepareRetry(exportId, allowOverwrite)) {
            throw ApiException.conflict("当前 PDF 导出任务不能重试");
        }
        PdfExportTaskResponse queued = requireTask(exportId);
        publish(queued);
        schedule(plan);
        return queued;
    }

    public synchronized boolean deleteTask(String exportId) {
        PdfExportTaskResponse task = requireTask(requireText(exportId, "exportId"));
        if (!PdfExportStore.isTerminal(task.status())) {
            throw ApiException.conflict("活动 PDF 导出任务不能删除");
        }
        for (PdfExportStore.Volume volume : store.volumes(exportId)) {
            deleteQuietly(Path.of(volume.tempPath()));
        }
        return store.delete(exportId);
    }

    private void schedule(Plan plan) {
        try {
            executor.execute(() -> execute(plan));
        } catch (RejectedExecutionException exception) {
            PdfExportTaskResponse failed = store.updateProgress(
                    plan.exportId(), "failed", "failed", 0, plan.images().size(),
                    0, plan.volumes().size(), "QUEUE_REJECTED", "PDF 导出队列已关闭");
            cleanupTemporaryFiles(plan);
            publish(failed);
        }
    }

    private void execute(Plan plan) {
        PdfExportTaskResponse claimed = store.claim(plan.exportId());
        if (claimed == null) return;
        publish(claimed);
        try {
            for (VolumePlan volume : plan.volumes()) {
                checkCancelled(plan.exportId());
                store.markVolumeStatus(plan.exportId(), volume.record().volumeIndex(), "writing");
                publish(store.updateProgress(
                        plan.exportId(), "running", "writing", volume.record().startPage(),
                        plan.images().size(), volume.record().volumeIndex(), plan.volumes().size(),
                        null, null));

                List<Path> volumeImages = plan.images().subList(
                        volume.record().startPage(), volume.record().endPage());
                writer.write(volumeImages, volume.temporaryFile(), plan.task().useOriginal(),
                        plan.task().compressionRatio(), pageCount -> {
                            checkCancelled(plan.exportId());
                            int taskPage = volume.record().startPage() + pageCount;
                            publish(store.updateProgress(
                                    plan.exportId(), "running", "writing", taskPage,
                                    plan.images().size(), volume.record().volumeIndex(),
                                    plan.volumes().size(), null, null));
                        });
                checkCancelled(plan.exportId());
                PdfFileValidator.Report report = validateTemporary(volume);
                publishTemporary(plan, volume);
                String fileRef = FileReferences.fileRef(volume.outputFile());
                store.completeVolumeAndRegisterFile(
                        plan.exportId(), volume.record().volumeIndex(), fileRef,
                        volume.record().displayPath(), volume.outputFile().getFileName().toString(),
                        report.fileSize(), report.pageCount());
            }
            publish(store.updateProgress(
                    plan.exportId(), "completed", "completed", plan.images().size(),
                    plan.images().size(), plan.volumes().size(), plan.volumes().size(),
                    null, null));
        } catch (CancelledException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            finishFailure(plan, store.completedVolumeCount(plan.exportId()) > 0 ? "partial" : "cancelled",
                    "CANCELLED", "PDF 导出已取消");
        } catch (Exception exception) {
            ExportFailure failure = describe(exception);
            finishFailure(plan, store.completedVolumeCount(plan.exportId()) > 0 ? "partial" : "failed",
                    failure.code(), failure.message());
        }
    }

    private void finishFailure(Plan plan, String status, String code, String message) {
        cleanupTemporaryFiles(plan);
        for (PdfExportStore.Volume volume : store.volumes(plan.exportId())) {
            store.markVolumeStatus(plan.exportId(), volume.volumeIndex(), status);
        }
        PdfExportTaskResponse current = requireTask(plan.exportId());
        publish(store.updateProgress(
                plan.exportId(), status, status, current.currentPage(), plan.images().size(),
                store.completedVolumeCount(plan.exportId()), plan.volumes().size(), code, message));
    }

    private Plan preflight(
            String exportId,
            String batchId,
            NormalizedTask task,
            boolean allowOverwrite
    ) throws ExportException {
        List<PdfExportStore.Chapter> chapters = new ArrayList<>();
        List<Path> images = new ArrayList<>();
        List<RequestedChapter> requested = task.chapters();
        for (int index = 0; index < requested.size(); index++) {
            RequestedChapter chapter = requested.get(index);
            StoredDownloadTask download = downloads.findTask(chapter.albumId(), chapter.chapterId());
            if (download == null || !"completed".equals(download.status())) {
                throw new ExportException("DOWNLOAD_NOT_COMPLETED",
                        "章节“" + chapter.title() + "”尚未完成下载");
            }
            List<StoredDownloadPage> pages = downloads.pages(download.taskId());
            if (download.totalPages() <= 0 || pages.size() != download.totalPages()
                    || pages.stream().anyMatch(page -> !page.completed())) {
                throw new ExportException("DOWNLOAD_MANIFEST_INVALID",
                        "章节“" + chapter.title() + "”下载清单不完整");
            }
            try {
                downloadFiles.inspect(pages);
            } catch (RuntimeException exception) {
                throw new ExportException("DOWNLOAD_FILE_INVALID",
                        "章节“" + chapter.title() + "”下载文件不可用", exception);
            }
            for (StoredDownloadPage page : pages) images.add(downloadFiles.resolvePage(page));
            int sortOrder = chapter.sortOrder() != null
                    ? chapter.sortOrder() : download.chapterSortOrder();
            chapters.add(new PdfExportStore.Chapter(
                    index, chapter.albumId(), chapter.chapterId(), chapter.title(),
                    sortOrder, pages.size()));
        }
        if (images.isEmpty()) throw new ExportException("DOWNLOAD_MANIFEST_INVALID", "没有可导出的图片");

        Path root;
        try {
            root = io.github.jukomu.desktop.feature.files.FileReferences.parseFolder(task.folderRef());
        } catch (RuntimeException exception) {
            throw new ExportException("PDF_TARGET_INVALID", "PDF 导出目录引用无效", exception);
        }
        if (!Files.isDirectory(root) || !Files.isWritable(root)) {
            throw new ExportException("PDF_TARGET_INACCESSIBLE", "PDF 导出目录不存在或不可写");
        }
        List<VolumePlan> volumes = buildVolumes(
                exportId, task, images.size(), allowOverwrite);
        return new Plan(exportId, batchId, task.withAllowOverwrite(allowOverwrite),
                List.copyOf(chapters), List.copyOf(images), List.copyOf(volumes));
    }

    private List<VolumePlan> buildVolumes(
            String exportId,
            NormalizedTask task,
            int totalPages,
            boolean allowOverwrite
    ) throws ExportException {
        int pagesPerVolume = task.splitPages() > 0 ? task.splitPages() : totalPages;
        int volumeCount = (totalPages + pagesPerVolume - 1) / pagesPerVolume;
        List<VolumePlan> volumes = new ArrayList<>(volumeCount);
        for (int index = 0; index < volumeCount; index++) {
            int start = index * pagesPerVolume;
            int end = Math.min(totalPages, start + pagesPerVolume);
            String targetName = volumeCount == 1 ? task.targetName()
                    : withVolumeSuffix(task.targetName(), start + 1, end);
            Path output;
            try {
                output = ExportTargetResolver.resolve(task.folderRef(), targetName);
                Files.createDirectories(output.getParent());
            } catch (RuntimeException | IOException exception) {
                throw new ExportException("PDF_TARGET_INVALID", "PDF 导出目标路径无效", exception);
            }
            if (Files.exists(output)) {
                if (!Files.isRegularFile(output)) {
                    throw new ExportException("PDF_TARGET_INVALID", "PDF 导出目标不是普通文件");
                }
                if (!allowOverwrite) {
                    throw new OutputExistsException("PDF_OUTPUT_EXISTS",
                            "目标文件已存在，请确认覆盖后重试: " + output);
                }
            }
            String displayPath = volumeCount == 1 ? task.displayPath()
                    : withVolumeSuffix(task.displayPath(), start + 1, end);
            Path temporary = output.resolveSibling("." + output.getFileName() + "."
                    + exportId + ".tmp.pdf");
            PdfExportStore.Volume record = new PdfExportStore.Volume(
                    index + 1, start, end, end - start, targetName,
                    displayPath == null || displayPath.isBlank() ? output.toString() : displayPath,
                    temporary.toString());
            volumes.add(new VolumePlan(record, output, temporary));
        }
        return volumes;
    }

    private void reserve(Plan plan, String status, String phase, String code, String message) {
        long now = System.currentTimeMillis();
        NormalizedTask task = plan.task();
        store.reserve(new PdfExportStore.ReserveTask(
                        plan.exportId(), plan.batchId(), task.mode(), task.albumId(),
                        task.albumTitle(), task.coverUrl(), task.authors(), task.singleEpisode(),
                        "merged".equals(task.mode()) ? null : task.chapters().get(0).chapterId(),
                        task.displayTitle(), task.folderRef(), task.targetName(), task.displayPath(),
                        task.allowOverwrite(), task.useOriginal(), task.compressionRatio(),
                        task.splitPages(), status, phase, plan.images().size(), code, message, now),
                plan.chapters(), plan.volumes().stream().map(VolumePlan::record).toList());
    }

    private static Plan failedPlan(String exportId, String batchId, NormalizedTask task) {
        List<PdfExportStore.Chapter> chapters = new ArrayList<>();
        for (int index = 0; index < task.chapters().size(); index++) {
            RequestedChapter chapter = task.chapters().get(index);
            chapters.add(new PdfExportStore.Chapter(index, chapter.albumId(), chapter.chapterId(),
                    chapter.title(), chapter.sortOrder() == null ? 0 : chapter.sortOrder(), 0));
        }
        return new Plan(exportId, batchId, task, List.copyOf(chapters), List.of(), List.of());
    }

    private static NormalizedTask normalize(PdfExportTaskRequest request) {
        if (request == null) throw ApiException.invalidRequest("tasks包含空任务");
        String mode = request.mode() == null || request.mode().isBlank() ? "chapter" : request.mode();
        if (!"chapter".equals(mode) && !"merged".equals(mode)) {
            throw ApiException.invalidRequest("mode必须是chapter或merged");
        }
        String albumId = requireText(request.albumId(), "albumId");
        if (request.target() == null) throw ApiException.invalidRequest("target不能为空");
        String folderRef = requireText(request.target().folder(), "target.folder");
        String targetName = requireText(request.target().relativePath(), "target.relativePath")
                .replace('\\', '/');
        if (!targetName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw ApiException.invalidRequest("target.relativePath必须以.pdf结尾");
        }
        List<RequestedChapter> chapters = new ArrayList<>();
        if ("merged".equals(mode)) {
            if (request.chapters() == null || request.chapters().isEmpty()) {
                throw ApiException.invalidRequest("merged任务的chapters不能为空");
            }
            for (PdfExportChapterRequest chapter : request.chapters()) {
                if (chapter == null) throw ApiException.invalidRequest("chapters包含空章节");
                chapters.add(new RequestedChapter(
                        requireText(chapter.albumId(), "chapters.albumId"),
                        requireText(chapter.chapterId(), "chapters.chapterId"),
                        requireText(chapter.chapterTitle(), "chapters.chapterTitle"),
                        chapter.sortOrder()));
            }
        } else {
            chapters.add(new RequestedChapter(albumId,
                    requireText(request.chapterId(), "chapterId"),
                    requireText(request.chapterTitle(), "chapterTitle"), null));
        }
        String displayTitle = request.chapterTitle();
        if (displayTitle == null || displayTitle.isBlank()) displayTitle = request.albumTitle();
        if (displayTitle == null || displayTitle.isBlank()) displayTitle = albumId;
        double compressionRatio = request.compressionRatio() == null ? 1D
                : Math.max(0.1D, Math.min(1D, request.compressionRatio()));
        int splitPages = Math.max(0, request.splitPages() == null ? 0 : request.splitPages());
        return new NormalizedTask(
                mode, albumId, value(request.albumTitle()), value(request.coverUrl()),
                value(request.authors()), request.isSingleEpisode(), displayTitle,
                List.copyOf(chapters), folderRef, targetName, value(request.displayPath()),
                request.useOriginal() == null || request.useOriginal(), compressionRatio,
                splitPages, request.allowOverwrite() != null && request.allowOverwrite());
    }

    private static NormalizedTask taskFromPersisted(
            PdfExportTaskResponse task,
            List<PdfExportStore.Chapter> chapters
    ) {
        List<RequestedChapter> requested = chapters.stream()
                .map(chapter -> new RequestedChapter(chapter.albumId(), chapter.chapterId(),
                        chapter.chapterTitle(), chapter.sortOrder()))
                .toList();
        String displayPath = ExportTargetResolver.resolve(
                task.targetFolderRef(), task.targetName()).toString();
        return new NormalizedTask(
                task.mode(), task.albumId(), task.albumTitle(), task.coverUrl(), task.authors(),
                task.isSingleEpisode(), task.displayTitle(), requested, task.targetFolderRef(),
                task.targetName(), displayPath, task.useOriginal(), task.compressionRatio(),
                task.splitPages(), task.allowOverwrite());
    }

    private static void ensureRetryLayoutUnchanged(
            List<PdfExportStore.Chapter> persistedChapters,
            List<PdfExportStore.Volume> persistedVolumes,
            Plan plan
    ) throws ExportException {
        if (persistedChapters.size() != plan.chapters().size()
                || persistedVolumes.size() != plan.volumes().size()) {
            throw new ExportException("PDF_RETRY_INPUT_CHANGED", "下载内容已变化，请新建导出任务");
        }
        for (int index = 0; index < persistedChapters.size(); index++) {
            PdfExportStore.Chapter before = persistedChapters.get(index);
            PdfExportStore.Chapter after = plan.chapters().get(index);
            if (!before.albumId().equals(after.albumId())
                    || !before.chapterId().equals(after.chapterId())
                    || before.expectedPageCount() != after.expectedPageCount()) {
                throw new ExportException("PDF_RETRY_INPUT_CHANGED", "下载内容已变化，请新建导出任务");
            }
        }
        for (int index = 0; index < persistedVolumes.size(); index++) {
            PdfExportStore.Volume before = persistedVolumes.get(index);
            PdfExportStore.Volume after = plan.volumes().get(index).record();
            if (before.startPage() != after.startPage() || before.endPage() != after.endPage()
                    || !before.targetName().equals(after.targetName())) {
                throw new ExportException("PDF_RETRY_INPUT_CHANGED", "下载内容已变化，请新建导出任务");
            }
        }
    }

    private static PdfFileValidator.Report validateTemporary(VolumePlan volume) throws ExportException {
        try {
            return PdfFileValidator.validate(FileReferences.fileRef(volume.temporaryFile()),
                    volume.record().expectedPageCount());
        } catch (PdfFileValidator.ValidationException exception) {
            throw new ExportException(exception.code(), exception.getMessage(), exception);
        }
    }

    private static void publishTemporary(Plan plan, VolumePlan volume) throws Exception {
        try {
            if (plan.task().allowOverwrite()) {
                try {
                    Files.move(volume.temporaryFile(), volume.outputFile(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(volume.temporaryFile(), volume.outputFile(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                try {
                    Files.move(volume.temporaryFile(), volume.outputFile(),
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(volume.temporaryFile(), volume.outputFile());
                }
            }
        } catch (FileAlreadyExistsException exception) {
            throw new OutputExistsException("PDF_OUTPUT_EXISTS",
                    "目标文件已存在，请确认覆盖后重试: " + volume.outputFile());
        }
    }

    private void checkCancelled(String exportId) throws CancelledException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (store.isCancellationRequested(exportId)) throw new CancelledException();
    }

    private PdfExportTaskResponse requireTask(String exportId) {
        PdfExportTaskResponse task = store.find(exportId);
        if (task == null) throw ApiException.notFound("PDF 导出任务不存在");
        return task;
    }

    private void publish(PdfExportTaskResponse task) {
        if (task != null) events.publish("pdfExportProgress", task);
    }

    private static ExportFailure describe(Exception exception) {
        if (exception instanceof OutputExistsException exists) {
            return new ExportFailure(exists.code(), exists.getMessage());
        }
        if (exception instanceof ExportException export) {
            return new ExportFailure(export.code(), export.getMessage());
        }
        String message = exception.getMessage();
        if (message != null) {
            int separator = message.indexOf(':');
            if (separator > 0 && separator < 40) {
                return new ExportFailure(message.substring(0, separator),
                        message.substring(separator + 1).trim());
            }
        }
        return new ExportFailure("PDF_EXPORT_FAILED",
                message == null || message.isBlank() ? "PDF 导出失败" : message);
    }

    private static String withVolumeSuffix(String value, int start, int end) {
        String suffix = String.format(Locale.ROOT, "_%03d-%03d.pdf", start, end);
        if (value == null || value.isBlank()) return suffix.substring(1);
        return value.toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? value.substring(0, value.length() - 4) + suffix : value + suffix;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw ApiException.invalidRequest(name + "不能为空");
        return value.trim();
    }

    private synchronized void requireOpen() {
        if (closed) throw ApiException.unavailable("PDF 导出服务已关闭");
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 后续重试或启动恢复仍会再次尝试清理同一个持久化临时路径。
        }
    }

    private static void cleanupTemporaryFiles(Plan plan) {
        for (VolumePlan volume : plan.volumes()) deleteQuietly(volume.temporaryFile());
    }

    private void cleanupQueuedCancellation(String exportId) {
        for (PdfExportStore.Volume volume : store.volumes(exportId)) {
            deleteQuietly(Path.of(volume.tempPath()));
            store.markVolumeStatus(exportId, volume.volumeIndex(), "cancelled");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (String exportId : store.activeExportIds()) {
            PdfExportTaskResponse task = store.requestCancel(exportId);
            if (task != null && "cancelled".equals(task.status())) {
                cleanupQueuedCancellation(exportId);
            }
            publish(task);
        }
    }

    private record RequestedChapter(String albumId, String chapterId, String title, Integer sortOrder) {
    }

    private record NormalizedTask(
            String mode,
            String albumId,
            String albumTitle,
            String coverUrl,
            String authors,
            Boolean singleEpisode,
            String displayTitle,
            List<RequestedChapter> chapters,
            String folderRef,
            String targetName,
            String displayPath,
            boolean useOriginal,
            double compressionRatio,
            int splitPages,
            boolean allowOverwrite
    ) {
        NormalizedTask withAllowOverwrite(boolean value) {
            return new NormalizedTask(mode, albumId, albumTitle, coverUrl, authors, singleEpisode,
                    displayTitle, chapters, folderRef, targetName, displayPath, useOriginal,
                    compressionRatio, splitPages, value);
        }
    }

    private record Plan(
            String exportId,
            String batchId,
            NormalizedTask task,
            List<PdfExportStore.Chapter> chapters,
            List<Path> images,
            List<VolumePlan> volumes
    ) {
    }

    private record VolumePlan(PdfExportStore.Volume record, Path outputFile, Path temporaryFile) {
    }

    private record ExportFailure(String code, String message) {
    }

    private static class ExportException extends Exception {
        private final String code;

        ExportException(String code, String message) {
            super(message);
            this.code = code;
        }

        ExportException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private static final class OutputExistsException extends ExportException {
        OutputExistsException(String code, String message) {
            super(code, message);
        }
    }

    private static final class CancelledException extends Exception {
    }
}
