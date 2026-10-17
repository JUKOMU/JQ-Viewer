package io.github.jukomu.desktop.feature.download;

import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.download.data.StoredDownloadPage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 管理下载根目录内的安全路径、清理和空间统计。
 */
public final class DownloadFiles {
    private Path root;

    public DownloadFiles(Paths paths) {
        this(paths.downloadsDirectory());
    }

    public DownloadFiles(Path root) {
        this.root = normalizeRoot(root);
    }

    public synchronized Path root() {
        return root;
    }

    public synchronized void switchRoot(Path root) {
        this.root = normalizeRoot(root);
    }

    public String relativeDirectory(String albumId, String chapterId) {
        validateSegment(albumId, "albumId");
        validateSegment(chapterId, "chapterId");
        return albumId + "/" + chapterId;
    }

    public String relativeImagePath(String relativeDirectory, String filename) {
        validateFilename(filename);
        Path relative = relativePath(relativeDirectory).resolve(filename).normalize();
        resolve(relative.toString());
        return relative.toString().replace('\\', '/');
    }

    public synchronized Path chapterDirectory(String relativeDirectory) {
        return resolve(relativeDirectory);
    }

    public synchronized void prepareChapter(String relativeDirectory) {
        cleanup(relativeDirectory);
        try {
            Files.createDirectories(chapterDirectory(relativeDirectory));
        } catch (IOException exception) {
            throw failure("创建下载目录失败", exception);
        }
    }

    public synchronized void cleanup(String relativeDirectory) {
        Path directory = chapterDirectory(relativeDirectory);
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw failure("清理下载目录失败", exception);
        }
    }

    public synchronized Path resolvePage(StoredDownloadPage page) {
        return resolve(page.relativePath());
    }

    public synchronized long directorySize(String relativeDirectory) {
        Path directory = chapterDirectory(relativeDirectory);
        if (!Files.exists(directory)) return 0;
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                .mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (IOException exception) {
                        throw failure("统计下载文件大小失败", exception);
                    }
                })
                .sum();
        } catch (IOException exception) {
            throw failure("统计下载目录大小失败", exception);
        }
    }

    public synchronized long usedBytes() {
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw failure("创建下载根目录失败", exception);
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                .mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (IOException exception) {
                        throw failure("统计下载空间失败", exception);
                    }
                })
                .sum();
        } catch (IOException exception) {
            throw failure("统计下载空间失败", exception);
        }
    }

    public synchronized long availableBytes() {
        try {
            Files.createDirectories(root);
            return Files.getFileStore(root).getUsableSpace();
        } catch (IOException exception) {
            throw failure("读取下载目录可用空间失败", exception);
        }
    }

    private Path resolve(String relativePath) {
        Path relative = relativePath(relativePath);
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException("下载路径超出根目录");
        }
        return resolved;
    }

    private static Path relativePath(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("下载路径不能为空");
        Path path = java.nio.file.Paths.get(value);
        if (path.isAbsolute()) throw new IllegalArgumentException("下载路径必须是相对路径");
        Path normalized = path.normalize();
        if (normalized.getNameCount() == 0 || normalized.startsWith("..")) {
            throw new IllegalArgumentException("下载路径无效");
        }
        return normalized;
    }

    private static void validateSegment(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
        if (".".equals(value) || "..".equals(value)
            || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + "包含无效路径字符");
        }
    }

    private static void validateFilename(String value) {
        validateSegment(value, "filename");
    }

    private static IllegalStateException failure(String message, IOException exception) {
        return new IllegalStateException(message, exception);
    }

    private static Path normalizeRoot(Path root) {
        return java.util.Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

}
