package io.github.jukomu.desktop.feature.favorite.model;

import java.util.List;

/**
 * 承载离线收藏备份的 key 和可选条目。
 */
public record OfflineFavoriteBackupRequest(
    String key,
    List<OfflineFavoriteItem> items
) {
}
