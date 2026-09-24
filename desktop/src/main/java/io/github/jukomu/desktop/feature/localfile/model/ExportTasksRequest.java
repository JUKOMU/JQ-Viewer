package io.github.jukomu.desktop.feature.localfile.model;

/** 导出任务分页筛选。 */
public record ExportTasksRequest(String format, String status, String cursor, Integer limit) {
}
