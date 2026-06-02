package io.github.jukomu.service;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import io.github.jukomu.data.FileStore;
import io.github.jukomu.data.ImageCache;
import io.github.jukomu.data.SettingsStore;
import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import io.github.jukomu.jmcomic.core.crypto.JmImageTool;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 图片预加载与缓存管理——预加载、缩略图生成、缓存容量/清理。
 * 纯业务逻辑，不依赖 Capacitor API。
 */
public class PreloadService {

    private static final String TAG = "PreloadService";

    private final ImageCache imageCache;
    private final FileStore fileStore;
    private final SettingsStore settingsDb;
    private final JmApiClient client;
    private final ExecutorService imageExecutor;
    private final ServiceListener listener;
    private final Context context;
    private final ConcurrentHashMap<String, Long> pendingKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> activeGenerations = new ConcurrentHashMap<>();
    private final AtomicLong generationCounter = new AtomicLong();

    public PreloadService(ImageCache imageCache, FileStore fileStore,
                          SettingsStore settingsDb, JmApiClient client,
                          ExecutorService imageExecutor, ServiceListener listener,
                          Context context) {
        this.imageCache = imageCache;
        this.fileStore = fileStore;
        this.settingsDb = settingsDb;
        this.client = client;
        this.imageExecutor = imageExecutor;
        this.listener = listener;
        this.context = context;
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
                    imageCache.put(cacheKey, thumbBytes, "image/jpeg");
                    cached.add(sortOrder);
                    notifyImageReady(photoId, sortOrder, type);
                    continue;
                }
            }

            // 本地文件命中（已下载图片）
            byte[] localBytes = fileStore.getImageBytesByPhotoId(photoId, sortOrder);
            if (localBytes != null) {
                if (isThumb) {
                    // 缩略图：提交到线程池生成
                    pending.add(sortOrder);
                    if (!markPending(cacheKey, generation, replacePending)) {
                        continue;
                    }
                    imageExecutor.submit(() -> {
                        try {
                            if (isStale(scopeKey, generation)) return;
                            byte[] thumbBytes = ImageCache.createThumbnail(localBytes);
                            if (isStale(scopeKey, generation)) return;
                            imageCache.put(cacheKey, thumbBytes, "image/jpeg");
                            String mime = "image/" + ImageCache.guessFormatName(localBytes);
                            imageCache.put(photoId + "/" + sortOrder, localBytes, mime);
                            notifyImageReady(photoId, sortOrder, type);
                        } catch (Exception e) {
                            Log.d(TAG, "缩略图生成失败", e);
                        } finally {
                            pendingKeys.remove(cacheKey, generation);
                        }
                    });
                } else {
                    // 原图：直接缓存，同步通知
                    String mime = "image/" + ImageCache.guessFormatName(localBytes);
                    imageCache.put(cacheKey, localBytes, mime);
                    cached.add(sortOrder);
                    notifyImageReady(photoId, sortOrder, type);
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

            imageExecutor.submit(() -> {
                try {
                    if (isStale(scopeKey, generation)) return;
                    JmImage jmImage = new JmImage(photoId, scrambleId, filename, url, queryParams, sortOrder);
                    byte[] decrypted = client.fetchImageBytes(jmImage);
                    if (isStale(scopeKey, generation)) return;
                    String formatName = JmImageTool.getFormatName(filename);
                    String mimeType = "image/" + formatName;

                    if (isThumb) {
                        byte[] thumbBytes = ImageCache.createThumbnail(decrypted);
                        imageCache.put(cacheKey, thumbBytes, "image/jpeg");
                        imageCache.put(photoId + "/" + sortOrder, decrypted, mimeType);
                    } else {
                        imageCache.put(cacheKey, decrypted, mimeType);
                    }

                    notifyImageReady(photoId, sortOrder, type);
                } catch (Exception e) {
                    Log.d(TAG, "图片下载或解密失败", e);
                } finally {
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

    public void setCacheCapacity(long userMb) {
        if (userMb < 16) userMb = 16;
        if (userMb > 1024) userMb = 1024;

        // 持久化用户原始偏好
        settingsDb.putString("cache_capacity_mb", String.valueOf(userMb));

        // 自适应计算 → 实际生效值
        long effectiveMb = computeAdaptiveCapacity(userMb);
        long bytes = effectiveMb * 1024 * 1024;
        imageCache.setCapacity(bytes);
    }

    private long computeAdaptiveCapacity(long userMb) {
        if (context == null) return userMb;
        ActivityManager am = (ActivityManager) context
            .getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return userMb;

        int heapLimitMb = Math.max(am.getMemoryClass(), am.getLargeMemoryClass());

        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(memInfo);
        long availMemMb = memInfo.availMem / (1024 * 1024);

        long systemLimit = (long)(availMemMb * 0.25);
        long heapLimit = (long)(heapLimitMb * 0.50);

        long effective = userMb;
        if (systemLimit < effective) effective = systemLimit;
        if (heapLimit < effective) effective = heapLimit;
        if (effective < 16) effective = 16;

        Log.d(TAG, "自适应缓存: 用户=" + userMb + "MB, 可用内存限制="
            + systemLimit + "MB, 堆限制=" + heapLimit + "MB, 生效=" + effective + "MB");
        return effective;
    }

    public JSONObject getCacheCapacityInfo() {
        JSONObject ret = new JSONObject();
        try {
            ret.put("capacityMb", Math.round(imageCache.getCapacity() / (1024.0 * 1024.0)));
            ret.put("usedMb", Math.round(imageCache.getCurrentSize() / (1024.0 * 1024.0)));
        } catch (Exception e) {
            Log.d(TAG, "构建缓存容量信息失败", e);
        }
        return ret;
    }

    public void clearImageCache() {
        imageCache.clear();
    }

    // ---- 内部 ----

    private void notifyImageReady(String photoId, int sortOrder, String type) {
        if (listener != null) {
            listener.onImageReady(photoId, sortOrder, type);
        }
    }
}
