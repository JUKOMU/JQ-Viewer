package io.github.jukomu.desktop.feature.localfile.model;

import java.util.List;

/**
 * 全部本地文件结果。
 */
public record ImportedLocalFilesResponse(List<LocalFileResponse> files) {
}
