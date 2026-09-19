package io.github.jukomu.desktop.feature.pdf.model;

/** PDF 文件库分页筛选参数。 */
public record PdfFilesRequest(
        String sourceType,
        String availability,
        String folderId,
        String query,
        String cursor,
        Integer limit
) {
}
