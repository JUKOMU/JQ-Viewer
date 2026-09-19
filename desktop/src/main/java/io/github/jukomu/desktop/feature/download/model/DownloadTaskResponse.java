package io.github.jukomu.desktop.feature.download.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 下载页使用的持久化任务快照。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DownloadTaskResponse(
        String taskId,
        String albumId,
        String chapterId,
        String albumTitle,
        String chapterTitle,
        String coverUrl,
        Integer firstImageSortOrder,
        Integer chapterSortOrder,
        Boolean isSingleEpisode,
        int totalPages,
        int downloadedPages,
        String status,
        long createdAt,
        Long completedAt,
        String error,
        long downloadedBytes,
        long totalSize
) {
}
