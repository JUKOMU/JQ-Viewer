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
import io.github.jukomu.desktop.feature.files.FileReferences;
import io.github.jukomu.desktop.feature.files.FileService;
import io.github.jukomu.desktop.feature.files.model.FolderDescriptorResponse;
import io.github.jukomu.desktop.feature.pdf.export.PdfExportStore;
import io.github.jukomu.desktop.feature.settings.SettingsService;
import io.github.jukomu.desktop.feature.settings.model.DownloadLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 选择 Desktop 下载目录，并在不破坏源文件的前提下切换下载根目录。 */
public final class DownloadLocationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadLocationService.class);

    private final Path privateRoot;
    private final SettingsService settings;
    private final DownloadStore store;
    private final DownloadFiles files;
    private final PdfExportStore pdfExports;
    private final FileService fileService;
    private final EventHub events;
    private final FileOperations fileOperations;

    public DownloadLocationService(
            Paths paths,
            SettingsService settings,
            DownloadStore store,
            DownloadFiles files,
            PdfExportStore pdfExports,
            FileService fileService,
            EventHub events
    ) {
        this(paths, settings, store, files, pdfExports, fileService, events,
                new DefaultFileOperations());
    }

    DownloadLocationService(
            Paths paths,
            SettingsService settings,
            DownloadStore store,
            DownloadFiles files,
            PdfExportStore pdfExports,
            FileService fileService,
            EventHub events,
            FileOperations fileOperations
    ) {
        this.privateRoot = paths.downloadsDirectory().toAbsolutePath().normalize();
        this.settings = settings;
        this.store = store;
        this.files = files;
        this.pdfExports = pdfExports;
        this.fileService = fileService;
        this.events = events;
        this.fileOperations = fileOperations;
    }

    public DownloadLocationResponse get() {
        DownloadLocation location = settings.downloadLocation();
        Path root = files.root();
        return new DownloadLocationResponse(location.downloadPublic(), root.toString());
    }

    public DownloadRelocationResponse set(boolean open) {
        synchronized (files) {
            if (!store.listActiveTasks().isEmpty()) {
                throw ApiException.conflict("有下载任务未完成，请等待全部完成或取消后再切换");
            }
            if (!pdfExports.activeExportIds().isEmpty()) {
                throw ApiException.conflict("有 PDF 导出任务未完成，请等待全部完成或取消后再切换");
            }

            DownloadLocation current = settings.downloadLocation();
            Path source = files.root();
            if (current.downloadPublic() == open) {
                return response(open, 0, source);
            }

            Path target = open ? selectTarget() : privateRoot;
            if (open && target.equals(privateRoot)) {
                throw ApiException.conflict("所选目录是应用内部下载目录，请选择其他目录");
            }
            validateRoots(source, target);
            if (source.equals(target)) {
                settings.setDownloadLocation(open, target);
                return response(open, 0, target);
            }

            validateCompletedDownloads();
            MigrationResult migration = migrate(source, target);
            try {
                settings.setDownloadLocation(open, target);
                files.switchRoot(target);
            } catch (RuntimeException exception) {
                rollbackCreatedFiles(migration.createdFiles());
                throw exception;
            }

            publish(migration.moved(), migration.moved(), "deleting", null);
            try {
                fileOperations.deleteTree(source);
            } catch (IOException exception) {
                LOGGER.warn("下载目录已切换，但旧目录清理失败: {}", source, exception);
            }
            return response(open, migration.moved(), target);
        }
    }

    private Path selectTarget() {
        FolderDescriptorResponse selected = fileService.pickFolder("download");
        if (selected == null) throw ApiException.cancelled("目录选择已取消");
        return FileReferences.parseFolder(selected.ref())
                .resolve(Paths.APPLICATION_NAME)
                .toAbsolutePath()
                .normalize();
    }

    private MigrationResult migrate(Path source, Path target) {
        List<Path> sourceFiles = collectFiles(source, "源下载目录");
        List<Path> targetFiles = collectFiles(target, "目标下载目录");
        Set<Path> sourceRelativePaths = new HashSet<>();
        for (Path sourceFile : sourceFiles) {
            sourceRelativePaths.add(source.relativize(sourceFile));
        }
        for (Path targetFile : targetFiles) {
            Path relative = target.relativize(targetFile);
            Path sourceFile = source.resolve(relative);
            if (!sourceRelativePaths.contains(relative) || !matches(sourceFile, targetFile)) {
                throw ApiException.conflict("目标目录包含与现有下载不一致的文件: " + display(relative));
            }
        }

        List<Path> createdFiles = new ArrayList<>();
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
                throw ApiException.unavailable("目标存储空间不足，需要至少 "
                        + Math.max(1, (requiredBytes + 1024 * 1024 - 1) / (1024 * 1024)) + " MB");
            }

            int total = sourceFiles.size();
            for (int index = 0; index < total; index++) {
                Path sourceFile = sourceFiles.get(index);
                Path relative = source.relativize(sourceFile);
                Path targetFile = target.resolve(relative);
                publish(index, total, "copying", display(relative));
                if (!Files.exists(targetFile)) {
                    fileOperations.createDirectories(targetFile.getParent());
                    createdFiles.add(targetFile);
                    fileOperations.copy(sourceFile, targetFile);
                }
                publish(index + 1, total, "copying", display(relative));
            }

            for (int index = 0; index < total; index++) {
                Path sourceFile = sourceFiles.get(index);
                Path relative = source.relativize(sourceFile);
                Path targetFile = target.resolve(relative);
                publish(index, total, "verifying", display(relative));
                if (!matches(sourceFile, targetFile)) {
                    throw new IOException("文件校验失败: " + display(relative));
                }
                publish(index + 1, total, "verifying", display(relative));
            }
            return new MigrationResult(total, List.copyOf(createdFiles));
        } catch (ApiException exception) {
            rollbackCreatedFiles(createdFiles);
            throw exception;
        } catch (IOException exception) {
            rollbackCreatedFiles(createdFiles);
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

    private void rollbackCreatedFiles(List<Path> createdFiles) {
        for (int index = createdFiles.size() - 1; index >= 0; index--) {
            try {
                fileOperations.deleteIfExists(createdFiles.get(index));
            } catch (IOException exception) {
                LOGGER.warn("回滚迁移文件失败: {}", createdFiles.get(index), exception);
            }
        }
    }

    private void publish(int current, int total, String phase, String currentFile) {
        events.publish("relocationProgress",
                new RelocationProgressEvent(current, total, phase, currentFile));
    }

    private boolean matches(Path source, Path target) {
        try {
            return fileOperations.matches(source, target);
        } catch (IOException exception) {
            throw ApiException.unavailable("校验下载文件失败: " + messageOf(exception));
        }
    }

    private static DownloadRelocationResponse response(boolean open, int moved, Path target) {
        return new DownloadRelocationResponse(true, open, moved, target.toString());
    }

    private static String display(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private static String messageOf(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? "文件系统操作失败" : message;
    }

    private record MigrationResult(int moved, List<Path> createdFiles) {
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
