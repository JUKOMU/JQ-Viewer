package io.github.jukomu.desktop.feature.pdf.model;

/** 单个外部本地文件的导入元数据。 */
public record ImportLocalFileItemRequest(
        String format,
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
