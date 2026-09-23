package io.github.jukomu.desktop.feature.pdf.model;

import io.github.jukomu.desktop.feature.pdf.data.StoredLocalFileChapter;

/** 本地文件中的章节及其页码范围。 */
public record LocalFileChapterResponse(
        int sequence,
        String albumId,
        String chapterId,
        String chapterTitle,
        int sortOrder,
        int startPage,
        int endPage,
        int pageCount
) {
    public static LocalFileChapterResponse from(StoredLocalFileChapter chapter) {
        return new LocalFileChapterResponse(
                chapter.sequence(), chapter.albumId(), chapter.chapterId(),
                chapter.chapterTitle(), chapter.sortOrder(), chapter.startPage(),
                chapter.endPage(), chapter.pageCount());
    }
}
