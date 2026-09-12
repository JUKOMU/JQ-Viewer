package io.github.jukomu.desktop.feature.image.model;

import io.github.jukomu.jmcomic.api.model.JmImage;

/** 承载单张图片重试参数。 */
public record RetryImageRequest(String photoId, JmImage image) {
}
