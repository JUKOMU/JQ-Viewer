package io.github.jukomu.desktop.feature.history.model;

/**
 * 承载解析历史分页参数。
 */
public record ParseHistoryPageRequest(Integer limit, Integer offset) {
}
