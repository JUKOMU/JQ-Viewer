package io.github.jukomu.desktop.feature.favorite.model;

/** 承载离线收藏夹创建、重命名、删除和查询参数。 */
public record OfflineFavoriteFolderRequest(String folderId, String name) {
}
