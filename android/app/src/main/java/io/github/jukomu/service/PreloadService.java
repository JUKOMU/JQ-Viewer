package io.github.jukomu.service;

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

    public PreloadService(ImageCache imageCache, FileStore fileStore,
                          SettingsStore settingsDb, JmApiClient client,
                          ExecutorService imageExecutor, ServiceListener listener) {
        this.imageCache = imageCache;
        this.fileStore = fileStore;
        this.settingsDb = settingsDb;
        this.client = client;
        this.imageExecutor = imageExecutor;
        this.listener = listener;
    }

    // ---- 图片预加载 ----

    public JSONObject preloadImages(String photoId, String type, JSONArray imagesArray) {
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

        for (int i = 0; i < imagesArray.length(); i++) {
            JSONObject imgObj;
            try {
                imgObj = imagesArray.getJSONObject(i);
            } catch (Exception e) {
                continue;
            }

            final int sortOrder = imgObj.optInt("sortOrder");
            final String cacheKey = photoId + "/" + sortOrder
                + ("thumb".equals(type) ? "/thumb" : "");

            if (imageCache.has(cacheKey)) {
                cached.add(sortOrder);
                continue;
            }

            if ("thumb".equals(type)) {
                ImageCache.ImageEntry original = imageCache.get(photoId + "/" + sortOrder);
                if (original != null) {
                    byte[] thumbBytes = ImageCache.createThumbnail(original.data);
                    imageCache.put(cacheKey, thumbBytes, "image/jpeg");
                    cached.add(sortOrder);
                    notifyImageReady(photoId, sortOrder, type);
                    continue;
                }

                byte[] fromFile = fileStore.getImageBytesByPhotoId(photoId, sortOrder);
                if (fromFile != null) {
                    pending.add(sortOrder);
                    imageExecutor.submit(() -> {
                        try {
                            byte[] thumbBytes = ImageCache.createThumbnail(fromFile);
                            imageCache.put(cacheKey, thumbBytes, "image/jpeg");
                            String mime = "image/" + ImageCache.guessFormatName(fromFile);
                            imageCache.put(photoId + "/" + sortOrder, fromFile, mime);
                            notifyImageReady(photoId, sortOrder, type);
                        } catch (Exception e) {
                            Log.d(TAG, "缩略图生成失败", e);
                        }
                    });
                    continue;
                }
            }

            pending.add(sortOrder);

            final String scrambleId = imgObj.optString("scrambleId");
            final String filename = imgObj.optString("filename");
            final String url = imgObj.optString("url");
            final String queryParams = imgObj.optString("queryParams", "");

            imageExecutor.submit(() -> {
                try {
                    JmImage jmImage = new JmImage(photoId, scrambleId, filename, url, queryParams, sortOrder);
                    byte[] decrypted = client.fetchImageBytes(jmImage);
                    String formatName = JmImageTool.getFormatName(filename);
                    String mimeType = "image/" + formatName;

                    if ("thumb".equals(type)) {
                        byte[] thumbBytes = ImageCache.createThumbnail(decrypted);
                        imageCache.put(cacheKey, thumbBytes, "image/jpeg");
                        imageCache.put(photoId + "/" + sortOrder, decrypted, mimeType);
                    } else {
                        imageCache.put(cacheKey, decrypted, mimeType);
                    }

                    notifyImageReady(photoId, sortOrder, type);
                } catch (Exception e) {
                    Log.d(TAG, "图片下载或解密失败", e);
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

    // ---- 缓存管理 ----

    public void clearPhotoCache(String photoId) {
        imageCache.clearByPrefix(photoId + "/");
    }

    public void setCacheCapacity(long mb) {
        long bytes = mb * 1024 * 1024;
        imageCache.setCapacity(bytes);
        settingsDb.putString("cache_capacity_mb", String.valueOf(mb));
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
