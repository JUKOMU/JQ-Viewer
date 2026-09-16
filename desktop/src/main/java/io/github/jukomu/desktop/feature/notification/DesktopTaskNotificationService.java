package io.github.jukomu.desktop.feature.notification;

import io.github.jukomu.desktop.feature.download.DownloadService;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.download.data.StoredDownloadTask;
import io.github.jukomu.desktop.feature.pdf.export.PdfExportStore;
import io.github.jukomu.desktop.feature.pdf.model.PdfExportTaskResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 从持久化任务快照生成 Desktop 终态系统通知。 */
public final class DesktopTaskNotificationService implements AutoCloseable {
    private final DownloadStore downloads;
    private final PdfExportStore pdfExports;
    private final LaunchRouteService launchRoutes;
    private final Map<String, String> fingerprints = new LinkedHashMap<>();
    private final Map<String, PendingNotification> pending = new LinkedHashMap<>();

    private DesktopNotificationSink sink;
    private boolean started;
    private boolean closed;

    public DesktopTaskNotificationService(
            DownloadStore downloads,
            PdfExportStore pdfExports,
            LaunchRouteService launchRoutes
    ) {
        this.downloads = Objects.requireNonNull(downloads, "downloads");
        this.pdfExports = Objects.requireNonNull(pdfExports, "pdfExports");
        this.launchRoutes = Objects.requireNonNull(launchRoutes, "launchRoutes");
    }

    /** 启动恢复完成后才接受快照变更，避免把历史中断任务当作新通知。 */
    public synchronized void start() {
        if (!closed) started = true;
    }

    public synchronized void attach(DesktopNotificationSink notificationSink) {
        if (closed) return;
        sink = Objects.requireNonNull(notificationSink, "notificationSink");
        for (PendingNotification notification : pending.values()) show(notification);
        pending.clear();
    }

    public synchronized void detach() {
        sink = null;
    }

    public synchronized void downloadChanged(String taskId) {
        if (!started || closed || taskId == null) return;
        String key = "download:" + taskId;
        StoredDownloadTask task = downloads.findTask(taskId);
        if (task == null || !isDownloadTerminal(task.status())) {
            clear(key);
            return;
        }
        String fingerprint = task.status();
        if (fingerprint.equals(fingerprints.put(key, fingerprint))) return;

        String label = taskLabel(task.albumTitle(), task.chapterTitle(), task.taskId());
        DesktopNotification notification;
        if (DownloadService.STATUS_COMPLETED.equals(task.status())) {
            notification = new DesktopNotification(key, "下载完成", label, "/download");
        } else {
            notification = new DesktopNotification(
                    key,
                    "下载失败",
                    label + ": " + fallback(task.error(), "下载失败"),
                    "/download"
            );
        }
        enqueue(notification, () -> {
            StoredDownloadTask current = downloads.findTask(taskId);
            return current != null && isDownloadTerminal(current.status());
        });
    }

    public synchronized void pdfExportChanged(String exportId) {
        if (!started || closed || exportId == null) return;
        String key = "pdf:" + exportId;
        PdfExportTaskResponse task = pdfExports.find(exportId);
        if (task == null || !isNotifiablePdfTerminal(task.status())) {
            clear(key);
            return;
        }
        String fingerprint = task.status();
        if (fingerprint.equals(fingerprints.put(key, fingerprint))) return;

        String route = "/download?view=pdf&tab=tasks&exportId="
                + URLEncoder.encode(exportId, StandardCharsets.UTF_8);
        String title = switch (task.status()) {
            case "completed" -> "PDF 导出完成";
            case "partial" -> "PDF 部分导出完成";
            case "interrupted" -> "PDF 导出中断";
            default -> "PDF 导出失败";
        };
        String message = fallback(task.displayTitle(), task.targetName());
        if (!"completed".equals(task.status())) {
            message += ": " + fallback(task.errorMessage(), title);
        }
        enqueue(new DesktopNotification(key, title, message, route), () -> {
            PdfExportTaskResponse current = pdfExports.find(exportId);
            return current != null && isNotifiablePdfTerminal(current.status());
        });
    }

    private void enqueue(DesktopNotification notification, TargetValidator validator) {
        PendingNotification pendingNotification = new PendingNotification(notification, validator);
        if (sink == null) {
            pending.put(notification.key(), pendingNotification);
            return;
        }
        show(pendingNotification);
    }

    private void show(PendingNotification pendingNotification) {
        DesktopNotificationSink currentSink = sink;
        if (currentSink == null || closed) return;
        AtomicBoolean handled = new AtomicBoolean();
        currentSink.show(pendingNotification.notification(), () -> {
            if (!handled.compareAndSet(false, true)) return;
            synchronized (DesktopTaskNotificationService.this) {
                if (closed || !pendingNotification.validator().exists()) return;
            }
            launchRoutes.activate(pendingNotification.notification().route());
        });
    }

    private void clear(String key) {
        fingerprints.remove(key);
        pending.remove(key);
    }

    private static boolean isDownloadTerminal(String status) {
        return DownloadService.STATUS_COMPLETED.equals(status)
                || DownloadService.STATUS_FAILED.equals(status);
    }

    private static boolean isNotifiablePdfTerminal(String status) {
        return "completed".equals(status) || "failed".equals(status)
                || "partial".equals(status) || "interrupted".equals(status);
    }

    private static String taskLabel(String albumTitle, String chapterTitle, String taskId) {
        String album = fallback(albumTitle, "");
        String chapter = fallback(chapterTitle, "");
        if (!album.isBlank() && !chapter.isBlank()) return album + " - " + chapter;
        if (!album.isBlank()) return album;
        if (!chapter.isBlank()) return chapter;
        return taskId;
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public synchronized void close() {
        closed = true;
        started = false;
        sink = null;
        pending.clear();
        fingerprints.clear();
    }

    private record PendingNotification(
            DesktopNotification notification,
            TargetValidator validator
    ) {
    }

    @FunctionalInterface
    private interface TargetValidator {
        boolean exists();
    }
}
