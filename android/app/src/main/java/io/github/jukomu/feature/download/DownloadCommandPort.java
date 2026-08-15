package io.github.jukomu.feature.download;

/**
 * 执行下载通知发起的任务控制命令。
 */
public interface DownloadCommandPort {
    void pauseDownload(String taskId);

    void resumeDownload(String taskId);

    void cancelDownload(String taskId);
}
