package io.github.jukomu.desktop.feature.localfile.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 本地文件库分页结果。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LocalFilesResponse(List<LocalFileResponse> files, String nextCursor) {
}
