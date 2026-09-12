package io.github.jukomu.desktop.feature.catalog;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jukomu.desktop.feature.image.ImageService;
import io.github.jukomu.jmcomic.api.client.JmClient;
import io.github.jukomu.jmcomic.api.enums.Category;
import io.github.jukomu.jmcomic.api.enums.ForumMode;
import io.github.jukomu.jmcomic.api.enums.OrderBy;
import io.github.jukomu.jmcomic.api.enums.SearchMainTag;
import io.github.jukomu.jmcomic.api.enums.TimeOption;
import io.github.jukomu.jmcomic.api.model.ForumQuery;
import io.github.jukomu.jmcomic.api.model.JmAlbum;
import io.github.jukomu.jmcomic.api.model.JmAlbumMeta;
import io.github.jukomu.jmcomic.api.model.JmCategoryMeta;
import io.github.jukomu.jmcomic.api.model.JmComment;
import io.github.jukomu.jmcomic.api.model.JmCommentList;
import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.api.model.JmPhoto;
import io.github.jukomu.jmcomic.api.model.JmPhotoMeta;
import io.github.jukomu.jmcomic.api.model.JmSearchPage;
import io.github.jukomu.jmcomic.api.model.SearchQuery;

import java.util.List;
import java.util.function.Function;

/** 调用在线客户端并转换为页面使用的 JSON 结构。 */
public final class CatalogService {
    private final JmClient client;
    private final ImageService imageService;
    private final Function<String, String> albumCoverUrl;

    public CatalogService(
            JmClient client,
            ImageService imageService,
            Function<String, String> albumCoverUrl
    ) {
        this.client = client;
        this.imageService = imageService;
        this.albumCoverUrl = albumCoverUrl;
    }

    public ObjectNode search(ObjectNode request) {
        return toSearchPage(client.search(query(request)));
    }

    public ObjectNode categories(ObjectNode request) {
        return toSearchPage(client.getCategories(query(request)));
    }

    public ObjectNode getAlbum(String id) {
        return toAlbum(client.getAlbum(id));
    }

    public ObjectNode getPhoto(String id) {
        JmPhoto photo = client.getPhoto(id);
        imageService.register(photo);
        return toPhoto(photo);
    }

    public ObjectNode getComments(String albumId, int page) {
        JmCommentList comments = client.getComments(
                ForumQuery.album(albumId).mode(ForumMode.ALL).page(page).build());
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("total", comments.getTotal());
        ArrayNode list = result.putArray("list");
        for (JmComment comment : safe(comments.getList())) {
            list.add(toComment(comment));
        }
        return result;
    }

    private SearchQuery query(ObjectNode request) {
        String keyword = request.has("keyword") ? request.path("keyword").asText() : "";
        return new SearchQuery.Builder()
                .text(keyword)
                .category(category(request.path("category").asText()))
                .orderBy(orderBy(request.path("orderBy").asText()))
                .time(time(request.path("time").asText()))
                .mainTag(mainTag(request.path("searchMainTag").asInt()))
                .page(Math.max(1, request.path("page").asInt(1)))
                .build();
    }

    private static Category category(String value) {
        for (Category candidate : Category.values()) {
            if (candidate.getValue().equals(value)) return candidate;
        }
        return Category.ALL;
    }

    private static OrderBy orderBy(String value) {
        for (OrderBy candidate : OrderBy.values()) {
            if (candidate.getValue().equals(value)) return candidate;
        }
        return OrderBy.LATEST;
    }

    private static TimeOption time(String value) {
        for (TimeOption candidate : TimeOption.values()) {
            if (candidate.getValue().equals(value)) return candidate;
        }
        return TimeOption.ALL;
    }

    private static SearchMainTag mainTag(int value) {
        for (SearchMainTag candidate : SearchMainTag.values()) {
            if (candidate.getValue() == value) return candidate;
        }
        return SearchMainTag.SITE_SEARCH;
    }

