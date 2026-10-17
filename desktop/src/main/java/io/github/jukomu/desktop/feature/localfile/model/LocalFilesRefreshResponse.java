package io.github.jukomu.desktop.feature.localfile.model;

import java.util.List;

/**
 * PDF 文件状态刷新结果。
 */
public record LocalFilesRefreshResponse(List<LocalFileResponse> files) {
}
