package io.github.jukomu.desktop.feature.files;

import java.nio.file.Path;

/**
 * 系统目录选择器边界，返回 null 表示用户取消。
 */
@FunctionalInterface
public interface FolderPicker {
    Path pick(Path initialDirectory);
}
