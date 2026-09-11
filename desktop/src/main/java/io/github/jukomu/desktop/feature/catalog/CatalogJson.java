package io.github.jukomu.desktop.feature.catalog;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jukomu.jmcomic.api.model.JmAlbum;
import io.github.jukomu.jmcomic.api.model.JmAlbumMeta;
import io.github.jukomu.jmcomic.api.model.JmCategoryMeta;
import io.github.jukomu.jmcomic.api.model.JmComment;
import io.github.jukomu.jmcomic.api.model.JmCommentList;
import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.api.model.JmPhoto;
import io.github.jukomu.jmcomic.api.model.JmPhotoMeta;
import io.github.jukomu.jmcomic.api.model.JmSearchPage;

import java.util.List;
import java.util.function.Function;

/** 将上游 JM 模型转换为前端既有的目录 JSON 契约。 */
public final class CatalogJson {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private CatalogJson() {
    }

    public static ObjectNode searchPage(JmSearchPage page, Function<String, String> coverUrl) {
        ObjectNode result = JSON.objectNode()
                .put("currentPage", page.getCurrentPage())
                .put("totalItems", page.getTotalItems())
                .put("totalPages", page.getTotalPages());
        ArrayNode content = result.putArray("content");
        for (JmAlbumMeta item : list(page.getContent())) {
            ObjectNode row = content.addObject()
                    .put("id", text(item.getId()))
                    .put("title", text(item.getTitle()))
                    .put("coverUrl", text(coverUrl.apply(item.getId())));
            strings(row.putArray("authors"), item.getAuthors());
            strings(row.putArray("tags"), item.getTags());
        }
        return result;
    }

    public static ObjectNode album(JmAlbum album, Function<String, String> coverUrl) {
        ObjectNode result = JSON.objectNode()
                .put("id", text(album.getId()))
                .put("title", text(album.getTitle()))
                .put("description", text(album.getDescription()))
                .put("addTime", text(album.getAddTime()))
                .put("pageCount", album.getPageCount())
                .put("likes", text(album.getLikes()))
                .put("views", text(album.getViews()))
                .put("commentCount", album.getCommentCount())
                .put("image", text(coverUrl.apply(album.getId())))
                .put("seriesId", text(album.getSeriesId()))
                .put("isSingleEpisode", album.isSingleAlbum())
                .put("isFavorite", album.isFavorite())
                .put("isLiked", album.isLiked())
                .put("price", text(album.getPrice()))
                .put("purchased", text(album.getPurchased()));
        putCategory(result, "category", album.getCategory());
        putCategory(result, "subCategory", album.getSubCategory());
        strings(result.putArray("authors"), album.getAuthors());
        strings(result.putArray("works"), album.getWorks());
        strings(result.putArray("actors"), album.getActors());
        strings(result.putArray("tags"), album.getTags());

        ArrayNode related = result.putArray("relatedAlbums");
        for (JmAlbumMeta item : list(album.getRelatedAlbums())) {
            ObjectNode row = related.addObject()
                    .put("id", text(item.getId()))
                    .put("title", text(item.getTitle()))
                    .put("coverUrl", text(coverUrl.apply(item.getId())))
                    .put("description", text(item.getDescription()))
                    .put("image", text(item.getImage()));
            strings(row.putArray("authors"), item.getAuthors());
            strings(row.putArray("tags"), item.getTags());
            putCategory(row, "category", item.getCategory());
            putCategory(row, "subCategory", item.getSubCategory());
        }

        ArrayNode photos = result.putArray("photoMetas");
        for (JmPhotoMeta item : list(album.getPhotoMetas())) {
            photos.addObject()
                    .put("id", text(item.getId()))
                    .put("title", text(item.getTitle()))
                    .put("sortOrder", item.getSortOrder());
        }
        return result;
    }

    public static ObjectNode photo(JmPhoto photo) {
        ObjectNode result = JSON.objectNode()
                .put("id", text(photo.getId()))
                .put("title", text(photo.getTitle()))
                .put("albumId", text(photo.getAlbumId()))
                .put("sortOrder", photo.getSortOrder())
                .put("author", text(photo.getAuthor()))
                .put("isSingleEpisode", photo.isSingleAlbum());
        strings(result.putArray("tags"), photo.getTags());
        ArrayNode images = result.putArray("images");
        for (JmImage image : list(photo.getImages())) {
            images.addObject()
                    .put("photoId", text(image.getPhotoId()))
                    .put("scrambleId", text(image.scrambleId()))
                    .put("filename", text(image.getFilename()))
                    .put("url", text(image.getUrl()))
                    .put("queryParams", text(image.getQueryParams()))
                    .put("sortOrder", image.getSortOrder());
        }
        return result;
    }

    public static ObjectNode comments(JmCommentList comments) {
        ObjectNode result = JSON.objectNode().put("total", comments.getTotal());
        ArrayNode list = result.putArray("list");
        for (JmComment comment : list(comments.getList())) list.add(comment(comment));
        return result;
    }

    private static ObjectNode comment(JmComment comment) {
        ObjectNode result = JSON.objectNode()
                .put("commentId", text(comment.getCommentId()))
                .put("userId", text(comment.getUserId()))
                .put("username", text(comment.getUsername()))
                .put("nickname", text(comment.getNickname()))
                .put("content", text(comment.getContent()))
                .put("postDate", text(comment.getPostDate()))
                .put("photo", text(comment.getPhoto()))
                .put("expinfo", text(comment.getExpinfo()))
                .put("aid", text(comment.getAid()))
                .put("name", text(comment.getName()))
                .put("likes", comment.getLikes())
                .put("voteUp", comment.getVoteUp())
                .put("voteDown", comment.getVoteDown());
        ArrayNode replies = result.putArray("replys");
        for (JmComment reply : list(comment.getReplys())) replies.add(comment(reply));
        return result;
    }

    private static void putCategory(ObjectNode target, String name, JmCategoryMeta category) {
        if (category == null) {
            target.putNull(name);
            return;
        }
        target.putObject(name)
                .put("id", text(category.getId()))
                .put("title", text(category.getTitle()));
    }

    private static void strings(ArrayNode target, List<String> values) {
        for (String value : list(values)) target.add(text(value));
    }

    private static <T> List<T> list(List<T> value) {
        return value == null ? List.of() : value;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
