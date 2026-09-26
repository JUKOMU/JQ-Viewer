package io.github.jukomu.desktop.feature.localfile.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单个 PDF 导入结果。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportLocalFileResultResponse(
    String result,
    String fileRef,
    String displayPath,
    String fileName,
    Long id
) {
}
