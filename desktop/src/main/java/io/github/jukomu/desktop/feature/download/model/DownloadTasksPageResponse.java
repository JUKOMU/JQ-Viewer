package io.github.jukomu.desktop.feature.download.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DownloadTasksPageResponse(
    List<?> items,
    String nextCursor,
    String previousCursor,
    int totalCount,
    long usedBytes,
    long availableBytes
) {
}
