package io.github.jukomu.desktop.feature.localfile.model;

import java.util.List;

/**
 * 单个导出任务的公共输入。
 */
public record ExportTaskRequest(
    String format,
    String mode,
    String albumId,
    String albumTitle,
    String coverUrl,
    String authors,
    Boolean isSingleEpisode,
    String chapterId,
    String chapterTitle,
    List<ExportTaskChapterRequest> chapters,
    ExportTargetRequest target,
    String displayPath,
    Boolean useOriginal,
    Double compressionRatio,
    Integer splitPages,
    Boolean allowOverwrite
) {
}
