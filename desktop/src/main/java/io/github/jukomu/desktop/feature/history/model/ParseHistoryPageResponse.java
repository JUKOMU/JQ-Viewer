package io.github.jukomu.desktop.feature.history.model;

import java.util.List;

/** 返回解析历史分页结果。 */
public record ParseHistoryPageResponse(List<ParseHistoryItemResponse> items, long totalCount) {
}
