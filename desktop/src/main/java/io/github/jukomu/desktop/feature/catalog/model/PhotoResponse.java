package io.github.jukomu.desktop.feature.catalog.model;

import java.util.List;

/** 返回章节详情及其图片元数据。 */
public record PhotoResponse(
        String id,
        String title,
        String albumId,
        int sortOrder,
        String author,
        List<String> tags,
        List<ImageResponse> images,
        boolean isSingleEpisode
) {
}
