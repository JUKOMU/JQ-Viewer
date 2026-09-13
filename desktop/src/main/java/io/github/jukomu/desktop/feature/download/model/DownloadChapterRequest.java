package io.github.jukomu.desktop.feature.download.model;

/** 提交章节下载所需的展示与远端标识。 */
public record DownloadChapterRequest(
        String albumId,
        String chapterId,
        String albumTitle,
        String chapterTitle,
        String coverUrl
) {
}
