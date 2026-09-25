package io.github.jukomu.desktop.feature.localfile.model;

import java.util.List;

/** 文件导出批量提交结果。 */
public record ExportBatchResponse(List<ExportTaskResponse> tasks) {
}
