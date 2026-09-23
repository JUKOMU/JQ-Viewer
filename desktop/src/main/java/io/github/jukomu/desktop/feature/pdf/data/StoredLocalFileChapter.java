package io.github.jukomu.desktop.feature.pdf.data;

/** 本地文件中一个可阅读章节的持久化页码映射。 */
public record StoredLocalFileChapter(
        int sequence,
        String albumId,
        String chapterId,
        String chapterTitle,
        int sortOrder,
        int startPage,
        int endPage,
        int pageCount
) {
}
