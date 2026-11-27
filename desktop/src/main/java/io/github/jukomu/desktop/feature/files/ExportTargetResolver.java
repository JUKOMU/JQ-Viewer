package io.github.jukomu.desktop.feature.files;

import io.github.jukomu.desktop.bridge.ApiException;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 将 FolderRef 与目录内相对路径解析为受约束的本地导出目标。
 */
public final class ExportTargetResolver {
    private ExportTargetResolver() {
    }

    public static Path resolve(String folderReference, String relativePath) {
        Path root = FileReferences.parseFolder(folderReference);
        String normalizedPath = normalizeRelativePath(relativePath);

        try {
            Path relative = Path.of(normalizedPath);
            if (relative.isAbsolute()) {
                throw ApiException.invalidRequest("relativePath必须是相对路径");
            }
            Path canonicalRoot = root.toFile().getCanonicalFile().toPath();
            Path target = canonicalRoot.resolve(relative).toFile().getCanonicalFile().toPath();
            if (target.equals(canonicalRoot) || !target.startsWith(canonicalRoot)) {
                throw ApiException.invalidRequest("导出目标超出所选目录");
            }
            return target;
        } catch (InvalidPathException exception) {
            throw ApiException.invalidRequest("relativePath包含无效路径");
        } catch (IOException exception) {
            throw ApiException.invalidRequest("导出目标路径无法解析");
        }
    }

    public static String normalizeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw ApiException.invalidRequest("relativePath不能为空");
        }

        String normalized = relativePath.replace('\\', '/');
        if (normalized.startsWith("/") || isWindowsAbsolute(normalized)) {
            throw ApiException.invalidRequest("relativePath必须是相对路径");
        }

        String[] rawSegments = normalized.split("/", -1);
        StringBuilder result = new StringBuilder(normalized.length());
        for (int index = 0; index < rawSegments.length; index++) {
            String segment = rawSegments[index];
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw ApiException.invalidRequest("relativePath包含无效路径段");
            }
            String sanitized = index == rawSegments.length - 1
                ? sanitizeFileName(segment)
                : sanitizeSegment(segment);
            if (index == rawSegments.length - 1 && sanitized.isEmpty()) {
                throw ApiException.invalidRequest("relativePath文件名不能为空");
            }
            if (sanitized.isEmpty()) continue;
            if (result.length() > 0) result.append('/');
            result.append(sanitized);
        }
        return result.toString();
    }

    private static String sanitizeSegment(String segment) {
        String sanitized = segment
            .replace(':', '_')
            .replace('*', '_')
            .replace('?', '_')
            .replace('"', '_')
            .replace('<', '_')
            .replace('>', '_')
            .replace('|', '_')
            .replaceAll("_+", "_")
            .replaceAll("^[_.\\s]+|[_.\\s]+$", "");
        return sanitized.substring(0, Math.min(255, sanitized.length()));
    }

    private static String sanitizeFileName(String segment) {
        int separator = segment.lastIndexOf('.');
        String extension = separator >= 0 ? segment.substring(separator) : "";
        String base = separator >= 0 ? segment.substring(0, separator) : segment;
        String sanitizedBase = sanitizeSegment(base);
        int maxBaseLength = Math.max(0, 255 - extension.length());
        return sanitizedBase.substring(0, Math.min(maxBaseLength, sanitizedBase.length())) + extension;
    }

    private static boolean isWindowsAbsolute(String value) {
        return value.length() >= 2
            && Character.isLetter(value.charAt(0))
            && value.charAt(1) == ':';
    }
}
