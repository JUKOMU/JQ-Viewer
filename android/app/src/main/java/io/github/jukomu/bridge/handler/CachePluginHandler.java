package io.github.jukomu.bridge.handler;

import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import io.github.jukomu.feature.preload.PreloadService;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 负责图片预加载和内存缓存 Bridge 的参数转换与响应适配。
 */
public final class CachePluginHandler {

    private static final String TAG = "CachePluginHandler";

    private final PreloadService preloadService;

    public CachePluginHandler(PreloadService preloadService) {
        this.preloadService = preloadService;
    }

    /**
     * 提交图片预加载请求；类型缺省值为 image，无效图片条目会被忽略。
     */
    public void preloadImages(PluginCall call) {
        try {
            String photoId = call.getString("photoId");
            String type = call.getString("type", "image");
            boolean replacePending = call.getBoolean("replacePending", false);
            JSArray imagesArray = call.getArray("images");
            JSONArray jsonImages = new JSONArray();
            if (imagesArray != null) {
                for (int index = 0; index < imagesArray.length(); index++) {
                    try {
                        jsonImages.put(imagesArray.getJSONObject(index));
                    } catch (Exception error) {
                        Log.d(TAG, "跳过无效图片条目", error);
                    }
                }
            }
            JSONObject result = preloadService.preloadImages(
                photoId, type, jsonImages, replacePending);
            call.resolve(JSObject.fromJSONObject(result));
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 使用最新图片元数据直接重试单页，并等待缓存替换完成。
     */
    public void retryImage(PluginCall call) {
        String photoId = call.getString("photoId");
        JSObject image = call.getObject("image");
        if (photoId == null || photoId.isEmpty()) {
            call.reject("photoId is required");
            return;
        }
        if (image == null) {
            call.reject("image is required");
            return;
        }

        preloadService.retryImage(photoId, image, new PreloadService.ImageRetryCallback() {
            @Override
            public void onSuccess() {
                JSObject result = new JSObject();
                result.put("success", true);
                call.resolve(result);
            }

            @Override
            public void onError(Exception error) {
                Log.w(TAG, "图片重试失败", error);
                call.reject(error.getMessage(), error);
            }
        });
    }

    /**
     * 设置 64 至 1024 MB 的缓存容量，并返回当前容量信息。
     */
    public void setCacheCapacity(PluginCall call) {
        try {
            Integer capacityMb = call.getInt("mb");
            if (capacityMb == null || capacityMb < 64 || capacityMb > 1024) {
                call.reject("mb must be between 64 and 1024");
                return;
            }
            preloadService.setCacheCapacity(capacityMb);
            JSObject result = JSObject.fromJSONObject(preloadService.getCacheCapacityInfo());
            result.put("success", true);
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 返回用户请求容量、实际容量、占用量和压力状态。
     */
    public void getCacheCapacityInfo(PluginCall call) {
        try {
            call.resolve(JSObject.fromJSONObject(preloadService.getCacheCapacityInfo()));
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 返回当前图片缓存条目的结构化快照。
     */
    public void getImageCacheContents(PluginCall call) {
        try {
            call.resolve(JSObject.fromJSONObject(preloadService.getImageCacheContents()));
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 清空全部内存图片缓存。
     */
    public void clearImageCache(PluginCall call) {
        preloadService.clearImageCache();
        JSObject result = new JSObject();
        result.put("success", true);
        call.resolve(result);
    }
}
