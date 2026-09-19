package io.github.jukomu.desktop.feature.history.model;

import java.util.Map;

/** 返回浏览历史总数和各时间分组数量。 */
public record HistoryOverviewResponse(long totalCount, Map<String, Long> groupCounts) {
}
