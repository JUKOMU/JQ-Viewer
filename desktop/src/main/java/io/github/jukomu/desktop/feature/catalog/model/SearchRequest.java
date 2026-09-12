package io.github.jukomu.desktop.feature.catalog.model;

/** 承载搜索和分类页使用的查询参数。 */
public record SearchRequest(
        String keyword,
        String category,
        String orderBy,
        String time,
        Integer searchMainTag,
        Integer page
) {
}
