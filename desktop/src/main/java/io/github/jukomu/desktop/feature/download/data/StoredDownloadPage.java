package io.github.jukomu.desktop.feature.download.data;

/** SQLite 中一张下载图片的元数据和任务内相对路径。 */
public record StoredDownloadPage(
        String taskId,
        int sortOrder,
        String photoId,
        String filename,
        String relativePath,
        String sourceUrl,
        String scrambleId,
        String queryParams,
        boolean completed
) {
}
