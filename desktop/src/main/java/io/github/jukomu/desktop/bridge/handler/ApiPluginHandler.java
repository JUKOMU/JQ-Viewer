package io.github.jukomu.desktop.bridge.handler;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jukomu.desktop.bridge.Request;
import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.feature.catalog.CatalogService;
import io.github.jukomu.desktop.feature.image.ImageService;
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
        requests.run(context, catalog::search);
    }

    public void categories(Context context) {
        requests.run(context, catalog::categories);
    }

    public void getAlbum(Context context) {
        requests.run(context, request -> catalog.getAlbum(required(request, "id")));
    }

    public void getPhoto(Context context) {
        requests.run(context, request -> catalog.getPhoto(required(request, "id")));
    }

    public void getComments(Context context) {
        requests.run(context, request -> catalog.getComments(
                required(request, "albumId"), positive(request, "page", 1)));
    }

    public void preloadImages(Context context) {
        requests.run(context, request -> images.preload(
                required(request, "photoId"), request.path("type").asText("image"),
                Request.array(request, "images"),
                request.path("replacePending").asBoolean(false)));
    }

    public void retryImage(Context context) {
        requests.run(context, request -> images.retry(
                required(request, "photoId"), image(request)));
    }

    private static String required(ObjectNode request, String name) {
        return io.github.jukomu.desktop.bridge.Request.requiredText(request, name);
    }

    private static int positive(ObjectNode request, String name, int fallback) {
        int value = io.github.jukomu.desktop.bridge.Request.integer(request, name, fallback);
        if (value < 1) throw new io.github.jukomu.desktop.bridge.ApiException("bad-request", 400, name + "必须是正整数");
        return value;
    }

    private static ObjectNode image(ObjectNode request) {
        if (!request.has("image") || !request.get("image").isObject()) {
            throw new io.github.jukomu.desktop.bridge.ApiException("bad-request", 400, "image必须是对象");
        }
        return (ObjectNode) request.get("image");
    }
}
