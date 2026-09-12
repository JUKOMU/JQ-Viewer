package io.github.jukomu.desktop.feature.catalog.model;

import java.util.List;

/** 表示搜索结果和关联作品共用的作品摘要。 */
public record AlbumSummaryResponse(
        String id,
        String title,
        String coverUrl,
        List<String> authors,
        List<String> tags,
        String description,
        String image,
        CategoryResponse category,
        CategoryResponse subCategory
) {
}
