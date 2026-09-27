package io.github.jukomu.desktop.feature.localfile.model;

/**
 * 重试导出任务，并显式携带覆盖授权。
 */
public record ExportRetryRequest(String exportId, Boolean allowOverwrite) {
}
