package io.github.jukomu.feature.preload;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;
import io.github.jukomu.feature.cache.CacheCapacityPolicy;
import io.github.jukomu.feature.cache.DiskImageCache;
import io.github.jukomu.feature.cache.ImageCache;
import io.github.jukomu.feature.download.storage.FileStore;
import io.github.jukomu.feature.download.validation.ImageFileValidator;
import io.github.jukomu.feature.pdf.render.PdfPageCache;
import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import io.github.jukomu.jmcomic.core.crypto.JmImageTool;
import io.github.jukomu.platform.persistence.SettingsStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 图片预加载与缓存管理——预加载、缩略图生成、缓存容量/清理。
 * 纯业务逻辑，不依赖 Capacitor API。
 */
public class PreloadService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PreloadService.class);
    private static final long SLOW_IMAGE_LOG_MS = 500L;

    @FunctionalInterface
    interface ImageFetcher {
        byte[] fetch(JmImage image) throws Exception;
    }

    public interface ImageRetryCallback {
        void onSuccess();

        void onError(Exception error);
    }

    private final ImageCache imageCache;
    private final FileStore fileStore;
    private final SettingsStore settingsDb;
    private final ImageFetcher imageFetcher;
    private final ExecutorService imageExecutor;
    private final ExecutorService fileExecutor;
    private volatile PreloadEventSink eventSink;
    private final Context context;
    private final CacheCapacityPolicy cacheCapacityPolicy;
    private final NetworkImageTaskScheduler networkTasks;
    private final ConcurrentHashMap<String, Long> pendingKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> activeGenerations = new ConcurrentHashMap<>();
    private final AtomicLong generationCounter = new AtomicLong();
    private final AtomicLong legacySessionCounter = new AtomicLong();
    private final AtomicLong retrySessionCounter = new AtomicLong();
    private final NetworkLoadGate networkLoadGate;
    private volatile CacheCapacityPolicy.PressureLevel pressureLevel =
        CacheCapacityPolicy.PressureLevel.NORMAL;

    public PreloadService(ImageCache imageCache, FileStore fileStore,
                          SettingsStore settingsDb, Supplier<JmApiClient> clientSupplier,
                          ExecutorService imageExecutor, ExecutorService fileExecutor,
                          PreloadEventSink eventSink, Context context,
                          CacheCapacityPolicy cacheCapacityPolicy, int networkConcurrency) {
        this(imageCache, fileStore, settingsDb, imageExecutor, fileExecutor,
            eventSink, context, cacheCapacityPolicy, networkConcurrency,
            image -> {
                JmApiClient client = clientSupplier.get();
                if (client == null) {
                    throw new IllegalStateException("在线客户端不可用");
                }
                return client.fetchImageBytes(image);
            });
    }

    PreloadService(ImageCache imageCache, FileStore fileStore,
                   SettingsStore settingsDb, ExecutorService imageExecutor,
                   ExecutorService fileExecutor,
                   PreloadEventSink eventSink, Context context,
                   CacheCapacityPolicy cacheCapacityPolicy, int networkConcurrency,
                   ImageFetcher imageFetcher) {
        this.imageCache = imageCache;
        this.fileStore = fileStore;
        this.settingsDb = settingsDb;
        this.imageFetcher = imageFetcher;
        this.imageExecutor = imageExecutor;
        this.fileExecutor = fileExecutor;
        this.eventSink = eventSink;
        this.context = context;
        this.cacheCapacityPolicy = cacheCapacityPolicy;
        this.networkLoadGate = new NetworkLoadGate(networkConcurrency);
        this.networkTasks = new NetworkImageTaskScheduler(networkConcurrency);
    }

    // ---- 图片预加载 ----

    public JSONObject preloadImages(String photoId, String type, JSONArray imagesArray) {
        return preloadImages(photoId, type, imagesArray, false,
            "legacy-" + legacySessionCounter.incrementAndGet(), "thumb".equals(type) ? 100 : 200);
    }

    public JSONObject preloadImages(String photoId, String type, JSONArray imagesArray, boolean replacePending) {
        return preloadImages(photoId, type, imagesArray, replacePending,
            "legacy-" + legacySessionCounter.incrementAndGet(), "thumb".equals(type) ? 100 : 200);
    }

    public JSONObject preloadImages(String photoId, String type, JSONArray imagesArray,
                                    boolean replacePending, String sessionId, int priority) {
        final long startedNanos = SystemClock.elapsedRealtimeNanos();
        JSONObject ret = new JSONObject();
        if (imagesArray == null || imagesArray.length() == 0) {
            try {
                ret.put("cached", new JSONArray());
            } catch (Exception e) {
                LOGGER.debug("构建空缓存列表失败", e);
            }
            try {
                ret.put("pending", new JSONArray());
            } catch (Exception e) {
                LOGGER.debug("构建空待处理列表失败", e);
            }
            LOGGER.debug("预加载任务结束: event=empty, photoId={}, type={}, durationMs={}",
                photoId, type, elapsedMs(startedNanos));
            return ret;
        }

        List<Integer> cached = new ArrayList<>();
        List<Integer> pending = new ArrayList<>();
        final boolean isThumb = "thumb".equals(type);
        final String requiredSession = requiredSession(sessionId);
        final String scopeKey = requiredSession + "/" + photoId + "/" + type;
        final long generation = replacePending
            ? generationCounter.incrementAndGet()
            : activeGenerations.getOrDefault(scopeKey, 0L);
        if (replacePending) {
            activeGenerations.put(scopeKey, generation);
        }

        LOGGER.info("预加载任务开始: session={}, photoId={}, type={}, requested={}, replacePending={}",
            sessionFingerprint(requiredSession), photoId, type, imagesArray.length(), replacePending);
        int invalidEntries = 0;
        int skippedPending = 0;

        for (int i = 0; i < imagesArray.length(); i++) {
            JSONObject imgObj;
            try {
                imgObj = imagesArray.getJSONObject(i);
            } catch (Exception e) {
                invalidEntries++;
                continue;
            }

            final int sortOrder = imgObj.optInt("sortOrder");
            final String imageKey = photoId + "/" + sortOrder;
            final String cacheKey = imageKey + (isThumb ? "/thumb" : "");
            final String taskPendingKey = requiredSession + "/" + cacheKey;

            // 目标缓存命中
            if (imageCache.has(cacheKey)) {
                cached.add(sortOrder);
                continue;
            }

            // 缩略图：优先从已缓存的原图生成
            if (isThumb) {
                ImageCache.ImageEntry original = imageCache.get(imageKey);
                if (original != null) {
                    pending.add(sortOrder);
                    if (!markPending(taskPendingKey, generation, replacePending)) {
                        skippedPending++;
                        continue;
                    }
                    generateThumbnailFromCachedImage(
                        photoId, sortOrder, type, scopeKey, cacheKey, taskPendingKey,
                        generation, requiredSession, original);
                    continue;
                }
            }

            pending.add(sortOrder);
            if (!markPending(taskPendingKey, generation, replacePending)) {
                skippedPending++;
                continue;
            }

            final String scrambleId = imgObj.optString("scrambleId");
            final String filename = imgObj.optString("filename");
            final String url = imgObj.optString("url");
            final String queryParams = imgObj.optString("queryParams", "");

            try {
                fileExecutor.execute(() -> resolveImageSource(
                    photoId, sortOrder, type, scopeKey, imageKey, cacheKey, taskPendingKey,
                    generation, requiredSession, priority, isThumb, scrambleId, filename,
                    url, queryParams));
            } catch (RuntimeException error) {
                pendingKeys.remove(taskPendingKey, generation);
                logImageFailure("queue-file", photoId, sortOrder, type, requiredSession, error, 0L);
                if (!isStale(scopeKey, generation)) {
                    notifyImageFailed(photoId, sortOrder, type, requiredSession);
                }
            }
        }

        try {
            ret.put("cached", new JSONArray(cached));
        } catch (Exception e) {
            LOGGER.debug("构建缓存列表失败", e);
        }
        try {
            ret.put("pending", new JSONArray(pending));
        } catch (Exception e) {
            LOGGER.debug("构建待处理列表失败", e);
        }
        LOGGER.info("预加载任务排队完成: session={}, photoId={}, type={}, cached={}, pending={}, skippedPending={}, invalid={}, durationMs={}",
            sessionFingerprint(requiredSession), photoId, type, cached.size(), pending.size(), skippedPending, invalidEntries,
            elapsedMs(startedNanos));
        return ret;
    }

    public void cancelPreload(String sessionId) {
        String requiredSession = requiredSession(sessionId);
        LOGGER.debug("预加载需求取消: session={}", sessionFingerprint(requiredSession));
        networkTasks.cancelSession(requiredSession);
    }

    private void generateThumbnailFromCachedImage(
        String photoId, int sortOrder, String type, String scopeKey,
        String cacheKey, String taskPendingKey, long generation, String sessionId,
        ImageCache.ImageEntry original
    ) {
        try {
            imageExecutor.execute(() -> {
                long startedNanos = SystemClock.elapsedRealtimeNanos();
                try {
                    if (isStale(scopeKey, generation)) {
                        logImageCancelled("thumbnail-cache", photoId, sortOrder, type, sessionId, startedNanos);
                        return;
                    }
                    byte[] thumbBytes = ImageCache.createThumbnail(original.data);
                    if (isStale(scopeKey, generation)) {
                        logImageCancelled("thumbnail-cache", photoId, sortOrder, type, sessionId, startedNanos);
                        return;
                    }
                    if (!imageCache.put(cacheKey, thumbBytes, "image/jpeg")) {
                        throw new IOException("缓存缩略图无法写入内存缓存");
                    }
                    notifyImageReady(photoId, sortOrder, type, sessionId);
                    logImageCompleted("thumbnail-cache", photoId, sortOrder, type, sessionId, startedNanos);
                } catch (Exception error) {
                    logImageFailure("thumbnail-cache", photoId, sortOrder, type, sessionId, error, startedNanos);
                    if (!isStale(scopeKey, generation)) {
                        notifyImageFailed(photoId, sortOrder, type, sessionId);
                    }
                } finally {
                    pendingKeys.remove(taskPendingKey, generation);
                }
            });
        } catch (RuntimeException error) {
            pendingKeys.remove(taskPendingKey, generation);
            logImageFailure("queue-thumbnail", photoId, sortOrder, type, sessionId, error, 0L);
            if (!isStale(scopeKey, generation)) {
                notifyImageFailed(photoId, sortOrder, type, sessionId);
            }
        }
    }

    private void resolveImageSource(
        String photoId, int sortOrder, String type, String scopeKey,
        String imageKey, String cacheKey, String taskPendingKey, long generation,
        String sessionId, int priority, boolean createThumbnail,
        String scrambleId, String filename, String url, String queryParams
    ) {
        long startedNanos = SystemClock.elapsedRealtimeNanos();
        ImageCache.IncomingReservation reservation = null;
        boolean handedOff = false;
        try {
            if (isStale(scopeKey, generation)) {
                logImageCancelled("resolve-source", photoId, sortOrder, type, sessionId, startedNanos);
                return;
            }
            File localFile = fileStore.getImageFileByPhotoId(photoId, sortOrder);
            if (ImageFileValidator.validateQuick(localFile)) {
                reservation = imageCache.prepareForIncomingBytes(localFile.length());
                if (reservation == null) {
                    throw new IOException("本地图片无法写入内存缓存");
                }
                byte[] localBytes = fileStore.readImageBytes(localFile);
                if (isStale(scopeKey, generation)) {
                    logImageCancelled("read-local", photoId, sortOrder, type, sessionId, startedNanos);
                    return;
                }
                String mimeType = "image/" + ImageCache.guessFormatName(localBytes);
                ImageCache.IncomingReservation transferredReservation = reservation;
                imageExecutor.execute(() -> cacheImageBytes(
                    photoId, sortOrder, type, scopeKey, imageKey, cacheKey, taskPendingKey,
                    generation, sessionId, createThumbnail, localBytes, mimeType,
                    transferredReservation));
                reservation = null;
                handedOff = true;
            } else {
                if (imageCache.has(cacheKey)) {
                    notifyImageReady(photoId, sortOrder, type, sessionId);
                    return;
                }
                submitNetworkImage(photoId, sortOrder, type, scopeKey, imageKey, cacheKey,
                    taskPendingKey, generation, sessionId, priority, createThumbnail,
                    scrambleId, filename, url, queryParams);
                handedOff = true;
            }
        } catch (Exception error) {
            logImageFailure("resolve-source", photoId, sortOrder, type, sessionId, error, startedNanos);
            if (!isStale(scopeKey, generation)) {
                notifyImageFailed(photoId, sortOrder, type, sessionId);
            }
        } finally {
            if (reservation != null) {
                reservation.close();
            }
            if (!handedOff) {
                pendingKeys.remove(taskPendingKey, generation);
            }
        }
    }

    private void submitNetworkImage(
        String photoId, int sortOrder, String type, String scopeKey,
        String imageKey, String cacheKey, String taskPendingKey, long generation,
        String sessionId, int priority, boolean createThumbnail,
        String scrambleId, String filename, String url, String queryParams
    ) {
        submitNetworkImage(photoId, sortOrder, type, scopeKey, imageKey, cacheKey,
            taskPendingKey, generation, sessionId, priority, createThumbnail,
            false, null, scrambleId, filename, url, queryParams);
    }

    private void submitNetworkImage(
        String photoId, int sortOrder, String type, String scopeKey,
        String imageKey, String cacheKey, String taskPendingKey, long generation,
        String sessionId, int priority, boolean createThumbnail, boolean replaceCache,
        ImageRetryCallback retryCallback,
        String scrambleId, String filename, String url, String queryParams
    ) {
        JmImage image = new JmImage(photoId, scrambleId, filename, url, queryParams, sortOrder);
        String demandId = sessionId + "/" + cacheKey;
        boolean accepted = networkTasks.submit(image, demandId, sessionId, priority,
            replaceCache,
            (taskImage, detached, forceNetwork, cacheLookup, networkFetch) -> {
                if (!forceNetwork.getAsBoolean()) {
                    byte[] cached = cacheLookup.read(() -> {
                        ImageCache.ImageEntry entry = imageCache.get(imageKey);
                        return entry == null ? null : entry.data;
                    });
                    if (cached != null) return cached;
                }
                NetworkLoadGate.Permit permit = null;
                try {
                    permit = networkLoadGate.acquire(null, detached);
                    if (permit == null) {
                        throw new IOException("当前内存压力过高，暂时无法加载图片");
                    }
                    if (!forceNetwork.getAsBoolean()) {
                        byte[] latest = cacheLookup.read(() -> {
                            ImageCache.ImageEntry entry = imageCache.get(imageKey);
                            return entry == null ? null : entry.data;
                        });
                        if (latest != null) return latest;
                    }
                    byte[] fetched = networkFetch.fetch(taskImage);
                    if (networkLoadGate.isCompletePressure()) {
                        throw new IOException("当前内存压力过高，已丢弃图片加载结果");
                    }
                    return fetched;
                } finally {
                    if (permit != null) permit.close();
                }
            },
            taskImage -> {
                if (imageFetcher == null) {
                    throw new IllegalStateException("图片获取器未初始化");
                }
                return imageFetcher.fetch(taskImage);
            },
            (taskImage, imageBytes, forceReplace) -> {
                if (!ImageFileValidator.validateQuick(imageBytes)) {
                    throw new IOException("获取的图片无法解析");
                }
                synchronized (imageCache) {
                    if (!forceReplace && imageCache.has(imageKey)) return;
                    ImageCache.IncomingReservation reservation =
                        imageCache.prepareForIncomingBytes(imageBytes.length);
                    if (reservation == null) {
                        throw new IOException("网络图片无法写入内存缓存");
                    }
                    try {
                        String mimeType = "image/" + JmImageTool.getFormatName(filename);
                        if (!imageCache.put(imageKey, imageBytes, mimeType, reservation)) {
                            throw new IOException("原图无法写入内存缓存");
                        }
                        if (forceReplace) imageCache.removeDisk(imageKey);
                    } finally {
                        reservation.close();
                    }
                }
            },
            imageBytes -> {
                try {
                    if (isStale(scopeKey, generation)) return;
                    if (createThumbnail) {
                        cacheMissingThumbnailFromCurrentImage(imageKey, cacheKey);
                    }
                    if (retryCallback != null) {
                        retryCallback.onSuccess();
                    } else {
                        if (createThumbnail) notifyImageReady(photoId, sortOrder, "image", sessionId);
                        notifyImageReady(photoId, sortOrder, type, sessionId);
                    }
                } catch (Exception error) {
                    logImageFailure("network-result", photoId, sortOrder, type, sessionId, error, 0L);
                    if (retryCallback != null) {
                        retryCallback.onError(error);
                    } else if (!isStale(scopeKey, generation)) {
                        notifyImageFailed(photoId, sortOrder, type, sessionId);
                    }
                } finally {
                    if (taskPendingKey != null) pendingKeys.remove(taskPendingKey, generation);
                }
            },
            error -> {
                if (taskPendingKey != null) pendingKeys.remove(taskPendingKey, generation);
                logImageFailure("network-fetch", photoId, sortOrder, type, sessionId, error, 0L);
                if (retryCallback != null) {
                    retryCallback.onError(error);
                } else if (!isStale(scopeKey, generation)) {
                    notifyImageFailed(photoId, sortOrder, type, sessionId);
                }
            });
        if (!accepted && taskPendingKey != null) pendingKeys.remove(taskPendingKey, generation);
    }

    void cacheMissingThumbnailFromCurrentImage(String imageKey, String cacheKey)
        throws IOException {
        synchronized (imageCache) {
            if (imageCache.has(cacheKey)) return;
            ImageCache.ImageEntry original = imageCache.get(imageKey);
            if (original == null) {
                throw new IOException("原图缓存未命中，无法生成缩略图");
            }
            byte[] thumbBytes = ImageCache.createThumbnail(original.data);
            if (!imageCache.put(cacheKey, thumbBytes, "image/jpeg")) {
                throw new IOException("缩略图无法写入内存缓存");
            }
        }
    }

    private void cacheImageBytes(
        String photoId, int sortOrder, String type, String scopeKey,
        String imageKey, String cacheKey, String taskPendingKey, long generation,
        String sessionId, boolean createThumbnail,
        byte[] imageBytes, String mimeType,
        ImageCache.IncomingReservation existingReservation
    ) {
        long startedNanos = SystemClock.elapsedRealtimeNanos();
        ImageCache.IncomingReservation reservation = existingReservation;
        try {
            if (isStale(scopeKey, generation)) {
                logImageCancelled("cache-write", photoId, sortOrder, type, sessionId, startedNanos);
                return;
            }
            if (!ImageFileValidator.validateQuick(imageBytes)) {
                throw new IOException("获取的图片无法解析");
            }
            if (reservation == null) {
                reservation = imageCache.prepareForIncomingBytes(imageBytes.length);
                if (reservation == null) {
                    throw new IOException("图片无法写入内存缓存");
                }
            }
            if (createThumbnail) {
                byte[] thumbBytes = ImageCache.createThumbnail(imageBytes);
                if (isStale(scopeKey, generation)) {
                    logImageCancelled("thumbnail-write", photoId, sortOrder, type, sessionId, startedNanos);
                    return;
                }
                boolean originalCached = imageCache.put(
                    imageKey, imageBytes, mimeType, reservation);
                if (!imageCache.put(cacheKey, thumbBytes, "image/jpeg")) {
                    throw new IOException("缩略图无法写入内存缓存");
                }
                if (originalCached && imageCache.has(imageKey)) {
                    notifyImageReady(photoId, sortOrder, "image", sessionId);
                }
            } else if (!imageCache.put(cacheKey, imageBytes, mimeType, reservation)) {
                throw new IOException("图片无法写入内存缓存");
            }
            notifyImageReady(photoId, sortOrder, type, sessionId);
            logImageCompleted("local-cache", photoId, sortOrder, type, sessionId, startedNanos);
        } catch (Exception error) {
            logImageFailure("local-cache", photoId, sortOrder, type, sessionId, error, startedNanos);
            if (!isStale(scopeKey, generation)) {
                notifyImageFailed(photoId, sortOrder, type, sessionId);
            }
        } finally {
            if (reservation != null) {
                reservation.close();
            }
            pendingKeys.remove(taskPendingKey, generation);
        }
    }

    /**
     * 使用调用方提供的最新图片元数据直接重新获取单页，仅替换内存缓存。
     */
    public void retryImage(String photoId, JSONObject imageObject, ImageRetryCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback is required");
        }
        if (photoId == null || photoId.isEmpty()) {
            callback.onError(new IllegalArgumentException("photoId is required"));
            return;
        }
        if (imageObject == null) {
            callback.onError(new IllegalArgumentException("image is required"));
            return;
        }

        final int sortOrder = imageObject.optInt("sortOrder");
        if (sortOrder <= 0) {
            callback.onError(new IllegalArgumentException("sortOrder must be positive"));
            return;
        }
        final String scrambleId = imageObject.optString("scrambleId");
        final String filename = imageObject.optString("filename");
        final String url = imageObject.optString("url");
        final String queryParams = imageObject.optString("queryParams", "");

        String retrySession = "retry-" + retrySessionCounter.incrementAndGet();
        try {
            submitNetworkImage(photoId, sortOrder, "image", retrySession + "/" + photoId,
                photoId + "/" + sortOrder, photoId + "/" + sortOrder, null, 0L,
                retrySession, 300, false, true, callback,
                scrambleId, filename, url, queryParams);
        } catch (RuntimeException error) {
            logImageFailure("queue-retry", photoId, sortOrder, "image", retrySession, error, 0L);
            callback.onError(error);
        }
    }

    private boolean isStale(String scopeKey, long generation) {
        return activeGenerations.getOrDefault(scopeKey, 0L) != generation;
    }

    private static String requiredSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return sessionId;
    }

    private boolean markPending(String cacheKey, long generation, boolean replacePending) {
        if (replacePending) {
            pendingKeys.put(cacheKey, generation);
            return true;
        }
        return pendingKeys.putIfAbsent(cacheKey, generation) == null;
    }

    // ---- 缓存管理 ----

    public void clearPhotoCache(String photoId) {
        imageCache.clearByPrefix(photoId + "/");
    }

    public CacheCapacityPolicy.Result setCacheCapacity(long userMb) {
        if (userMb < 64) userMb = 64;
        if (userMb > 1024) userMb = 1024;

        // 持久化用户原始偏好
        settingsDb.putString("cache_capacity_mb", String.valueOf(userMb));

        // 自适应计算 → 实际生效值
        CacheCapacityPolicy.Result result = calculateCapacity(userMb);
        imageCache.applyPolicy(result);
        logCapacity("setting-change");
        return result;
    }

    private CacheCapacityPolicy.Result calculateCapacity(long requestedMb) {
        ActivityManager am = context == null ? null : (ActivityManager) context
                                                                        .getSystemService(Context.ACTIVITY_SERVICE);
        boolean lowRam = am != null && am.isLowRamDevice();
        return cacheCapacityPolicy.calculate(requestedMb, Runtime.getRuntime().maxMemory(),
            lowRam, pressureLevel, imageCache.getSpaceSnapshot());
    }

    public void setMemoryPressureLevel(CacheCapacityPolicy.PressureLevel level) {
        pressureLevel = level == null ? CacheCapacityPolicy.PressureLevel.NORMAL : level;
        networkLoadGate.setPressureLevel(pressureLevel);
    }

    public void setEventSink(PreloadEventSink eventSink) {
        this.eventSink = eventSink;
    }

    public JSONObject getCacheCapacityInfo() {
        JSONObject ret = new JSONObject();
        try {
            ImageCache.CacheStats stats = imageCache.getStats();
            ret.put("capacityMb", stats.effectiveMb);
            ret.put("usedMb", Math.round(stats.currentBytes / (1024.0 * 1024.0)));
            ret.put("requestedMb", stats.requestedMb);
            ret.put("effectiveMb", stats.effectiveMb);
            ret.put("maxHeapMb", stats.maxHeapMb);
            ret.put("safeRatio", stats.safeRatio);
            ret.put("pressureLevel", stats.pressureLevel);
            ret.put("temporaryClamp", stats.temporaryClamp);
            ret.put("limitReason", stats.reason);
            ret.put("memoryCapacityMb", stats.memoryCapacityMb);
            ret.put("memoryUsedMb", Math.round(stats.memoryBytes / (1024.0 * 1024.0)));
            ret.put("diskCapacityMb", stats.diskCapacityMb);
            ret.put("diskUsedMb", Math.round(stats.diskUsedBytes / (1024.0 * 1024.0)));
            ret.put("diskReserveMb", stats.diskReserveMb);
            ret.put("diskState", stats.diskState);
        } catch (Exception e) {
            LOGGER.debug("构建缓存容量信息失败", e);
        }
        return ret;
    }

    public JSONObject getImageCacheContents() {
        JSONObject ret = new JSONObject();
        JSONArray entries = new JSONArray();
        Map<String, JSONObject> byKey = new LinkedHashMap<>();
        try {
            for (ImageCache.CacheEntryInfo entry : imageCache.getEntriesSnapshot()) {
                String[] parts = entry.key.split("/", -1);
                if (parts.length < 2 || parts.length > 3 || parts[0].isEmpty()) continue;

                int sortOrder;
                try {
                    sortOrder = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                    continue;
                }

                String type;
                if (parts.length == 2) {
                    type = "image";
                } else if ("thumb".equals(parts[2])) {
                    type = "thumb";
                } else {
                    continue;
                }

                JSONObject item = new JSONObject();
                item.put("photoId", parts[0]);
                item.put("sortOrder", sortOrder);
                item.put("type", type);
                item.put("sizeBytes", entry.sizeBytes);
                item.put("mimeType", entry.mimeType == null ? "" : entry.mimeType);
                byKey.put(parts[0] + "/" + parts[1] + "/" + type, item);
            }
            for (DiskImageCache.Info entry : imageCache.getDiskEntries()) {
                String[] parts = entry.key().split("/", -1);
                if (parts.length < 2 || parts.length > 3 || parts[0].isEmpty()) continue;
                int sortOrder;
                try {
                    sortOrder = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                    continue;
                }
                String type = parts.length == 2 ? "image" : "thumb";
                JSONObject item = new JSONObject();
                item.put("photoId", parts[0]);
                item.put("sortOrder", sortOrder);
                item.put("type", type);
                item.put("sizeBytes", entry.sizeBytes());
                item.put("mimeType", entry.mimeType() == null ? "" : entry.mimeType());
                byKey.put(parts[0] + "/" + parts[1] + "/" + type, item);
            }
            for (JSONObject item : byKey.values()) entries.put(item);
            ret.put("entries", entries);
        } catch (Exception e) {
            LOGGER.debug("构建图片缓存内容失败", e);
        }
        return ret;
    }

    public void clearImageCache() {
        imageCache.clear();
        if (context == null) return;
        try {
            PdfPageCache.getInstance(context).clear();
        } catch (RuntimeException error) {
            LOGGER.warn("清理 PDF 页面缓存失败，已完成内存缓存清理", error);
        }
    }

    private void logCapacity(String reason) {
        ImageCache.CacheStats stats = imageCache.getStats();
        LOGGER.info("缓存策略: requestedMb=" + stats.requestedMb
            + ", effectiveMb=" + stats.effectiveMb
            + ", currentMb=" + Math.round(stats.currentBytes / (1024.0 * 1024.0))
            + ", maxHeapMb=" + stats.maxHeapMb
            + ", safeRatio=" + stats.safeRatio
            + ", pressureLevel=" + stats.pressureLevel
            + ", temporaryClamp=" + stats.temporaryClamp
            + ", reason=" + stats.reason + ", event=" + reason);
    }

    // ---- 内部 ----

    private void notifyImageReady(String photoId, int sortOrder, String type, String sessionId) {
        PreloadEventSink current = eventSink;
        if (current != null) {
            current.onImageReady(photoId, sortOrder, type, sessionId);
        }
    }

    private void notifyImageFailed(String photoId, int sortOrder, String type, String sessionId) {
        PreloadEventSink current = eventSink;
        if (current != null) {
            current.onImageFailed(photoId, sortOrder, type, sessionId);
        }
    }

    private void logImageCompleted(String stage, String photoId, int sortOrder,
                                   String type, String sessionId, long startedNanos) {
        long durationMs = elapsedMs(startedNanos);
        if (durationMs >= SLOW_IMAGE_LOG_MS) {
            LOGGER.warn("图片预加载慢操作: event=completed, stage={}, session={}, photoId={}, sortOrder={}, type={}, durationMs={}",
                stage, sessionFingerprint(sessionId), photoId, sortOrder, type, durationMs);
        } else {
            LOGGER.debug("图片预加载完成: stage={}, photoId={}, sortOrder={}, type={}, durationMs={}",
                stage, photoId, sortOrder, type, durationMs);
        }
    }

    private void logImageCancelled(String stage, String photoId, int sortOrder,
                                   String type, String sessionId, long startedNanos) {
        LOGGER.debug("图片预加载取消: event=cancelled, stage={}, session={}, photoId={}, sortOrder={}, type={}, durationMs={}",
            stage, sessionFingerprint(sessionId), photoId, sortOrder, type, elapsedMs(startedNanos));
    }

    private void logImageFailure(String stage, String photoId, int sortOrder,
                                 String type, String sessionId, Throwable error, long startedNanos) {
        LOGGER.warn("图片预加载失败: event=failed, stage={}, session={}, photoId={}, sortOrder={}, type={}, errorClass={}, durationMs={}",
            stage, sessionFingerprint(sessionId), photoId, sortOrder, type,
            error == null ? "unknown" : error.getClass().getSimpleName(),
            elapsedMs(startedNanos));
    }

    private static String sessionFingerprint(String sessionId) {
        return Integer.toHexString(sessionId.hashCode());
    }

    private static long elapsedMs(long startedNanos) {
        return startedNanos <= 0L ? 0L
            : (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000L;
    }
}
