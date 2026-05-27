package io.github.jukomu.service;

import android.util.Log;
import io.github.jukomu.jmcomic.api.enums.*;
import io.github.jukomu.jmcomic.api.model.*;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * API 服务——搜索、详情、评论、收藏等远程 API 调用。
 * 纯业务逻辑，不依赖 Capacitor API。
 */
public class ApiService {

    private static final String TAG = "ApiService";
    private static final String SEARCH_COVER_SIZE = "_3x4";
    private static final long API_TIMEOUT_MINUTES = 5;

    private final JmApiClient client;
    private final ExecutorService apiExecutor;
    private final ScheduledExecutorService timeoutExecutor;

    public ApiService(JmApiClient client, ExecutorService apiExecutor,
                      ScheduledExecutorService timeoutExecutor) {
        this.client = client;
        this.apiExecutor = apiExecutor;
        this.timeoutExecutor = timeoutExecutor;
    }

    // ---- 异步执行基础设施 ----

    @FunctionalInterface
    private interface ApiTask {
        JSONObject execute() throws Exception;
    }

    private void runAsync(ApiTask task, ApiCallback callback) {
        Future<?> future = apiExecutor.submit(() -> {
            try {
                JSONObject result = task.execute();
                callback.onSuccess(result);
            } catch (Exception e) {
                Log.e(TAG, "API call failed", e);
                callback.onError(e.getMessage(), e);
            }
        });
        timeoutExecutor.schedule(() -> {
            if (!future.isDone()) {
                future.cancel(true);
                callback.onError("API call timeout (" + API_TIMEOUT_MINUTES + " minutes)", null);
            }
        }, API_TIMEOUT_MINUTES, TimeUnit.MINUTES);
    }

    // ---- API 方法 ----

    public void search(String keyword, String category, String orderBy, String time,
                       int searchMainTag, int page, ApiCallback callback) {
        try {
            SearchQuery query = buildQuery(keyword, category, orderBy, time, searchMainTag, page);
            runAsync(() -> toSearchPage(client.search(query)), callback);
        } catch (Exception e) {
            callback.onError(e.getMessage(), e);
        }
    }

    public void categories(String keyword, String category, String orderBy, String time,
                           int searchMainTag, int page, ApiCallback callback) {
        try {
            SearchQuery query = buildQuery(keyword, category, orderBy, time, searchMainTag, page);
            runAsync(() -> toSearchPage(client.getCategories(query)), callback);
        } catch (Exception e) {
            callback.onError(e.getMessage(), e);
        }
    }

    public void getAlbum(String id, ApiCallback callback) {
        try {
            runAsync(() -> {
                JmAlbum album = client.getAlbum(id);
                return toAlbumObject(album);
            }, callback);
        } catch (Exception e) {
            callback.onError(e.getMessage(), e);
        }
    }

    public void getPhoto(String id, ApiCallback callback) {
        try {
            runAsync(() -> toPhotoObject(client.getPhoto(id)), callback);
        } catch (Exception e) {
            callback.onError(e.getMessage(), e);
        }
    }

    public void getComments(String albumId, int page, ApiCallback callback) {
        try {
            ForumQuery query = ForumQuery.album(albumId).mode(ForumMode.ALL).page(page).build();
            runAsync(() -> toCommentListObject(client.getComments(query)), callback);
        } catch (Exception e) {
            callback.onError(e.getMessage(), e);
        }
    }

    public void toggleAlbumLike(String id, ApiCallback callback) {
        try {
            runAsync(() -> {
                client.toggleAlbumLike(id);
                JSONObject ret = new JSONObject();
                ret.put("success", true);
                return ret;
            }, callback);
        } catch (Exception e) {
            callback.onError(e.getMessage(), e);
        }
    }

    public void getFavorites(int folderId, int page, ApiCallback callback) {
        try {
            FavoriteQuery query = new FavoriteQuery.Builder()
                .folderId(folderId)
                .page(page)
                .build();
            runAsync(() -> toFavoritePage(client.getFavorites(query)), callback);
        } catch (Exception e) {
            callback.onError(e.getMessage(), e);
        }
    }

    public void toggleAlbumFavorite(String id, String folderId, ApiCallback callback) {
        try {
            runAsync(() -> {
                client.toggleAlbumFavorite(id, folderId);
                JSONObject ret = new JSONObject();
                ret.put("success", true);
                return ret;
            }, callback);
        } catch (Exception e) {
            callback.onError(e.getMessage(), e);
        }
    }

