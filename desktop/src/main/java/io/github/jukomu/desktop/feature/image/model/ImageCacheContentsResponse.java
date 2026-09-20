package io.github.jukomu.desktop.feature.image.model;

import java.util.List;

/** 当前图片缓存内容快照。 */
public record ImageCacheContentsResponse(List<ImageCacheEntryResponse> entries) {
}
