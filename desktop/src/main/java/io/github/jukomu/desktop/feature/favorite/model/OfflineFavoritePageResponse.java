package io.github.jukomu.desktop.feature.favorite.model;

import java.util.List;

/** 返回单个离线收藏夹的分页结果。 */
public record OfflineFavoritePageResponse(
        long totalItems,
        int totalPages,
        int currentPage,
        List<OfflineFavoriteItem> content
) {
}
