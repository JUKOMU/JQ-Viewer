package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.Request;
import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.feature.catalog.CatalogService;
import io.github.jukomu.desktop.feature.catalog.model.CommentsRequest;
import io.github.jukomu.desktop.feature.catalog.model.IdRequest;
import io.github.jukomu.desktop.feature.catalog.model.SearchRequest;
import io.github.jukomu.desktop.feature.image.ImageService;
import io.github.jukomu.desktop.feature.image.model.PreloadImagesRequest;
import io.github.jukomu.desktop.feature.image.model.RetryImageRequest;
import io.javalin.http.Context;

/** 处理目录、章节和图片预加载 bridge 请求。 */
public final class ApiPluginHandler {
    private final RequestExecutor requests;
    private final CatalogService catalog;
    private final ImageService images;

    public ApiPluginHandler(RequestExecutor requests, CatalogService catalog, ImageService images) {
        this.requests = requests;
        this.catalog = catalog;
        this.images = images;
    }

    public void search(Context context) {
        requests.run(context, SearchRequest.class, catalog::search);
    }

    public void categories(Context context) {
        requests.run(context, SearchRequest.class, catalog::categories);
    }

    public void getAlbum(Context context) {
        requests.run(context, IdRequest.class,
                request -> catalog.getAlbum(Request.requiredText(request.id(), "id")));
    }

    public void getPhoto(Context context) {
        requests.run(context, IdRequest.class,
                request -> catalog.getPhoto(Request.requiredText(request.id(), "id")));
    }

    public void getComments(Context context) {
        requests.run(context, CommentsRequest.class, request -> catalog.getComments(
                Request.requiredText(request.albumId(), "albumId"),
                positive(request.page(), "page", 1)));
    }

    public void preloadImages(Context context) {
        requests.run(context, PreloadImagesRequest.class, request -> images.preload(
                Request.requiredText(request.photoId(), "photoId"),
                request.type() == null ? "image" : request.type(),
                request.images(),
                Request.bool(request.replacePending(), false)));
    }

    public void retryImage(Context context) {
        requests.run(context, RetryImageRequest.class, request -> images.retry(
                Request.requiredText(request.photoId(), "photoId"), request.image()));
    }

    private static int positive(Integer candidate, String name, int fallback) {
        int value = Request.integer(candidate, fallback);
        if (value < 1) throw ApiException.invalidRequest(name + "必须是正整数");
        return value;
    }
}
