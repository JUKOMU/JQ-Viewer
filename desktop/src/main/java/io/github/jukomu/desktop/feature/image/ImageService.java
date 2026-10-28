package io.github.jukomu.desktop.feature.image;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.feature.download.validation.ImageFileValidator;
import io.github.jukomu.desktop.feature.image.model.ImageEvent;
import io.github.jukomu.desktop.feature.image.model.PreloadImagesResponse;
import io.github.jukomu.jmcomic.api.client.JmClient;
import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.api.model.JmPhoto;
import io.github.jukomu.jmcomic.core.crypto.JmImageTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static io.github.jukomu.desktop.util.TextUtils.text;

/**
 * 管理章节图片元数据、实际下载、缩略图生成和事件发布。
 */
public final class ImageService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageService.class);
    private static final int THUMBNAIL_MAX_WIDTH = 300;
    private static final long CACHE_CAPACITY_MB = 256;
    private static final long SLOW_OPERATION_MS = 500L;

    private final Supplier<JmClient> clientSupplier;
    private final Executor executor;
    private final EventHub events;
    private final BiFunction<String, Integer, Optional<Path>> localImageFinder;
    private final ImageCache cache = new ImageCache(CACHE_CAPACITY_MB * 1024 * 1024);
    private final Map<String, Map<Integer, JmImage>> images = new ConcurrentHashMap<>();
    private final Map<String, Long> generations = new ConcurrentHashMap<>();
    private final Object preloadLock = new Object();
    private final PriorityQueue<PreloadTask> waiting = new PriorityQueue<>(
        Comparator.comparingInt((PreloadTask task) -> task.priority).reversed()
            .thenComparingLong(task -> task.sequence));
    private final Set<PreloadRun> running = new HashSet<>();
    private final Map<String, PreloadTask> pending = new HashMap<>();
    private final Set<String> cancelledSessions = new HashSet<>();
    private final AtomicInteger detachedThreads = new AtomicInteger();
    private long generation;
    private long sequence;
    private boolean closed;

    public ImageService(JmClient client, Executor executor, EventHub events) {
        this(() -> client, executor, events, (photoId, sortOrder) -> Optional.empty());
    }

    public ImageService(Supplier<JmClient> clientSupplier, Executor executor, EventHub events) {
        this(clientSupplier, executor, events, (photoId, sortOrder) -> Optional.empty());
    }

    public ImageService(
        Supplier<JmClient> clientSupplier,
        Executor executor,
        EventHub events,
        BiFunction<String, Integer, Optional<Path>> localImageFinder
    ) {
        this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
        this.localImageFinder = Objects.requireNonNull(localImageFinder, "localImageFinder");
        ImageIO.scanForPlugins();
    }

    public void register(JmPhoto photo) {
        Map<Integer, JmImage> photoImages = new ConcurrentHashMap<>();
        for (JmImage image : safe(photo.getImages())) photoImages.put(image.getSortOrder(), image);
        images.put(photo.getId(), photoImages);
    }

    public PreloadImagesResponse preload(
        String photoId,
        String type,
        List<JmImage> input,
        boolean replacePending,
        String sessionId,
        int priority
    ) {
        if (closed) throw ApiException.unavailable("图片预载服务已关闭");
        validateType(type);
        if (input == null) throw ApiException.invalidRequest("images必须是数组");
        Map<Integer, JmImage> photoImages = images.computeIfAbsent(photoId, ignored -> new ConcurrentHashMap<>());
        String taskSession = sessionId == null ? "legacy-" + UUID.randomUUID() : requiredSession(sessionId);
        String scope = scopeKey(photoId, type, taskSession);
        long currentGeneration;
        synchronized (this) {
            currentGeneration = replacePending ? ++generation : generations.getOrDefault(scope, 0L);
            if (replacePending) generations.put(scope, currentGeneration);
        }

        if (replacePending) cancelScope(scope, currentGeneration);

        List<Integer> cached = new ArrayList<>();
        List<Integer> waiting = new ArrayList<>();
        for (JmImage value : input) {
            if (value == null) throw ApiException.invalidRequest("images包含无效元素");
            JmImage image = toImage(photoId, value);
            photoImages.put(image.getSortOrder(), image);
            String cacheKey = key(photoId, image.getSortOrder(), type);
            if (cache.get(cacheKey) != null) {
                cached.add(image.getSortOrder());
                continue;
            }
            ImageCache.Entry original = cache.get(key(photoId, image.getSortOrder(), "image"));
            if ("thumb".equals(type) && original != null) {
                waiting.add(image.getSortOrder());
                scheduleCachedThumbnail(
                    photoId, image.getSortOrder(), currentGeneration, taskSession,
                    priority, original);
                continue;
            }
            waiting.add(image.getSortOrder());
            schedule(photoId, type, image, currentGeneration, taskSession,
                priority, true, true);
        }
        LOGGER.info("image.preload event=scheduled photoId={} type={} requested={} cached={} pending={} replacePending={}",
            clean(photoId), type, input.size(), cached.size(), waiting.size(), replacePending);
        return new PreloadImagesResponse(List.copyOf(cached), List.copyOf(waiting));
    }

    public PreloadImagesResponse preload(
        String photoId, String type, List<JmImage> input, boolean replacePending
    ) {
        return preload(photoId, type, input, replacePending, "legacy-" + UUID.randomUUID(), 100);
    }

    public SuccessResponse cancelPreload(String sessionId) {
        String targetSession = requiredSession(sessionId);
        synchronized (preloadLock) {
            if (closed) return SuccessResponse.ok();
            cancelledSessions.add(targetSession);
            waiting.removeIf(task -> {
                if (!task.sessionId.equals(targetSession)) return false;
                pending.remove(task.pendingId, task);
                task.state = TaskState.CANCELLED;
                return true;
            });
            for (PreloadRun run : List.copyOf(running)) {
                if (run.task.sessionId.equals(targetSession)) cancelRun(run, false);
            }
            pumpLocked();
        }
        return SuccessResponse.ok();
    }

    public SuccessResponse retry(String photoId, JmImage value, String sessionId, int priority) {
        if (closed) throw ApiException.unavailable("图片预载服务已关闭");
        if (value == null) throw ApiException.invalidRequest("image必须是对象");
        JmImage image = toImage(photoId, value);
        images.computeIfAbsent(photoId, ignored -> new ConcurrentHashMap<>()).put(image.getSortOrder(), image);
        String taskSession = sessionId == null ? "legacy-" + UUID.randomUUID() : requiredSession(sessionId);
        String scope = scopeKey(photoId, "image", taskSession);
        long currentGeneration = generations.getOrDefault(scope, 0L);
        cache.remove(key(photoId, image.getSortOrder(), "image"));
        cache.remove(key(photoId, image.getSortOrder(), "thumb"));
        synchronized (preloadLock) {
            PreloadTask old = pending.get(pendingId(taskSession,
                key(photoId, image.getSortOrder(), "image")));
            if (old != null) cancelTaskLocked(old);
        }
        schedule(photoId, "image", image, currentGeneration, taskSession,
            priority, false, false);
        return SuccessResponse.ok();
    }

    public SuccessResponse retry(String photoId, JmImage value) {
        return retry(photoId, value, "legacy-" + UUID.randomUUID(), 300);
    }

    public ImageCache.Entry read(String photoId, int sortOrder, String type) {
        validateType(type);
        ImageCache.Entry cached = cache.get(key(photoId, sortOrder, type));
        if (cached != null) {
            LOGGER.debug("image.read event=cache-hit photoId={} sortOrder={} type={} sizeBytes={}",
                clean(photoId), sortOrder, type, cached.bytes().length);
            return cached;
        }
        long startedNanos = System.nanoTime();
        JmImage image = images.getOrDefault(photoId, Map.of()).get(sortOrder);
        if (image == null) {
            try {
                JmPhoto photo = client().getPhoto(photoId);
                register(photo);
            } catch (RuntimeException exception) {
                LOGGER.warn("image.read event=failed photoId={} sortOrder={} type={} elapsedMs={} errorClass={}",
                    clean(photoId), sortOrder, type, elapsed(startedNanos),
                    exception.getClass().getSimpleName());
                throw exception;
            }
            image = images.getOrDefault(photoId, Map.of()).get(sortOrder);
        }
        if (image == null) {
            LOGGER.warn("image.read event=failed photoId={} sortOrder={} type={} elapsedMs={} errorClass=NotFound",
                clean(photoId), sortOrder, type, elapsed(startedNanos));
            throw new ApiException("not-found", 404, "图片不存在");
        }
        try {
            byte[] bytes = client().fetchImageBytes(image);
            if (!ImageFileValidator.validateQuick(bytes)) {
                throw new IOException("无法解析图片");
            }
            String mime = mime(image);
            cache.put(key(photoId, sortOrder, "image"), bytes, mime);
            if ("thumb".equals(type)) {
                byte[] thumb = thumbnail(bytes);
                cache.put(key(photoId, sortOrder, "thumb"), thumb, "image/jpeg");
                logSlowRead(photoId, sortOrder, type, startedNanos);
                return new ImageCache.Entry(thumb, "image/jpeg");
            }
            logSlowRead(photoId, sortOrder, type, startedNanos);
            return new ImageCache.Entry(bytes, mime);
        } catch (IOException exception) {
            LOGGER.warn("image.read event=failed photoId={} sortOrder={} type={} elapsedMs={} errorClass={}",
                clean(photoId), sortOrder, type, elapsed(startedNanos), exception.getClass().getSimpleName());
            throw new ApiException("internal", 502, "图片处理失败: " + exception.getMessage());
        } catch (RuntimeException exception) {
            LOGGER.warn("image.read event=failed photoId={} sortOrder={} type={} elapsedMs={} errorClass={}",
                clean(photoId), sortOrder, type, elapsed(startedNanos), exception.getClass().getSimpleName());
            throw exception;
        }
    }

    public ImageCache.Entry readLocal(
        String photoId,
        int sortOrder,
        String type,
        Path path
    ) {
        validateType(type);
        ImageCache.Entry cached = cache.get(key(photoId, sortOrder, type));
        if (cached != null) {
            LOGGER.debug("image.read-local event=cache-hit photoId={} sortOrder={} type={} sizeBytes={}",
                clean(photoId), sortOrder, type, cached.bytes().length);
            return cached;
        }
        long startedNanos = System.nanoTime();
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (!ImageFileValidator.validateQuick(bytes)) {
                throw new IOException("无法解析图片");
            }
            String mime = mime(path.getFileName().toString());
            if ("thumb".equals(type)) {
                byte[] thumb = thumbnail(bytes);
                cache.put(key(photoId, sortOrder, "image"), bytes, mime);
                cache.put(key(photoId, sortOrder, "thumb"), thumb, "image/jpeg");
                logSlowRead(photoId, sortOrder, type, startedNanos);
                return new ImageCache.Entry(thumb, "image/jpeg");
            }
            cache.put(key(photoId, sortOrder, "image"), bytes, mime);
            logSlowRead(photoId, sortOrder, type, startedNanos);
            return new ImageCache.Entry(bytes, mime);
        } catch (IOException exception) {
            LOGGER.warn("image.read-local event=failed photoId={} sortOrder={} type={} elapsedMs={} errorClass={}",
                clean(photoId), sortOrder, type, elapsed(startedNanos), exception.getClass().getSimpleName());
            throw new ApiException("internal", 500, "读取本地图片失败: " + exception.getMessage());
        } catch (RuntimeException exception) {
            LOGGER.warn("image.read-local event=failed photoId={} sortOrder={} type={} elapsedMs={} errorClass={}",
                clean(photoId), sortOrder, type, elapsed(startedNanos), exception.getClass().getSimpleName());
            throw exception;
        }
    }

    public ImageCache.Entry readCached(String photoId, int sortOrder, String type) {
        ImageCache.Entry cached = cache.get(key(photoId, sortOrder, type));
        if (cached != null) {
            LOGGER.debug("image.read-cached event=cache-hit photoId={} sortOrder={} type={} sizeBytes={}",
                clean(photoId), sortOrder, type, cached.bytes().length);
        }
        return cached;
    }

    public ImageCache cache() {
        return cache;
    }

    @Override
    public void close() {
        synchronized (preloadLock) {
            if (closed) return;
            closed = true;
            for (PreloadTask task : waiting) {
                task.state = TaskState.CANCELLED;
                pending.remove(task.pendingId, task);
            }
            waiting.clear();
            for (PreloadRun run : List.copyOf(running)) cancelRun(run, false);
            pending.clear();
        }
    }

    private void schedule(
        String photoId,
        String type,
        JmImage image,
        long currentGeneration,
        String sessionId,
        int priority,
        boolean preferLocal,
        boolean publishEvents
    ) {
        String scope = photoId + "/" + type;
        String cacheKey = key(photoId, image.getSortOrder(), type);
        String pendingId = pendingId(sessionId, cacheKey);
        synchronized (preloadLock) {
            if (closed) throw ApiException.unavailable("图片预载服务已关闭");
            PreloadTask old = pending.get(pendingId);
            if (old != null && old.generation == currentGeneration && old.sessionId.equals(sessionId)) return;
            if (old != null) cancelTaskLocked(old);
            PreloadTask task = new PreloadTask(
                photoId, type, image, currentGeneration, sessionId, priority,
                preferLocal, publishEvents, cacheKey, pendingId, null, ++sequence);
            pending.put(pendingId, task);
            waiting.add(task);
            pumpLocked();
        }
    }

    private void scheduleCachedThumbnail(
        String photoId,
        int sortOrder,
        long currentGeneration,
        String sessionId,
        int priority,
        ImageCache.Entry original
    ) {
        String scope = photoId + "/thumb";
        String cacheKey = key(photoId, sortOrder, "thumb");
        String pendingId = pendingId(sessionId, cacheKey);
        synchronized (preloadLock) {
            if (closed) throw ApiException.unavailable("图片预载服务已关闭");
            PreloadTask old = pending.get(pendingId);
            if (old != null && old.generation == currentGeneration && old.sessionId.equals(sessionId)) return;
            if (old != null) cancelTaskLocked(old);
            PreloadTask task = new PreloadTask(
                photoId, "thumb", new JmImage(photoId, "", "", "", "", sortOrder),
                currentGeneration, sessionId, priority, false, true,
                cacheKey, pendingId, original, ++sequence);
            pending.put(pendingId, task);
            waiting.add(task);
            pumpLocked();
        }
    }

    private void pumpLocked() {
        if (closed) return;
        while (true) {
            waiting.removeIf(task -> !isValidLocked(task));
            if (waiting.isEmpty()) break;
            if (running.size() < maxConcurrency()) {
                startLocked(waiting.poll());
                continue;
            }
            PreloadTask next = waiting.peek();
            PreloadRun lowest = running.stream().min(
                Comparator.comparingInt((PreloadRun run) -> run.task.priority)
                    .thenComparing(Comparator.comparingLong(
                        (PreloadRun run) -> run.task.sequence).reversed())).orElseThrow();
            if (next.priority <= lowest.task.priority) break;
            cancelRun(lowest, true);
        }
    }

    private void startLocked(PreloadTask task) {
        if (!isValidLocked(task)) return;
        PreloadRun run = new PreloadRun(task, ++task.runId);
        task.state = TaskState.RUNNING;
        running.add(run);
        task.thread = new Thread(() -> execute(task, run.runId),
            "jq-viewer-image-preload-" + task.sequence + "-" + run.runId);
        task.thread.setDaemon(true);
        task.thread.start();
    }

    private int maxConcurrency() {
        return executor instanceof ThreadPoolExecutor pool
            ? pool.getMaximumPoolSize()
            : io.github.jukomu.desktop.feature.settings.SettingsService.DEFAULT_CONCURRENCY;
    }

    private void execute(PreloadTask task, long runId) {
        long startedNanos = System.nanoTime();
        try {
            if (!isActive(task, runId)) return;
            if (task.original != null) {
                byte[] thumb = thumbnail(task.original.bytes());
                storeIfActive(task, runId, null, null, thumb);
                return;
            }
            if (task.preferLocal) {
                Optional<Path> local = findLocalImage(task.photoId, task.image.getSortOrder());
                if (local.isPresent() && ImageFileValidator.validateQuick(local.get())) {
                    try {
                        byte[] bytes = Files.readAllBytes(local.get());
                        if (!ImageFileValidator.validateQuick(bytes)) throw new IOException("无法解析本地图片");
                        byte[] thumb = "thumb".equals(task.type) ? thumbnail(bytes) : null;
                        storeIfActive(task, runId, bytes, mime(local.get().getFileName().toString()), thumb);
                        logSlowPreload(task.photoId, task.image.getSortOrder(), task.type,
                            startedNanos, "local");
                        return;
                    } catch (IOException ignored) {
                        // 本地文件不可读时按 Android 语义回退到网络来源。
                    }
                }
            }
            byte[] bytes = client().fetchImageBytes(task.image);
            if (!isActive(task, runId)) return;
            if (!ImageFileValidator.validateQuick(bytes)) throw new IOException("无法解析图片");
            String mime = mime(task.image);
            byte[] thumb = "thumb".equals(task.type) ? thumbnail(bytes) : null;
            storeIfActive(task, runId, bytes, mime, thumb);
            logSlowPreload(task.photoId, task.image.getSortOrder(), task.type, startedNanos, "remote");
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            LOGGER.warn("image.preload event=failed photoId={} sortOrder={} type={} elapsedMs={} errorClass={}",
                clean(task.photoId), task.image.getSortOrder(), task.type, elapsed(startedNanos),
                exception.getClass().getSimpleName());
            publishFailureIfActive(task, runId);
        } finally {
            finish(task, runId);
        }
    }

    private void storeIfActive(
        PreloadTask task, long runId, byte[] imageBytes, String mime, byte[] thumbBytes
    ) {
        synchronized (preloadLock) {
            if (!isActiveLocked(task, runId)) return;
            if (imageBytes != null) {
                cache.put(key(task.photoId, task.image.getSortOrder(), "image"), imageBytes, mime);
            }
            if (thumbBytes != null) cache.put(task.cacheKey, thumbBytes, "image/jpeg");
            if (task.publishEvents) {
                publishLoaded(task.photoId, task.image.getSortOrder(), task.type, task.sessionId);
            }
            task.state = TaskState.DONE;
            pending.remove(task.pendingId, task);
        }
    }

    private void publishFailureIfActive(PreloadTask task, long runId) {
        synchronized (preloadLock) {
            if (task.publishEvents && isActiveLocked(task, runId)) {
                events.publish("imageFailed", new ImageEvent(
                    task.photoId, task.image.getSortOrder(), task.type, task.sessionId));
                task.state = TaskState.DONE;
                pending.remove(task.pendingId, task);
            }
        }
    }

    private boolean isActive(PreloadTask task, long runId) {
        synchronized (preloadLock) {
            return isActiveLocked(task, runId);
        }
    }

    private boolean isActiveLocked(PreloadTask task, long runId) {
        return !closed && task.runId == runId
            && running.stream().anyMatch(run -> run.task == task && run.runId == runId)
            && !cancelledSessions.contains(task.sessionId)
            && generations.getOrDefault(scopeKey(task.photoId, task.type, task.sessionId), task.generation)
                == task.generation
            && task.state == TaskState.RUNNING;
    }

    private boolean isValidLocked(PreloadTask task) {
        return !closed && !cancelledSessions.contains(task.sessionId)
            && generations.getOrDefault(scopeKey(task.photoId, task.type, task.sessionId), task.generation)
                == task.generation
            && task.state != TaskState.CANCELLED
            && task.state != TaskState.DONE;
    }

    private void finish(PreloadTask task, long runId) {
        synchronized (preloadLock) {
            PreloadRun run = running.stream().filter(value -> value.task == task && value.runId == runId)
                .findFirst().orElse(null);
            if (run == null) {
                PreloadRun detached = task.detachedRuns.remove(runId);
                if (detached != null && detached.detached) {
                    detachedThreads.decrementAndGet();
                    LOGGER.info("image.preload event=detached-finished photoId={} sortOrder={} durationMs={} detached={}",
                        clean(task.photoId), task.image.getSortOrder(), elapsed(detached.startedNanos),
                        detachedThreads.get());
                }
                return;
            }
            running.remove(run);
            if (task.runId == runId && task.state == TaskState.RUNNING) {
                task.state = TaskState.DONE;
                pending.remove(task.pendingId, task);
            }
            pumpLocked();
        }
    }

    private void cancelRun(PreloadRun run, boolean requeue) {
        running.remove(run);
        run.task.state = requeue && isValidLocked(run.task) ? TaskState.QUEUED : TaskState.CANCELLED;
        if (requeue && run.task.state == TaskState.QUEUED) waiting.add(run.task);
        else pending.remove(run.task.pendingId, run.task);
        Thread thread = run.task.thread;
        if (thread != null && thread.isAlive()) {
            run.detached = true;
            run.startedNanos = System.nanoTime();
            run.task.detachedRuns.put(run.runId, run);
            detachedThreads.incrementAndGet();
            LOGGER.info("image.preload event=detached photoId={} sortOrder={} priority={} detached={}",
                clean(run.task.photoId), run.task.image.getSortOrder(), run.task.priority,
                detachedThreads.get());
            thread.interrupt();
        }
    }

    private void cancelTaskLocked(PreloadTask task) {
        waiting.remove(task);
        PreloadRun run = running.stream().filter(value -> value.task == task).findFirst().orElse(null);
        if (run != null) cancelRun(run, false);
        else task.state = TaskState.CANCELLED;
        pending.remove(task.pendingId, task);
    }

    private void cancelScope(String scope, long currentGeneration) {
        synchronized (preloadLock) {
            for (PreloadTask task : List.copyOf(pending.values())) {
                if (scopeKey(task.photoId, task.type, task.sessionId).equals(scope)
                    && task.generation != currentGeneration) {
                    task.state = TaskState.CANCELLED;
                    pending.remove(task.pendingId, task);
                }
            }
            waiting.removeIf(task -> task.state == TaskState.CANCELLED);
            for (PreloadRun run : List.copyOf(running)) {
                if (scopeKey(run.task.photoId, run.task.type, run.task.sessionId).equals(scope)
                    && run.task.generation != currentGeneration) cancelRun(run, false);
            }
            pumpLocked();
        }
    }

    private static String scopeKey(String photoId, String type, String sessionId) {
        return sessionId + "/" + photoId + "/" + type;
    }

    private static String pendingId(String sessionId, String cacheKey) {
        return sessionId + "/" + cacheKey;
    }

    private void publish(String photoId, int sortOrder, String type, String sessionId) {
        events.publish("imageReady", new ImageEvent(photoId, sortOrder, type, sessionId));
    }

    private void publishLoaded(String photoId, int sortOrder, String type, String sessionId) {
        if ("thumb".equals(type)) publish(photoId, sortOrder, "image", sessionId);
        publish(photoId, sortOrder, type, sessionId);
    }

    private static String requiredSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw ApiException.invalidRequest("sessionId必须是非空字符串");
        }
        return sessionId;
    }

    private enum TaskState { QUEUED, RUNNING, CANCELLED, DONE }

    private static final class PreloadTask {
        private final String photoId;
        private final String type;
        private final JmImage image;
        private final long generation;
        private final String sessionId;
        private final int priority;
        private final boolean preferLocal;
        private final boolean publishEvents;
        private final String cacheKey;
        private final String pendingId;
        private final ImageCache.Entry original;
        private final long sequence;
        private long runId;
        private volatile TaskState state = TaskState.QUEUED;
        private volatile Thread thread;
        private final Map<Long, PreloadRun> detachedRuns = new HashMap<>();

        private PreloadTask(
            String photoId, String type, JmImage image, long generation,
            String sessionId, int priority, boolean preferLocal, boolean publishEvents,
            String cacheKey, String pendingId, ImageCache.Entry original, long sequence
        ) {
            this.photoId = photoId;
            this.type = type;
            this.image = image;
            this.generation = generation;
            this.sessionId = sessionId;
            this.priority = priority;
            this.preferLocal = preferLocal;
            this.publishEvents = publishEvents;
            this.cacheKey = cacheKey;
            this.pendingId = pendingId;
            this.original = original;
            this.sequence = sequence;
        }
    }

    private static final class PreloadRun {
        private final PreloadTask task;
        private final long runId;
        private boolean detached;
        private long startedNanos;

        private PreloadRun(PreloadTask task, long runId) {
            this.task = task;
            this.runId = runId;
        }
    }

    private Optional<Path> findLocalImage(String photoId, int sortOrder) {
        try {
            Optional<Path> path = localImageFinder.apply(photoId, sortOrder);
            return path == null ? Optional.empty() : path.filter(Files::isRegularFile);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static JmImage toImage(String photoId, JmImage value) {
        int sortOrder = value.getSortOrder();
        if (sortOrder <= 0) throw ApiException.invalidRequest("sortOrder必须是正整数");
        return new JmImage(
            photoId,
            text(value.getScrambleId()),
            text(value.getFilename()),
            text(value.getUrl()),
            text(value.getQueryParams()),
            sortOrder
        );
    }

    private static void validateType(String type) {
        if (!"image".equals(type) && !"thumb".equals(type)) {
            throw ApiException.invalidRequest("type必须是image或thumb");
        }
    }

    private static String key(String photoId, int sortOrder, String type) {
        return photoId + "/" + sortOrder + "/" + type;
    }

    private static String mime(JmImage image) {
        return mime(image.getFilename());
    }

    private static String mime(String filename) {
        String format = JmImageTool.getFormatName(filename);
        return "image/" + (format == null || format.isBlank() ? "jpeg" : format);
    }

    private static byte[] thumbnail(byte[] bytes) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
        if (source == null) throw new IOException("无法解析图片");
        int width = source.getWidth();
        int height = source.getHeight();
        if (width > THUMBNAIL_MAX_WIDTH) {
            int targetHeight = Math.max(1, (int) Math.round(height * (THUMBNAIL_MAX_WIDTH / (double) width)));
            BufferedImage scaled = new BufferedImage(THUMBNAIL_MAX_WIDTH, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
            graphics.dispose();
            source = scaled;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(source, "jpg", output)) throw new IOException("JPEG编码器不可用");
        return output.toByteArray();
    }

    private static List<JmImage> safe(List<JmImage> value) {
        return value == null ? List.of() : value;
    }

    private JmClient client() {
        JmClient client = clientSupplier.get();
        if (client == null) throw ApiException.unavailable("在线客户端不可用");
        return client;
    }

    private static void logSlowRead(String photoId, int sortOrder, String type, long startedNanos) {
        long elapsedMs = elapsed(startedNanos);
        if (elapsedMs >= SLOW_OPERATION_MS) {
            LOGGER.warn("image.read event=slow photoId={} sortOrder={} type={} elapsedMs={}",
                clean(photoId), sortOrder, type, elapsedMs);
        }
    }

    private static void logSlowPreload(
        String photoId, int sortOrder, String type, long startedNanos, String source
    ) {
        long elapsedMs = elapsed(startedNanos);
        if (elapsedMs >= SLOW_OPERATION_MS) {
            LOGGER.warn("image.preload event=slow photoId={} sortOrder={} type={} source={} elapsedMs={}",
                clean(photoId), sortOrder, type, source, elapsedMs);
        }
    }

    private static long elapsed(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime() - startedNanos);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? "empty" : "present";
    }
}
