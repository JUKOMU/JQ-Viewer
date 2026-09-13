package io.github.jukomu.desktop.feature.pdf.model;

import java.util.List;

/** 兼容公共 PdfService 的全部文件库结果。 */
public record ImportedPdfsResponse(List<PdfFileResponse> pdfs) {
}
