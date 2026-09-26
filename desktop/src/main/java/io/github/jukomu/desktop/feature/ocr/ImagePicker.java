package io.github.jukomu.desktop.feature.ocr;

import java.nio.file.Path;

/**
 * 选择待识别图片的宿主端口，便于在无图形环境下保持明确的不可用语义。
 */
@FunctionalInterface
public interface ImagePicker {
    Path pickImage();
}
