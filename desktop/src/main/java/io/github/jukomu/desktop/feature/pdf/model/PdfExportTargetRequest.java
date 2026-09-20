package io.github.jukomu.desktop.feature.pdf.model;

/** Desktop PDF 导出目标，实际定位只使用目录引用和相对路径。 */
public record PdfExportTargetRequest(String folder, String relativePath) {
}
