package io.github.jukomu.desktop.feature.catalog.model;

import java.util.List;

/** 返回作品评论及递归回复列表。 */
public record CommentListResponse(int total, List<Comment> list) {
    public record Comment(
            String commentId,
            String userId,
            String username,
            String nickname,
            String content,
            String postDate,
            String photo,
            String expinfo,
            String aid,
            String name,
            int likes,
            int voteUp,
            int voteDown,
            List<Comment> replys
    ) {
    }
}
