package io.github.jukomu.desktop.feature.image.model;

/**
 * Cache 页面展示的一条图片缓存记录。
 */
public record ImageCacheEntryResponse(
    String photoId,
    int sortOrder,
    String type,
    long sizeBytes,
    String mimeType
) {
}
