package io.github.jukomu.desktop.feature.download;

import io.github.jukomu.jmcomic.api.download.DownloadProgress;
import io.github.jukomu.jmcomic.api.download.DownloadResult;
import io.github.jukomu.jmcomic.api.download.enums.TaskState;
import io.github.jukomu.jmcomic.api.download.task.BaseDownloadTask;
import io.github.jukomu.jmcomic.api.download.task.TaskObserver;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将 JMComic 任务状态按“先写 SQLite、后发事件”的顺序同步到 Desktop。
 */
final class DownloadObserver implements TaskObserver {
    private final String taskId;
    private final int totalPages;
    private final DownloadService service;
    private final AtomicBoolean finalized = new AtomicBoolean();
    private long lastBytes;
    private long lastTimestamp = System.currentTimeMillis();

    DownloadObserver(String taskId, int totalPages, DownloadService service) {
        this.taskId = taskId;
        this.totalPages = totalPages;
        this.service = service;
    }

    @Override
    public void onStateChanged(BaseDownloadTask task, TaskState state) {
        if (service.isClosing()) {
            if (state.isTerminal()) service.signalStopped(taskId);
            return;
        }
        if (service.isCancelled(taskId)) {
            if (state.isTerminal()) service.signalStopped(taskId);
            return;
        }
        if (state.isTerminal() && !finalized.compareAndSet(false, true)) return;

        switch (state) {
            case QUEUED, RUNNING -> {
                service.logObserverState(state == TaskState.QUEUED ? "queued" : "downloading",
                    task, "downloading", "downloading");
                service.markDownloading(taskId);
            }
            case PAUSED -> {
                service.logObserverState("paused", task, "downloading", "paused");
                service.markPaused(taskId, task.getCompletedCount(), task.getDownloadedBytes());
            }
            case COMPLETED, SKIPPED -> {
                service.logObserverState("end", task, "downloading", "downloading");
                service.finishDownload(taskId);
            }
            case COMPLETED_WITH_ERRORS -> {
                DownloadResult result = task.getCurrentDownloadResult();
                if (result == null || result.getFailedTasks() == null) {
                    service.logObserverFailure(task, "DOWNLOAD_FAILED", null);
                    service.failDownload(taskId, task.getCompletedCount(), task.getDownloadedBytes(),
                        "下载失败");
                    return;
                }
                int failed = result.getFailedTasks().size();
                if (failed == 0) {
                    service.logObserverState("end", task, "downloading", "downloading");
                    service.finishDownload(taskId);
                } else {
                    service.logObserverFailure(task, "DOWNLOAD_FAILED", null);
                    int completed = Math.max(0, totalPages - failed);
                    service.failDownload(taskId, completed, task.getDownloadedBytes(),
                        failed + "/" + totalPages + " 张图片下载失败");
                }
            }
            case FAILED -> service.failDownload(taskId, task.getCompletedCount(),
                task.getDownloadedBytes(), "下载失败");
            case CANCELLED -> {
                service.logObserverState("cancelled", task, "downloading", "cancelled");
                service.failDownload(taskId, task.getCompletedCount(),
                    task.getDownloadedBytes(), "下载已取消");
            }
            default -> {
            }
        }
    }

    @Override
    public void onProgressUpdate(BaseDownloadTask task, DownloadProgress progress) {
        if (service.isCancelled(taskId) || service.isClosing()) return;
        long now = System.currentTimeMillis();
        long currentBytes = Math.max(0, progress.downloadedBytes());
        long speed = lastBytes > 0 && now > lastTimestamp
            ? Math.max(0, (currentBytes - lastBytes) * 1000 / (now - lastTimestamp))
            : 0;
        lastBytes = currentBytes;
        lastTimestamp = now;
        Long totalBytes = task.getTotalBytes() >= 0 ? task.getTotalBytes() : null;
        service.logObserverProgress(task, progress.completedImages(), currentBytes, speed, totalBytes);
        service.updateProgress(taskId, progress.completedImages(), currentBytes, speed, totalBytes);
    }

    @Override
    public void onFinished(BaseDownloadTask task, DownloadResult result) {
        // 终态统一由 onStateChanged 处理。
    }

    @Override
    public void onError(BaseDownloadTask task, Exception exception) {
        if (service.isCancelled(taskId) || service.isClosing()) {
            service.signalStopped(taskId);
            return;
        }
        if (!finalized.compareAndSet(false, true)) return;
        service.logObserverFailure(task, "DOWNLOAD_FAILED", exception);
        service.failDownload(taskId, task.getCompletedCount(), task.getDownloadedBytes(),
            exception == null ? "下载失败" : exception.getMessage());
    }
}
