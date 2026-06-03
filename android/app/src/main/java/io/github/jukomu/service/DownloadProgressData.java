package io.github.jukomu.service;

/**
 * 下载进度数据——纯 POJO，不含 Capacitor/Android UI 类型。
 */
public class DownloadProgressData {
    public final String taskId;
    public final String albumId;
    public final String chapterId;
    public final int downloadedPages;
    public final int totalPages;
    public final String status;
    public final String error;
    public final long speed;
    public final long downloadedBytes;
    public final long totalSize;

    public DownloadProgressData(String taskId, String albumId, String chapterId,
                                int downloadedPages, int totalPages, String status,
                                String error, long speed, long totalSize) {
        this(taskId, albumId, chapterId, downloadedPages, totalPages, status,
            error, speed, totalSize, 0);
    }

    public DownloadProgressData(String taskId, String albumId, String chapterId,
                                int downloadedPages, int totalPages, String status,
                                String error, long speed, long totalSize,
                                long downloadedBytes) {
        this.taskId = taskId;
        this.albumId = albumId;
        this.chapterId = chapterId;
        this.downloadedPages = downloadedPages;
        this.totalPages = totalPages;
        this.status = status;
        this.error = error;
        this.speed = speed;
        this.totalSize = totalSize;
        this.downloadedBytes = downloadedBytes;
    }
}
