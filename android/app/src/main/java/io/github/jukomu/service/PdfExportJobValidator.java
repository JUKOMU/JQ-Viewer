package io.github.jukomu.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates and identifies PDF export jobs before they enter the worker queue.
 */
public final class PdfExportJobValidator {

    private static final Pattern RESOURCE_ID_PATTERN = Pattern.compile("\\d+");

    private PdfExportJobValidator() {
    }

    public static void validate(PdfExportService.ExportJob job) {
        if (job == null) {
            throw new IllegalArgumentException("导出任务不能为空");
        }
        requireResourceId(job.albumId, "albumId");
        requireText(job.savePath, "savePath");

        if ("chapter".equals(job.mode)) {
            requireResourceId(job.chapterId, "chapterId");
            return;
        }
        if (!"merged".equals(job.mode)) {
            throw new IllegalArgumentException("不支持的 PDF 导出模式: " + job.mode);
        }
        if (job.chapters == null || job.chapters.size() < 2) {
            throw new IllegalArgumentException("合并导出至少需要两个章节");
        }

        Set<String> chapterIds = new HashSet<>();
        int previousPositiveOrder = 0;
        for (int i = 0; i < job.chapters.size(); i++) {
            PdfExportService.ExportChapter chapter = job.chapters.get(i);
            if (chapter == null) {
                throw new IllegalArgumentException("chapters[" + i + "] 不能为空");
            }
            requireResourceId(chapter.albumId, "chapters[" + i + "].albumId");
            requireResourceId(chapter.chapterId, "chapters[" + i + "].chapterId");
            if (!job.albumId.equals(chapter.albumId)) {
                throw new IllegalArgumentException("合并导出的章节必须属于同一本漫画");
            }
            if (!chapterIds.add(chapter.chapterId)) {
                throw new IllegalArgumentException("合并导出包含重复章节: " + chapter.chapterId);
            }
            if (chapter.sortOrder > 0) {
                if (chapter.sortOrder < previousPositiveOrder) {
                    throw new IllegalArgumentException("合并导出的章节顺序无效");
                }
                previousPositiveOrder = chapter.sortOrder;
            }
        }
    }

    public static String taskKey(PdfExportService.ExportJob job) {
        if ("merged".equals(job.mode)) {
            StringBuilder key = new StringBuilder("merged:").append(job.albumId).append(':');
            for (int i = 0; i < job.chapters.size(); i++) {
                if (i > 0) key.append(',');
                key.append(job.chapters.get(i).chapterId);
            }
            return key.toString();
        }
        return "chapter:" + job.albumId + ':' + job.chapterId;
    }

    public static List<String> chapterResourceKeys(PdfExportService.ExportJob job) {
        List<String> keys = new ArrayList<>();
        if ("merged".equals(job.mode)) {
            for (PdfExportService.ExportChapter chapter : job.chapters) {
                keys.add(chapter.albumId + ':' + chapter.chapterId);
            }
        } else {
            keys.add(job.albumId + ':' + job.chapterId);
        }
        return keys;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static void requireResourceId(String value, String field) {
        requireText(value, field);
        if (!RESOURCE_ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " 必须是纯数字 ID");
        }
    }
}
