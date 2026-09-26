package io.github.jukomu.desktop.feature.pdf.model;

/**
 * PDFBox 页面 PNG 回退渲染参数。
 */
public record PdfRenderPageRequest(String fileRef, Integer page, Integer targetWidth) {
}
