package io.github.jukomu.desktop.feature.pdf.data;

import java.util.List;

/** 本地文件库中的持久化记录。 */
public record StoredLocalFile(
        long id,
        String format,
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
        Long verifiedAt,
        List<StoredLocalFileChapter> chapters
) {
}
