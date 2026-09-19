package io.github.jukomu.desktop.feature.pdf.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** PDF 文件库分页结果。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PdfFilesResponse(List<PdfFileResponse> files, String nextCursor) {
}