    private ObjectNode toSearchPage(JmSearchPage page) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("currentPage", page.getCurrentPage());
        result.put("totalItems", page.getTotalItems());
        result.put("totalPages", page.getTotalPages());
        ArrayNode content = result.putArray("content");
        for (JmAlbumMeta item : page.getContent()) {
            content.add(toAlbumMeta(item));
        }
        return result;
    }

    private ObjectNode toAlbum(JmAlbum album) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("id", text(album.getId()));
        result.put("title", text(album.getTitle()));
        result.put("description", text(album.getDescription()));
        result.put("addTime", text(album.getAddTime()));
        result.put("pageCount", album.getPageCount());
        result.put("likes", text(album.getLikes()));
        result.put("views", text(album.getViews()));
        result.put("commentCount", album.getCommentCount());
        result.put("image", albumCoverUrl.apply(album.getId()));
        result.set("category", toCategory(album.getCategory()));
        result.set("subCategory", toCategory(album.getSubCategory()));
        addStrings(result.putArray("authors"), album.getAuthors());
        addStrings(result.putArray("works"), album.getWorks());
        addStrings(result.putArray("actors"), album.getActors());
        addStrings(result.putArray("tags"), album.getTags());
        ArrayNode related = result.putArray("relatedAlbums");
        for (JmAlbumMeta item : safe(album.getRelatedAlbums())) related.add(toAlbumMeta(item));
        ArrayNode photos = result.putArray("photoMetas");
        for (JmPhotoMeta item : safe(album.getPhotoMetas())) {
            ObjectNode photo = photos.addObject();
            photo.put("id", text(item.getId()));
            photo.put("title", text(item.getTitle()));
            photo.put("sortOrder", item.getSortOrder());
        }
        result.put("seriesId", text(album.getSeriesId()));
        result.put("isSingleEpisode", album.isSingleAlbum());
        result.put("isFavorite", album.isFavorite());
        result.put("isLiked", album.isLiked());
        result.put("price", text(album.getPrice()));
        result.put("purchased", text(album.getPurchased()));
        return result;
    }

    private ObjectNode toAlbumMeta(JmAlbumMeta item) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("id", text(item.getId()));
        result.put("title", text(item.getTitle()));
        result.put("coverUrl", albumCoverUrl.apply(item.getId()));
        addStrings(result.putArray("authors"), item.getAuthors());
        addStrings(result.putArray("tags"), item.getTags());
        result.put("description", text(item.getDescription()));
        result.put("image", text(item.getImage()));
        result.set("category", toCategory(item.getCategory()));
        result.set("subCategory", toCategory(item.getSubCategory()));
        return result;
    }

    private ObjectNode toPhoto(JmPhoto photo) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("id", text(photo.getId()));
        result.put("title", text(photo.getTitle()));
        result.put("albumId", text(photo.getAlbumId()));
        result.put("sortOrder", photo.getSortOrder());
        result.put("author", text(photo.getAuthor()));
        addStrings(result.putArray("tags"), photo.getTags());
        ArrayNode images = result.putArray("images");
        for (JmImage image : safe(photo.getImages())) {
            ObjectNode item = images.addObject();
            item.put("photoId", text(image.getPhotoId()));
            item.put("scrambleId", text(image.getScrambleId()));
            item.put("filename", text(image.getFilename()));
            item.put("url", text(image.getUrl()));
            item.put("queryParams", text(image.getQueryParams()));
            item.put("sortOrder", image.getSortOrder());
        }
        result.put("isSingleEpisode", photo.isSingleAlbum());
        return result;
    }

    private ObjectNode toComment(JmComment comment) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("commentId", text(comment.getCommentId()));
        result.put("userId", text(comment.getUserId()));
        result.put("username", text(comment.getUsername()));
        result.put("nickname", text(comment.getNickname()));
        result.put("content", text(comment.getContent()));
        result.put("postDate", text(comment.getPostDate()));
        result.put("photo", text(comment.getPhoto()));
        result.put("expinfo", text(comment.getExpinfo()));
        result.put("aid", text(comment.getAid()));
        result.put("name", text(comment.getName()));
        result.put("likes", comment.getLikes());
        result.put("voteUp", comment.getVoteUp());
        result.put("voteDown", comment.getVoteDown());
        ArrayNode replies = result.putArray("replys");
        for (JmComment reply : safe(comment.getReplys())) replies.add(toComment(reply));
        return result;
    }

    private static ObjectNode toCategory(JmCategoryMeta category) {
        if (category == null) return null;
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("id", text(category.getId()));
        result.put("title", text(category.getTitle()));
        return result;
    }

    private static void addStrings(ArrayNode target, List<String> values) {
        for (String value : safe(values)) target.add(text(value));
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
