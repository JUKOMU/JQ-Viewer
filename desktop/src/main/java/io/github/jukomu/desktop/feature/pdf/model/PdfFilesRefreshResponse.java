package io.github.jukomu.desktop.feature.pdf.model;

import java.util.List;

/** PDF 文件状态刷新结果。 */
public record PdfFilesRefreshResponse(List<PdfFileResponse> files) {
}
