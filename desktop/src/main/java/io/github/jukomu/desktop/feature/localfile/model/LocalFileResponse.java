package io.github.jukomu.desktop.feature.localfile.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.jukomu.desktop.feature.localfile.data.StoredLocalFile;

import java.util.List;

/**
 * 前端本地文件库使用的完整文件快照。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LocalFileResponse(
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
    Boolean isSingleEpisode,
    long createdAt,
    String folderId,
    long fileSize,
    int pageCount,
    String availability,
    String verificationStatus,
    String verificationError,
    long updatedAt,
    Long verifiedAt,
    List<LocalFileChapterResponse> chapters
) {
    public static LocalFileResponse from(StoredLocalFile file) {
        return new LocalFileResponse(
            file.id(), file.format(), file.fileRef(), file.displayPath(), file.fileName(),
            file.sourceType(), file.ownership(), file.chapterLinkStatus(),
            file.albumId(), file.albumTitle(), file.coverUrl(), file.authors(),
            file.chapterId(), file.chapterTitle(), file.chapterSortOrder(),
            file.singleEpisode(), file.createdAt(), file.folderId(), file.fileSize(),
            file.pageCount(), file.availability(), file.verificationStatus(),
            file.verificationError(), file.updatedAt(), file.verifiedAt(),
            file.chapters().stream().map(LocalFileChapterResponse::from).toList()
        );
    }
}
