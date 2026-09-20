package io.github.jukomu.desktop.feature.history.model;

import java.util.List;

/** 返回浏览历史分页结果。 */
public record HistoryPageResponse(List<HistoryItemResponse> items, long totalCount) {
}
