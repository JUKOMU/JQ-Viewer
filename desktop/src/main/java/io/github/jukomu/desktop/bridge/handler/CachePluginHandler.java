package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.feature.image.CacheService;
import io.github.jukomu.desktop.feature.image.model.CacheCapacityRequest;
import io.javalin.http.Context;

/**
 * 处理图片缓存容量、内容查询和缓存域清理。
 */
public final class CachePluginHandler {
    private final RequestExecutor requests;
    private final CacheService cache;

    public CachePluginHandler(RequestExecutor requests, CacheService cache) {
        this.requests = requests;
        this.cache = cache;
    }

    public void setCacheCapacity(Context context) {
        requests.run(context, CacheCapacityRequest.class,
            request -> cache.setCapacity(request.mb()));
    }

    public void getCacheCapacityInfo(Context context) {
        requests.run(context, cache::capacityInfo);
    }

    public void getImageCacheContents(Context context) {
        requests.run(context, cache::contents);
    }

    public void clearImageCache(Context context) {
        requests.runLongOperation(context, () -> {
            cache.clear();
            return SuccessResponse.ok();
        });
    }
}
