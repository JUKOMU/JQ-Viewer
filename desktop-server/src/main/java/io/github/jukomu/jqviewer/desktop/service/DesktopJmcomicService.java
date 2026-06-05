package io.github.jukomu.jqviewer.desktop.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import io.github.jukomu.jmcomic.api.model.JmUserInfo;
import io.github.jukomu.jmcomic.api.model.JmUserProfile;
import io.github.jukomu.jmcomic.api.model.SearchQuery;
import io.github.jukomu.jmcomic.core.JmComic;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import io.github.jukomu.jmcomic.core.config.JmConfiguration;

import java.util.List;

public final class DesktopJmcomicService {
    private static final String SEARCH_COVER_SIZE = "_3x4";

    private final Gson gson = new Gson();
    private final JmApiClient client;
    private JsonObject currentUserInfo;

    public DesktopJmcomicService() {
        this.client = JmComic.newApiClient(new JmConfiguration.Builder().build());
    }

    public JsonObject getInitStatus() {
        JsonObject result = new JsonObject();
        result.addProperty("complete", true);
        return result;
    }

    public JsonObject search(JsonObject body) {
        JsonObject query = queryBody(body);
        return toSearchPage(client.search(buildQuery(query)));
    }

    public JsonObject categories(JsonObject body) {
        JsonObject query = queryBody(body);
        return toSearchPage(client.getCategories(buildQuery(query)));
    }

    public JsonObject getAlbum(String id) {
        return toAlbumObject(client.getAlbum(id));
    }

    public JsonObject getPhoto(String id) {
        return toPhotoObject(client.getPhoto(id));
    }

    public JsonObject getComments(String albumId, int page) {
        ForumQuery query = ForumQuery.album(albumId).mode(ForumMode.ALL).page(page).build();
        return toCommentListObject(client.getComments(query));
    }

    public JsonObject login(JsonObject body) {
        String username = stringValue(body, "username", "");
        String password = stringValue(body, "password", "");
        if (username.isEmpty() || password.isEmpty()) {
            throw new IllegalArgumentException("username and password are required");
        }
        currentUserInfo = toUserInfoObject(client.login(username, password));
        return currentUserInfo;
    }

    public JsonObject logout() {
        client.logout();
        currentUserInfo = null;
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        return result;
    }

    public JsonObject authState() {
        JsonObject result = new JsonObject();
        if (currentUserInfo == null) {
            result.addProperty("loggedIn", false);
            return result;
        }
        result.addProperty("loggedIn", true);
        result.addProperty("username", stringValue(currentUserInfo, "username", ""));
        result.add("userInfo", currentUserInfo.deepCopy());
        return result;
    }

    public JsonObject getUserProfile(String uid) {
        return toUserProfileObject(client.getUserProfile(uid));
    }

    private SearchQuery buildQuery(JsonObject query) {
        return new SearchQuery.Builder()
            .text(stringValue(query, "keyword", ""))
            .category(findCategory(stringValue(query, "category", "")))
            .orderBy(findOrderBy(stringValue(query, "orderBy", "")))
            .time(findTimeOption(stringValue(query, "time", "")))
            .mainTag(findSearchMainTag(intValue(query, "searchMainTag", SearchMainTag.SITE_SEARCH.getValue())))
            .page(intValue(query, "page", 1))
            .build();
    }

    private JsonObject toSearchPage(JmSearchPage page) {
        JsonObject result = new JsonObject();
        result.addProperty("currentPage", page.getCurrentPage());
        result.addProperty("totalItems", page.getTotalItems());
        result.addProperty("totalPages", page.getTotalPages());

        JsonArray content = new JsonArray();
        for (JmAlbumMeta item : page.getContent()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", item.getId());
            row.addProperty("title", item.getTitle());
            row.addProperty("coverUrl", client.getAlbumCoverUrl(item.getId(), SEARCH_COVER_SIZE));
            row.add("authors", stringArray(item.getAuthors()));
            row.add("tags", stringArray(item.getTags()));
            content.add(row);
        }
        result.add("content", content);
        return result;
    }

