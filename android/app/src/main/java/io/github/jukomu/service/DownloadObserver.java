package io.github.jukomu.service;

import android.annotation.SuppressLint;
import io.github.jukomu.data.DownloadStore;
import io.github.jukomu.data.FileStore;
import io.github.jukomu.jmcomic.api.download.DownloadProgress;
import io.github.jukomu.jmcomic.api.download.DownloadResult;
import io.github.jukomu.jmcomic.api.download.enums.TaskState;
import io.github.jukomu.jmcomic.api.download.task.BaseDownloadTask;
import io.github.jukomu.jmcomic.api.download.task.TaskObserver;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 库下载任务观察者——监听任务状态/进度，同步到 DB 并通过 ServiceListener 推送事件。
 */
class DownloadObserver implements TaskObserver {

    private final String ourTaskId;
    private final String albumId;
    private final String chapterId;
    private final int totalImages;
    private final DownloadStore downloadDb;
    private final FileStore fileStore;
    private final ServiceListener listener;
    private final DownloadService service;
    private final AtomicBoolean finalized = new AtomicBoolean(false);
    private long lastBytes = 0;
    private long lastTimestamp = 0;

    DownloadObserver(String ourTaskId, String albumId, String chapterId, int totalImages,
                     DownloadStore downloadDb, FileStore fileStore,
                     ServiceListener listener, DownloadService service) {
        this.ourTaskId = ourTaskId;
        this.albumId = albumId;
        this.chapterId = chapterId;
        this.totalImages = totalImages;
        this.downloadDb = downloadDb;
        this.fileStore = fileStore;
        this.listener = listener;
        this.service = service;
        this.lastTimestamp = System.currentTimeMillis();
    }

    private boolean markFinalized() {
        return finalized.compareAndSet(false, true);
    }

    @Override
    public void onStateChanged(BaseDownloadTask task, TaskState newState) {
        if (service.isCancelled(ourTaskId)) return;
        if (newState.isTerminal() && !markFinalized()) return;

        if (newState == TaskState.COMPLETED) {
            Integer firstSO = fileStore.getFirstImageSortOrder(albumId, chapterId);
            downloadDb.updateCompleted(ourTaskId, totalImages,
                firstSO != null ? firstSO : 1);
            long totalSize = service.calcChapterFileSize(albumId, chapterId);
            downloadDb.updateSize(ourTaskId, totalSize);
            notifyProgress(totalImages, totalImages, DownloadService.STATUS_COMPLETED,
                null, 0, totalSize);
            service.updateDownloadNotification(ourTaskId, totalImages, totalImages,
                DownloadService.STATUS_COMPLETED, null);
            service.cleanupTaskMapping(ourTaskId);
        } else if (newState == TaskState.FAILED) {
            downloadDb.updateFailed(ourTaskId, 0, "下载失败");
            notifyProgress(0, totalImages, DownloadService.STATUS_FAILED, "下载失败");
            service.updateDownloadNotification(ourTaskId, 0, totalImages,
                DownloadService.STATUS_FAILED, "下载失败");
            service.cleanupTaskMapping(ourTaskId);
        } else if (newState == TaskState.CANCELLED) {
            if (downloadDb.getTask(ourTaskId) == null) return;
            fileStore.deleteChapter(albumId, chapterId);
            downloadDb.deleteImages(ourTaskId);
            downloadDb.deleteTask(ourTaskId);
            notifyProgress(0, totalImages, DownloadService.STATUS_FAILED, "已取消");
            service.updateDownloadNotification(ourTaskId, 0, totalImages,
                DownloadService.STATUS_FAILED, "已取消");
            service.cleanupTaskMapping(ourTaskId);
        } else if (newState == TaskState.PAUSED) {
            int downloadedPages = task.getCompletedCount();
            downloadDb.updateProgress(ourTaskId, downloadedPages);
            notifyProgress(downloadedPages, totalImages,
                DownloadService.STATUS_PAUSED, null);
            service.updateDownloadNotification(ourTaskId, downloadedPages, totalImages,
                DownloadService.STATUS_PAUSED, null);
        } else if (newState == TaskState.COMPLETED_WITH_ERRORS) {
            DownloadResult result = task.getCurrentDownloadResult();
            int failed = result.getFailedTasks().size();
            if (failed == 0) {
                Integer firstSO = fileStore.getFirstImageSortOrder(albumId, chapterId);
                downloadDb.updateCompleted(ourTaskId, totalImages,
                    firstSO != null ? firstSO : 1);
                long totalSize = service.calcChapterFileSize(albumId, chapterId);
                downloadDb.updateSize(ourTaskId, totalSize);
                notifyProgress(totalImages, totalImages, DownloadService.STATUS_COMPLETED,
                    null, 0, totalSize);
                service.updateDownloadNotification(ourTaskId, totalImages, totalImages,
                    DownloadService.STATUS_COMPLETED, null);
                service.cleanupTaskMapping(ourTaskId);
                return;
            }
            int succeeded = totalImages - failed;
            String error = failed + "/" + totalImages + " 张图片下载失败";
            downloadDb.updateFailed(ourTaskId, succeeded, error);
            long totalSize = service.calcChapterFileSize(albumId, chapterId);
            downloadDb.updateSize(ourTaskId, totalSize);
            notifyProgress(succeeded, totalImages, DownloadService.STATUS_FAILED,
                error, 0, totalSize);
            service.updateDownloadNotification(ourTaskId, succeeded, totalImages,
                DownloadService.STATUS_FAILED, error);
            service.cleanupTaskMapping(ourTaskId);
        } else if (newState == TaskState.SKIPPED) {
            Integer firstSO = fileStore.getFirstImageSortOrder(albumId, chapterId);
            downloadDb.updateCompleted(ourTaskId, totalImages,
                firstSO != null ? firstSO : 1);
            long totalSize = service.calcChapterFileSize(albumId, chapterId);
            downloadDb.updateSize(ourTaskId, totalSize);
            notifyProgress(totalImages, totalImages, DownloadService.STATUS_COMPLETED,
                null, 0, totalSize);
            service.updateDownloadNotification(ourTaskId, totalImages, totalImages,
                DownloadService.STATUS_COMPLETED, null);
            service.cleanupTaskMapping(ourTaskId);
        }
    }

