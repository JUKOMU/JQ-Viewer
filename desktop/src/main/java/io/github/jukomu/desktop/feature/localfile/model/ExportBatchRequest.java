package io.github.jukomu.desktop.feature.localfile.model;

import java.util.List;

/**
 * 批量提交文件导出任务。
 */
public record ExportBatchRequest(List<ExportTaskRequest> tasks) {
}
