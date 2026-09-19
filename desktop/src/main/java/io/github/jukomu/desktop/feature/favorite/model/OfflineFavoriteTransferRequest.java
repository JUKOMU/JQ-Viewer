package io.github.jukomu.desktop.feature.favorite.model;

/** 承载离线收藏夹移动或复制参数。 */
public record OfflineFavoriteTransferRequest(
        String sourceId,
        String targetId,
        String name
) {
}
