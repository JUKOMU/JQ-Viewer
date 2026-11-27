package io.github.jukomu.feature.export;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 导出目标的窄范围相对路径规则。
 * 目录引用负责提供根目录，本类只校验并拆分根目录下的文件段。
 */
final class ExportTargetPath {

    private ExportTargetPath() {
    }

    static String normalize(String targetName) {
        List<String> segments = segments(targetName);
        return String.join("/", segments);
    }

    static List<String> segments(String targetName) {
        if (targetName == null || targetName.isEmpty()) {
            throw new IllegalArgumentException("targetName 不能为空");
        }

        String normalized = targetName.replace('\\', '/');
        if (normalized.startsWith("/") || isWindowsAbsolute(normalized)) {
            throw new IllegalArgumentException("targetName 必须是相对路径");
        }

        String[] rawSegments = normalized.split("/", -1);
        List<String> result = new ArrayList<>(rawSegments.length);
        for (int index = 0; index < rawSegments.length; index++) {
            String segment = rawSegments[index];
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("targetName 不能包含空目录段");
            }
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("targetName 不能包含 . 或 .. 目录段");
            }
            String sanitized = index == rawSegments.length - 1
                ? sanitizeFileName(segment)
                : sanitizeSegment(segment);
            if (index == rawSegments.length - 1 && sanitized.isEmpty()) {
                throw new IllegalArgumentException("targetName 文件名不能为空");
            }
            if (!sanitized.isEmpty()) result.add(sanitized);
        }
        return Collections.unmodifiableList(result);
    }

    static String basename(String targetName) {
        List<String> segments = segments(targetName);
        return segments.get(segments.size() - 1);
    }

    static String withVolumeSuffix(String targetName, int startPage, int endPage) {
        String normalized = normalize(targetName);
        int separator = normalized.lastIndexOf('.');
        String extension = separator >= 0 ? normalized.substring(separator) : "";
        String base = separator >= 0 ? normalized.substring(0, separator) : normalized;
        return base + String.format(java.util.Locale.ROOT,
            "_%03d-%03d%s", startPage, endPage, extension);
    }

    private static boolean isWindowsAbsolute(String value) {
        return value.length() >= 2
            && Character.isLetter(value.charAt(0))
            && value.charAt(1) == ':';
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
}
