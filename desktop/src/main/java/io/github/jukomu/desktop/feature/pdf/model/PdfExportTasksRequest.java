package io.github.jukomu.desktop.feature.pdf.model;

/** PDF 导出任务分页筛选。 */
public record PdfExportTasksRequest(String status, String cursor, Integer limit) {
}
