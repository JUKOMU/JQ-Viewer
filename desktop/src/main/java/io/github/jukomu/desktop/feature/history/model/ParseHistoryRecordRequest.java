package io.github.jukomu.desktop.feature.history.model;

/** 承载一条解析历史的文本和模式。 */
public record ParseHistoryRecordRequest(String text, String mode) {
}
