package io.github.jukomu.desktop.feature.pdf.data;

/** PDF 文件库中的持久化记录。 */
public record StoredPdfFile(
        long id,
        String fileRef,
        String displayPath,
        String fileName,
        String sourceType,
        String ownership,
        String chapterLinkStatus,
        String albumId,
        String albumTitle,
        String coverUrl,
        String authors,
        String chapterId,
        String chapterTitle,
        int chapterSortOrder,
        Boolean singleEpisode,
        String folderId,
        long fileSize,
        int pageCount,
        String availability,
        String verificationStatus,
        String verificationError,
        long createdAt,
        long updatedAt,
        Long verifiedAt
) {
}
