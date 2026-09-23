package io.github.jukomu.desktop.feature.pdf.model;

import java.util.List;

/** 外部 PDF 批量导入汇总。 */
public record ImportLocalFilesResponse(
        int imported,
        int skipped,
        int duplicateCount,
        int errorCount,
        List<ImportLocalFileResultResponse> results
) {
}
