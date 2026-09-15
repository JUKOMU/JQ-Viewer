package io.github.jukomu.desktop.feature.catalog.model;

/** 承载在线收藏夹的分页查询参数。 */
public record FavoriteRequest(String folderId, Integer page) {
}
