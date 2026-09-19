package io.github.jukomu.desktop.feature.image.model;

import io.github.jukomu.jmcomic.api.model.JmImage;

import java.util.List;

/** 承载章节图片预加载参数。 */
public record PreloadImagesRequest(
        String photoId,
        String type,
        List<JmImage> images,
        Boolean replacePending
) {
}
