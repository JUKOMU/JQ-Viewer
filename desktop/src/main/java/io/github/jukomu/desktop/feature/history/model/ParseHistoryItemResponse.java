package io.github.jukomu.desktop.feature.history.model;

/**
 * 表示一条持久化解析记录。
 */
public record ParseHistoryItemResponse(
    long id,
    String text,
    long timestamp,
    String mode
) {
}
