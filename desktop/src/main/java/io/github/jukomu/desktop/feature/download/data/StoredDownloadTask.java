package io.github.jukomu.desktop.feature.download.data;

/**
 * SQLite 中完整的下载任务记录。
 */
public record StoredDownloadTask(
    String taskId,
    String albumId,
    String chapterId,
    String albumTitle,
    String chapterTitle,
    String coverUrl,
    String author,
    String tagsJson,
    int totalPages,
    int downloadedPages,
    long downloadedBytes,
    Integer firstImageSortOrder,
    String status,
    String error,
    long totalSize,
    int chapterSortOrder,
    Boolean isSingleEpisode,
    String relativeDirectory,
    long createdAt,
    Long completedAt
) {
}
