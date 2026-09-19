package io.github.jukomu.desktop.feature.history.model;

import java.util.List;

/** 承载浏览历史概览的时间分组。 */
public record HistoryOverviewRequest(List<Range> ranges) {
    public record Range(String key, Long startInclusive, Long endExclusive) {
    }
}
