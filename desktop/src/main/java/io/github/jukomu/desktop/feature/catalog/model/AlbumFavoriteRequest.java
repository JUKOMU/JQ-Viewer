package io.github.jukomu.desktop.feature.catalog.model;

/**
 * 承载作品收藏切换使用的作品和文件夹标识。
 */
public record AlbumFavoriteRequest(String id, String folderId) {
}
