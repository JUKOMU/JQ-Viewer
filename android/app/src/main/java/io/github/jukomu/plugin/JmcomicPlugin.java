package io.github.jukomu.plugin;

import com.getcapacitor.*;
import org.json.JSONObject;
import com.getcapacitor.annotation.CapacitorPlugin;
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
import io.github.jukomu.jmcomic.core.JmComic;
import io.github.jukomu.jmcomic.core.client.AbstractJmClient;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import io.github.jukomu.jmcomic.core.config.JmConfiguration;
import io.github.jukomu.jmcomic.core.crypto.JmImageTool;

import java.util.Base64;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.ByteArrayOutputStream;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/**
 * @author JUKOMU
 * @Description:
 * @Project: jq-viewer
 * @Date: 2026/4/22
 */
@CapacitorPlugin(name = "Jmcomic")
public class JmcomicPlugin extends Plugin {
    private static final String SEARCH_COVER_SIZE = "_3x4";

    private volatile JmApiClient sharedClient;
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(6);
    private final ConcurrentHashMap<String, String> imageCache = new ConcurrentHashMap<>();
    private static final int THUMBNAIL_MAX_WIDTH = 300;
    private static final int THUMBNAIL_JPEG_QUALITY = 70;

    private synchronized JmApiClient getClient() {
        if (sharedClient == null) {
            sharedClient = JmComic.newApiClient(new JmConfiguration.Builder().build());
        }
        return sharedClient;
    }

