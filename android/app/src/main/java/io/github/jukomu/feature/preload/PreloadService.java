package io.github.jukomu.feature.preload;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;
import io.github.jukomu.feature.cache.CacheCapacityPolicy;
import io.github.jukomu.feature.cache.ImageCache;
import io.github.jukomu.feature.download.storage.FileStore;
import io.github.jukomu.feature.download.validation.ImageFileValidator;
import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import io.github.jukomu.jmcomic.core.crypto.JmImageTool;
import io.github.jukomu.platform.persistence.SettingsStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 图片预加载与缓存管理——预加载、缩略图生成、缓存容量/清理。
 * 纯业务逻辑，不依赖 Capacitor API。
 */
public class PreloadService {

    @FunctionalInterface
    interface ImageFetcher {
        byte[] fetch(JmImage image) throws Exception;
    }

    public interface ImageRetryCallback {
        void onSuccess();

        void onError(Exception error);
    }

    private static final String TAG = "PreloadService";

    private final ImageCache imageCache;
    private final FileStore fileStore;
    private final SettingsStore settingsDb;
    private final ImageFetcher imageFetcher;
    private final ExecutorService imageExecutor;
    private volatile PreloadEventSink eventSink;
    private final Context context;
    private final CacheCapacityPolicy cacheCapacityPolicy;
    private final ExecutorService networkExecutor;
    private final ConcurrentHashMap<String, Long> pendingKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> activeGenerations = new ConcurrentHashMap<>();
    private final AtomicLong generationCounter = new AtomicLong();
    private final NetworkLoadGate networkLoadGate;
    private volatile CacheCapacityPolicy.PressureLevel pressureLevel =
        CacheCapacityPolicy.PressureLevel.NORMAL;

    public PreloadService(ImageCache imageCache, FileStore fileStore,
                          SettingsStore settingsDb, JmApiClient client,
                          ExecutorService imageExecutor, PreloadEventSink eventSink,
                          Context context, CacheCapacityPolicy cacheCapacityPolicy) {
        this(imageCache, fileStore, settingsDb, client, imageExecutor, imageExecutor,
            eventSink, context, cacheCapacityPolicy, 6,
            client == null ? null : client::fetchImageBytes);
    }

    public PreloadService(ImageCache imageCache, FileStore fileStore,
                          SettingsStore settingsDb, JmApiClient client,
                          ExecutorService imageExecutor, ExecutorService networkExecutor,
                          PreloadEventSink eventSink, Context context,
                          CacheCapacityPolicy cacheCapacityPolicy, int networkConcurrency) {
        this(imageCache, fileStore, settingsDb, client, imageExecutor, networkExecutor,
            eventSink, context, cacheCapacityPolicy, networkConcurrency,
            client == null ? null : client::fetchImageBytes);
    }

    PreloadService(ImageCache imageCache, FileStore fileStore,
                   SettingsStore settingsDb, JmApiClient client,
                   ExecutorService imageExecutor, ExecutorService networkExecutor,
                   PreloadEventSink eventSink, Context context,
                   CacheCapacityPolicy cacheCapacityPolicy, int networkConcurrency,
                   ImageFetcher imageFetcher) {
        this.imageCache = imageCache;
        this.fileStore = fileStore;
        this.settingsDb = settingsDb;
        this.imageFetcher = imageFetcher;
        this.imageExecutor = imageExecutor;
        this.networkExecutor = networkExecutor;
        this.eventSink = eventSink;
        this.context = context;
        this.cacheCapacityPolicy = cacheCapacityPolicy;
        this.networkLoadGate = new NetworkLoadGate(networkConcurrency);
    }

    // ---- 图片预加载 ----

    public JSONObject preloadImages(String photoId, String type, JSONArray imagesArray) {
        return preloadImages(photoId, type, imagesArray, false);
    }

