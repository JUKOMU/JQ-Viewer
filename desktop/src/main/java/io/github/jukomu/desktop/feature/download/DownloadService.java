package io.github.jukomu.desktop.feature.download;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.desktop.feature.catalog.model.ImageResponse;
import io.github.jukomu.desktop.feature.catalog.model.PhotoResponse;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.download.data.StoredDownloadPage;
import io.github.jukomu.desktop.feature.download.data.StoredDownloadTask;
import io.github.jukomu.desktop.feature.download.model.DownloadChapterRequest;
import io.github.jukomu.desktop.feature.download.model.DownloadProgressEvent;
import io.github.jukomu.desktop.feature.download.model.DownloadSubmissionResponse;
import io.github.jukomu.desktop.feature.download.model.DownloadTaskResponse;
import io.github.jukomu.desktop.feature.download.model.DownloadTasksResponse;
import io.github.jukomu.jmcomic.api.client.JmClient;
import io.github.jukomu.jmcomic.api.client.JmDownloadClient;
import io.github.jukomu.jmcomic.api.download.IDownloadManager;
import io.github.jukomu.jmcomic.api.download.task.BaseDownloadTask;
import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.api.model.JmPhoto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Desktop 下载任务的持久化状态机与 JMComic 运行时适配。 */
public final class DownloadService implements AutoCloseable {
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
    private final ObjectMapper mapper;
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
            EventHub events,
            ObjectMapper mapper
    ) {
        this(store, files, () -> client, () -> downloadClient, prepareExecutor, events, mapper);
    }

    public DownloadService(
            DownloadStore store,
            DownloadFiles files,
            Supplier<JmClient> clientSupplier,
            Supplier<JmDownloadClient> downloadClientSupplier,
            Executor prepareExecutor,
            EventHub events,
            ObjectMapper mapper
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.files = Objects.requireNonNull(files, "files");
        this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
        this.downloadClientSupplier = Objects.requireNonNull(
                downloadClientSupplier, "downloadClientSupplier");
        this.prepareExecutor = Objects.requireNonNull(prepareExecutor, "prepareExecutor");
        this.events = Objects.requireNonNull(events, "events");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
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
        }
    }

    public DownloadSubmissionResponse downloadChapter(DownloadChapterRequest request) {
        if (closing) throw ApiException.unavailable("下载服务正在关闭");
        String albumId = required(request.albumId(), "albumId");
        String chapterId = required(request.chapterId(), "chapterId");
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
                publish(store.findTask(taskId), 0, null);
                try {
                    prepareExecutor.execute(() -> prepare(taskId, runtime));
                } catch (RejectedExecutionException exception) {
                    runtimes.remove(taskId, runtime);
                    store.fail(taskId, 0, 0, 0, "下载准备队列已满");
                    publish(store.findTask(taskId), 0, null);
                    throw ApiException.unavailable("下载准备队列已满");
                }
            }
        }
        return new DownloadSubmissionResponse(taskId);
    }

    public DownloadTasksResponse getDownloadTasks() {
        return new DownloadTasksResponse(
                store.listTasks().stream().map(DownloadService::response).toList(),
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
        StoredDownloadTask task = store.findTask(required(taskId, "taskId"));
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
            files.cleanup(task.relativeDirectory());
            store.deleteTask(taskId);
            if (runtime == null) runtimes.remove(taskId);
            else runtimes.remove(taskId, runtime);
            publishCancelled(task);
        } finally {
            cancelledTaskIds.remove(taskId);
        }
    }

    public void deleteDownloaded(String albumId, String chapterId) {
        required(albumId, "albumId");
        required(chapterId, "chapterId");
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
        StoredDownloadTask task = store.findTask(required(albumId, "albumId"), required(chapterId, "chapterId"));
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
                tags(task.tagsJson()),
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
        publish(store.findTask(taskId), 0, null);
    }

    void markPaused(String taskId, int completedPages, long downloadedBytes) {
        if (ignoreCallbacks(taskId)) return;
        store.updateProgress(taskId, completedPages, downloadedBytes);
        store.updateStatus(taskId, STATUS_PAUSED, null);
        publish(store.findTask(taskId), 0, null);
    }

    void updateProgress(String taskId, int completedPages, long downloadedBytes,
                        long speed, Long totalBytes) {
        if (ignoreCallbacks(taskId)) return;
        store.updateProgress(taskId, completedPages, downloadedBytes);
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
            publish(store.findTask(taskId), 0, null);
            List<StoredDownloadPage> pages = store.pages(taskId);
            DownloadFiles.ChapterInspection inspection = files.inspect(pages);
            store.complete(taskId, pages.size(), inspection.firstSortOrder(),
                    inspection.totalSize(), System.currentTimeMillis());
            publish(store.findTask(taskId), 0, inspection.totalSize());
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
            if (photo == null || !task.chapterId().equals(photo.getId())) {
                throw new IllegalStateException("远端章节信息与请求不一致");
            }
            List<JmImage> images = photo.getImages() == null ? List.of() : List.copyOf(photo.getImages());
            if (images.isEmpty()) throw new IllegalStateException("章节没有可下载的图片");

            synchronized (runtime) {
                if (cancelledTaskIds.contains(taskId) || closing || store.findTask(taskId) == null) return;
                files.prepareChapter(task.relativeDirectory());
                List<StoredDownloadPage> pages = images.stream()
                        .map(image -> page(taskId, task.relativeDirectory(), image))
                        .toList();
                store.saveManifest(taskId, pages.size(), text(photo.getAuthor()), tagsJson(photo.getTags()),
                        photo.getSortOrder(), photo.isSingleAlbum(), pages);
                Path chapterDirectory = files.chapterDirectory(task.relativeDirectory());
                Path savePath = photo.isSingleAlbum() ? chapterDirectory : chapterDirectory.getParent();
                JmDownloadClient downloadClient = requireDownloadClient();
                BaseDownloadTask libraryTask = downloadClient.createDownloadTask(photo, savePath);
                runtime.libraryTaskId = libraryTask.getTaskId();
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
        String requiredTaskId = required(taskId, "taskId");
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
        StoredDownloadTask task = store.findTask(required(taskId, "taskId"));
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

    private String tagsJson(List<String> tags) {
        try {
            return mapper.writeValueAsString(tags == null ? List.of() : tags);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("保存章节标签失败", exception);
        }
    }

    private List<String> tags(String tagsJson) {
        try {
            return mapper.readValue(tagsJson, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private static ImageResponse imageResponse(StoredDownloadPage page) {
        return new ImageResponse(page.photoId(), page.scrambleId(), page.filename(),
                page.sourceUrl(), page.queryParams(), page.sortOrder());
    }

    private static DownloadTaskResponse response(StoredDownloadTask task) {
        return new DownloadTaskResponse(
                task.taskId(), task.albumId(), task.chapterId(), task.albumTitle(), task.chapterTitle(),
                task.coverUrl(), task.firstImageSortOrder(), task.chapterSortOrder(), task.isSingleEpisode(),
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

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw ApiException.invalidRequest(name + "不能为空");
        return value;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String messageOf(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? "下载失败" : message;
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
        private volatile String libraryTaskId;
    }
}
