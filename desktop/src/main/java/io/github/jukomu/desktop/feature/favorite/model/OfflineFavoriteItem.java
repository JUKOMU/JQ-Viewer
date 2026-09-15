package io.github.jukomu.desktop.feature.favorite.model;

import java.util.List;

/** 表示离线收藏中持久化的作品摘要。 */
public record OfflineFavoriteItem(
        String id,
        String title,
        String coverUrl,
        List<String> authors,
        List<String> tags
) {
}
