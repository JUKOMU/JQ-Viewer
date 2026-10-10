package io.github.jukomu.desktop.feature.download;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.desktop.feature.catalog.model.ImageResponse;
import io.github.jukomu.desktop.feature.catalog.model.PhotoResponse;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.download.data.StoredDownloadPage;
import io.github.jukomu.desktop.feature.download.data.StoredDownloadTask;
import io.github.jukomu.desktop.feature.download.model.*;
import io.github.jukomu.desktop.feature.download.validation.ChapterManifestValidator;
import io.github.jukomu.desktop.util.JsonUtils;
import io.github.jukomu.jmcomic.api.client.JmClient;
import io.github.jukomu.jmcomic.api.client.JmDownloadClient;
import io.github.jukomu.jmcomic.api.download.IDownloadManager;
import io.github.jukomu.jmcomic.api.download.task.BaseDownloadTask;
import io.github.jukomu.jmcomic.api.model.JmAlbum;
import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.api.model.JmPhoto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static io.github.jukomu.desktop.util.LogFields.clean;
import static io.github.jukomu.desktop.util.RequestValidation.requiredText;
import static io.github.jukomu.desktop.util.TextUtils.text;

/**
 * Desktop 下载任务的持久化状态机与 JMComic 运行时适配。
 */
public final class DownloadService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadService.class);
    private static final long LOG_HEARTBEAT_NANOS = TimeUnit.SECONDS.toNanos(5);
    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_DOWNLOADING = "downloading";
    public static final String STATUS_PAUSED = "paused";
    public static final String STATUS_VERIFYING = "verifying";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    private static final String INTERRUPTED_ERROR = "应用重启，下载或校验中断";

    private final DownloadStore store;
    private final DownloadFiles files;
    private final Supplier<JmClient> clientSupplier;
    private final Supplier<JmDownloadClient> downloadClientSupplier;
    private final Executor prepareExecutor;
    private final EventHub events;
    private final Object submitLock = new Object();
    private final ConcurrentHashMap<String, RuntimeTask> runtimes = new ConcurrentHashMap<>();
    private final Set<String> cancelledTaskIds = ConcurrentHashMap.newKeySet();
    private volatile boolean closing;

    public DownloadService(
        DownloadStore store,
        DownloadFiles files,
        JmClient client,
        JmDownloadClient downloadClient,
        Executor prepareExecutor,
        EventHub events
    ) {
        this(store, files, () -> client, () -> downloadClient, prepareExecutor, events);
    }

    public DownloadService(
        DownloadStore store,
        DownloadFiles files,
        Supplier<JmClient> clientSupplier,
        Supplier<JmDownloadClient> downloadClientSupplier,
        Executor prepareExecutor,
        EventHub events
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.files = Objects.requireNonNull(files, "files");
        this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
        this.downloadClientSupplier = Objects.requireNonNull(
            downloadClientSupplier, "downloadClientSupplier");
        this.prepareExecutor = Objects.requireNonNull(prepareExecutor, "prepareExecutor");
        this.events = Objects.requireNonNull(events, "events");
    }

    public void reconcileOnStartup() {
        for (StoredDownloadTask task : store.listActiveTasks()) {
            String error = INTERRUPTED_ERROR;
            try {
                files.cleanup(task.relativeDirectory());
            } catch (RuntimeException exception) {
                error += "；残留文件清理失败: " + messageOf(exception);
            }
            store.interrupt(task.taskId(), error);
            logWarn(task, "interrupted", "interrupted", "PROCESS_INTERRUPTED", error, null, null);
        }
    }

    public DownloadSubmissionResponse downloadChapter(DownloadChapterRequest request) {
        if (closing) throw ApiException.unavailable("下载服务正在关闭");
        String albumId = requiredText(request.albumId(), "albumId");
        String chapterId = requiredText(request.chapterId(), "chapterId");
        String taskId = albumId + "_" + chapterId;
        String relativeDirectory;
        try {
            relativeDirectory = files.relativeDirectory(albumId, chapterId);
        } catch (IllegalArgumentException exception) {
            throw ApiException.invalidRequest(exception.getMessage());
        }
        requireClient();
        requireDownloadClient();

        RuntimeTask runtime = new RuntimeTask();
        synchronized (files) {
            synchronized (submitLock) {
                StoredDownloadTask existing = store.findTask(taskId);
                if (existing != null && STATUS_COMPLETED.equals(existing.status())) {
                    throw ApiException.conflict("该章节已下载完成");
                }
                if (existing != null && isActive(existing.status())) {
                    throw ApiException.conflict("该章节已在下载队列中");
                }
                files.cleanup(relativeDirectory);
                cancelledTaskIds.remove(taskId);
                store.createOrResetTask(
                    taskId,
                    albumId,
                    chapterId,
                    text(request.albumTitle()),
                    text(request.chapterTitle()),
                    text(request.coverUrl()),
                    relativeDirectory,
                    System.currentTimeMillis()
                );
                runtimes.put(taskId, runtime);
                logInfo("submit", taskId, albumId, chapterId, "submit", null, null, 0,
                    null, "queued", null, 0);
                publish(store.findTask(taskId), 0, null);
                try {
                    prepareExecutor.execute(() -> prepare(taskId, runtime));
                } catch (RejectedExecutionException exception) {
                    runtimes.remove(taskId, runtime);
                    store.fail(taskId, 0, 0, 0, "下载准备队列已满");
                    logError("failed", taskId, albumId, chapterId, "submit", "QUEUE_REJECTED",
                        "下载准备队列已满", 0, 0, 0);
                    publish(store.findTask(taskId), 0, null);
                    throw ApiException.unavailable("下载准备队列已满");
                }
            }
        }
        return new DownloadSubmissionResponse(taskId);
    }

    public DownloadTasksResponse getDownloadTasks() {
        return new DownloadTasksResponse(
            store.listTasks().stream().map(this::response).toList(),
            files.usedBytes(),
            files.availableBytes()
        );
    }

    public void pauseDownload(String taskId) {
        StoredDownloadTask task = requireTask(taskId);
        if (!STATUS_DOWNLOADING.equals(task.status())) {
            throw ApiException.conflict("只有下载中的任务可以暂停");
        }
        requireDownloadClient().downloadManager().pause(requireLibraryTask(taskId));
    }

    public void resumeDownload(String taskId) {
        StoredDownloadTask task = requireTask(taskId);
        if (!STATUS_PAUSED.equals(task.status())) {
            throw ApiException.conflict("只有已暂停的任务可以继续");
        }
        requireDownloadClient().downloadManager().resume(requireLibraryTask(taskId));
    }

    public void cancelDownload(String taskId) {
        StoredDownloadTask task = store.findTask(requiredText(taskId, "taskId"));
        if (task == null) return;
        if (STATUS_VERIFYING.equals(task.status())) {
            throw ApiException.conflict("校验中的任务不能取消");
        }

        cancelledTaskIds.add(taskId);
        RuntimeTask runtime = runtimes.get(taskId);
        if (runtime != null) {
            synchronized (runtime) {
                String libraryTaskId = runtime.libraryTaskId;
                if (libraryTaskId != null) {
                    requireDownloadClient().downloadManager().cancel(libraryTaskId);
                }
            }
            try {
                runtime.stopped.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException exception) {
                throw ApiException.conflict("底层下载任务仍在取消，请稍后重试");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw ApiException.unavailable("等待下载任务取消时被中断");
            } catch (Exception exception) {
                throw new IllegalStateException("等待下载任务取消失败", exception);
            }
        }

        try {
            long cancelElapsed = elapsed(taskId);
            files.cleanup(task.relativeDirectory());
            store.deleteTask(taskId);
            if (runtime == null) runtimes.remove(taskId);
            else runtimes.remove(taskId, runtime);
            publishCancelled(task);
            logInfo("cancelled", task.taskId(), task.albumId(), task.chapterId(), "downloading", null,
                runtime == null ? null : runtime.libraryTaskId, task.downloadedPages(),
                task.downloadedBytes(), "cancelled", task, cancelElapsed);
        } finally {
            cancelledTaskIds.remove(taskId);
        }
    }

    public void deleteDownloaded(String albumId, String chapterId) {
        requiredText(albumId, "albumId");
        requiredText(chapterId, "chapterId");
        StoredDownloadTask task = store.findTask(albumId, chapterId);
        if (task == null) return;
        if (isActive(task.status())) {
            cancelDownload(task.taskId());
            return;
        }
        files.cleanup(task.relativeDirectory());
        store.deleteTask(task.taskId());
    }

    public PhotoResponse getDownloadedPhoto(String albumId, String chapterId) {
        StoredDownloadTask task = store.findTask(requiredText(albumId, "albumId"), requiredText(chapterId, "chapterId"));
        if (task == null) throw ApiException.notFound("下载任务不存在");
        if (!STATUS_COMPLETED.equals(task.status())) {
            throw ApiException.conflict("章节尚未下载完成");
        }
        List<StoredDownloadPage> pages = store.pages(task.taskId());
        if (pages.isEmpty() || pages.stream().anyMatch(page -> !page.completed()
            || !Files.isRegularFile(files.resolvePage(page)))) {
            throw ApiException.notFound("已下载章节文件不完整");
        }
        return new PhotoResponse(
            task.chapterId(),
            task.chapterTitle(),
            task.albumId(),
            task.chapterSortOrder(),
            task.author(),
            JsonUtils.parseJsonStringListOrEmpty(task.tagsJson()),
            pages.stream().map(DownloadService::imageResponse).toList(),
            Boolean.TRUE.equals(task.isSingleEpisode())
        );
    }

    public Optional<Path> findCompletedImage(String photoId, int sortOrder) {
        StoredDownloadPage page = store.findCompletedPage(photoId, sortOrder);
        if (page == null) return Optional.empty();
        Path path = files.resolvePage(page);
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    void markDownloading(String taskId) {
        if (ignoreCallbacks(taskId)) return;
        store.updateStatus(taskId, STATUS_DOWNLOADING, null);
        logState(taskId, "downloading", "downloading", null);
        publish(store.findTask(taskId), 0, null);
    }

    void markPaused(String taskId, int completedPages, long downloadedBytes) {
        if (ignoreCallbacks(taskId)) return;
        store.updateProgress(taskId, completedPages, downloadedBytes);
        store.updateStatus(taskId, STATUS_PAUSED, null);
        logState(taskId, "paused", "downloading", null);
        publish(store.findTask(taskId), 0, null);
    }

    void updateProgress(String taskId, int completedPages, long downloadedBytes,
                        long speed, Long totalBytes) {
        if (ignoreCallbacks(taskId)) return;
        store.updateProgress(taskId, completedPages, downloadedBytes);
        RuntimeTask runtime = runtimes.get(taskId);
        if (runtime != null) logProgress(taskId, completedPages, downloadedBytes, speed,
            totalBytes, runtime);
        publish(store.findTask(taskId), speed, totalBytes);
    }

    void finishDownload(String taskId) {
        if (ignoreCallbacks(taskId)) {
            signalStopped(taskId);
            return;
        }
        StoredDownloadTask task = store.findTask(taskId);
        if (task == null) {
            signalStopped(taskId);
            return;
        }
        try {
            store.updateStatus(taskId, STATUS_VERIFYING, null);
            logState(taskId, "verifying", "verifying", null);
            publish(store.findTask(taskId), 0, null);
            ChapterManifestValidator.Report inspection = ChapterManifestValidator.validate(
                store, files, task.albumId(), task.chapterId());
            store.complete(taskId, inspection.totalPages(), inspection.firstSortOrder(),
                inspection.totalSize(), System.currentTimeMillis());
            logInfo("completed", taskId, task.albumId(), task.chapterId(), "completed", null,
                null, inspection.totalPages(), inspection.totalSize(), "completed", task,
                elapsed(taskId));
            publish(store.findTask(taskId), 0, inspection.totalSize());
        } catch (ChapterManifestValidator.ValidationException exception) {
            failDownload(taskId, exception.verifiedPages(), task.downloadedBytes(),
                "下载校验失败: " + exception.code() + ": " + exception.getMessage());
        } catch (RuntimeException exception) {
            failDownload(taskId, task.downloadedPages(), task.downloadedBytes(),
                "下载校验失败: " + messageOf(exception));
        } finally {
            finishRuntime(taskId);
        }
    }

    void failDownload(String taskId, int completedPages, long downloadedBytes, String error) {
        if (ignoreCallbacks(taskId)) {
            signalStopped(taskId);
            return;
        }
        StoredDownloadTask task = store.findTask(taskId);
        if (task == null) {
            signalStopped(taskId);
            return;
        }
        long totalSize = 0;
        String failure = error == null || error.isBlank() ? "下载失败" : error;
        try {
            totalSize = files.directorySize(task.relativeDirectory());
        } catch (RuntimeException exception) {
            failure += "；统计残留文件失败: " + messageOf(exception);
        }
        try {
            store.fail(taskId, completedPages, downloadedBytes, totalSize, failure);
            String phase = switch (task.status()) {
                case STATUS_VERIFYING -> STATUS_VERIFYING;
                case STATUS_DOWNLOADING -> STATUS_DOWNLOADING;
                default -> "prepare";
            };
            logError("failed", taskId, task.albumId(), task.chapterId(), phase, "DOWNLOAD_FAILED",
                failure, elapsed(taskId), completedPages, downloadedBytes);
            publish(store.findTask(taskId), 0, totalSize);
        } finally {
            finishRuntime(taskId);
        }
    }

    boolean isCancelled(String taskId) {
        return cancelledTaskIds.contains(taskId);
    }

    boolean isClosing() {
        return closing;
    }

    void signalStopped(String taskId) {
        RuntimeTask runtime = runtimes.get(taskId);
        if (runtime != null) runtime.stopped.complete(null);
    }

    @Override
    public void close() {
        closing = true;
    }

    private void prepare(String taskId, RuntimeTask runtime) {
        try {
            StoredDownloadTask task = store.findTask(taskId);
            if (task == null || cancelledTaskIds.contains(taskId) || closing) return;
            JmPhoto photo = requireClient().getPhoto(task.chapterId());
            if (photo == null || !task.chapterId().equals(photo.getId())
                || !(task.albumId().equals(photo.getAlbumId())
                || photo.isSingleAlbum() && task.albumId().equals(photo.getId()))) {
                throw new IllegalStateException("远端章节信息与请求不一致");
            }
            List<JmImage> images = photo.getImages() == null ? List.of() : List.copyOf(photo.getImages());
            if (images.isEmpty()) throw new IllegalStateException("章节没有可下载的图片");
            JmAlbum album = null;
            try {
                JmAlbum candidate = requireClient().getAlbum(task.albumId());
                if (candidate != null && task.albumId().equals(candidate.getId())) album = candidate;
            } catch (RuntimeException exception) {
                LOGGER.debug("读取作品元数据失败，使用章节元数据", exception);
            }
            List<String> authors = album != null && album.getAuthors() != null && !album.getAuthors().isEmpty()
                ? album.getAuthors() : text(photo.getAuthor()).isBlank() ? List.of() : List.of(photo.getAuthor());
            logInfo("remote_metadata", taskId, task.albumId(), task.chapterId(), "remote_metadata",
                null, null, images.size(), null, "queued", task, elapsed(taskId));

            synchronized (runtime) {
                if (cancelledTaskIds.contains(taskId) || closing || store.findTask(taskId) == null) return;
                files.prepareChapter(task.relativeDirectory());
                List<StoredDownloadPage> pages = images.stream()
                    .map(image -> page(taskId, task.relativeDirectory(), image))
                    .toList();
                store.saveManifest(taskId, pages.size(), text(photo.getAuthor()),
                    JsonUtils.toJsonString(authors, "保存作者列表失败"),
                    JsonUtils.toJsonString(photo.getTags() == null ? List.of() : photo.getTags(), "保存章节标签失败"),
                    photo.getSortOrder(), photo.isSingleAlbum(), pages);
                store.updateMetadata(taskId, album == null ? task.albumTitle() : album.getTitle(),
                    photo.getTitle(), authors.isEmpty() ? text(photo.getAuthor()) : authors.get(0),
                    JsonUtils.toJsonString(authors, "保存作者列表失败"),
                    JsonUtils.toJsonString(album == null || album.getTags() == null ? List.of() : album.getTags(), "保存章节标签失败"),
                    photo.getSortOrder(), photo.isSingleAlbum());
                Path chapterDirectory = files.chapterDirectory(task.relativeDirectory());
                Path savePath = photo.isSingleAlbum() ? chapterDirectory : chapterDirectory.getParent();
                JmDownloadClient downloadClient = requireDownloadClient();
                BaseDownloadTask libraryTask = downloadClient.createDownloadTask(photo, savePath);
                runtime.libraryTaskId = libraryTask.getTaskId();
                logInfo("downloading", taskId, task.albumId(), task.chapterId(), "downloading",
                    null, runtime.libraryTaskId, images.size(), null, "downloading", task,
                    elapsed(taskId));
                libraryTask.addObserver(new DownloadObserver(taskId, pages.size(), this));
                downloadClient.downloadManager().submit(libraryTask);
            }
        } catch (RuntimeException exception) {
            if (!cancelledTaskIds.contains(taskId) && !closing) {
                failDownload(taskId, 0, 0, messageOf(exception));
            }
        } finally {
            if (cancelledTaskIds.contains(taskId) || closing) runtime.stopped.complete(null);
        }
    }

    private StoredDownloadPage page(String taskId, String relativeDirectory, JmImage image) {
        if (image == null || image.getSortOrder() <= 0) {
            throw new IllegalStateException("章节包含无效图片元数据");
        }
        String filename = image.getFilename();
        if (image.getPhotoId() == null || image.getPhotoId().isBlank()) {
            throw new IllegalStateException("图片缺少章节 ID");
        }
        try {
            return new StoredDownloadPage(
                taskId,
                image.getSortOrder(),
                text(image.getPhotoId()),
                filename,
                files.relativeImagePath(relativeDirectory, filename),
                text(image.getUrl()),
                text(image.getScrambleId()),
                text(image.getQueryParams()),
                false
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("图片文件名无效: " + filename, exception);
        }
    }

    private String requireLibraryTask(String taskId) {
        String requiredTaskId = requiredText(taskId, "taskId");
        RuntimeTask runtime = runtimes.get(requiredTaskId);
        if (runtime == null || runtime.libraryTaskId == null) {
            throw ApiException.conflict("底层下载任务尚未就绪");
        }
        IDownloadManager manager = requireDownloadClient().downloadManager();
        if (manager.getTask(runtime.libraryTaskId) == null) {
            throw ApiException.conflict("底层下载任务不存在");
        }
        return runtime.libraryTaskId;
    }

    private StoredDownloadTask requireTask(String taskId) {
        StoredDownloadTask task = store.findTask(requiredText(taskId, "taskId"));
        if (task == null) throw ApiException.notFound("下载任务不存在");
        return task;
    }

    private boolean ignoreCallbacks(String taskId) {
        return closing || cancelledTaskIds.contains(taskId);
    }

    private void finishRuntime(String taskId) {
        RuntimeTask runtime = runtimes.remove(taskId);
        if (runtime != null) runtime.stopped.complete(null);
    }

    private void publish(StoredDownloadTask task, long speed, Long totalBytes) {
        if (task == null || closing) return;
        events.publish("downloadProgress", new DownloadProgressEvent(
            task.taskId(), task.albumId(), task.chapterId(), task.downloadedPages(),
            task.totalPages(), task.status(), task.error(), speed, task.downloadedBytes(),
            totalBytes == null ? sizeOrNull(task.totalSize()) : totalBytes
        ));
    }

    private void publishCancelled(StoredDownloadTask task) {
        if (closing) return;
        events.publish("downloadProgress", new DownloadProgressEvent(
            task.taskId(), task.albumId(), task.chapterId(), task.downloadedPages(),
            task.totalPages(), "cancelled", null, 0, task.downloadedBytes(),
            sizeOrNull(task.totalSize())
        ));
    }

    public StoredDownloadTask prepareExportMetadata(StoredDownloadTask task) {
        boolean chapterMissing = text(task.chapterTitle()).isBlank() || task.isSingleEpisode() == null;
        List<String> authors = JsonUtils.parseJsonStringListOrEmpty(task.authorsJson());
        List<String> metadataTags = JsonUtils.parseJsonStringListOrEmpty(task.tagsJson());
        if (!chapterMissing && !text(task.albumTitle()).isBlank()
            && !authors.isEmpty() && !metadataTags.isEmpty()) return task;
        JmClient client = clientSupplier.get();
        JmPhoto photo = null;
        if (chapterMissing) {
            if (client == null) throw new IllegalStateException("下载记录缺少章节信息，需要联网补全");
            photo = client.getPhoto(task.chapterId());
            if (photo == null || !task.chapterId().equals(photo.getId())
                || !(task.albumId().equals(photo.getAlbumId())
                || photo.isSingleAlbum() && task.albumId().equals(photo.getId()))) {
                throw new IllegalStateException("远端章节信息与请求不一致");
            }
        }
        JmAlbum album = null;
        if (client != null) {
            try {
                album = client.getAlbum(task.albumId());
                if (album == null || !task.albumId().equals(album.getId())) {
                    LOGGER.debug("读取作品元数据失败，使用已有下载记录");
                }
            } catch (RuntimeException exception) {
                LOGGER.debug("读取作品元数据失败，使用已有下载记录", exception);
            }
        }
        String albumTitle = text(task.albumTitle());
        if (albumTitle.isBlank() && album != null) albumTitle = text(album.getTitle());
        String chapterTitle = photo == null ? text(task.chapterTitle()) : text(photo.getTitle());
        if (albumTitle.isBlank() || chapterTitle.isBlank()) {
            throw new IllegalStateException("下载记录缺少标题，无法补全导出元数据");
        }
        if (authors.isEmpty() && album != null && album.getAuthors() != null) authors = album.getAuthors();
        String author = text(task.author());
        if (author.isBlank() && photo != null) author = text(photo.getAuthor());
        if (authors.isEmpty() && !author.isBlank()) authors = List.of(author);
        if (authors.isEmpty() && album == null) {
            throw new IllegalStateException("下载记录缺少作者，需要联网补全");
        }
        if (metadataTags.isEmpty() && album != null && album.getTags() != null) metadataTags = album.getTags();
        return store.updateMetadata(task.taskId(), albumTitle, chapterTitle,
            authors.isEmpty() ? author : authors.get(0),
            JsonUtils.toJsonString(authors, "保存作者列表失败"),
            JsonUtils.toJsonString(metadataTags, "保存章节标签失败"),
            photo == null ? task.chapterSortOrder() : photo.getSortOrder(),
            photo == null ? Boolean.TRUE.equals(task.isSingleEpisode()) : photo.isSingleAlbum());
    }

    private static ImageResponse imageResponse(StoredDownloadPage page) {
        return new ImageResponse(page.photoId(), page.scrambleId(), page.filename(),
            page.sourceUrl(), page.queryParams(), page.sortOrder());
    }

    private DownloadTaskResponse response(StoredDownloadTask task) {
        return new DownloadTaskResponse(
            task.taskId(), task.albumId(), task.chapterId(), task.albumTitle(), task.chapterTitle(),
            task.coverUrl(), task.author(), JsonUtils.parseJsonStringListOrEmpty(task.authorsJson()),
            JsonUtils.parseJsonStringListOrEmpty(task.tagsJson()),
            task.firstImageSortOrder(), task.chapterSortOrder(), task.isSingleEpisode(),
            task.totalPages(), task.downloadedPages(), task.status(), task.createdAt(), task.completedAt(),
            task.error(), task.downloadedBytes(), task.totalSize()
        );
    }

    private static boolean isActive(String status) {
        return STATUS_QUEUED.equals(status) || STATUS_DOWNLOADING.equals(status)
            || STATUS_PAUSED.equals(status) || STATUS_VERIFYING.equals(status);
    }

    private static Long sizeOrNull(long size) {
        return size > 0 ? size : null;
    }

    private static String messageOf(Throwable failure) {
        if (failure == null) return "下载失败";
        String message = failure.getMessage();
        return message == null || message.isBlank() ? "下载失败" : message;
    }

    private void logState(String taskId, String event, String phase, String errorCode) {
        StoredDownloadTask task = store.findTask(taskId);
        if (task == null) return;
        logInfo(event, taskId, task.albumId(), task.chapterId(), phase, errorCode, null,
            task.downloadedPages(), task.downloadedBytes(), task.status(), task, elapsed(taskId));
    }

    void logObserverState(String event, BaseDownloadTask task, String phase, String status) {
        StoredDownloadTask stored = store.findTask(taskIdOf(task));
        if (stored == null) return;
        logInfo(event, stored.taskId(), stored.albumId(), stored.chapterId(), phase, null,
            task.getTaskId(), task.getCompletedCount(), task.getDownloadedBytes(), status,
            stored, elapsed(stored.taskId()));
    }

    void logObserverFailure(BaseDownloadTask task, String code, Throwable error) {
        StoredDownloadTask stored = store.findTask(taskIdOf(task));
        if (stored == null) return;
        logError("failed", stored.taskId(), stored.albumId(), stored.chapterId(), "downloading",
            code, messageOf(error), elapsed(stored.taskId()), task.getCompletedCount(),
            task.getDownloadedBytes());
    }

    void logObserverProgress(BaseDownloadTask task, int page, long bytes, long speed, Long totalBytes) {
        StoredDownloadTask stored = store.findTask(taskIdOf(task));
        RuntimeTask runtime = stored == null ? null : runtimes.get(stored.taskId());
        if (stored != null && runtime != null && runtime.shouldLog(page)) {
            logInfo("progress", stored.taskId(), stored.albumId(), stored.chapterId(), "downloading",
                null, task.getTaskId(), page, bytes, "downloading", stored, elapsed(stored.taskId()),
                speed, totalBytes);
        }
    }

    private String taskIdOf(BaseDownloadTask task) {
        for (var entry : runtimes.entrySet()) {
            if (Objects.equals(entry.getValue().libraryTaskId, task.getTaskId())) return entry.getKey();
        }
        return task.getTaskId();
    }

    private long elapsed(String taskId) {
        RuntimeTask runtime = runtimes.get(taskId);
        return runtime == null ? 0 : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - runtime.startedNanos);
    }

    private void logProgress(String taskId, int page, long bytes, long speed, Long totalBytes,
                             RuntimeTask runtime) {
        if (!runtime.shouldLog(page)) return;
        StoredDownloadTask task = store.findTask(taskId);
        if (task != null) logInfo("progress", taskId, task.albumId(), task.chapterId(), "downloading",
            null, runtime.libraryTaskId, page, bytes, task.status(), task, elapsed(taskId),
            speed, totalBytes);
    }

    private void logInfo(String event, String taskId, String albumId, String chapterId,
                         String phase, String errorCode, String libraryTaskId, int page,
                         Long bytes, String status, StoredDownloadTask task, long elapsedMs,
                         Object... extra) {
        StringBuilder line = new StringBuilder("download event=").append(clean(event))
            .append(" taskId=").append(clean(taskId)).append(" albumId=").append(clean(albumId))
            .append(" chapterId=").append(clean(chapterId));
        append(line, "phase", phase);
        append(line, "status", status);
        append(line, "elapsedMs", elapsedMs);
        append(line, "page", page);
        if (task != null) append(line, "totalPages", task.totalPages());
        append(line, "bytes", bytes);
        append(line, "libraryTaskId", libraryTaskId);
        append(line, "errorCode", errorCode);
        if (extra.length > 0 && extra[0] instanceof Long speed) append(line, "speed", speed);
        if (extra.length > 1 && extra[1] instanceof Long totalBytes) append(line, "totalBytes", totalBytes);
        LOGGER.info(line.toString());
    }

    private void logWarn(StoredDownloadTask task, String event, String phase, String code,
                         String message, Long page, Long bytes) {
        String line = base(task, event, phase, "failed", 0, page, bytes, code, null);
        LOGGER.warn(line + " message=" + clean(message));
    }

    private void logError(String event, String taskId, String albumId, String chapterId, String phase,
                          String code, String message, long elapsedMs, int page, long bytes) {
        LOGGER.error("download event={} taskId={} albumId={} chapterId={} phase={} status=failed elapsedMs={} page={} bytes={} errorCode={} message={}",
            clean(event), clean(taskId), clean(albumId), clean(chapterId), clean(phase), elapsedMs,
            page, bytes, clean(code), clean(message));
    }

    private String base(StoredDownloadTask task, String event, String phase, String status,
                        long elapsedMs, Long page, Long bytes, String code, String libraryTaskId) {
        return "download event=" + clean(event) + " taskId=" + clean(task.taskId())
            + " albumId=" + clean(task.albumId()) + " chapterId=" + clean(task.chapterId())
            + " phase=" + clean(phase) + " status=" + clean(status) + " elapsedMs=" + elapsedMs
            + " page=" + (page == null ? "-" : page) + " totalPages=" + task.totalPages()
            + " bytes=" + (bytes == null ? "-" : bytes) + " errorCode=" + clean(code)
            + (libraryTaskId == null ? "" : " libraryTaskId=" + clean(libraryTaskId));
    }

    private static void append(StringBuilder line, String key, Object value) {
        if (value != null && (!(value instanceof String string) || !string.isBlank())) {
            line.append(' ').append(key).append('=').append(clean(String.valueOf(value)));
        }
    }

    private JmClient requireClient() {
        JmClient client = clientSupplier.get();
        if (client == null) throw ApiException.unavailable("在线客户端不可用");
        return client;
    }

    private JmDownloadClient requireDownloadClient() {
        JmDownloadClient client = downloadClientSupplier.get();
        if (client == null) throw ApiException.unavailable("在线客户端不可用");
        return client;
    }

    private static final class RuntimeTask {
        private final CompletableFuture<Void> stopped = new CompletableFuture<>();
        private final long startedNanos = System.nanoTime();
        private long lastLogNanos;
        private int lastLoggedPage;
        private volatile String libraryTaskId;

        private synchronized boolean shouldLog(int page) {
            long now = System.nanoTime();
            if (lastLogNanos != 0 && now - lastLogNanos < LOG_HEARTBEAT_NANOS
                && page - lastLoggedPage < 25) return false;
            lastLogNanos = now;
            lastLoggedPage = page;
            return true;
        }
    }
}
