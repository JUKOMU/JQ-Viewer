package io.github.jukomu.desktop.feature.catalog.model;

import java.util.List;
import java.util.Map;

/** 返回在线收藏夹分页内容和可用文件夹列表。 */
public record FavoriteResponse(
        String folderName,
        String folderId,
        int currentPage,
        int totalItems,
        int totalPages,
        List<AlbumSummaryResponse> content,
        Map<String, String> folderList
) {
}
