package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.bridge.Request;
import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.feature.favorite.OfflineFavoriteService;
import io.github.jukomu.desktop.feature.favorite.model.*;
import io.javalin.http.Context;

/**
 * 处理 Desktop 离线收藏夹及操作备份的 JSON bridge 请求。
 */
public final class OfflineFavoritePluginHandler {
    private final RequestExecutor requests;
    private final OfflineFavoriteService favorites;

    public OfflineFavoritePluginHandler(
        RequestExecutor requests,
        OfflineFavoriteService favorites
    ) {
        this.requests = requests;
        this.favorites = favorites;
    }

    public void getOfflineFolders(Context context) {
        requests.run(context, favorites::folders);
    }

    public void createOfflineFolder(Context context) {
        requests.run(context, OfflineFavoriteFolderRequest.class,
            request -> favorites.createFolder(request.name()));
    }

    public void renameOfflineFolder(Context context) {
        requests.run(context, OfflineFavoriteFolderRequest.class,
            request -> favorites.renameFolder(request.folderId(), request.name()));
    }

    public void deleteOfflineFolder(Context context) {
        requests.run(context, OfflineFavoriteFolderRequest.class,
            request -> favorites.deleteFolder(request.folderId()));
    }

    public void addOfflineFavorite(Context context) {
        requests.run(context, OfflineFavoriteItemRequest.class,
            request -> favorites.addItem(request.folderId(), request.item()));
    }

    public void removeOfflineFavorite(Context context) {
        requests.run(context, OfflineFavoriteItemRequest.class,
            request -> favorites.removeItem(request.folderId(), request.albumId()));
    }

    public void getOfflineFavorites(Context context) {
        requests.run(context, OfflineFavoritePageRequest.class, request -> favorites.page(
            request.folderId(),
            request.keyword(),
            Request.integer(request.page(), 1),
            Request.integer(request.pageSize(), 20)));
    }

    public void getAllOfflineFavorites(Context context) {
        requests.run(context, OfflineFavoriteFolderRequest.class,
            request -> favorites.allItems(request.folderId()));
    }

    public void getOfflineFavoritesTotalCount(Context context) {
        requests.run(context, favorites::totalCount);
    }

    public void getAllOfflineFavoritesMerged(Context context) {
        requests.run(context, favorites::allItemsMerged);
    }

    public void moveAllOfflineFavorites(Context context) {
        requests.run(context, OfflineFavoriteTransferRequest.class,
            request -> favorites.moveAllItems(request.sourceId(), request.targetId()));
    }

    public void copyOfflineFolder(Context context) {
        requests.run(context, OfflineFavoriteTransferRequest.class,
            request -> favorites.copyFolder(request.sourceId(), request.name()));
    }

    public void addOfflineFavoritesBatch(Context context) {
        requests.run(context, OfflineFavoriteBatchRequest.class,
            request -> favorites.addItemsBatch(request.folderId(), request.items()));
    }

    public void mergeOfflineAllToFolder(Context context) {
        requests.run(context, OfflineFavoriteMergeRequest.class,
            request -> favorites.mergeAllToFolder(request.targetId()));
    }

    public void saveOfflineBackup(Context context) {
        requests.run(context, OfflineFavoriteBackupRequest.class,
            request -> favorites.saveBackup(request.key(), request.items()));
    }

    public void loadOfflineBackup(Context context) {
        requests.run(context, OfflineFavoriteBackupRequest.class,
            request -> favorites.loadBackup(request.key()));
    }

    public void deleteOfflineBackup(Context context) {
        requests.run(context, OfflineFavoriteBackupRequest.class,
            request -> favorites.deleteBackup(request.key()));
    }

    public void listOfflineBackupKeys(Context context) {
        requests.run(context, favorites::backupKeys);
    }
}
