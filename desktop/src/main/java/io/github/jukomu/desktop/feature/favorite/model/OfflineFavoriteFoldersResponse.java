package io.github.jukomu.desktop.feature.favorite.model;

import java.util.List;

/**
 * 返回全部离线收藏夹。
 */
public record OfflineFavoriteFoldersResponse(List<OfflineFavoriteFolder> folders) {
}
