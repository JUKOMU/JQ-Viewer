package io.github.jukomu.desktop.feature.pdf.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** PDF 导出任务分页结果。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PdfExportTasksResponse(List<PdfExportTaskResponse> tasks, String nextCursor) {
}
