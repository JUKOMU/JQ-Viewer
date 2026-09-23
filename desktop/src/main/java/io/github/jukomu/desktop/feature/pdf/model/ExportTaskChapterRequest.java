package io.github.jukomu.desktop.feature.pdf.model;

/** 合并导出中的单个章节。 */
public record ExportTaskChapterRequest(
        String albumId,
        String chapterId,
        String chapterTitle,
        Integer sortOrder
) {
}
