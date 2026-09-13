package io.github.jukomu.desktop.feature.pdf.model;

/** 重试 PDF 导出任务，并显式携带覆盖授权。 */
public record PdfExportRetryRequest(String exportId, Boolean allowOverwrite) {
}
