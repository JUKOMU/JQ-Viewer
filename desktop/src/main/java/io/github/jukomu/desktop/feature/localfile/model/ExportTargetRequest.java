package io.github.jukomu.desktop.feature.localfile.model;

/** Desktop 导出目标，实际定位只使用目录引用和相对路径。 */
public record ExportTargetRequest(String folder, String relativePath) {
}
