package io.github.jukomu.desktop.feature.favorite.model;

/** 承载单条离线收藏的添加或删除参数。 */
public record OfflineFavoriteItemRequest(
        String folderId,
        String albumId,
        OfflineFavoriteItem item
) {
}
