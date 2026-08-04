package io.github.jukomu.service;

import android.annotation.SuppressLint;
import io.github.jukomu.data.DownloadStore;
import io.github.jukomu.data.FileStore;
import io.github.jukomu.jmcomic.api.download.DownloadProgress;
import io.github.jukomu.jmcomic.api.download.DownloadResult;
import io.github.jukomu.jmcomic.api.download.enums.TaskState;
import io.github.jukomu.jmcomic.api.download.task.BaseDownloadTask;
import io.github.jukomu.jmcomic.api.download.task.TaskObserver;
import io.github.jukomu.jmcomic.api.model.JmImage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 库下载任务观察者——监听任务状态/进度，同步到 DB 并通过 ServiceListener 推送事件。
 */
class DownloadObserver implements TaskObserver {

    private final String ourTaskId;
    private final String albumId;
    private final String chapterId;
    private final List<JmImage> images;
    private final int totalImages;
    private final DownloadStore downloadDb;
    private final FileStore fileStore;
    private final DownloadService service;
    private final AtomicBoolean finalized = new AtomicBoolean(false);
    private long lastBytes = 0;
    private long lastTimestamp = 0;

    DownloadObserver(String ourTaskId, String albumId, String chapterId,
                     List<JmImage> images,
                     DownloadStore downloadDb, FileStore fileStore,
                     DownloadService service) {
        this.ourTaskId = ourTaskId;
        this.albumId = albumId;
        this.chapterId = chapterId;
        this.images = images == null ? new ArrayList<>() : new ArrayList<>(images);
        this.totalImages = this.images.size();
        this.downloadDb = downloadDb;
        this.fileStore = fileStore;
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
            completeDownloadIfValid();
        } else if (newState == TaskState.FAILED) {
            FileStore.DownloadValidationResult validation = validateDownloadedImages();
            failDownload(validation.getValidCount(), "下载失败");
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
                completeDownloadIfValid();
                return;
            }
            FileStore.DownloadValidationResult validation = validateDownloadedImages();
            int succeeded = Math.min(totalImages - failed, validation.getValidCount());
            int effectiveFailed = totalImages - succeeded;
            String error = effectiveFailed + "/" + totalImages + " 张图片下载失败";
            if (validation.getInvalidContentCount() > 0) {
                error += "（含 " + validation.getInvalidContentCount()
                    + " 张文件校验失败）";
            }
            failDownload(succeeded, error);
        } else if (newState == TaskState.SKIPPED) {
            completeDownloadIfValid();
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
        FileStore.DownloadValidationResult validation = validateDownloadedImages();
        failDownload(validation.getValidCount(), e.getMessage());
    }

    private FileStore.DownloadValidationResult validateDownloadedImages() {
        return fileStore.validateDownloadedImages(albumId, chapterId, images);
    }

    private void completeDownloadIfValid() {
        FileStore.DownloadValidationResult validation = validateDownloadedImages();
        if (!validation.isComplete()) {
            failDownload(validation.getValidCount(), validation.getFailureMessage());
            return;
        }

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

    private void failDownload(int succeeded, String error) {
        int safeSucceeded = Math.max(0, Math.min(succeeded, totalImages));
        String safeError = error == null || error.isEmpty() ? "下载失败" : error;
        downloadDb.updateFailed(ourTaskId, safeSucceeded, safeError);
        long totalSize = service.calcChapterFileSize(albumId, chapterId);
        downloadDb.updateSize(ourTaskId, totalSize);
        notifyProgress(safeSucceeded, totalImages, DownloadService.STATUS_FAILED,
            safeError, 0, totalSize);
        service.updateDownloadNotification(ourTaskId, safeSucceeded, totalImages,
            DownloadService.STATUS_FAILED, safeError);
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
        service.notifyDownloadProgress(ourTaskId, albumId, chapterId, downloadedPages,
            totalPages, status, error, speed, totalSize, downloadedBytes);
    }
}
