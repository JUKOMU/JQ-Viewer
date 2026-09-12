package io.github.jukomu.desktop.feature.catalog.model;

/** 承载作品评论的分页查询参数。 */
public record CommentsRequest(String albumId, Integer page) {
}
