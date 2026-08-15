package io.github.jukomu.bridge;

import io.github.jukomu.feature.download.DownloadCommandPort;
import io.github.jukomu.feature.download.DownloadService;

/**
 * 使用下载服务执行当前会话收到的通知命令。
 */
final class DownloadCommandAdapter implements DownloadCommandPort {

    private final DownloadService downloadService;

    DownloadCommandAdapter(DownloadService downloadService) {
        this.downloadService = downloadService;
    }

    @Override
    public void pauseDownload(String taskId) {
        downloadService.pauseDownload(taskId);
    }

    @Override
    public void resumeDownload(String taskId) {
        downloadService.resumeDownload(taskId);
    }

    @Override
    public void cancelDownload(String taskId) {
        downloadService.cancelDownload(taskId);
    }
}