    public void manageFavoriteFolder(String type, String folderId,
                                     String folderName, String albumId,
                                     ApiCallback callback) {
        try {
            FavoriteFolderType folderType = findFavoriteFolderType(type);
            runAsync(() -> {
                JmFavoriteFolderResult result = client.manageFavoriteFolder(
                    folderType, folderId, folderName, albumId);
                JSONObject ret = new JSONObject();
                ret.put("status", result.getStatus());
                ret.put("msg", result.getMsg());
                return ret;
            }, callback);
        } catch (Exception e) {
            callback.onError(e.getMessage(), e);
        }
    }

    // ---- 用户认证 ----

    public void login(String username, String password, ApiCallback callback) {
        try {
            runAsync(() -> {
                JmUserInfo userInfo = client.login(username, password);
                return toUserInfoObject(userInfo);
            }, callback);
        } catch (Exception e) {
            callback.onError(e.getMessage(), e);
        }
    }

    public void logout(ApiCallback callback) {
        try {
            runAsync(() -> {
                client.logout();
                JSONObject ret = new JSONObject();
                ret.put("success", true);
                return ret;
            }, callback);
        } catch (Exception e) {
            callback.onError(e.getMessage(), e);
        }
    }

    // ---- 查询构建 ----

    private SearchQuery buildQuery(String keyword, String category, String orderBy,
                                   String time, int searchMainTag, int page) {
        return new SearchQuery.Builder()
            .text(keyword == null ? "" : keyword)
            .category(findCategory(category))
            .orderBy(findOrderBy(orderBy))
            .time(findTimeOption(time))
            .mainTag(findSearchMainTag(searchMainTag))
            .page(page)
            .build();
    }

    // ---- Enum 查找 ----

    private Category findCategory(String value) {
        for (Category c : Category.values()) {
            if (c.getValue().equals(value)) return c;
        }
        return Category.ALL;
    }

    private OrderBy findOrderBy(String value) {
        for (OrderBy o : OrderBy.values()) {
            if (o.getValue().equals(value)) return o;
        }
        return OrderBy.LATEST;
    }

    private TimeOption findTimeOption(String value) {
        for (TimeOption t : TimeOption.values()) {
            if (t.getValue().equals(value)) return t;
        }
        return TimeOption.ALL;
    }

    private FavoriteFolderType findFavoriteFolderType(String value) {
        for (FavoriteFolderType t : FavoriteFolderType.values()) {
            if (t.getValue().equals(value)) return t;
        }
        throw new IllegalArgumentException("Unknown FavoriteFolderType: " + value);
    }

    private SearchMainTag findSearchMainTag(Integer value) {
        for (SearchMainTag tag : SearchMainTag.values()) {
            if (tag.getValue() == value) return tag;
        }
        return SearchMainTag.SITE_SEARCH;
    }

    // ---- 数据转换 (返回 org.json.JSONObject/JSONArray) ----

    private JSONObject toSearchPage(JmSearchPage page) throws JSONException {
        JSONObject ret = new JSONObject();
        ret.put("currentPage", page.getCurrentPage());
        ret.put("totalItems", page.getTotalItems());
        ret.put("totalPages", page.getTotalPages());

        JSONArray content = new JSONArray();
        List<JmAlbumMeta> items = page.getContent();
        for (JmAlbumMeta item : items) {
            JSONObject row = new JSONObject();
            row.put("id", item.getId());
            row.put("title", item.getTitle());
            row.put("coverUrl", client.getAlbumCoverUrl(item.getId(), SEARCH_COVER_SIZE));
            row.put("authors", new JSONArray(item.getAuthors()));
            row.put("tags", new JSONArray(item.getTags()));
            content.put(row);
        }
        ret.put("content", content);
        return ret;
    }

