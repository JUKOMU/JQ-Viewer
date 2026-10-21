package io.github.jukomu.desktop.feature.download.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompletedDownloadGroupResponse(
    String type,
    String albumId,
    String albumTitle,
    String coverUrl,
    String author,
    Boolean isSingleEpisode,
    List<DownloadTaskResponse> chapters,
    long totalSize,
    Long completedAt
) {
}
