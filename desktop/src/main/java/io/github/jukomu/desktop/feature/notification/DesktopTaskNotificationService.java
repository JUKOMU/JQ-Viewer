package io.github.jukomu.desktop.feature.notification;

import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.desktop.feature.download.DownloadService;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.download.data.StoredDownloadTask;
import io.github.jukomu.desktop.feature.download.model.DownloadProgressEvent;
import io.github.jukomu.desktop.feature.pdf.export.ExportStore;
import io.github.jukomu.desktop.feature.pdf.model.ExportTaskResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 从持久化任务快照生成 Desktop 终态系统通知。 */
public final class DesktopTaskNotificationService implements AutoCloseable {
    private final DownloadStore downloads;
    private final ExportStore exports;
    private final LaunchRouteService launchRoutes;
    private final EventHub events;
    private final Consumer<String> openContainingFolder;
    private final Map<String, String> fingerprints = new LinkedHashMap<>();
    private final Map<String, PendingNotification> pending = new LinkedHashMap<>();

    private DesktopNotificationSink sink;
    private boolean started;
    private boolean closed;
    private AutoCloseable downloadEvents;
    private AutoCloseable pdfEvents;

    public DesktopTaskNotificationService(
            DownloadStore downloads,
            ExportStore exports,
            LaunchRouteService launchRoutes,
            EventHub events,
            Consumer<String> openContainingFolder
    ) {
        this.downloads = Objects.requireNonNull(downloads, "downloads");
        this.exports = Objects.requireNonNull(exports, "exports");
        this.launchRoutes = Objects.requireNonNull(launchRoutes, "launchRoutes");
        this.events = Objects.requireNonNull(events, "events");
        this.openContainingFolder = Objects.requireNonNull(
                openContainingFolder, "openContainingFolder");
    }

    /** 启动恢复完成后才接受快照变更，避免把历史中断任务当作新通知。 */
    public synchronized void start() {
        if (closed || started) return;
        downloadEvents = events.subscribe("downloadProgress", payload -> {
            if (payload instanceof DownloadProgressEvent event) downloadChanged(event.taskId());
        });
        pdfEvents = events.subscribe("exportProgress", payload -> {
            if (payload instanceof ExportTaskResponse event && event.exportId() != null) {
                pdfExportChanged(event.exportId());
            }
        });
        started = true;
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
        ExportTaskResponse task = exports.find(exportId);
        String format = task == null ? "export" : task.format();
        String key = "export:" + format + ":" + exportId;
        if (task == null || !isNotifiableExportTerminal(task.status())) {
            clear(key);
            return;
        }
        String fingerprint = task.status();
        if (fingerprint.equals(fingerprints.put(key, fingerprint))) return;

        String taskRoute = "/download?view=export&tab=tasks&format=" + format + "&exportId="
                + encode(exportId);
        String label = format.toUpperCase(java.util.Locale.ROOT);
        String title = switch (task.status()) {
            case "completed" -> label + " 导出完成";
            case "partial" -> label + " 部分导出完成";
            case "interrupted" -> label + " 导出中断";
            default -> label + " 导出失败";
        };
        String message = fallback(task.displayTitle(), fallback(task.targetName(), exportId));
        if (!"completed".equals(task.status())) {
            message += ": " + fallback(task.errorMessage(), title);
        }
        Runnable action = () -> launchRoutes.activate(taskRoute);
        String notificationRoute = taskRoute;
        ExportStore.Volume output = firstCompletedVolume(exportId);
        if ("completed".equals(task.status()) && output != null) {
            if ("zip".equals(format)) {
                notificationRoute = "/download?view=export&tab=files&format=zip";
                String fileRef = output.outputFileRef();
                action = () -> openContainingFolder.accept(fileRef);
            } else {
                notificationRoute = readerRoute(format, task, output.outputFileRef());
                String route = notificationRoute;
                action = () -> launchRoutes.activate(route);
            }
        }
        enqueue(new DesktopNotification(key, title, message, notificationRoute), () -> {
            ExportTaskResponse current = exports.find(exportId);
            return current != null && isNotifiableExportTerminal(current.status());
        }, action);
    }

    private void enqueue(DesktopNotification notification, TargetValidator validator) {
        enqueue(notification, validator, () -> launchRoutes.activate(notification.route()));
    }

    private void enqueue(
            DesktopNotification notification,
            TargetValidator validator,
            Runnable action
    ) {
        PendingNotification pendingNotification = new PendingNotification(
                notification, validator, action);
        if (sink == null) {
            pending.put(notification.key(), pendingNotification);
            return;
        }
        show(pendingNotification);
    }

    private void show(PendingNotification pendingNotification) {
        DesktopNotificationSink currentSink = sink;
        if (currentSink == null || closed || !pendingNotification.validator().exists()) return;
        AtomicBoolean handled = new AtomicBoolean();
        currentSink.show(pendingNotification.notification(), () -> {
            if (!handled.compareAndSet(false, true)) return;
            synchronized (DesktopTaskNotificationService.this) {
                if (closed || !pendingNotification.validator().exists()) return;
            }
            pendingNotification.action().run();
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

    private static boolean isNotifiableExportTerminal(String status) {
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

    private ExportStore.Volume firstCompletedVolume(String exportId) {
        return exports.volumes(exportId).stream()
                .filter(volume -> "completed".equals(volume.status()))
                .filter(volume -> volume.outputFileRef() != null
                        && !volume.outputFileRef().isBlank())
                .findFirst()
                .orElse(null);
    }

    private static String readerRoute(
            String format,
            ExportTaskResponse task,
            String fileRef
    ) {
        String reader = "cbz".equals(format) ? "/cbz-reader" : "/pdf-reader";
        return reader + "?fileRef=" + encode(fileRef)
                + "&title=" + encode(fallback(task.targetName(), task.displayTitle()))
                + "&albumId=" + encode(task.albumId())
                + "&albumTitle=" + encode(task.albumTitle())
                + "&authors=" + encode(task.authors())
                + "&coverUrl=" + encode(task.coverUrl())
                + "&chapterId=" + encode(fallback(task.chapterId(), task.albumId()))
                + "&chapterTitle=" + encode(task.displayTitle());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    @Override
    public synchronized void close() {
        closed = true;
        started = false;
        sink = null;
        closeQuietly(downloadEvents);
        closeQuietly(pdfEvents);
        downloadEvents = null;
        pdfEvents = null;
        pending.clear();
        fingerprints.clear();
    }

    private static void closeQuietly(AutoCloseable handle) {
        if (handle == null) return;
        try {
            handle.close();
        } catch (Exception ignored) {
        }
    }

    private record PendingNotification(
            DesktopNotification notification,
            TargetValidator validator,
            Runnable action
    ) {
    }

    @FunctionalInterface
    private interface TargetValidator {
        boolean exists();
    }
}
