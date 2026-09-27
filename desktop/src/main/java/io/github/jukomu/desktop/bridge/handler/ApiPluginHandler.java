package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.Request;
import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.feature.catalog.CatalogService;
import io.github.jukomu.desktop.feature.catalog.model.*;
import io.github.jukomu.desktop.feature.image.ImageService;
import io.github.jukomu.desktop.feature.image.model.PreloadImagesRequest;
import io.github.jukomu.desktop.feature.image.model.RetryImageRequest;
import io.github.jukomu.jmcomic.api.enums.FavoriteFolderType;
import io.javalin.http.Context;

/**
 * 处理目录、章节和图片预加载 bridge 请求。
 */
public final class ApiPluginHandler {
    private final RequestExecutor apiRequests;
    private final RequestExecutor imageRequests;
    private final CatalogService catalog;
    private final ImageService images;

    public ApiPluginHandler(
        RequestExecutor apiRequests,
        RequestExecutor imageRequests,
        CatalogService catalog,
        ImageService images
    ) {
        this.apiRequests = apiRequests;
        this.imageRequests = imageRequests;
        this.catalog = catalog;
        this.images = images;
    }

    public void search(Context context) {
        apiRequests.run(context, SearchRequest.class, catalog::search);
    }

    public void categories(Context context) {
        apiRequests.run(context, SearchRequest.class, catalog::categories);
    }

    public void getAlbum(Context context) {
        apiRequests.run(context, IdRequest.class,
            request -> catalog.getAlbum(Request.requiredText(request.id(), "id")));
    }

    public void getPhoto(Context context) {
        apiRequests.run(context, IdRequest.class,
            request -> catalog.getPhoto(Request.requiredText(request.id(), "id")));
    }

    public void getComments(Context context) {
        apiRequests.run(context, CommentsRequest.class, request -> catalog.getComments(
            Request.requiredText(request.albumId(), "albumId"),
            positive(request.page(), "page", 1)));
    }

    public void getFavorites(Context context) {
        apiRequests.run(context, FavoriteRequest.class, request -> catalog.getFavorites(
            folderId(request.folderId(), "0"),
            positive(request.page(), "page", 1)));
    }

    public void toggleAlbumLike(Context context) {
        apiRequests.run(context, IdRequest.class,
            request -> catalog.toggleAlbumLike(Request.requiredText(request.id(), "id")));
    }

    public void toggleAlbumFavorite(Context context) {
        apiRequests.run(context, AlbumFavoriteRequest.class, request -> catalog.toggleAlbumFavorite(
            Request.requiredText(request.id(), "id"),
            textOrDefault(request.folderId(), "0")));
    }

    public void manageFavoriteFolder(Context context) {
        apiRequests.run(context, FavoriteFolderRequest.class, request -> {
            FavoriteFolderType type = favoriteFolderType(
                Request.requiredText(request.type(), "type"));
            String folderId = textOrDefault(request.folderId(), "0");
            if ((type == FavoriteFolderType.EDIT || type == FavoriteFolderType.DELETE)
                && "0".equals(folderId)) {
                throw ApiException.invalidRequest("编辑或删除收藏夹时 folderId 不能为空");
            }
            return catalog.manageFavoriteFolder(
                type,
                folderId,
                textOrDefault(request.folderName(), ""),
                textOrDefault(request.albumId(), ""));
        });
    }

    public void preloadImages(Context context) {
        imageRequests.run(context, PreloadImagesRequest.class, request -> images.preload(
            Request.requiredText(request.photoId(), "photoId"),
            request.type() == null ? "image" : request.type(),
            request.images(),
            Request.bool(request.replacePending(), false)));
    }

    public void retryImage(Context context) {
        imageRequests.run(context, RetryImageRequest.class, request -> images.retry(
            Request.requiredText(request.photoId(), "photoId"), request.image()));
    }

    private static int positive(Integer candidate, String name, int fallback) {
        int value = Request.integer(candidate, fallback);
        if (value < 1) throw ApiException.invalidRequest(name + "必须是正整数");
        return value;
    }

    private static int folderId(String candidate, String fallback) {
        String value = textOrDefault(candidate, fallback);
        try {
            int folderId = Integer.parseInt(value);
            if (folderId < 0) throw new NumberFormatException();
            return folderId;
        } catch (NumberFormatException exception) {
            throw ApiException.invalidRequest("folderId必须是非负整数");
        }
    }

    private static FavoriteFolderType favoriteFolderType(String value) {
        for (FavoriteFolderType candidate : FavoriteFolderType.values()) {
            if (candidate.getValue().equals(value)) return candidate;
        }
        throw ApiException.invalidRequest("type必须是 add、edit、move 或 del");
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
