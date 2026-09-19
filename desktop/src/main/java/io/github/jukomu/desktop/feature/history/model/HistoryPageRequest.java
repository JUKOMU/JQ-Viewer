package io.github.jukomu.desktop.feature.history.model;

/** 承载浏览历史分页和时间范围参数。 */
public record HistoryPageRequest(
        Integer limit,
        Integer offset,
        Long startInclusive,
        Long endExclusive
) {
}
