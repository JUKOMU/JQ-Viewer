package io.github.jukomu.desktop.feature.download;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.download.data.StoredDownloadPage;
import io.github.jukomu.desktop.feature.download.data.StoredDownloadTask;
import io.github.jukomu.desktop.feature.download.model.DownloadLocationResponse;
import io.github.jukomu.desktop.feature.download.model.DownloadRelocationResponse;
import io.github.jukomu.desktop.feature.download.model.RelocationProgressEvent;
import io.github.jukomu.desktop.feature.export.ExportStore;
import io.github.jukomu.desktop.feature.files.FileReferences;
import io.github.jukomu.desktop.feature.files.FileService;
import io.github.jukomu.desktop.feature.files.model.FolderDescriptorResponse;
import io.github.jukomu.desktop.feature.settings.SettingsService;
import io.github.jukomu.desktop.feature.settings.model.DownloadLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 选择 Desktop 下载目录，并在不破坏源文件的前提下切换下载根目录。
 */
public final class DownloadLocationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadLocationService.class);
    private static final AtomicLong RELOCATION_SEQUENCE = new AtomicLong();

    private final Path privateRoot;
    private final SettingsService settings;
    private final DownloadStore store;
    private final DownloadFiles files;
    private final ExportStore exports;
    private final FileService fileService;
    private final EventHub events;
    private final FileOperations fileOperations;
    private final RelocationLogSink relocationLog;

    public DownloadLocationService(
        Paths paths,
        SettingsService settings,
        DownloadStore store,
        DownloadFiles files,
        ExportStore exports,
        FileService fileService,
        EventHub events
    ) {
        this(paths, settings, store, files, exports, fileService, events,
            new DefaultFileOperations(), DownloadLocationService::writeRelocationLog);
    }

    DownloadLocationService(
        Paths paths,
        SettingsService settings,
        DownloadStore store,
        DownloadFiles files,
        ExportStore exports,
        FileService fileService,
        EventHub events,
        FileOperations fileOperations
    ) {
        this(paths, settings, store, files, exports, fileService, events,
            fileOperations, DownloadLocationService::writeRelocationLog);
    }

    DownloadLocationService(
        Paths paths,
        SettingsService settings,
        DownloadStore store,
        DownloadFiles files,
        ExportStore exports,
        FileService fileService,
        EventHub events,
        FileOperations fileOperations,
        RelocationLogSink relocationLog
    ) {
        this.privateRoot = paths.downloadsDirectory().toAbsolutePath().normalize();
        this.settings = settings;
        this.store = store;
        this.files = files;
        this.exports = exports;
        this.fileService = fileService;
        this.events = events;
        this.fileOperations = fileOperations;
        this.relocationLog = relocationLog;
    }

    public DownloadLocationResponse get() {
        DownloadLocation location = settings.downloadLocation();
        Path root = files.root();
        CleanupResult cleanup = cleanupStatus();
        return new DownloadLocationResponse(
            location.downloadPublic(), root.toString(),
            cleanup.pending(), cleanup.message());
    }

    public void reconcileOnStartup() {
        synchronized (files) {
            RelocationContext context = new RelocationContext("startup-retry", files.root());
            logStarted(context, null, files.root(), "startup");
            CleanupResult cleanup = retryPendingCleanup(context, "startup");
            logCompleted(context, files.root(), 0, cleanup, Map.of());
            if (cleanup.pending()) LOGGER.warn("下载位置清理仍待处理 errorCode=CLEANUP_PENDING");
        }
    }

    public DownloadRelocationResponse set(boolean open) {
        synchronized (files) {
            RelocationContext context = new RelocationContext(open ? "enable" : "disable", files.root());
            logStarted(context, null, files.root(), "setting");
            try {
                int activeTasks = store.listActiveTasks().size();
                if (activeTasks > 0) {
                    reject(context, "ACTIVE_DOWNLOADS", Map.of("activeTaskCount", activeTasks));
                    throw ApiException.conflict("有下载任务未完成，请等待全部完成或取消后再切换");
                }
                int activeExports = exports.activeExportIds().size();
                if (activeExports > 0) {
                    reject(context, "ACTIVE_EXPORTS", Map.of("activeExportCount", activeExports));
                    throw ApiException.conflict("有导出任务未完成，请等待全部完成或取消后再切换");
                }

                DownloadLocation current = settings.downloadLocation();
                Path source = files.root();
                if (current.downloadPublic() == open) {
                    CleanupResult cleanup = retryPendingCleanup(context, "same-state");
                    logCompleted(context, source, 0, cleanup, Map.of());
                    return response(open, 0, source, cleanup);
                }

                Path target;
                try {
                    target = open ? selectTarget() : privateRoot;
                } catch (ApiException exception) {
                    reject(context, "FOLDER_SELECTION_CANCELLED", Map.of());
                    throw exception;
                }
                context.targetRoot = target;
                if (open && target.equals(privateRoot)) {
                    reject(context, "TARGET_PRIVATE_ROOT", Map.of());
                    throw ApiException.conflict("所选目录是应用内部下载目录，请选择其他目录");
                }
                try {
                    validateRoots(source, target);
                } catch (ApiException exception) {
                    reject(context, "ROOT_CONTAINMENT", Map.of());
                    throw exception;
                }
                if (source.equals(target)) {
                    writeSettings(context, open, target, null);
                    CleanupResult cleanup = CleanupResult.complete();
                    logCompleted(context, target, 0, cleanup, Map.of());
                    return response(open, 0, target, cleanup);
                }

                try {
                    validateCompletedDownloads();
                } catch (ApiException exception) {
                    reject(context, "INCOMPLETE_DOWNLOAD", Map.of());
                    throw exception;
                }
                MigrationResult migration = migrate(context, source, target);
                try {
                    writeSettings(context, open, target, source);
                } catch (RuntimeException exception) {
                    rollbackCreatedFiles(context, migration.createdFiles());
                    throw exception;
                }
                files.switchRoot(target);

                CleanupResult cleanup = retryPendingCleanup(context, "post-relocation");
                logCompleted(context, target, migration.moved(), cleanup,
                    Map.of("copiedFileCount", migration.copiedFiles(),
                        "reusedFileCount", migration.reusedFiles()));
                return response(open, migration.moved(), target, cleanup);
            } catch (ApiException exception) {
                if (!context.terminalLogged) {
                    log(context, "relocation_rejected", fields(
                        "status", "rejected", "reasonCode", exception.code().toUpperCase(Locale.ROOT),
                        "elapsedMs", elapsedMs(context)));
                    context.terminalLogged = true;
                }
                throw exception;
            } catch (RuntimeException exception) {
                if (!context.terminalLogged) {
                    log(context, "relocation_failed", fields(
                        "status", "failed", "errorCode", "RELOCATION_FAILED",
                        "errorClass", exception.getClass().getSimpleName(),
                        "elapsedMs", elapsedMs(context)));
                    context.terminalLogged = true;
                }
                throw exception;
            }
        }
    }

    private CleanupResult retryPendingCleanup(RelocationContext context, String trigger) {
        Path pending = settings.pendingDownloadCleanup();
        if (pending == null) return CleanupResult.complete();
        Path current = files.root();
        if (!isManagedRoot(pending)
            || pending.equals(current)
            || pending.startsWith(current)
            || current.startsWith(pending)) {
            log(context, "relocation_cleanup", fields(
                "phase", "deleting", "trigger", trigger, "status", "pending",
                "errorCode", "CLEANUP_PATH_INVALID", "pendingCleanupRoot", pending,
                "elapsedMs", elapsedMs(context)));
            return CleanupResult.pending("下载位置已切换，但旧目录清理状态异常，请手动检查："
                + pending);
        }

        long cleanupStarted = System.nanoTime();
        log(context, "relocation_cleanup", fields(
            "phase", "deleting", "trigger", trigger, "status", "started",
            "pendingCleanupRoot", pending));
        publish(0, 0, "deleting", null);
        try {
            fileOperations.deleteTree(pending);
            settings.clearPendingDownloadCleanup();
            log(context, "relocation_cleanup", fields(
                "phase", "deleting", "trigger", trigger, "status", "succeeded",
                "pendingCleanupRoot", pending, "elapsedMs", elapsedMs(cleanupStarted)));
            return CleanupResult.complete();
        } catch (IOException | RuntimeException exception) {
            String message = "下载位置已切换，但旧目录暂未清理：" + pending
                + "。应用会在下次启动或再次确认此设置时重试。";
            log(context, "relocation_cleanup", fields(
                "phase", "deleting", "trigger", trigger, "status", "pending",
                "errorCode", "CLEANUP_FAILED", "pendingCleanupRoot", pending,
                "errorClass", exception.getClass().getSimpleName(),
                "elapsedMs", elapsedMs(cleanupStarted)));
            LOGGER.warn("下载位置清理失败 errorCode=CLEANUP_FAILED errorClass={}",
                exception.getClass().getSimpleName());
            return CleanupResult.pending(message);
        }
    }

    private CleanupResult cleanupStatus() {
        Path pending = settings.pendingDownloadCleanup();
        if (pending == null) return CleanupResult.complete();
        return CleanupResult.pending("旧下载目录仍待清理：" + pending
            + "。应用会在下次启动或再次确认此设置时重试。");
    }

    private boolean isManagedRoot(Path root) {
        Path fileName = root.getFileName();
        return root.equals(privateRoot)
            || fileName != null && Paths.APPLICATION_NAME.equals(fileName.toString());
    }

    private Path selectTarget() {
        FolderDescriptorResponse selected = fileService.pickFolder("download");
        if (selected == null) throw ApiException.cancelled("目录选择已取消");
        return FileReferences.parseFolder(selected.ref())
            .resolve(Paths.APPLICATION_NAME)
            .toAbsolutePath()
            .normalize();
    }

    private MigrationResult migrate(RelocationContext context, Path source, Path target) {
        List<Path> sourceFiles = collectFiles(source, "源下载目录");
        List<Path> targetFiles = collectFiles(target, "目标下载目录");
        long sourceBytes = totalBytes(sourceFiles);
        long targetBytes = totalBytes(targetFiles);
        logStarted(context, source, target, "setting", sourceFiles.size(), targetFiles.size(),
            sourceBytes, targetBytes);
        Set<Path> sourceRelativePaths = new HashSet<>();
        for (Path sourceFile : sourceFiles) {
            sourceRelativePaths.add(source.relativize(sourceFile));
        }
        for (Path targetFile : targetFiles) {
            Path relative = target.relativize(targetFile);
            Path sourceFile = source.resolve(relative);
            if (!sourceRelativePaths.contains(relative) || !matches(sourceFile, targetFile)) {
                reject(context, "TARGET_CONFLICT", Map.of("relativePath", display(relative)));
                throw ApiException.conflict("目标目录包含与现有下载不一致的文件: " + display(relative));
            }
        }

        List<Path> createdFiles = new ArrayList<>();
        int copiedFiles = 0;
        int reusedFiles = 0;
        long copyStarted = System.nanoTime();
        try {
            fileOperations.createDirectories(target);
            long requiredBytes = 0;
            for (Path sourceFile : sourceFiles) {
                Path targetFile = target.resolve(source.relativize(sourceFile));
                if (Files.exists(targetFile) && !Files.isRegularFile(targetFile)) {
                    throw ApiException.conflict("目标目录包含与现有下载不一致的路径: "
                        + display(source.relativize(sourceFile)));
                }
                if (!Files.isRegularFile(targetFile)) requiredBytes += Files.size(sourceFile);
            }
                if (fileOperations.availableBytes(target) < requiredBytes) {
                reject(context, "INSUFFICIENT_SPACE", fields(
                    "requiredBytes", requiredBytes, "availableBytes", fileOperations.availableBytes(target)));
                throw ApiException.unavailable("目标存储空间不足，需要至少 "
                    + Math.max(1, (requiredBytes + 1024 * 1024 - 1) / (1024 * 1024)) + " MB");
            }

            int total = sourceFiles.size();
            for (int index = 0; index < total; index++) {
                Path sourceFile = sourceFiles.get(index);
                Path relative = source.relativize(sourceFile);
                Path targetFile = target.resolve(relative);
                publish(index, total, "copying", display(relative));
                boolean reused = Files.exists(targetFile);
                long fileStarted = System.nanoTime();
                log(context, "relocation_copy", fields(
                    "phase", "copying", "status", "started", "relativePath", display(relative),
                    "index", index + 1, "total", total, "action", reused ? "reused" : "copied"));
                try {
                    if (!reused) {
                        fileOperations.createDirectories(targetFile.getParent());
                        createdFiles.add(targetFile);
                        fileOperations.copy(sourceFile, targetFile);
                        copiedFiles++;
                    } else {
                        reusedFiles++;
                    }
                    log(context, "relocation_copy", fields(
                        "phase", "copying", "status", "succeeded", "relativePath", display(relative),
                        "index", index + 1, "total", total, "action", reused ? "reused" : "copied",
                        "bytes", sizeOf(sourceFile), "elapsedMs", elapsedMs(fileStarted)));
                } catch (IOException exception) {
                    log(context, "relocation_copy", fields(
                        "phase", "copying", "status", "failed", "errorCode", "COPY_FAILED",
                        "relativePath", display(relative), "index", index + 1, "total", total,
                        "errorClass", exception.getClass().getSimpleName(),
                        "elapsedMs", elapsedMs(fileStarted)));
                    throw exception;
                }
                publish(index + 1, total, "copying", display(relative));
            }
            log(context, "relocation_copy", fields(
                "phase", "copying", "status", "succeeded", "fileCount", total,
                "copiedFileCount", copiedFiles, "reusedFileCount", reusedFiles,
                "elapsedMs", elapsedMs(copyStarted)));

            long verifyStarted = System.nanoTime();
            for (int index = 0; index < total; index++) {
                Path sourceFile = sourceFiles.get(index);
                Path relative = source.relativize(sourceFile);
                Path targetFile = target.resolve(relative);
                publish(index, total, "verifying", display(relative));
                long fileStarted = System.nanoTime();
                log(context, "relocation_verify", fields(
                    "phase", "verifying", "status", "started", "relativePath", display(relative),
                    "index", index + 1, "total", total));
                try {
                    if (!matches(sourceFile, targetFile)) {
                        log(context, "relocation_verify", fields(
                            "phase", "verifying", "status", "failed", "errorCode", "VERIFY_FAILED",
                            "relativePath", display(relative), "index", index + 1, "total", total,
                            "sourceBytes", sizeOf(sourceFile), "targetBytes", sizeOf(targetFile),
                            "elapsedMs", elapsedMs(fileStarted)));
                        throw new IOException("文件校验失败: " + display(relative));
                    }
                    log(context, "relocation_verify", fields(
                        "phase", "verifying", "status", "succeeded", "relativePath", display(relative),
                        "index", index + 1, "total", total, "sourceBytes", sizeOf(sourceFile),
                        "targetBytes", sizeOf(targetFile), "elapsedMs", elapsedMs(fileStarted)));
                } catch (ApiException exception) {
                    log(context, "relocation_verify", fields(
                        "phase", "verifying", "status", "failed", "errorCode", "VERIFY_FAILED",
                        "relativePath", display(relative), "index", index + 1, "total", total,
                        "errorClass", exception.getClass().getSimpleName(),
                        "elapsedMs", elapsedMs(fileStarted)));
                    throw exception;
                }
                publish(index + 1, total, "verifying", display(relative));
            }
            log(context, "relocation_verify", fields(
                "phase", "verifying", "status", "succeeded", "fileCount", total,
                "elapsedMs", elapsedMs(verifyStarted), "copyElapsedMs", elapsedMs(copyStarted)));
            return new MigrationResult(total, copiedFiles, reusedFiles, List.copyOf(createdFiles));
        } catch (ApiException exception) {
            rollbackCreatedFiles(context, createdFiles);
            throw exception;
        } catch (IOException exception) {
            rollbackCreatedFiles(context, createdFiles);
            throw ApiException.unavailable("迁移下载文件失败: " + messageOf(exception));
        }
    }

    private void validateCompletedDownloads() {
        for (StoredDownloadTask task : store.listTasks()) {
            if (!DownloadService.STATUS_COMPLETED.equals(task.status())) continue;
            List<StoredDownloadPage> pages = store.pages(task.taskId());
            boolean complete = task.totalPages() > 0
                && pages.size() == task.totalPages()
                && pages.stream().allMatch(page -> page.completed()
                && Files.isRegularFile(files.resolvePage(page)));
            if (!complete) {
                throw ApiException.conflict("已下载章节文件不完整，不能切换下载目录: "
                    + task.chapterTitle());
            }
        }
    }

    private static List<Path> collectFiles(Path root, String label) {
        if (!Files.exists(root)) return List.of();
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
            throw ApiException.conflict(label + "不是可用文件夹");
        }
        try (var paths = Files.walk(root)) {
            List<Path> entries = paths.sorted().toList();
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry)) {
                    throw ApiException.conflict(label + "包含符号链接: " + display(root.relativize(entry)));
                }
                if (!Files.isDirectory(entry) && !Files.isRegularFile(entry)) {
                    throw ApiException.conflict(label + "包含不支持的文件类型: "
                        + display(root.relativize(entry)));
                }
            }
            return entries.stream().filter(Files::isRegularFile).toList();
        } catch (IOException exception) {
            throw ApiException.unavailable("读取" + label + "失败: " + messageOf(exception));
        }
    }

    private static void validateRoots(Path source, Path target) {
        if (source.equals(target)) return;
        if (source.startsWith(target) || target.startsWith(source)) {
            throw ApiException.conflict("新旧下载目录不能互相包含");
        }
    }

    private RollbackResult rollbackCreatedFiles(RelocationContext context, List<Path> createdFiles) {
        long rollbackStarted = System.nanoTime();
        log(context, "relocation_rollback", fields(
            "phase", "rollback", "status", "started", "createdFileCount", createdFiles.size()));
        int deleted = 0;
        int remaining = 0;
        for (int index = createdFiles.size() - 1; index >= 0; index--) {
            try {
                fileOperations.deleteIfExists(createdFiles.get(index));
                deleted++;
            } catch (IOException exception) {
                remaining++;
                LOGGER.warn("回滚迁移文件失败 errorCode=ROLLBACK_FAILED relativePath={}",
                    context.targetRoot.relativize(createdFiles.get(index)));
                log(context, "relocation_rollback", fields(
                    "phase", "rollback", "status", "failed", "errorCode", "ROLLBACK_FAILED",
                    "relativePath", display(context.targetRoot.relativize(createdFiles.get(index))),
                    "errorClass", exception.getClass().getSimpleName()));
            }
        }
        log(context, "relocation_rollback", fields(
            "phase", "rollback", "status", remaining == 0 ? "succeeded" : "failed",
            "createdFileCount", createdFiles.size(), "deletedFileCount", deleted,
            "remainingFileCount", remaining, "elapsedMs", elapsedMs(rollbackStarted)));
        return new RollbackResult(deleted, remaining);
    }

    private void publish(int current, int total, String phase, String currentFile) {
        events.publish("relocationProgress",
            new RelocationProgressEvent(current, total, phase, currentFile));
    }

    private void writeSettings(RelocationContext context, boolean open, Path target, Path pendingCleanup) {
        long started = System.nanoTime();
        log(context, "relocation_settings", fields(
            "phase", "settings", "status", "started", "sourceRoot", context.sourceRoot,
            "targetRoot", target, "pendingCleanupRoot", pendingCleanup));
        try {
            settings.setDownloadLocation(open, target, pendingCleanup);
            log(context, "relocation_settings", fields(
                "phase", "settings", "status", "succeeded", "sourceRoot", context.sourceRoot,
                "targetRoot", target, "pendingCleanupRoot", pendingCleanup,
                "elapsedMs", elapsedMs(started)));
        } catch (RuntimeException exception) {
            log(context, "relocation_settings", fields(
                "phase", "settings", "status", "failed", "errorCode", "SETTINGS_COMMIT_FAILED",
                "sourceRoot", context.sourceRoot, "targetRoot", target,
                "pendingCleanupRoot", pendingCleanup, "errorClass", exception.getClass().getSimpleName(),
                "elapsedMs", elapsedMs(started)));
            throw exception;
        }
    }

    private void logStarted(RelocationContext context, Path source, Path target, String trigger) {
        if (source != null) context.sourceRoot = source;
        if (target != null) context.targetRoot = target;
        log(context, "relocation_started", fields(
            "status", "started", "operation", context.operation, "trigger", trigger,
            "sourceRoot", context.sourceRoot, "targetRoot", context.targetRoot));
    }

    private void logStarted(
        RelocationContext context,
        Path source,
        Path target,
        String trigger,
        int sourceFileCount,
        int targetFileCount,
        long sourceBytes,
        long targetBytes
    ) {
        if (source != null) context.sourceRoot = source;
        if (target != null) context.targetRoot = target;
        log(context, "relocation_started", fields(
            "status", "started", "operation", context.operation, "trigger", trigger,
            "sourceRoot", context.sourceRoot, "targetRoot", context.targetRoot,
            "sourceFileCount", sourceFileCount, "targetFileCount", targetFileCount,
            "sourceBytes", sourceBytes, "targetBytes", targetBytes));
    }

    private void reject(RelocationContext context, String reasonCode, Map<String, Object> extra) {
        Map<String, Object> values = new LinkedHashMap<>(extra);
        values.put("status", "rejected");
        values.put("reasonCode", reasonCode);
        values.put("elapsedMs", elapsedMs(context));
        log(context, "relocation_rejected", values);
        context.terminalLogged = true;
    }

    private void logCompleted(
        RelocationContext context,
        Path target,
        int moved,
        CleanupResult cleanup,
        Map<String, Object> extra
    ) {
        Map<String, Object> values = new LinkedHashMap<>(extra);
        values.put("status", "succeeded");
        values.put("sourceRoot", context.sourceRoot);
        values.put("targetRoot", target);
        values.put("moved", moved);
        values.put("cleanupPending", cleanup.pending());
        values.put("elapsedMs", elapsedMs(context));
        log(context, "relocation_completed", values);
        context.terminalLogged = true;
    }

    private void log(RelocationContext context, String event, Map<String, Object> values) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("relocationId", context.relocationId);
        fields.put("operation", context.operation);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey().endsWith("Root") && entry.getValue() instanceof Path) {
                fields.put(entry.getKey(), rootLabel(entry.getKey()));
            } else if (!"errorMessage".equals(entry.getKey())) {
                fields.put(entry.getKey(), entry.getValue());
            }
        }
        relocationLog.write(event, fields);
    }

    private static String rootLabel(String field) {
        if ("sourceRoot".equals(field)) return "source-root";
        if ("targetRoot".equals(field)) return "target-root";
        return "pending-cleanup-root";
    }

    private static Map<String, Object> fields(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private static long elapsedMs(RelocationContext context) {
        return elapsedMs(context.startedAtNanos);
    }

    private static long elapsedMs(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private static long sizeOf(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0;
        } catch (IOException exception) {
            return 0;
        }
    }

    private static long totalBytes(List<Path> paths) {
        long total = 0;
        for (Path path : paths) total += sizeOf(path);
        return total;
    }

    private static void writeRelocationLog(String event, Map<String, Object> values) {
        StringBuilder message = new StringBuilder("download-relocation event=").append(event);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getValue() == null) continue;
            message.append(' ').append(entry.getKey()).append('=').append(logValue(entry.getValue()));
        }
        LOGGER.info(message.toString());
    }

    private static String logValue(Object value) {
        return String.valueOf(value).replace(' ', '_').replace('\n', '_').replace('\r', '_');
    }

    private boolean matches(Path source, Path target) {
        try {
            return fileOperations.matches(source, target);
        } catch (IOException exception) {
            throw ApiException.unavailable("校验下载文件失败: " + messageOf(exception));
        }
    }

    private static DownloadRelocationResponse response(
        boolean open,
        int moved,
        Path target,
        CleanupResult cleanup
    ) {
        return new DownloadRelocationResponse(
            true, open, moved, target.toString(), cleanup.pending(), cleanup.message());
    }

    private static String display(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private static String messageOf(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? "文件系统操作失败" : message;
    }

    private record MigrationResult(int moved, int copiedFiles, int reusedFiles, List<Path> createdFiles) {
    }

    private record RollbackResult(int deletedFiles, int remainingFiles) {
    }

    private static final class RelocationContext {
        private final String relocationId = "relocation-" + RELOCATION_SEQUENCE.incrementAndGet();
        private final String operation;
        private final long startedAtNanos = System.nanoTime();
        private Path sourceRoot;
        private Path targetRoot;
        private boolean terminalLogged;

        private RelocationContext(String operation, Path sourceRoot) {
            this.operation = operation;
            this.sourceRoot = sourceRoot;
        }
    }

    @FunctionalInterface
    interface RelocationLogSink {
        void write(String event, Map<String, Object> values);
    }

    private record CleanupResult(boolean pending, String message) {
        private static CleanupResult complete() {
            return new CleanupResult(false, null);
        }

        private static CleanupResult pending(String message) {
            return new CleanupResult(true, message);
        }
    }

    interface FileOperations {
        void createDirectories(Path directory) throws IOException;

        long availableBytes(Path directory) throws IOException;

        void copy(Path source, Path target) throws IOException;

        boolean matches(Path source, Path target) throws IOException;

        void deleteIfExists(Path path) throws IOException;

        void deleteTree(Path root) throws IOException;
    }

    private static final class DefaultFileOperations implements FileOperations {
        @Override
        public void createDirectories(Path directory) throws IOException {
            Files.createDirectories(directory);
        }

        @Override
        public long availableBytes(Path directory) throws IOException {
            return Files.getFileStore(directory).getUsableSpace();
        }

        @Override
        public void copy(Path source, Path target) throws IOException {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        }

        @Override
        public boolean matches(Path source, Path target) throws IOException {
            return Files.isRegularFile(source)
                && Files.isRegularFile(target)
                && Files.mismatch(source, target) == -1;
        }

        @Override
        public void deleteIfExists(Path path) throws IOException {
            Files.deleteIfExists(path);
        }

        @Override
        public void deleteTree(Path root) throws IOException {
            if (!Files.exists(root)) return;
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
