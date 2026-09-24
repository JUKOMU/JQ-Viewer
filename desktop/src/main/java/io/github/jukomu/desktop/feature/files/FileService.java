package io.github.jukomu.desktop.feature.files;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.files.model.FileDescriptorResponse;
import io.github.jukomu.desktop.feature.files.model.FileRefsResponse;
import io.github.jukomu.desktop.feature.files.model.FolderDescriptorResponse;
import io.github.jukomu.desktop.feature.files.model.LocalFilesResponse;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Desktop 目录选择、文件引用与系统打开能力。 */
public final class FileService {
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
        Path file = FileReferences.parseFile(reference);
        if (!Files.isRegularFile(file)) throw ApiException.notFound("文件不存在");
        fileOpener.accept(file);
        return SuccessResponse.ok();
    }

    public SuccessResponse openContainingFolder(String reference) {
        Path file = FileReferences.parseFile(reference);
        Path parent = file.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw ApiException.notFound("文件所在目录不存在");
        }
        fileOpener.accept(parent);
        return SuccessResponse.ok();
    }

    public LocalFilesResponse scanImportableFiles(String reference, List<String> formats) {
        List<String> requested = formats == null || formats.isEmpty() ? List.of("pdf") : formats;
        if (requested.stream().anyMatch(format -> !"pdf".equals(format) && !"cbz".equals(format))) {
            throw ApiException.invalidRequest("导入扫描仅支持 PDF 和 CBZ");
        }
        Set<String> requestedFormats = Set.copyOf(requested);
        Path folder = FileReferences.parseFolder(reference);
        if (!Files.isDirectory(folder)) throw ApiException.notFound("目录不存在");
        try (var files = Files.list(folder)) {
            List<FileDescriptorResponse> results = files
                    .filter(Files::isRegularFile)
                    .filter(path -> requestedFormats.contains(format(path)))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER))
                    .map(FileService::file)
                    .toList();
            return new LocalFilesResponse(results);
        } catch (IOException exception) {
            throw new IllegalStateException("扫描可导入文件失败", exception);
        }
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
