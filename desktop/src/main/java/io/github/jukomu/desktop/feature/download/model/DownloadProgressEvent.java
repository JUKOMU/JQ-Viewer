package io.github.jukomu.desktop.feature.download.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 通过 SSE 发布的下载任务状态或进度变化。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DownloadProgressEvent(
        String taskId,
        String albumId,
        String chapterId,
        int downloadedPages,
        int totalPages,
        String status,
        String error,
        long speed,
        long downloadedBytes,
        Long totalSize
) {
}
