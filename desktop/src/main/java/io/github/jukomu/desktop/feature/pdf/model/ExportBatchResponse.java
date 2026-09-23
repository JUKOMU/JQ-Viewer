package io.github.jukomu.desktop.feature.pdf.model;

import java.util.List;

/** PDF 导出批量提交结果。 */
public record ExportBatchResponse(List<ExportTaskResponse> tasks) {
}
