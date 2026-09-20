package io.github.jukomu.desktop.feature.favorite.model;

import java.util.List;

/** 承载离线收藏批量添加参数。 */
public record OfflineFavoriteBatchRequest(
        String folderId,
        List<OfflineFavoriteItem> items
) {
}
