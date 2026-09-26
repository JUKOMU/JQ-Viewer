package io.github.jukomu.desktop.feature.files;

import io.github.jukomu.desktop.bridge.ApiException;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Desktop 文件引用的编码与校验边界。
 */
public final class FileReferences {
    private static final String FILE_PREFIX = "file:path:";
    private static final String FOLDER_PREFIX = "folder:path:";

    private FileReferences() {
    }

    public static String fileRef(Path path) {
        return FILE_PREFIX + normalize(path).toString();
    }

    public static String folderRef(Path path) {
        return FOLDER_PREFIX + normalize(path).toString();
    }

    public static Path parseFile(String reference) {
        return parse(reference, FILE_PREFIX, "文件引用");
    }

    public static Path parseFolder(String reference) {
        return parse(reference, FOLDER_PREFIX, "目录引用");
    }

    private static Path parse(String reference, String prefix, String label) {
        if (reference == null || !reference.startsWith(prefix)) {
            throw ApiException.invalidRequest(label + "无效");
        }
        String rawPath = reference.substring(prefix.length());
        if (rawPath.isBlank()) throw ApiException.invalidRequest(label + "无效");
        try {
            Path path = Path.of(rawPath);
            if (!path.isAbsolute()) throw ApiException.invalidRequest(label + "必须指向绝对路径");
            return path.normalize();
        } catch (InvalidPathException exception) {
            throw ApiException.invalidRequest(label + "包含无效路径");
        }
    }

    private static Path normalize(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }
}
