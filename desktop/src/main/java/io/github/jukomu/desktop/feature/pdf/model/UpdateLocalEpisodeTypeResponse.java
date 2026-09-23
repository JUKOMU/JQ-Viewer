package io.github.jukomu.desktop.feature.pdf.model;

/** 本地章节类型更新数量。 */
public record UpdateLocalEpisodeTypeResponse(
        boolean success,
        int updatedDownloads,
        int updatedLocalFiles
) {
}
