package io.github.jukomu.plugin;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;
import io.github.jukomu.jmcomic.api.enums.*;
import io.github.jukomu.jmcomic.api.model.*;
import io.github.jukomu.jmcomic.core.JmComic;
import io.github.jukomu.jmcomic.core.client.AbstractJmClient;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import io.github.jukomu.jmcomic.core.config.JmConfiguration;
import io.github.jukomu.jmcomic.core.crypto.JmImageTool;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    public void preloadImages(PluginCall call) {
        try {
            String photoId = call.getString("photoId");
            String type = call.getString("type", "image");
            JSArray imagesArray = call.getArray("images");
            if (imagesArray == null || imagesArray.length() == 0) {
                JSObject ret = new JSObject();
                ret.put("cached", new JSArray());
                ret.put("pending", new JSArray());
                call.resolve(ret);
                return;
            }

            JmApiClient client = getClient();
            List<Integer> cached = new ArrayList<>();
            List<Integer> pending = new ArrayList<>();

            for (int i = 0; i < imagesArray.length(); i++) {
                JSONObject jsonObj;
                JSObject imgObj;
                try {
                    jsonObj = imagesArray.getJSONObject(i);
                    imgObj = JSObject.fromJSONObject(jsonObj);
                } catch (Exception e) {
                    continue;
                }

                final int sortOrder = imgObj.getInt("sortOrder");
                final String cacheKey = photoId + "/" + sortOrder
                        + ("thumb".equals(type) ? "/thumb" : "");

                if (ImageRegistry.getInstance().has(cacheKey)) {
                    cached.add(sortOrder);
                    continue;
                }

                // 缩略图未命中时，检查原图缓存；有则直接从原图生成缩略图
                if ("thumb".equals(type)) {
                    ImageRegistry.ImageEntry original = ImageRegistry.getInstance().get(photoId + "/" + sortOrder);
                    if (original != null) {
                        byte[] thumbBytes = createThumbnail(original.data);
                        ImageRegistry.getInstance().put(cacheKey, thumbBytes, "image/jpeg");
                        cached.add(sortOrder);
                        pushImageReady(photoId, sortOrder, type);
                        continue;
                    }
                }

                pending.add(sortOrder);

                final String scrambleId = imgObj.getString("scrambleId");
                final String filename = imgObj.getString("filename");
                final String url = imgObj.getString("url");
                final String queryParams = imgObj.getString("queryParams", "");

                imageExecutor.submit(() -> {
                    try {
                        JmImage jmImage = new JmImage(photoId, scrambleId, filename, url, queryParams, sortOrder);
                        byte[] decrypted = client.fetchImageBytes(jmImage);
                        String formatName = JmImageTool.getFormatName(filename);
                        String mimeType = "image/" + formatName;

                        if ("thumb".equals(type)) {
                            byte[] thumbBytes = createThumbnail(decrypted);
                            ImageRegistry.getInstance().put(cacheKey, thumbBytes, "image/jpeg");
                            // 原图一并缓存，后续阅读时无需重复下载
                            ImageRegistry.getInstance().put(photoId + "/" + sortOrder, decrypted, mimeType);
                        } else {
                            ImageRegistry.getInstance().put(cacheKey, decrypted, mimeType);
                        }

                        pushImageReady(photoId, sortOrder, type);
                    } catch (Exception ignored) {
                        // 单张失败跳过，Vue 侧的骨架永久保留
                    }
                });
            }

            JSObject ret = new JSObject();
            ret.put("cached", new JSArray(cached));
            ret.put("pending", new JSArray(pending));
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    // @PluginMethod  // 暂不暴露给 Vue，后续需要时取消注释
    public void clearPhotoCache(PluginCall call) {
        String photoId = call.getString("photoId");
        if (photoId == null || photoId.isEmpty()) {
            call.reject("photoId is required");
            return;
        }
        ImageRegistry.getInstance().clearByPrefix(photoId + "/");
        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void setCacheCapacity(PluginCall call) {
        Long mb = call.getLong("mb");
        if (mb == null || mb <= 0) {
            call.reject("mb must be a positive number");
            return;
        }
        long bytes = mb * 1024 * 1024;
        ImageRegistry.getInstance().setCapacity(bytes);
        JSObject ret = new JSObject();
        ret.put("success", true);
        ret.put("capacityMb", mb);
        call.resolve(ret);
    }

    @PluginMethod
    public void getCacheCapacityInfo(PluginCall call) {
        ImageRegistry reg = ImageRegistry.getInstance();
        JSObject ret = new JSObject();
        ret.put("capacityMb", reg.getCapacity() / (1024 * 1024));
        ret.put("usedMb", reg.getCurrentSize() / (1024 * 1024));
        call.resolve(ret);
    }

    // ---- 流式推送单张图片就绪通知 ----
    private void pushImageReady(String photoId, int sortOrder, String type) {
        JSObject data = new JSObject();
        data.put("photoId", photoId);
        data.put("sortOrder", sortOrder);
        data.put("type", type);
        notifyListeners("imageReady", data);
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
