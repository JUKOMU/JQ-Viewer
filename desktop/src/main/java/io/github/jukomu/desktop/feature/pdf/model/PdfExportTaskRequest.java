package io.github.jukomu.desktop.feature.pdf.model;

import java.util.List;

/** 单个 PDF 导出任务的公共输入。 */
public record PdfExportTaskRequest(
        String mode,
        String albumId,
        String albumTitle,
        String coverUrl,
        String authors,
        Boolean isSingleEpisode,
        String chapterId,
        String chapterTitle,
        List<PdfExportChapterRequest> chapters,
        PdfExportTargetRequest target,
        String displayPath,
        Boolean useOriginal,
        Double compressionRatio,
        Integer splitPages,
        Boolean allowOverwrite
) {
}
