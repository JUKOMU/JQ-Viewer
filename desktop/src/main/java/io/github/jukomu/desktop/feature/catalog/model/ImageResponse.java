package io.github.jukomu.desktop.feature.catalog.model;

/** 表示前端加载章节图片所需的元数据。 */
public record ImageResponse(
        String photoId,
        String scrambleId,
        String filename,
        String url,
        String queryParams,
        int sortOrder
) {
}
