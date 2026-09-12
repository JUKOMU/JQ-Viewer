package io.github.jukomu.desktop.feature.image.model;

import java.util.List;

/** 返回已缓存和等待下载的图片序号。 */
public record PreloadImagesResponse(List<Integer> cached, List<Integer> pending) {
}
