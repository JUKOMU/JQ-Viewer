package io.github.jukomu.desktop.feature.history.model;

/** 承载一次章节浏览记录。 */
public record HistoryRecordRequest(
        String albumId,
        String albumTitle,
        String coverUrl,
        String authors,
        String chapterId,
        String chapterTitle
) {
}
