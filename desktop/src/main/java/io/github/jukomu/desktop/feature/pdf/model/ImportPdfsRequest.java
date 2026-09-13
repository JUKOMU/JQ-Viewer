package io.github.jukomu.desktop.feature.pdf.model;

import java.util.List;

/** 外部 PDF 批量导入参数。 */
public record ImportPdfsRequest(List<ImportPdfItemRequest> items) {
}
