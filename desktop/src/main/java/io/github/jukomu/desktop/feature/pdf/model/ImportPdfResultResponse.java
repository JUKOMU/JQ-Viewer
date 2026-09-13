package io.github.jukomu.desktop.feature.pdf.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 单个 PDF 导入结果。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportPdfResultResponse(
        String result,
        String fileRef,
        String displayPath,
        String fileName,
        Long id
) {
}
