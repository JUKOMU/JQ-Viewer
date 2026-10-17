package io.github.jukomu.desktop.feature.localfile.model;

/**
 * Desktop 文件库无需 Android 数据库恢复确认。
 */
public record LocalFileManagementStateResponse(String recoveryState) {
}
