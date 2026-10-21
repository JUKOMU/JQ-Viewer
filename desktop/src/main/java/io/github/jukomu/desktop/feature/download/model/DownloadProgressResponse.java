package io.github.jukomu.desktop.feature.download.model;

public record DownloadProgressResponse(
    String taskId,
    String albumId,
    String chapterId,
    int downloadedPages,
    int totalPages,
    String status,
    long downloadedBytes,
    long totalSize
) {
}
