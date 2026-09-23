package io.github.jukomu.desktop.feature.pdf.model;

import java.util.List;

/** 批量提交 PDF 导出任务。 */
public record ExportBatchRequest(List<ExportTaskRequest> tasks) {
}
