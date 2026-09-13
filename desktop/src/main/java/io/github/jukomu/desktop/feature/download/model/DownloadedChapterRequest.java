package io.github.jukomu.desktop.feature.download.model;

/** 指向作品中的一个已下载章节。 */
public record DownloadedChapterRequest(String albumId, String chapterId) {
}
