package io.github.jukomu.desktop.feature.download.model;

import java.util.List;

/** 下载任务权威快照及当前下载目录空间信息。 */
public record DownloadTasksResponse(
        List<DownloadTaskResponse> tasks,
        long usedBytes,
        long availableBytes
) {
}
