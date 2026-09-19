package io.github.jukomu.desktop.feature.favorite.model;

/** 承载离线收藏分页查询参数。 */
public record OfflineFavoritePageRequest(
        String folderId,
        String keyword,
        Integer page,
        Integer pageSize
) {
}
