package io.github.jukomu.desktop.feature.localfile.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** 文件导出任务分页结果。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportTasksResponse(List<ExportTaskResponse> tasks, String nextCursor) {
}
