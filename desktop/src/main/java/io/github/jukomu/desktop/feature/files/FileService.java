package io.github.jukomu.desktop.feature.files;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.files.model.FileDescriptorResponse;
import io.github.jukomu.desktop.feature.files.model.FileRefsResponse;
import io.github.jukomu.desktop.feature.files.model.FolderDescriptorResponse;
import io.github.jukomu.desktop.feature.files.model.LocalFilesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

import static io.github.jukomu.desktop.util.LogFields.clean;

/**
 * Desktop 目录选择、文件引用与系统打开能力。
 */
public final class FileService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileService.class);
    private final Paths paths;
    private final FolderPicker folderPicker;
    private final Consumer<Path> fileOpener;

    public FileService(Paths paths) {
        this(paths, new SystemFolderPicker(), FileService::openWithDesktop);
    }

    public FileService(Paths paths, FolderPicker folderPicker) {
        this(paths, folderPicker, FileService::openWithDesktop);
    }

    public FileService(Paths paths, FolderPicker folderPicker, Consumer<Path> fileOpener) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.folderPicker = Objects.requireNonNull(folderPicker, "folderPicker");
        this.fileOpener = Objects.requireNonNull(fileOpener, "fileOpener");
    }

    public FolderDescriptorResponse pickFolder(String purpose) {
        String normalizedPurpose = requirePurpose(purpose);
        Path selected = folderPicker.pick(defaultPath(normalizedPurpose));
        return selected == null ? null : folder(selected);
    }

    public FolderDescriptorResponse getDefaultFolder(String purpose) {
        return folder(defaultPath(requirePurpose(purpose)));
    }

    public FileRefsResponse checkFilesExist(List<String> references) {
        if (references == null) throw ApiException.invalidRequest("files必须是数组");
        List<String> existing = references.stream()
            .filter(reference -> Files.isRegularFile(FileReferences.parseFile(reference)))
            .toList();
        return new FileRefsResponse(existing);
    }

    public SuccessResponse openFile(String reference) {
        return open(reference, "file");
    }

    public SuccessResponse openContainingFolder(String reference) {
        long startedNanos = System.nanoTime();
        String operationId = operationId();
        try {
            Path file = FileReferences.parseFile(reference);
            Path parent = file.getParent();
            if (parent == null || !Files.isDirectory(parent)) {
                throw ApiException.notFound("文件所在目录不存在");
            }
            fileOpener.accept(parent);
            logEvent("local_file.open", operationId, "folder", file, "completed",
                elapsed(startedNanos), null, null, null);
            return SuccessResponse.ok();
        } catch (RuntimeException exception) {
            logEvent("local_file.open", operationId, "folder", reference, "failed",
                elapsed(startedNanos), errorCode(exception), exception.getMessage(), exception);
            throw exception;
        }
    }

    public LocalFilesResponse scanImportableFiles(String reference, List<String> formats) {
        long startedNanos = System.nanoTime();
        String operationId = operationId();
        List<String> requested = formats == null || formats.isEmpty() ? List.of("pdf") : formats;
        LOGGER.info("local_file.scan event=started operationId={} requestedFormats={} elapsedMs=0",
            clean(operationId), clean(String.join(",", requested)));
        int candidateCount = 0;
        try {
            if (requested.stream().anyMatch(format -> !"pdf".equals(format) && !"cbz".equals(format))) {
                throw ApiException.invalidRequest("导入扫描仅支持 PDF 和 CBZ");
            }
            Set<String> requestedFormats = Set.copyOf(requested);
            Path folder = FileReferences.parseFolder(reference);
            if (!Files.isDirectory(folder)) throw ApiException.notFound("目录不存在");
            try (var files = Files.list(folder)) {
                List<Path> candidates = files.filter(Files::isRegularFile).toList();
                candidateCount = candidates.size();
                List<FileDescriptorResponse> results = candidates.stream()
                    .filter(path -> requestedFormats.contains(format(path)))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(),
                        String.CASE_INSENSITIVE_ORDER))
                    .map(FileService::file)
                    .toList();
                logEvent("local_file.scan", operationId, folder, "completed", elapsed(startedNanos),
                    candidateCount, results.size(), null, null, null);
                return new LocalFilesResponse(results);
            }
        } catch (IOException exception) {
            logEvent("local_file.scan", operationId, reference, "failed", elapsed(startedNanos),
                candidateCount, 0, "SCAN_IO_FAILED", exception.getMessage(), exception);
            throw new IllegalStateException("扫描可导入文件失败", exception);
        } catch (RuntimeException exception) {
            logEvent("local_file.scan", operationId, reference, "failed", elapsed(startedNanos),
                candidateCount, 0, errorCode(exception), exception.getMessage(), exception);
            throw exception;
        }
    }

    private SuccessResponse open(String reference, String operation) {
        long startedNanos = System.nanoTime();
        String operationId = operationId();
        try {
            Path file = FileReferences.parseFile(reference);
            if (!Files.isRegularFile(file)) throw ApiException.notFound("文件不存在");
            fileOpener.accept(file);
            logEvent("local_file.open", operationId, operation, file, "completed",
                elapsed(startedNanos), null, null, null);
            return SuccessResponse.ok();
        } catch (RuntimeException exception) {
            logEvent("local_file.open", operationId, operation, reference, "failed",
                elapsed(startedNanos), errorCode(exception), exception.getMessage(), exception);
            throw exception;
        }
    }

    private static void logEvent(String event, String operationId, String operation,
                                 Object reference, String status, long elapsedMs,
                                 String errorCode, String message, Throwable failure) {
        String referenceSummary = reference instanceof Path path
            ? path.getFileName() == null ? "-" : path.getFileName().toString()
            : fileName(reference == null ? null : reference.toString());
        String line = "local_file event=" + clean(event)
            + " operationId=" + clean(operationId)
            + " operation=" + clean(operation)
            + " fileName=" + clean(referenceSummary)
            + " status=" + clean(status)
            + " elapsedMs=" + elapsedMs;
        if (errorCode != null && !errorCode.isBlank()) line += " errorCode=" + clean(errorCode);
        if (failure == null) LOGGER.info(line);
        else LOGGER.warn(line + " errorClass=" + failure.getClass().getSimpleName());
    }

    private static void logEvent(String event, String operationId, Object folder, String status,
                                 long elapsedMs, int candidateCount, int matchedCount,
                                 String errorCode, String message, Throwable failure) {
        String line = "local_file event=" + clean(event)
            + " operationId=" + clean(operationId)
            + " folderKind=local-file-root status=" + clean(status)
            + " candidateCount=" + candidateCount + " matchedCount=" + matchedCount
            + " elapsedMs=" + elapsedMs;
        if (errorCode != null && !errorCode.isBlank()) line += " errorCode=" + clean(errorCode);
        if (failure == null) LOGGER.info(line);
        else LOGGER.error(line + " errorClass=" + failure.getClass().getSimpleName());
    }

    private static String errorCode(Throwable exception) {
        if (exception instanceof ApiException apiException) return apiException.code();
        return exception.getClass().getSimpleName();
    }

    private static String fileName(String reference) {
        if (reference == null || reference.isBlank()) return "-";
        int slash = Math.max(reference.lastIndexOf('/'), reference.lastIndexOf('\\'));
        return slash >= 0 && slash + 1 < reference.length()
            ? reference.substring(slash + 1) : reference;
    }

    private static String operationId() {
        return UUID.randomUUID().toString();
    }

    private static long elapsed(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private Path defaultPath(String purpose) {
        return "download".equals(purpose) ? paths.downloadsDirectory() : paths.pdfDirectory();
    }

    private static String requirePurpose(String purpose) {
        if (!"local-file-root".equals(purpose)
            && !"export".equals(purpose)
            && !"download".equals(purpose)) {
            throw ApiException.invalidRequest("purpose无效");
        }
        return purpose;
    }

    private static String format(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) return "pdf";
        if (name.endsWith(".cbz")) return "cbz";
        return "";
    }

    private static FolderDescriptorResponse folder(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return new FolderDescriptorResponse(
            FileReferences.folderRef(normalized), normalized.toString());
    }

    private static FileDescriptorResponse file(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return new FileDescriptorResponse(
            format(normalized),
            FileReferences.fileRef(normalized),
            normalized.getFileName().toString(),
            normalized.toString());
    }

    private static void openWithDesktop(Path path) {
        if (!Desktop.isDesktopSupported()) {
            throw ApiException.unavailable("当前环境不支持系统文件打开功能");
        }
        try {
            Desktop.getDesktop().open(path.toFile());
        } catch (IOException | UnsupportedOperationException exception) {
            throw ApiException.unavailable("无法使用系统程序打开文件");
        }
    }
}