    public JSONObject preloadImages(String photoId, String type, JSONArray imagesArray, boolean replacePending) {
        JSONObject ret = new JSONObject();
        if (imagesArray == null || imagesArray.length() == 0) {
            try {
                ret.put("cached", new JSONArray());
            } catch (Exception e) {
                Log.d(TAG, "构建空缓存列表失败", e);
            }
            try {
                ret.put("pending", new JSONArray());
            } catch (Exception e) {
                Log.d(TAG, "构建空待处理列表失败", e);
            }
            return ret;
        }

        List<Integer> cached = new ArrayList<>();
        List<Integer> pending = new ArrayList<>();
        final boolean isThumb = "thumb".equals(type);
        final String scopeKey = photoId + "/" + type;
        final long generation = replacePending
            ? generationCounter.incrementAndGet()
            : activeGenerations.getOrDefault(scopeKey, 0L);
        if (replacePending) {
            activeGenerations.put(scopeKey, generation);
        }

        for (int i = 0; i < imagesArray.length(); i++) {
            JSONObject imgObj;
            try {
                imgObj = imagesArray.getJSONObject(i);
            } catch (Exception e) {
                continue;
            }

            final int sortOrder = imgObj.optInt("sortOrder");
            final String cacheKey = photoId + "/" + sortOrder + (isThumb ? "/thumb" : "");

            // 目标缓存命中
            if (imageCache.has(cacheKey)) {
                cached.add(sortOrder);
                continue;
            }

            // 缩略图：优先从已缓存的原图生成
            if (isThumb) {
                ImageCache.ImageEntry original = imageCache.get(photoId + "/" + sortOrder);
                if (original != null) {
                    byte[] thumbBytes = ImageCache.createThumbnail(original.data);
                    if (imageCache.put(cacheKey, thumbBytes, "image/jpeg")) {
                        cached.add(sortOrder);
                        notifyImageReady(photoId, sortOrder, type);
                    }
                    continue;
                }
            }

            // 本地文件命中（已下载图片）
            File localFile = fileStore.getImageFileByPhotoId(photoId, sortOrder);
            if (ImageFileValidator.validateQuick(localFile)) {
                if (isThumb) {
                    // 缩略图：提交到线程池生成
                    pending.add(sortOrder);
                    if (!markPending(cacheKey, generation, replacePending)) {
                        continue;
                    }
                    imageExecutor.submit(() -> {
                        try (ImageCache.IncomingReservation reservation =
                                 imageCache.prepareForIncomingBytes(localFile.length())) {
                            if (reservation == null) return;
                            if (isStale(scopeKey, generation)) return;
                            byte[] localBytes = fileStore.readImageBytes(localFile);
                            byte[] thumbBytes = ImageCache.createThumbnail(localBytes);
                            if (isStale(scopeKey, generation)) return;
                            String mime = "image/" + ImageCache.guessFormatName(localBytes);
                            imageCache.put(photoId + "/" + sortOrder, localBytes, mime, reservation);
                            if (imageCache.put(cacheKey, thumbBytes, "image/jpeg")) {
                                notifyImageReady(photoId, sortOrder, type);
                            }
                        } catch (Exception e) {
                            Log.d(TAG, "缩略图生成失败", e);
                        } finally {
                            pendingKeys.remove(cacheKey, generation);
                        }
                    });
                } else {
                    // 原图：直接缓存，同步通知
                    try (ImageCache.IncomingReservation reservation =
                             imageCache.prepareForIncomingBytes(localFile.length())) {
                        if (reservation != null) {
                            byte[] localBytes = fileStore.readImageBytes(localFile);
                            String mime = "image/" + ImageCache.guessFormatName(localBytes);
                            if (imageCache.put(cacheKey, localBytes, mime, reservation)) {
                                cached.add(sortOrder);
                                notifyImageReady(photoId, sortOrder, type);
                            }
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "本地图片读取失败", e);
                    }
                }
                continue;
            }

            // 本地未命中 → 网络下载
            pending.add(sortOrder);
            if (!markPending(cacheKey, generation, replacePending)) {
                continue;
            }

            final String scrambleId = imgObj.optString("scrambleId");
            final String filename = imgObj.optString("filename");
            final String url = imgObj.optString("url");
            final String queryParams = imgObj.optString("queryParams", "");

            networkExecutor.submit(() -> {
                NetworkLoadGate.Permit permit = null;
                try {
                    permit = networkLoadGate.acquire(() -> isStale(scopeKey, generation));
                    if (permit == null) {
                        if (isStale(scopeKey, generation)) return;
                        throw new IOException("当前内存压力过高，暂时无法加载图片");
                    }
                    if (isStale(scopeKey, generation)) return;
                    JmImage jmImage = new JmImage(photoId, scrambleId, filename, url, queryParams, sortOrder);
                    if (imageFetcher == null) {
                        throw new IllegalStateException("图片获取器未初始化");
                    }
                    byte[] decrypted = imageFetcher.fetch(jmImage);
                    if (isStale(scopeKey, generation)) return;
                    if (networkLoadGate.isCompletePressure()) {
                        throw new IOException("当前内存压力过高，已丢弃图片加载结果");
                    }
                    String formatName = JmImageTool.getFormatName(filename);
                    String mimeType = "image/" + formatName;

                    try (ImageCache.IncomingReservation reservation =
                             imageCache.prepareForIncomingBytes(decrypted.length)) {
                        if (reservation == null) {
                            throw new IOException("图片无法写入内存缓存");
                        }
                        if (isThumb) {
                            byte[] thumbBytes = ImageCache.createThumbnail(decrypted);
                            imageCache.put(photoId + "/" + sortOrder, decrypted, mimeType, reservation);
                            if (!imageCache.put(cacheKey, thumbBytes, "image/jpeg")) {
                                throw new IOException("缩略图无法写入内存缓存");
                            }
                        } else {
                            if (!imageCache.put(cacheKey, decrypted, mimeType, reservation)) {
                                throw new IOException("图片无法写入内存缓存");
                            }
                        }
                    }

                    notifyImageReady(photoId, sortOrder, type);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    Log.d(TAG, "图片下载或解密失败", e);
                    if (!isStale(scopeKey, generation)) {
                        notifyImageFailed(photoId, sortOrder, type);
                    }
                } finally {
                    if (permit != null) permit.close();
                    pendingKeys.remove(cacheKey, generation);
                }
            });
        }

        try {
            ret.put("cached", new JSONArray(cached));
        } catch (Exception e) {
            Log.d(TAG, "构建缓存列表失败", e);
        }
        try {
            ret.put("pending", new JSONArray(pending));
        } catch (Exception e) {
            Log.d(TAG, "构建待处理列表失败", e);
        }
        return ret;
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

        try {
            networkExecutor.submit(() -> {
                NetworkLoadGate.Permit permit = null;
                Exception failure = null;
                try {
                    permit = networkLoadGate.acquire(null);
                    if (permit == null) {
                        throw new IOException("当前内存压力过高，暂时无法重试图片");
                    }
                    if (imageFetcher == null) {
                        throw new IllegalStateException("图片获取器未初始化");
                    }

                    JmImage image = new JmImage(
                        photoId, scrambleId, filename, url, queryParams, sortOrder);
                    byte[] imageBytes = imageFetcher.fetch(image);
                    if (!ImageFileValidator.validateQuick(imageBytes)) {
                        throw new IOException("重新获取的图片无法解析");
                    }
                    if (networkLoadGate.isCompletePressure()) {
                        throw new IOException("当前内存压力过高，已丢弃重试结果");
                    }

                    String mimeType = "image/" + JmImageTool.getFormatName(filename);
                    if (!imageCache.put(photoId + "/" + sortOrder, imageBytes, mimeType)) {
                        throw new IOException("重试图片无法写入内存缓存");
                    }
                } catch (Exception error) {
                    if (error instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    failure = error;
                } finally {
                    if (permit != null) {
                        permit.close();
                    }
                }

                if (failure == null) {
                    callback.onSuccess();
                } else {
                    callback.onError(failure);
                }
            });
        } catch (RuntimeException error) {
            callback.onError(error);
        }
    }

    private boolean isStale(String scopeKey, long generation) {
        return activeGenerations.getOrDefault(scopeKey, 0L) != generation;
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
            lowRam, pressureLevel);
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
        } catch (Exception e) {
            Log.d(TAG, "构建缓存容量信息失败", e);
        }
        return ret;
    }

    public JSONObject getImageCacheContents() {
        JSONObject ret = new JSONObject();
        JSONArray entries = new JSONArray();
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
                entries.put(item);
            }
            ret.put("entries", entries);
        } catch (Exception e) {
            Log.d(TAG, "构建图片缓存内容失败", e);
        }
        return ret;
    }

    public void clearImageCache() {
        imageCache.clear();
    }

    private void logCapacity(String reason) {
        ImageCache.CacheStats stats = imageCache.getStats();
        Log.i(TAG, "缓存策略: requestedMb=" + stats.requestedMb
            + ", effectiveMb=" + stats.effectiveMb
            + ", currentMb=" + Math.round(stats.currentBytes / (1024.0 * 1024.0))
            + ", maxHeapMb=" + stats.maxHeapMb
            + ", safeRatio=" + stats.safeRatio
            + ", pressureLevel=" + stats.pressureLevel
            + ", temporaryClamp=" + stats.temporaryClamp
            + ", reason=" + stats.reason + ", event=" + reason);
    }

    // ---- 内部 ----

    private void notifyImageReady(String photoId, int sortOrder, String type) {
        PreloadEventSink current = eventSink;
        if (current != null) {
            current.onImageReady(photoId, sortOrder, type);
        }
    }

    private void notifyImageFailed(String photoId, int sortOrder, String type) {
        PreloadEventSink current = eventSink;
        if (current != null) {
            current.onImageFailed(photoId, sortOrder, type);
        }
    }
}
