package io.github.jukomu.desktop.feature.pdf.model;

/** 本地文件库分页筛选参数。 */
public record LocalFilesRequest(
        String format,
        String sourceType,
        String availability,
        String folderId,
        String query,
        String cursor,
        Integer limit
) {
}
