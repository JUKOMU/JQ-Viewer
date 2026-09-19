package io.github.jukomu.desktop.feature.history.model;

/** 表示一条持久化浏览记录。 */
public record HistoryItemResponse(
        long id,
        String albumId,
        String albumTitle,
        String coverUrl,
        String authors,
        String chapterId,
        String chapterTitle,
        long timestamp
) {
}
