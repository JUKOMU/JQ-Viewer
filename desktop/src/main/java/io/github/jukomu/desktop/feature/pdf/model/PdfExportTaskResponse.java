package io.github.jukomu.desktop.feature.pdf.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 前端可观察的持久化 PDF 导出任务快照。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PdfExportTaskResponse(
        Boolean accepted,
        String exportId,
        String batchId,
        String mode,
        String albumId,
        String albumTitle,
        String coverUrl,
        String authors,
        Boolean isSingleEpisode,
        String chapterId,
        String displayTitle,
        String targetFolderRef,
        String targetName,
        String outputFileRef,
        String displayPath,
        boolean allowOverwrite,
        boolean useOriginal,
        double compressionRatio,
        int splitPages,
        String status,
        String phase,
        int currentPage,
        int totalPages,
        int currentVolume,
        int totalVolumes,
        long snapshotRevision,
        boolean cancelRequested,
        String errorCode,
        String errorMessage,
        long createdAt,
        Long startedAt,
        long updatedAt,
        Long completedAt
) {
    public PdfExportTaskResponse withAccepted(boolean value) {
        return new PdfExportTaskResponse(
                value, exportId, batchId, mode, albumId, albumTitle, coverUrl, authors,
                isSingleEpisode, chapterId, displayTitle, targetFolderRef, targetName,
                outputFileRef, displayPath, allowOverwrite, useOriginal, compressionRatio,
                splitPages, status, phase, currentPage, totalPages, currentVolume,
                totalVolumes, snapshotRevision, cancelRequested, errorCode, errorMessage,
                createdAt, startedAt, updatedAt, completedAt
        );
    }

    public static PdfExportTaskResponse rejected(String code, String message, String displayPath) {
        return new PdfExportTaskResponse(
                false, null, null, null, null, null, null, null, null, null, null,
                null, null, null, displayPath, false, true, 1D, 0, null, null,
                0, 0, 0, 0, 0, false, code, message, 0, null, 0, null
        );
    }
}