    @PluginMethod
    public void search(PluginCall call) {
        try {
            JSObject queryObject = call.getObject("query");
            if (queryObject == null) {
                call.reject("query is required");
                return;
            }
            SearchQuery query = buildQuery(queryObject);
            JmApiClient client = getClient();
            call.resolve(toSearchPage(client.search(query), (AbstractJmClient) client));
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void categories(PluginCall call) {
        try {
            JSObject queryObject = call.getObject("query");
            if (queryObject == null) {
                call.reject("query is required");
                return;
            }
            SearchQuery query = buildQuery(queryObject);
            JmApiClient client = getClient();
            call.resolve(toSearchPage(client.getCategories(query), (AbstractJmClient) client));
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void getAlbum(PluginCall call) {
        try {
            String id = call.getString("id");
            if (id == null || id.isEmpty()) {
                call.reject("id is required");
                return;
            }
            JmApiClient client = getClient();
            JmAlbum album = client.getAlbum(id);
            call.resolve(toAlbumObject(album, (AbstractJmClient) client));
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void getPhoto(PluginCall call) {
        try {
            String id = call.getString("id");
            if (id == null || id.isEmpty()) {
                call.reject("id is required");
                return;
            }
            JmPhoto photo = getClient().getPhoto(id);
            call.resolve(toPhotoObject(photo));
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void getComments(PluginCall call) {
        try {
            String albumId = call.getString("albumId");
            if (albumId == null || albumId.isEmpty()) {
                call.reject("albumId is required");
                return;
            }
            int page = call.getInt("page", 1);
            ForumQuery query = ForumQuery.album(albumId).mode(ForumMode.ALL).page(page).build();
            JmCommentList commentList = getClient().getComments(query);
            call.resolve(toCommentListObject(commentList));
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void toggleAlbumLike(PluginCall call) {
        try {
            String id = call.getString("id");
            if (id == null || id.isEmpty()) {
                call.reject("id is required");
                return;
            }
            getClient().toggleAlbumLike(id);
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void toggleAlbumFavorite(PluginCall call) {
        try {
            String id = call.getString("id");
            if (id == null || id.isEmpty()) {
                call.reject("id is required");
                return;
            }
            String folderId = call.getString("folderId", "0");
            getClient().toggleAlbumFavorite(id, folderId);
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void decryptImageUrl(PluginCall call) {
        try {
            String photoId = call.getString("photoId");
            String scrambleId = call.getString("scrambleId");
            String filename = call.getString("filename");
            String url = call.getString("url");
            String queryParams = call.getString("queryParams", "");
            int sortOrder = call.getInt("sortOrder", 0);

            if (photoId == null || scrambleId == null || filename == null || url == null) {
                call.reject("photoId, scrambleId, filename, url are required");
                return;
            }

            JmImage image = new JmImage(photoId, scrambleId, filename, url, queryParams, sortOrder);
            byte[] decrypted = getClient().fetchImageBytes(image);
            String base64 = "data:image/" + JmImageTool.getFormatName(filename) + ";base64,"
                    + Base64.getEncoder().encodeToString(decrypted);
            JSObject ret = new JSObject();
            ret.put("dataUrl", base64);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void decryptImageUrls(PluginCall call) {
        try {
            JSArray imagesArray = call.getArray("images");
            if (imagesArray == null || imagesArray.length() == 0) {
                call.reject("images array is required");
                return;
            }

            JmApiClient client = getClient();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            List<JSObject> syncResults = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < imagesArray.length(); i++) {
                JSONObject jsonObj;
                JSObject imgObj;
                try {
                    jsonObj = imagesArray.getJSONObject(i);
                    imgObj = JSObject.fromJSONObject(jsonObj);
                } catch (Exception e) {
                    continue;
                }

                final String photoId = imgObj.getString("photoId");
                final String scrambleId = imgObj.getString("scrambleId");
                final String filename = imgObj.getString("filename");
                final String url = imgObj.getString("url");
                final String queryParams = imgObj.getString("queryParams", "");
                final int sortOrder = imgObj.getInt("sortOrder");
                final String cacheKey = photoId + "/" + sortOrder;

                // 查缓存
                String cached = imageCache.get(cacheKey);
                if (cached != null) {
                    pushImage(photoId, sortOrder, cached);
                    JSObject item = new JSObject();
                    item.put("sortOrder", sortOrder);
                    item.put("dataUrl", cached);
                    syncResults.add(item);
                    continue;
                }

                // 缓存未命中 → 提交并行下载
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        JmImage jmImage = new JmImage(photoId, scrambleId, filename, url, queryParams, sortOrder);
                        byte[] decrypted = client.fetchImageBytes(jmImage);
                        byte[] thumbnail = createThumbnail(decrypted);
                        String dataUrl = "data:image/jpeg;base64,"
                                + Base64.getEncoder().encodeToString(thumbnail);

                        imageCache.put(cacheKey, dataUrl);
                        pushImage(photoId, sortOrder, dataUrl);

                        JSObject item = new JSObject();
                        item.put("sortOrder", sortOrder);
                        item.put("dataUrl", dataUrl);
                        syncResults.add(item);
                    } catch (Exception ignored) {
                        // 单张失败跳过
                    }
                }, imageExecutor);
                futures.add(future);
            }

            // 全部完成后 resolve
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenRun(() -> {
                        syncResults.sort((a, b) -> {
                            try { return Integer.compare(a.getInt("sortOrder"), b.getInt("sortOrder")); }
                            catch (Exception e) { return 0; }
                        });
                        JSArray results = new JSArray();
                        for (JSObject item : syncResults) {
                            results.put(item);
                        }
                        JSObject ret = new JSObject();
                        ret.put("results", results);
                        call.resolve(ret);
                    });

        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    // ---- 流式推送单张图片 ----
    private void pushImage(String photoId, int sortOrder, String dataUrl) {
        JSObject data = new JSObject();
        data.put("photoId", photoId);
        data.put("sortOrder", sortOrder);
        data.put("dataUrl", dataUrl);
        notifyListeners("previewImage", data);
    }

    // ---- 缩略图生成 ----
    private byte[] createThumbnail(byte[] imageBytes) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        if (bitmap == null) return imageBytes;

        int width = bitmap.getWidth();
        if (width <= THUMBNAIL_MAX_WIDTH) {
            // 图本身已足够小，直接 JPEG 编码（统一格式+适当压缩）
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, baos);
            bitmap.recycle();
            return baos.toByteArray();
        }

        float ratio = (float) THUMBNAIL_MAX_WIDTH / width;
        int targetHeight = Math.round(bitmap.getHeight() * ratio);
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, THUMBNAIL_MAX_WIDTH, targetHeight, true);
        bitmap.recycle();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, baos);
        scaled.recycle();
        return baos.toByteArray();
    }

    private SearchQuery buildQuery(JSObject queryObject) {
        String keyword = queryObject.getString("keyword", "");
        String category = queryObject.getString("category", Category.ALL.getValue());
        String orderBy = queryObject.getString("orderBy", OrderBy.LATEST.getValue());
        String time = queryObject.getString("time", TimeOption.ALL.getValue());
        Integer searchMainTagValue = queryObject.getInteger("searchMainTag", SearchMainTag.SITE_SEARCH.getValue());
        Integer page = queryObject.getInteger("page", 1);

        return new SearchQuery.Builder()
                .text(keyword == null ? "" : keyword)
                .category(findCategory(category))
                .orderBy(findOrderBy(orderBy))
                .time(findTimeOption(time))
                .mainTag(findSearchMainTag(searchMainTagValue))
                .page(page == null ? 1 : page)
                .build();
    }

    private JSObject toSearchPage(JmSearchPage page, AbstractJmClient client) {
        JSObject ret = new JSObject();
        ret.put("currentPage", page.getCurrentPage());
        ret.put("totalItems", page.getTotalItems());
        ret.put("totalPages", page.getTotalPages());

        JSArray content = new JSArray();
        List<JmAlbumMeta> items = page.getContent();
        for (JmAlbumMeta item : items) {
            JSObject row = new JSObject();
            row.put("id", item.getId());
            row.put("title", item.getTitle());
            row.put("coverUrl", client.getAlbumCoverUrl(item.getId(), SEARCH_COVER_SIZE));
            row.put("authors", new JSArray(item.getAuthors()));
            row.put("tags", new JSArray(item.getTags()));
            content.put(row);
        }
        ret.put("content", content);
        return ret;
    }

    private Category findCategory(String value) {
        for (Category category : Category.values()) {
            if (category.getValue().equals(value)) {
                return category;
            }
        }
        return Category.ALL;
    }

    private OrderBy findOrderBy(String value) {
        for (OrderBy orderBy : OrderBy.values()) {
            if (orderBy.getValue().equals(value)) {
                return orderBy;
            }
        }
        return OrderBy.LATEST;
    }

    private TimeOption findTimeOption(String value) {
        for (TimeOption timeOption : TimeOption.values()) {
            if (timeOption.getValue().equals(value)) {
                return timeOption;
            }
        }
        return TimeOption.ALL;
    }

    private SearchMainTag findSearchMainTag(Integer value) {
        for (SearchMainTag mainTag : SearchMainTag.values()) {
            if (mainTag.getValue() == value) {
                return mainTag;
            }
        }
        return SearchMainTag.SITE_SEARCH;
    }

    private JSObject toAlbumObject(JmAlbum album, AbstractJmClient client) {
        JSObject ret = new JSObject();
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
        ret.put("authors", new JSArray(album.getAuthors()));
        ret.put("works", new JSArray(album.getWorks()));
        ret.put("actors", new JSArray(album.getActors()));
        ret.put("tags", new JSArray(album.getTags()));
        ret.put("relatedAlbums", toAlbumMetaArray(album.getRelatedAlbums(), client));
        ret.put("photoMetas", toPhotoMetaArray(album.getPhotoMetas()));
        ret.put("seriesId", album.getSeriesId());
        ret.put("isFavorite", album.isFavorite());
        ret.put("isLiked", album.isLiked());
        ret.put("price", album.getPrice());
        ret.put("purchased", album.getPurchased());
        return ret;
    }

    private JSObject toPhotoObject(JmPhoto photo) {
        JSObject ret = new JSObject();
        ret.put("id", photo.getId());
        ret.put("title", photo.getTitle());
        ret.put("albumId", photo.getAlbumId());
        ret.put("sortOrder", photo.getSortOrder());
        ret.put("author", photo.getAuthor());
        ret.put("tags", new JSArray(photo.getTags()));
        ret.put("images", toImageArray(photo.getImages()));
        return ret;
    }

    private JSObject toCommentListObject(JmCommentList commentList) {
        JSObject ret = new JSObject();
        ret.put("total", commentList.getTotal());
        JSArray list = new JSArray();
        for (JmComment comment : commentList.getList()) {
            list.put(toCommentObject(comment));
        }
        ret.put("list", list);
        return ret;
    }

    private JSObject toCommentObject(JmComment comment) {
        JSObject ret = new JSObject();
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
        JSArray replys = new JSArray();
        if (comment.getReplys() != null) {
            for (JmComment reply : comment.getReplys()) {
                replys.put(toCommentObject(reply));
            }
        }
        ret.put("replys", replys);
        return ret;
    }

    private JSObject toCategoryMetaObject(JmCategoryMeta meta) {
        if (meta == null) {
            return null;
        }
        JSObject ret = new JSObject();
        ret.put("id", meta.getId());
        ret.put("title", meta.getTitle());
        return ret;
    }

    private JSArray toAlbumMetaArray(List<JmAlbumMeta> metas, AbstractJmClient client) {
        JSArray arr = new JSArray();
        if (metas == null) {
            return arr;
        }
        for (JmAlbumMeta meta : metas) {
            JSObject row = new JSObject();
            row.put("id", meta.getId());
            row.put("title", meta.getTitle());
            row.put("coverUrl", client.getAlbumCoverUrl(meta.getId(), SEARCH_COVER_SIZE));
            row.put("authors", new JSArray(meta.getAuthors()));
            row.put("tags", new JSArray(meta.getTags()));
            row.put("description", meta.getDescription());
            row.put("image", meta.getImage());
            row.put("category", toCategoryMetaObject(meta.getCategory()));
            row.put("subCategory", toCategoryMetaObject(meta.getSubCategory()));
            arr.put(row);
        }
        return arr;
    }

    private JSArray toPhotoMetaArray(List<JmPhotoMeta> metas) {
        JSArray arr = new JSArray();
        if (metas == null) {
            return arr;
        }
        for (JmPhotoMeta meta : metas) {
            JSObject row = new JSObject();
            row.put("id", meta.getId());
            row.put("title", meta.getTitle());
            row.put("sortOrder", meta.getSortOrder());
            arr.put(row);
        }
        return arr;
    }

    private JSArray toImageArray(List<JmImage> images) {
        JSArray arr = new JSArray();
        if (images == null) {
            return arr;
        }
        for (JmImage img : images) {
            JSObject row = new JSObject();
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
}
