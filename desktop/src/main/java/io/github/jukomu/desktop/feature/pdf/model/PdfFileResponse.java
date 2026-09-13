package io.github.jukomu.desktop.feature.pdf.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.jukomu.desktop.feature.pdf.data.StoredPdfFile;

/** 前端 PDF 文件库使用的完整文件快照。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PdfFileResponse(
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
        Boolean isSingleEpisode,
        long createdAt,
        String folderId,
        long fileSize,
        int pageCount,
        String availability,
        String verificationStatus,
        String verificationError,
        long updatedAt,
        Long verifiedAt
) {
    public static PdfFileResponse from(StoredPdfFile file) {
        return new PdfFileResponse(
                file.id(), file.fileRef(), file.displayPath(), file.fileName(),
                file.sourceType(), file.ownership(), file.chapterLinkStatus(),
                file.albumId(), file.albumTitle(), file.coverUrl(), file.authors(),
                file.chapterId(), file.chapterTitle(), file.chapterSortOrder(),
                file.singleEpisode(), file.createdAt(), file.folderId(), file.fileSize(),
                file.pageCount(), file.availability(), file.verificationStatus(),
                file.verificationError(), file.updatedAt(), file.verifiedAt()
        );
    }
}
