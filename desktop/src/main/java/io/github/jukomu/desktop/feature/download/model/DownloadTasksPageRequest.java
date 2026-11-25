package io.github.jukomu.desktop.feature.download.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DownloadTasksPageRequest(
    String status,
    String sort,
    String direction,
    String cursor,
    Integer limit,
    String search
) {
    public DownloadTasksPageRequest(String status, String sort, String direction, String cursor, Integer limit) {
        this(status, sort, direction, cursor, limit, null);
    }

    public int effectiveLimit() {
        return Math.max(1, Math.min(50, limit == null ? 50 : limit));
    }
}
