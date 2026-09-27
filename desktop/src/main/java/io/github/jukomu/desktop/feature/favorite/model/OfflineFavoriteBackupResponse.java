package io.github.jukomu.desktop.feature.favorite.model;

import java.util.List;

/**
 * 返回备份条目；key 不存在时 items 为 null。
 */
public record OfflineFavoriteBackupResponse(List<OfflineFavoriteItem> items) {
}
