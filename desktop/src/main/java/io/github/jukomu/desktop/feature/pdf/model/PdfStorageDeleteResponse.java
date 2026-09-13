package io.github.jukomu.desktop.feature.pdf.model;

/** 删除实际 PDF 文件后的稳定结果。 */
public record PdfStorageDeleteResponse(
        String result,
        long id,
        String sourceType,
        String ownership,
        String fileRef,
        String displayPath,
        String fileName
) {
}