    private JSONObject toFavoritePage(JmFavoritePage page) throws JSONException {
        JSONObject ret = new JSONObject();
        ret.put("folderName", page.getFolderName());
        ret.put("folderId", page.getFolderId());
        ret.put("currentPage", page.getCurrentPage());
        ret.put("totalItems", page.getTotalItems());
        ret.put("totalPages", page.getTotalPages());

        JSONArray content = new JSONArray();
        List<JmAlbumMeta> items = page.getContent();
        for (JmAlbumMeta item : items) {
            JSONObject row = new JSONObject();
            row.put("id", item.getId());
            row.put("title", item.getTitle());
            row.put("coverUrl", client.getAlbumCoverUrl(item.getId(), SEARCH_COVER_SIZE));
            row.put("authors", new JSONArray(item.getAuthors()));
            row.put("tags", new JSONArray(item.getTags()));
            content.put(row);
        }
        ret.put("content", content);

        JSONObject folderList = new JSONObject();
        Map<String, String> folders = page.getFolderList();
        if (folders != null) {
            for (Map.Entry<String, String> entry : folders.entrySet()) {
                folderList.put(entry.getKey(), entry.getValue());
            }
        }
        ret.put("folderList", folderList);
        return ret;
    }

    private JSONObject toAlbumObject(JmAlbum album) throws JSONException {
        JSONObject ret = new JSONObject();
        ret.put("id", album.getId());
        ret.put("title", album.getTitle());
        ret.put("description", album.getDescription());
        ret.put("addTime", album.getAddTime());
        ret.put("pageCount", album.getPageCount());
        ret.put("likes", album.getLikes());
        ret.put("views", album.getViews());
        ret.put("commentCount", album.getCommentCount());
        ret.put("image", client.getAlbumCoverUrl(album.getId(), SEARCH_COVER_SIZE));
        ret.put("category", toCategoryMetaObject(album.getCategory()));
        ret.put("subCategory", toCategoryMetaObject(album.getSubCategory()));
        ret.put("authors", new JSONArray(album.getAuthors()));
        ret.put("works", new JSONArray(album.getWorks()));
        ret.put("actors", new JSONArray(album.getActors()));
        ret.put("tags", new JSONArray(album.getTags()));
        ret.put("relatedAlbums", toAlbumMetaArray(album.getRelatedAlbums()));
        ret.put("photoMetas", toPhotoMetaArray(album.getPhotoMetas()));
        ret.put("seriesId", album.getSeriesId());
        ret.put("isFavorite", album.isFavorite());
        ret.put("isLiked", album.isLiked());
        ret.put("price", album.getPrice());
        ret.put("purchased", album.getPurchased());
        return ret;
    }

    private JSONObject toPhotoObject(JmPhoto photo) throws JSONException {
        JSONObject ret = new JSONObject();
        ret.put("id", photo.getId());
        ret.put("title", photo.getTitle());
        ret.put("albumId", photo.getAlbumId());
        ret.put("sortOrder", photo.getSortOrder());
        ret.put("author", photo.getAuthor());
        ret.put("tags", new JSONArray(photo.getTags()));
        ret.put("images", toImageArray(photo.getImages()));
        return ret;
    }

    private JSONObject toCommentListObject(JmCommentList commentList) throws JSONException {
        JSONObject ret = new JSONObject();
        ret.put("total", commentList.getTotal());
        JSONArray list = new JSONArray();
        for (JmComment comment : commentList.getList()) {
            list.put(toCommentObject(comment));
        }
        ret.put("list", list);
        return ret;
    }

    private JSONObject toCommentObject(JmComment comment) throws JSONException {
        JSONObject ret = new JSONObject();
        ret.put("commentId", comment.getCommentId());
        ret.put("userId", comment.getUserId());
        ret.put("username", comment.getUsername());
        ret.put("nickname", comment.getNickname());
        ret.put("content", comment.getContent());
        ret.put("postDate", comment.getPostDate());
        ret.put("photo", comment.getPhoto());
        ret.put("expinfo", comment.getExpinfo());
        ret.put("aid", comment.getAid());
        ret.put("name", comment.getName());
        ret.put("likes", comment.getLikes());
        ret.put("voteUp", comment.getVoteUp());
        ret.put("voteDown", comment.getVoteDown());
        JSONArray replys = new JSONArray();
        if (comment.getReplys() != null) {
            for (JmComment reply : comment.getReplys()) {
                replys.put(toCommentObject(reply));
            }
        }
        ret.put("replys", replys);
        return ret;
    }

    private JSONObject toCategoryMetaObject(JmCategoryMeta meta) throws JSONException {
        if (meta == null) return null;
        JSONObject ret = new JSONObject();
        ret.put("id", meta.getId());
        ret.put("title", meta.getTitle());
        return ret;
    }

