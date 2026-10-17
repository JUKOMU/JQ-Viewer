package io.github.jukomu.desktop.feature.favorite.model;

import java.util.List;

/**
 * 返回一组离线收藏条目。
 */
public record OfflineFavoriteItemsResponse(List<OfflineFavoriteItem> items) {
}
