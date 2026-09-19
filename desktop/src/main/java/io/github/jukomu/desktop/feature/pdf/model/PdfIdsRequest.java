package io.github.jukomu.desktop.feature.pdf.model;

import java.util.List;

/** 批量刷新 PDF 文件状态参数。 */
public record PdfIdsRequest(List<Long> ids) {
}