    private JsonObject toAlbumObject(JmAlbum album) {
        JsonObject result = new JsonObject();
        result.addProperty("id", album.getId());
        result.addProperty("title", album.getTitle());
        result.addProperty("description", album.getDescription());
        result.addProperty("addTime", album.getAddTime());
        result.addProperty("pageCount", album.getPageCount());
        result.addProperty("likes", album.getLikes());
        result.addProperty("views", album.getViews());
        result.addProperty("commentCount", album.getCommentCount());
        result.addProperty("image", client.getAlbumCoverUrl(album.getId(), SEARCH_COVER_SIZE));
        result.add("category", toCategoryMetaObject(album.getCategory()));
        result.add("subCategory", toCategoryMetaObject(album.getSubCategory()));
        result.add("authors", stringArray(album.getAuthors()));
        result.add("works", stringArray(album.getWorks()));
        result.add("actors", stringArray(album.getActors()));
        result.add("tags", stringArray(album.getTags()));
        result.add("relatedAlbums", toAlbumMetaArray(album.getRelatedAlbums()));
        result.add("photoMetas", toPhotoMetaArray(album.getPhotoMetas()));
        result.addProperty("seriesId", album.getSeriesId());
        result.addProperty("isSingleEpisode", album.isSingleAlbum());
        result.addProperty("isFavorite", album.isFavorite());
        result.addProperty("isLiked", album.isLiked());
        result.addProperty("price", album.getPrice());
        result.addProperty("purchased", album.getPurchased());
        return result;
    }

    private JsonObject toPhotoObject(JmPhoto photo) {
        JsonObject result = new JsonObject();
        result.addProperty("id", photo.getId());
        result.addProperty("title", photo.getTitle());
        result.addProperty("albumId", photo.getAlbumId());
        result.addProperty("sortOrder", photo.getSortOrder());
        result.addProperty("author", photo.getAuthor());
        result.add("tags", stringArray(photo.getTags()));
        result.add("images", toImageArray(photo.getImages()));
        result.addProperty("isSingleEpisode", photo.isSingleAlbum());
        return result;
    }

    private JsonObject toCommentListObject(JmCommentList commentList) {
        JsonObject result = new JsonObject();
        result.addProperty("total", commentList.getTotal());
        JsonArray list = new JsonArray();
        for (JmComment comment : commentList.getList()) {
            list.add(toCommentObject(comment));
        }
        result.add("list", list);
        return result;
    }

    private JsonObject toCommentObject(JmComment comment) {
        JsonObject result = new JsonObject();
        result.addProperty("commentId", comment.getCommentId());
        result.addProperty("userId", comment.getUserId());
        result.addProperty("username", comment.getUsername());
        result.addProperty("nickname", comment.getNickname());
        result.addProperty("content", comment.getContent());
        result.addProperty("postDate", comment.getPostDate());
        result.addProperty("photo", comment.getPhoto());
        result.addProperty("expinfo", comment.getExpinfo());
        result.addProperty("aid", comment.getAid());
        result.addProperty("name", comment.getName());
        result.addProperty("likes", comment.getLikes());
        result.addProperty("voteUp", comment.getVoteUp());
        result.addProperty("voteDown", comment.getVoteDown());

        JsonArray replies = new JsonArray();
        if (comment.getReplys() != null) {
            for (JmComment reply : comment.getReplys()) {
                replies.add(toCommentObject(reply));
            }
        }
        result.add("replys", replies);
        return result;
    }

    private JsonElement toCategoryMetaObject(JmCategoryMeta meta) {
        if (meta == null) return null;
        JsonObject result = new JsonObject();
        result.addProperty("id", meta.getId());
        result.addProperty("title", meta.getTitle());
        return result;
    }

    private JsonArray toAlbumMetaArray(List<JmAlbumMeta> metas) {
        JsonArray arr = new JsonArray();
        if (metas == null) return arr;
        for (JmAlbumMeta meta : metas) {
            JsonObject row = new JsonObject();
            row.addProperty("id", meta.getId());
            row.addProperty("title", meta.getTitle());
            row.addProperty("coverUrl", client.getAlbumCoverUrl(meta.getId(), SEARCH_COVER_SIZE));
            row.add("authors", stringArray(meta.getAuthors()));
            row.add("tags", stringArray(meta.getTags()));
            row.addProperty("description", meta.getDescription());
            row.addProperty("image", meta.getImage());
            row.add("category", toCategoryMetaObject(meta.getCategory()));
            row.add("subCategory", toCategoryMetaObject(meta.getSubCategory()));
            arr.add(row);
        }
        return arr;
    }

