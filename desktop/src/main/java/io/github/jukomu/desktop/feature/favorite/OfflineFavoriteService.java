package io.github.jukomu.desktop.feature.favorite;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.feature.favorite.data.OfflineFavoriteStore;
import io.github.jukomu.desktop.feature.favorite.model.*;

import java.util.List;

/**
 * 提供离线收藏的输入规范化、事务操作和共享响应契约。
 */
public final class OfflineFavoriteService {
    private final OfflineFavoriteStore store;

    public OfflineFavoriteService(OfflineFavoriteStore store) {
        this.store = store;
    }

    public OfflineFavoriteFoldersResponse folders() {
        return new OfflineFavoriteFoldersResponse(store.folders());
    }

    public OfflineFavoriteFolderResponse createFolder(String name) {
        return new OfflineFavoriteFolderResponse(store.createFolder(text(name)));
    }

    public SuccessResponse renameFolder(String folderId, String name) {
        String normalizedName = text(name).trim();
        boolean success = !normalizedName.isEmpty()
            && store.renameFolder(text(folderId), normalizedName);
        return new SuccessResponse(success);
    }

    public SuccessResponse deleteFolder(String folderId) {
        return new SuccessResponse(store.deleteFolder(text(folderId)));
    }

    public SuccessResponse addItem(String folderId, OfflineFavoriteItem item) {
        return new SuccessResponse(store.addItem(text(folderId), normalize(item)));
    }

    public SuccessResponse removeItem(String folderId, String albumId) {
        return new SuccessResponse(store.removeItem(text(folderId), text(albumId)));
    }

    public OfflineFavoritePageResponse page(
        String folderId,
        String keyword,
        int page,
        int pageSize
    ) {
        if (pageSize <= 0) throw ApiException.invalidRequest("pageSize必须是正整数");
        return store.page(text(folderId), keyword, page, pageSize);
    }

    public OfflineFavoriteItemsResponse allItems(String folderId) {
        return new OfflineFavoriteItemsResponse(store.allItems(text(folderId)));
    }

    public OfflineFavoriteCountResponse totalCount() {
        return new OfflineFavoriteCountResponse(store.totalCount());
    }

    public OfflineFavoriteItemsResponse allItemsMerged() {
        return new OfflineFavoriteItemsResponse(store.allItemsMerged());
    }

    public SuccessResponse moveAllItems(String sourceId, String targetId) {
        return new SuccessResponse(store.moveAllItems(text(sourceId), text(targetId)));
    }

    public OfflineFavoriteFolderResponse copyFolder(String sourceId, String name) {
        return new OfflineFavoriteFolderResponse(
            store.copyFolder(text(sourceId), text(name)));
    }

    public OfflineFavoriteBatchResponse addItemsBatch(
        String folderId,
        List<OfflineFavoriteItem> items
    ) {
        return new OfflineFavoriteBatchResponse(
            store.addItemsBatch(text(folderId), normalize(items)));
    }

    public SuccessResponse mergeAllToFolder(String targetId) {
        return new SuccessResponse(store.mergeAllToFolder(text(targetId)));
    }

    public SuccessResponse saveBackup(String key, List<OfflineFavoriteItem> items) {
        store.saveBackup(text(key), normalize(items));
        return SuccessResponse.ok();
    }

    public OfflineFavoriteBackupResponse loadBackup(String key) {
        return new OfflineFavoriteBackupResponse(store.loadBackup(text(key)));
    }

    public SuccessResponse deleteBackup(String key) {
        return new SuccessResponse(store.deleteBackup(text(key)));
    }

    public OfflineFavoriteBackupKeysResponse backupKeys() {
        return new OfflineFavoriteBackupKeysResponse(store.backupKeys());
    }

    private static List<OfflineFavoriteItem> normalize(List<OfflineFavoriteItem> items) {
        if (items == null) throw ApiException.invalidRequest("items必须是数组");
        return items.stream().map(OfflineFavoriteService::normalize).toList();
    }

    private static OfflineFavoriteItem normalize(OfflineFavoriteItem item) {
        if (item == null) throw ApiException.invalidRequest("item不能为空");
        String id = text(item.id());
        if (id.isBlank()) throw ApiException.invalidRequest("item.id不能为空");
        return new OfflineFavoriteItem(
            id,
            text(item.title()),
            text(item.coverUrl()),
            strings(item.authors()),
            strings(item.tags())
        );
    }

    private static List<String> strings(List<String> values) {
        if (values == null) return List.of();
        return values.stream().map(OfflineFavoriteService::text).toList();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
