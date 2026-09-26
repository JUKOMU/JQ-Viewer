package io.github.jukomu.desktop.feature.catalog.model;

/**
 * 承载在线收藏夹的新建、编辑、移动和删除参数。
 */
public record FavoriteFolderRequest(
    String type,
    String folderId,
    String folderName,
    String albumId
) {
}