    @Override
    @SuppressLint("NewApi")
    public void onProgressUpdate(BaseDownloadTask task, DownloadProgress progress) {
        if (service.isCancelled(ourTaskId)) return;
        int completed = progress.completedImages();
        if (completed > 0) {
            long now = System.currentTimeMillis();
            long currentBytes = progress.downloadedBytes();
            long speed = 0;
            if (lastBytes > 0 && now > lastTimestamp) {
                speed = (currentBytes - lastBytes) * 1000 / (now - lastTimestamp);
            }
            lastBytes = currentBytes;
            lastTimestamp = now;

            downloadDb.updateProgress(ourTaskId, completed);
            notifyProgress(completed, totalImages,
                DownloadService.STATUS_DOWNLOADING, null, speed, 0, currentBytes);
            service.updateDownloadNotification(ourTaskId, completed, totalImages,
                DownloadService.STATUS_DOWNLOADING, null);
        }
    }

    @Override
    public void onFinished(BaseDownloadTask task, DownloadResult result) {
        // 终态处理已在 onStateChanged 中完成
    }

    @Override
    public void onError(BaseDownloadTask task, Exception e) {
        if (service.isCancelled(ourTaskId)) return;
        if (!markFinalized()) return;
        downloadDb.updateFailed(ourTaskId, 0, e.getMessage());
        notifyProgress(0, totalImages, DownloadService.STATUS_FAILED, e.getMessage());
        service.updateDownloadNotification(ourTaskId, 0, totalImages,
            DownloadService.STATUS_FAILED, e.getMessage());
        service.cleanupTaskMapping(ourTaskId);
    }

    private void notifyProgress(int downloadedPages, int totalPages,
                                String status, String error) {
        notifyProgress(downloadedPages, totalPages, status, error, 0);
    }

    private void notifyProgress(int downloadedPages, int totalPages,
                                String status, String error, long speed) {
        notifyProgress(downloadedPages, totalPages, status, error, speed, 0);
    }

    private void notifyProgress(int downloadedPages, int totalPages,
                                String status, String error, long speed, long totalSize) {
        notifyProgress(downloadedPages, totalPages, status, error, speed, totalSize, 0);
    }

    private void notifyProgress(int downloadedPages, int totalPages,
                                String status, String error, long speed, long totalSize,
                                long downloadedBytes) {
        if (listener != null) {
            listener.onDownloadProgress(new DownloadProgressData(
                ourTaskId, albumId, chapterId, downloadedPages, totalPages,
                status, error, speed, totalSize, downloadedBytes));
        }
    }
}