    private JsonArray toPhotoMetaArray(List<JmPhotoMeta> metas) {
        JsonArray arr = new JsonArray();
        if (metas == null) return arr;
        for (JmPhotoMeta meta : metas) {
            JsonObject row = new JsonObject();
            row.addProperty("id", meta.getId());
            row.addProperty("title", meta.getTitle());
            row.addProperty("sortOrder", meta.getSortOrder());
            arr.add(row);
        }
        return arr;
    }

    private JsonArray toImageArray(List<JmImage> images) {
        JsonArray arr = new JsonArray();
        if (images == null) return arr;
        for (JmImage image : images) {
            JsonObject row = new JsonObject();
            row.addProperty("photoId", image.getPhotoId());
            row.addProperty("scrambleId", image.getScrambleId());
            row.addProperty("filename", image.getFilename());
            row.addProperty("url", image.getUrl());
            row.addProperty("queryParams", image.getQueryParams());
            row.addProperty("sortOrder", image.getSortOrder());
            arr.add(row);
        }
        return arr;
    }

    private JsonObject toUserInfoObject(JmUserInfo userInfo) {
        JsonObject result = new JsonObject();
        result.addProperty("uid", userInfo.getUid());
        result.addProperty("username", userInfo.getUsername());
        result.addProperty("email", nullToEmpty(userInfo.getEmail()));
        result.addProperty("emailVerified", userInfo.isEmailVerified());
        result.addProperty("avatarUrl", nullToEmpty(userInfo.getPhotoUrl()));
        result.addProperty("firstName", nullToEmpty(userInfo.getFirstName()));
        result.addProperty("gender", nullToEmpty(userInfo.getGender()));
        result.addProperty("message", nullToEmpty(userInfo.getMessage()));
        result.addProperty("level", userInfo.getLevel());
        result.addProperty("levelName", nullToEmpty(userInfo.getLevelName()));
        result.addProperty("nextLevelExp", userInfo.getNextLevelExp());
        result.addProperty("currentExp", userInfo.getCurrentExp());
        result.addProperty("expPercent", userInfo.getExpPercent());
        result.addProperty("coin", userInfo.getCoin());
        result.addProperty("albumFavorites", userInfo.getAlbumFavorites());
        result.addProperty("maxAlbumFavorites", userInfo.getMaxAlbumFavorites());
        return result;
    }

    private JsonObject toUserProfileObject(JmUserProfile profile) {
        JsonObject result = new JsonObject();
        result.addProperty("username", nullToEmpty(profile.username()));
        result.addProperty("email", nullToEmpty(profile.email()));
        result.addProperty("nickname", nullToEmpty(profile.nickname()));
        result.addProperty("birthday", nullToEmpty(profile.birthday()));
        result.addProperty("city", nullToEmpty(profile.city()));
        result.addProperty("country", nullToEmpty(profile.country()));
        result.addProperty("occupation", nullToEmpty(profile.occupation()));
        result.addProperty("aboutMe", nullToEmpty(profile.aboutMe()));
        result.addProperty("website", nullToEmpty(profile.website()));
        return result;
    }

    private JsonArray stringArray(List<String> values) {
        JsonArray arr = new JsonArray();
        if (values == null) return arr;
        for (String value : values) {
            arr.add(value);
        }
        return arr;
    }

    private JsonObject queryBody(JsonObject body) {
        if (body != null && body.has("query") && body.get("query").isJsonObject()) {
            return body.getAsJsonObject("query");
        }
        return body == null ? new JsonObject() : body;
    }

    private Category findCategory(String value) {
        for (Category category : Category.values()) {
            if (category.getValue().equals(value)) return category;
        }
        return Category.ALL;
    }

    private OrderBy findOrderBy(String value) {
        for (OrderBy orderBy : OrderBy.values()) {
            if (orderBy.getValue().equals(value)) return orderBy;
        }
        return OrderBy.LATEST;
    }

    private TimeOption findTimeOption(String value) {
        for (TimeOption timeOption : TimeOption.values()) {
            if (timeOption.getValue().equals(value)) return timeOption;
        }
        return TimeOption.ALL;
    }

    private SearchMainTag findSearchMainTag(int value) {
        for (SearchMainTag tag : SearchMainTag.values()) {
            if (tag.getValue() == value) return tag;
        }
        return SearchMainTag.SITE_SEARCH;
    }

    private int intValue(JsonObject obj, String key, int fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private String stringValue(JsonObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsString();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
