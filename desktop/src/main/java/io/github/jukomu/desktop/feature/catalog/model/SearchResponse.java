package io.github.jukomu.desktop.feature.catalog.model;

import java.util.List;

/** 返回搜索或分类页的分页结果。 */
public record SearchResponse(
        int currentPage,
        int totalItems,
        int totalPages,
        List<AlbumSummaryResponse> content
) {
}
