package io.github.jukomu.desktop.feature.pdf.model;

/** 单个外部 PDF 的导入元数据。 */
public record ImportPdfItemRequest(
        String fileRef,
        String displayPath,
        String fileName,
        String albumId,
        String albumTitle,
        String coverUrl,
        String authors,
        String chapterId,
        String chapterTitle,
        Integer chapterSortOrder,
        Boolean isSingleEpisode,
        String folderId
) {
}
