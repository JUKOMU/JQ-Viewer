package io.github.jukomu.desktop.feature.image.model;

/** 表示图片下载完成或失败事件。 */
public record ImageEvent(String photoId, int sortOrder, String type) {
}
