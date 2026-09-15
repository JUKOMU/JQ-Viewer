package io.github.jukomu.desktop.feature.favorite.model;

/** 表示一个离线收藏夹及其条目数量。 */
public record OfflineFavoriteFolder(String folderId, String name, long count) {
}
