package io.github.jukomu.desktop.feature.localfile.model;

/**
 * Desktop 没有独立 PDF 数据库重置通知。
 */
public record LocalFileDatabaseResetResponse(boolean acknowledged) {
}
