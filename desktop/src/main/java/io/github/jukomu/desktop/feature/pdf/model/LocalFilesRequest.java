package io.github.jukomu.desktop.feature.pdf.model;

import java.util.List;

/** 本地文件库分页筛选参数。 */
public record LocalFilesRequest(
        List<String> formats,
        String sourceType,
        String availability,
        String folderId,
        String query,
        String cursor,
        Integer limit
) {
}