    private JSONArray toAlbumMetaArray(List<JmAlbumMeta> metas) throws JSONException {
        JSONArray arr = new JSONArray();
        if (metas == null) return arr;
        for (JmAlbumMeta meta : metas) {
            JSONObject row = new JSONObject();
            row.put("id", meta.getId());
            row.put("title", meta.getTitle());
            row.put("coverUrl", client.getAlbumCoverUrl(meta.getId(), SEARCH_COVER_SIZE));
            row.put("authors", new JSONArray(meta.getAuthors()));
            row.put("tags", new JSONArray(meta.getTags()));
            row.put("description", meta.getDescription());
            row.put("image", meta.getImage());
            row.put("category", toCategoryMetaObject(meta.getCategory()));
            row.put("subCategory", toCategoryMetaObject(meta.getSubCategory()));
            arr.put(row);
        }
        return arr;
    }

    private JSONArray toPhotoMetaArray(List<JmPhotoMeta> metas) throws JSONException {
        JSONArray arr = new JSONArray();
        if (metas == null) return arr;
        for (JmPhotoMeta meta : metas) {
            JSONObject row = new JSONObject();
            row.put("id", meta.getId());
            row.put("title", meta.getTitle());
            row.put("sortOrder", meta.getSortOrder());
            arr.put(row);
        }
        return arr;
    }

    private JSONArray toImageArray(List<JmImage> images) throws JSONException {
        JSONArray arr = new JSONArray();
        if (images == null) return arr;
        for (JmImage img : images) {
            JSONObject row = new JSONObject();
            row.put("photoId", img.getPhotoId());
            row.put("scrambleId", img.scrambleId());
            row.put("filename", img.getFilename());
            row.put("url", img.getUrl());
            row.put("queryParams", img.getQueryParams());
            row.put("sortOrder", img.getSortOrder());
            arr.put(row);
        }
        return arr;
    }

    private JSONObject toUserInfoObject(JmUserInfo userInfo) throws JSONException {
        JSONObject ret = new JSONObject();
        ret.put("uid", userInfo.getUid());
        ret.put("username", userInfo.getUsername());
        ret.put("email", userInfo.getEmail() != null ? userInfo.getEmail() : "");
        ret.put("emailVerified", userInfo.isEmailVerified());
        ret.put("avatarUrl", userInfo.getPhotoUrl() != null ? userInfo.getPhotoUrl() : "");
        ret.put("firstName", userInfo.getFirstName() != null ? userInfo.getFirstName() : "");
        ret.put("gender", userInfo.getGender() != null ? userInfo.getGender() : "");
        ret.put("message", userInfo.getMessage() != null ? userInfo.getMessage() : "");
        ret.put("level", userInfo.getLevel());
        ret.put("levelName", userInfo.getLevelName() != null ? userInfo.getLevelName() : "");
        ret.put("nextLevelExp", userInfo.getNextLevelExp());
        ret.put("currentExp", userInfo.getCurrentExp());
        ret.put("expPercent", userInfo.getExpPercent());
        ret.put("coin", userInfo.getCoin());
        ret.put("albumFavorites", userInfo.getAlbumFavorites());
        ret.put("maxAlbumFavorites", userInfo.getMaxAlbumFavorites());
        return ret;
    }

    /**
     * 获取用户个人资料
     */
    public void getUserProfile(String uid, ApiCallback callback) {
        try {
            runAsync(() -> {
                JmUserProfile profile = client.getUserProfile(uid);
                return toUserProfileObject(profile);
            }, callback);
        } catch (Exception e) {
            callback.onError(e.getMessage(), e);
        }
    }

    private JSONObject toUserProfileObject(JmUserProfile profile) throws JSONException {
        JSONObject ret = new JSONObject();
        ret.put("username", profile.username() != null ? profile.username() : "");
        ret.put("email", profile.email() != null ? profile.email() : "");
        ret.put("nickname", profile.nickname() != null ? profile.nickname() : "");
        ret.put("birthday", profile.birthday() != null ? profile.birthday() : "");
        ret.put("city", profile.city() != null ? profile.city() : "");
        ret.put("country", profile.country() != null ? profile.country() : "");
        ret.put("occupation", profile.occupation() != null ? profile.occupation() : "");
        ret.put("aboutMe", profile.aboutMe() != null ? profile.aboutMe() : "");
        ret.put("website", profile.website() != null ? profile.website() : "");
        return ret;
    }
}
