package io.github.jukomu.plugin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;
import io.github.jukomu.jmcomic.api.download.DownloadProgress;
import io.github.jukomu.jmcomic.api.download.DownloadResult;
import io.github.jukomu.jmcomic.api.download.enums.TaskState;
import io.github.jukomu.jmcomic.api.download.task.BaseDownloadTask;
import io.github.jukomu.jmcomic.api.download.task.TaskObserver;
import io.github.jukomu.jmcomic.api.enums.*;
import io.github.jukomu.jmcomic.api.model.*;
import io.github.jukomu.jmcomic.core.JmComic;
import io.github.jukomu.jmcomic.core.client.AbstractJmClient;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import io.github.jukomu.jmcomic.core.config.JmConfiguration;
import io.github.jukomu.jmcomic.core.crypto.JmImageTool;
import io.github.jukomu.storage.DownloadDatabase;
import io.github.jukomu.storage.FileStorage;
import io.github.jukomu.storage.SettingsDatabase;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author JUKOMU
 * @Description:
 * @Project: jq-viewer
 * @Date: 2026/4/22
 */
@CapacitorPlugin(name = "Jmcomic")
public class JmcomicPlugin extends Plugin {
    private static final String SEARCH_COVER_SIZE = "_3x4";

    // ---- 下载状态常量 ----
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_DOWNLOADING = "downloading";
    private static final String STATUS_PAUSED = "paused";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";

    private volatile JmApiClient sharedClient;
    private volatile ExecutorService imageExecutor;
    private int imageConcurrency = 6;
    private int downloadConcurrency = 6;
    private static final int THUMBNAIL_MAX_WIDTH = 300;
    private static final int THUMBNAIL_JPEG_QUALITY = 70;

    // ---- 下载相关 ----
    private DownloadDatabase downloadDb;
    /** albumId_chapterId → library taskId */
    private final Map<String, String> taskIdMap = new ConcurrentHashMap<>();
    /** library taskId → albumId_chapterId */
    private final Map<String, String> reverseTaskIdMap = new ConcurrentHashMap<>();
    /** 在 async 块映射 taskId 之前到达的取消请求 */
    private final Set<String> pendingCancel = ConcurrentHashMap.newKeySet();

    @Override
    public void load() {
        Context ctx = getContext();
        SettingsDatabase settingsDb = SettingsDatabase.getInstance(ctx);

        // 读取下载公开设置 → FileStorage
        boolean downloadPublic = settingsDb.getBoolean("download_public", false);
        downloadDb = DownloadDatabase.getInstance(ctx);
        FileStorage.getInstance().init(ctx, downloadDb, downloadPublic);
        downloadDb.validateOnStartup(FileStorage.getInstance().getBaseDir());

        // 读取缓存容量 → 初始化 ImageRegistry
        long cacheCapacityMb = settingsDb.getLong("cache_capacity_mb", 640);
        ImageRegistry.getInstance().setCapacity(cacheCapacityMb * 1024 * 1024);

        // 读取并发数设置 → 初始化 imageExecutor 和 downloadConcurrency
        imageConcurrency = settingsDb.getInt("preload_concurrency", 6);
        downloadConcurrency = settingsDb.getInt("download_concurrency", 6);
        imageExecutor = Executors.newFixedThreadPool(imageConcurrency);
    }

    private synchronized JmApiClient getClient() {
        if (sharedClient == null) {
            sharedClient = JmComic.newApiClient(new JmConfiguration.Builder().downloadThreadPoolSize(downloadConcurrency).build());
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
    public void getFavorites(PluginCall call) {
        try {
            JSObject queryObject = call.getObject("query");
            if (queryObject == null) {
                call.reject("query is required");
                return;
            }
            int folderId = queryObject.getInteger("folderId", 0);
            int page = queryObject.getInteger("page", 1);
            FavoriteQuery query = new FavoriteQuery.Builder()
                    .folderId(folderId)
                    .page(page)
                    .build();
            JmApiClient client = getClient();
            call.resolve(toFavoritePage(client.getFavorites(query), (AbstractJmClient) client));
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
        SettingsDatabase.getInstance(getContext()).putString("cache_capacity_mb", String.valueOf(mb));
        JSObject ret = new JSObject();
        ret.put("success", true);
        ret.put("capacityMb", mb);
        call.resolve(ret);
    }

    @PluginMethod
    public void getCacheCapacityInfo(PluginCall call) {
        ImageRegistry reg = ImageRegistry.getInstance();
        JSObject ret = new JSObject();
        ret.put("capacityMb", Math.round(reg.getCapacity() / (1024.0 * 1024.0)));
        ret.put("usedMb", Math.round(reg.getCurrentSize() / (1024.0 * 1024.0)));
        call.resolve(ret);
    }

    @PluginMethod
    public void clearImageCache(PluginCall call) {
        ImageRegistry.getInstance().clear();
        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void setDownloadConcurrency(PluginCall call) {
        Integer n = call.getInt("n");
        if (n == null || n < 1 || n > 12) {
            call.reject("n must be between 1 and 12");
            return;
        }
        SettingsDatabase.getInstance(getContext()).putString("download_concurrency", String.valueOf(n));
        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void setPreloadConcurrency(PluginCall call) {
        Integer n = call.getInt("n");
        if (n == null || n < 1 || n > 12) {
            call.reject("n must be between 1 and 12");
            return;
        }
        SettingsDatabase.getInstance(getContext()).putString("preload_concurrency", String.valueOf(n));
        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void setDownloadPublic(PluginCall call) {
        boolean open = call.getBoolean("open", false);

        // 检查是否有进行中的下载任务，有则拒绝切换
        List<org.json.JSONObject> tasks = downloadDb.getAllTasks();
        for (org.json.JSONObject t : tasks) {
            String s = t.optString("status");
            if (STATUS_QUEUED.equals(s) || STATUS_DOWNLOADING.equals(s)) {
                call.reject("有下载任务进行中，请等待全部完成后再切换");
                return;
            }
        }

        int moved = FileStorage.getInstance().relocate(getContext(), open);
        SettingsDatabase.getInstance(getContext()).putString("download_public", String.valueOf(open));
        JSObject ret = new JSObject();
        ret.put("success", true);
        ret.put("downloadPublic", open);
        ret.put("moved", moved);
        call.resolve(ret);
    }

    @PluginMethod
    public void getDownloadPublic(PluginCall call) {
        boolean open = SettingsDatabase.getInstance(getContext()).getBoolean("download_public", false);
        JSObject ret = new JSObject();
        ret.put("downloadPublic", open);
        call.resolve(ret);
    }

    @PluginMethod
    public void getAllSettings(PluginCall call) {
        SettingsDatabase db = SettingsDatabase.getInstance(getContext());
        JSObject ret = new JSObject();
        ret.put("readerPreloadPages", db.getInt("reader_preload_pages", 15));
        ret.put("preloadConcurrency", db.getInt("preload_concurrency", 6));
        ret.put("downloadConcurrency", db.getInt("download_concurrency", 6));
        ret.put("downloadPublic", db.getBoolean("download_public", false));
        ret.put("cacheCapacityMb", db.getLong("cache_capacity_mb", 640));
        call.resolve(ret);
    }

    @PluginMethod
    public void setReaderPreloadPages(PluginCall call) {
        Integer n = call.getInt("n");
        if (n == null || n < 5 || n > 50) {
            call.reject("n must be between 5 and 50");
            return;
        }
        SettingsDatabase.getInstance(getContext()).putString("reader_preload_pages", String.valueOf(n));
        JSObject ret = new JSObject();
        ret.put("success", true);
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

    // ========== 下载相关 ==========

    @PluginMethod
    public void downloadChapter(PluginCall call) {
        try {
            String albumId = call.getString("albumId");
            String chapterId = call.getString("chapterId");
            String albumTitle = call.getString("albumTitle", "");
            String chapterTitle = call.getString("chapterTitle", "");
            String coverUrl = call.getString("coverUrl", "");

            if (albumId == null || chapterId == null) {
                call.reject("albumId and chapterId are required");
                return;
            }

            String taskId = albumId + "_" + chapterId;

            // 去重检查
            if (downloadDb.hasActiveOrCompleted(taskId)) {
                String curStatus = downloadDb.getTask(taskId).optString("status");
                if (STATUS_COMPLETED.equals(curStatus)) {
                    call.reject("该章节已下载完成");
                } else {
                    call.reject("该章节已在下载队列中");
                }
                return;
            }

            // 写入 DB（queued）
            downloadDb.insertTask(taskId, albumId, chapterId,
                    albumTitle, chapterTitle, coverUrl);

            // 异步执行：获取 Photo → 创建任务 → 提交到 DownloadManager
            imageExecutor.submit(() -> {
                try {
                    JmApiClient client = getClient();
                    JmPhoto photo = client.getPhoto(chapterId);
                    List<JmImage> images = photo.getImages();

                    // 写入 images 表
                    downloadDb.insertImages(taskId, images);

                    // 更新任务详情
                    downloadDb.updateTaskDetail(taskId, images.size(),
                            photo.getAuthor(), new org.json.JSONArray(photo.getTags()).toString());

                    // 创建目录 + meta.json
                    java.io.File chapterDir = FileStorage.getInstance()
                            .ensureChapterDir(albumId, chapterId);
                    FileStorage.getInstance().refreshMappings(albumId, chapterId, downloadDb);

                    org.json.JSONObject metaJson = new org.json.JSONObject();
                    metaJson.put("albumId", albumId);
                    metaJson.put("chapterId", chapterId);
                    metaJson.put("title", chapterTitle);
                    metaJson.put("author", photo.getAuthor());
                    metaJson.put("tags", new org.json.JSONArray(photo.getTags()));
                    metaJson.put("totalPages", images.size());
                    org.json.JSONArray metaImages = new org.json.JSONArray();
                    for (JmImage img : images) {
                        org.json.JSONObject im = new org.json.JSONObject();
                        im.put("sortOrder", img.getSortOrder());
                        im.put("filename", img.getFilename());
                        im.put("photoId", img.getPhotoId());
                        metaImages.put(im);
                    }
                    metaJson.put("images", metaImages);
                    FileStorage.getInstance().saveMeta(albumId, chapterId, metaJson);

                    // 使用库的任务系统创建下载任务
                    Path chapterPath = chapterDir.toPath();
                    AbstractJmClient abstractClient = (AbstractJmClient) client;
                    BaseDownloadTask task = abstractClient.createDownloadTask(photo, chapterPath);

                    // 记录映射
                    String libTaskId = task.getTaskId();
                    taskIdMap.put(taskId, libTaskId);
                    reverseTaskIdMap.put(libTaskId, taskId);

                    // 注册观察者
                    task.addObserver(new DownloadTaskObserver(taskId, albumId, chapterId, images.size()));

                    // 更新 DB → downloading
                    downloadDb.updateStatus(taskId, STATUS_DOWNLOADING);

                    // 推送初始进度
                    pushDownloadProgress(taskId, albumId, chapterId, 0, images.size(),
                            STATUS_DOWNLOADING, null);

                    // 检查是否有在映射前到达的取消请求
                    if (pendingCancel.remove(taskId)) {
                        // 取消已请求，直接清理不再提交
                        FileStorage.getInstance().deleteChapter(albumId, chapterId);
                        downloadDb.deleteImages(taskId);
                        downloadDb.deleteTask(taskId);
                        cleanupTaskMapping(taskId);
                        return;
                    }

                    // 提交到 DownloadManager
                    abstractClient.downloadManager().submit(task);
                } catch (Exception e) {
                    downloadDb.updateFailed(taskId, 0, e.getMessage());
                    pushDownloadProgress(taskId, albumId, chapterId, 0, 0,
                            STATUS_FAILED, e.getMessage());
                    cleanupTaskMapping(taskId);
                }
            });

            JSObject ret = new JSObject();
            ret.put("taskId", taskId);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void getDownloadTasks(PluginCall call) {
        try {
            JSArray arr = new JSArray();
            List<org.json.JSONObject> tasks = downloadDb.getAllTasks();
            for (org.json.JSONObject task : tasks) {
                arr.put(JSObject.fromJSONObject(task));
            }
            JSObject ret = new JSObject();
            ret.put("tasks", arr);
            ret.put("usedBytes", FileStorage.getInstance().getTotalUsedBytes());
            ret.put("availableBytes", FileStorage.getInstance().getAvailableBytes());
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void cancelDownload(PluginCall call) {
        try {
            String taskId = call.getString("taskId");
            if (taskId == null) {
                call.reject("taskId is required");
                return;
            }

            org.json.JSONObject task = downloadDb.getTask(taskId);
            if (task == null) {
                JSObject ret = new JSObject();
                ret.put("success", true);
                call.resolve(ret);
                return;
            }

            String status = task.optString("status");

            if (STATUS_QUEUED.equals(status)) {
                // 还未提交到库，直接从 DB 清理
                pendingCancel.remove(taskId);
                downloadDb.deleteImages(taskId);
                downloadDb.deleteTask(taskId);
                cleanupTaskMapping(taskId);
            } else if (STATUS_PAUSED.equals(status)) {
                // 暂停中的任务，通过库取消，由 Observer 处理清理
                String libTaskId = taskIdMap.get(taskId);
                if (libTaskId != null) {
                    ((AbstractJmClient) getClient()).downloadManager().cancel(libTaskId);
                } else {
                    // 兜底：映射丢失时直接清理 DB 和文件
                    String albumId = task.getString("albumId");
                    String chapterId = task.getString("chapterId");
                    FileStorage.getInstance().deleteChapter(albumId, chapterId);
                    downloadDb.deleteImages(taskId);
                    downloadDb.deleteTask(taskId);
                    cleanupTaskMapping(taskId);
                }
            } else if (STATUS_DOWNLOADING.equals(status)) {
                // 通过库的 DownloadManager 取消
                String libTaskId = taskIdMap.get(taskId);
                if (libTaskId != null) {
                    JmApiClient client = getClient();
                    ((AbstractJmClient) client).downloadManager().cancel(libTaskId);
                } else {
                    // async 块尚未映射 taskId，加入 pendingCancel 等待其检查
                    pendingCancel.add(taskId);
                }
                // 清理由 Observer.onStateChanged(CANCELLED) 处理
            } else {
                // completed/failed → 等同于删除
                String albumId = task.getString("albumId");
                String chapterId = task.getString("chapterId");
                FileStorage.getInstance().deleteChapter(albumId, chapterId);
                downloadDb.deleteImages(taskId);
                downloadDb.deleteTask(taskId);
                cleanupTaskMapping(taskId);
            }

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void pauseDownload(PluginCall call) {
        try {
            String taskId = call.getString("taskId");
            if (taskId == null) {
                call.reject("taskId is required");
                return;
            }

            org.json.JSONObject task = downloadDb.getTask(taskId);
            if (task == null) {
                call.reject("Task not found");
                return;
            }

            String status = task.optString("status");
            if (!STATUS_DOWNLOADING.equals(status)) {
                call.reject("只有下载中的任务可以暂停");
                return;
            }

            String libTaskId = taskIdMap.get(taskId);
            if (libTaskId == null) {
                call.reject("Library task not found");
                return;
            }

            AbstractJmClient client = (AbstractJmClient) getClient();
            if (client.downloadManager().getTask(libTaskId) == null) {
                call.reject("Task not found in download manager");
                return;
            }

            client.downloadManager().pause(libTaskId);

            downloadDb.updateStatus(taskId, STATUS_PAUSED);
            pushDownloadProgress(taskId, task.getString("albumId"),
                    task.getString("chapterId"),
                    task.optInt("downloadedPages"), task.optInt("totalPages"),
                    STATUS_PAUSED, null);

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void resumeDownload(PluginCall call) {
        try {
            String taskId = call.getString("taskId");
            if (taskId == null) {
                call.reject("taskId is required");
                return;
            }

            org.json.JSONObject task = downloadDb.getTask(taskId);
            if (task == null) {
                call.reject("Task not found");
                return;
            }

            String status = task.optString("status");
            if (!STATUS_PAUSED.equals(status)) {
                call.reject("只有已暂停的任务可以继续");
                return;
            }

            String libTaskId = taskIdMap.get(taskId);
            if (libTaskId == null) {
                call.reject("Library task not found");
                return;
            }

            AbstractJmClient client = (AbstractJmClient) getClient();
            if (client.downloadManager().getTask(libTaskId) == null) {
                call.reject("Task not found in download manager");
                return;
            }

            client.downloadManager().resume(libTaskId);

            downloadDb.updateStatus(taskId, STATUS_DOWNLOADING);
            pushDownloadProgress(taskId, task.getString("albumId"),
                    task.getString("chapterId"),
                    task.optInt("downloadedPages"), task.optInt("totalPages"),
                    STATUS_DOWNLOADING, null);

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void deleteDownloaded(PluginCall call) {
        try {
            String albumId = call.getString("albumId");
            String chapterId = call.getString("chapterId");
            if (albumId == null || chapterId == null) {
                call.reject("albumId and chapterId are required");
                return;
            }

            String taskId = albumId + "_" + chapterId;

            // 清理 pendingCancel（如果存在）
            pendingCancel.remove(taskId);

            // 如果任务正在下载中，先通过库取消
            String libTaskId = taskIdMap.get(taskId);
            if (libTaskId != null) {
                JmApiClient client = getClient();
                ((AbstractJmClient) client).downloadManager().cancel(libTaskId);
            }

            // 清理文件
            FileStorage.getInstance().deleteChapter(albumId, chapterId);

            // 清理 DB
            downloadDb.deleteImages(taskId);
            downloadDb.deleteTask(taskId);
            cleanupTaskMapping(taskId);

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void getDownloadedPhoto(PluginCall call) {
        try {
            String albumId = call.getString("albumId");
            String chapterId = call.getString("chapterId");
            if (albumId == null || chapterId == null) {
                call.reject("albumId and chapterId are required");
                return;
            }

            String taskId = albumId + "_" + chapterId;
            org.json.JSONObject task = downloadDb.getTask(taskId);
            if (task == null) {
                call.reject("Task not found");
                return;
            }

            String status = task.getString("status");
            if (!STATUS_COMPLETED.equals(status)) {
                call.reject("Task is not completed");
                return;
            }

            List<org.json.JSONObject> images = downloadDb.getImages(taskId);

            JSObject ret = new JSObject();
            ret.put("id", chapterId);
            ret.put("title", task.getString("chapterTitle"));
            ret.put("albumId", albumId);
            ret.put("sortOrder", 0);
            ret.put("author", task.optString("author", ""));
            ret.put("tags", new JSArray()); // tags 作为 JSON string 存储，这里简化处理

            JSArray imageArray = new JSArray();
            for (org.json.JSONObject img : images) {
                JSObject imgObj = new JSObject();
                imgObj.put("photoId", img.optString("photoId"));
                imgObj.put("scrambleId", img.optString("scrambleId"));
                imgObj.put("filename", img.optString("filename"));
                imgObj.put("url", img.optString("url"));
                imgObj.put("queryParams", img.optString("queryParams", ""));
                imgObj.put("sortOrder", img.optInt("sortOrder"));
                imageArray.put(imgObj);
            }
            ret.put("images", imageArray);

            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    // ---- TaskObserver：监听库任务状态/进度，同步到 DB 并推送到 JS ----

    private class DownloadTaskObserver implements TaskObserver {
        private final String ourTaskId;
        private final String albumId;
        private final String chapterId;
        private final int totalImages;
        private final AtomicBoolean finalized = new AtomicBoolean(false);
        private long lastBytes = 0;
        private long lastTimestamp = 0;

        DownloadTaskObserver(String ourTaskId, String albumId, String chapterId, int totalImages) {
            this.ourTaskId = ourTaskId;
            this.albumId = albumId;
            this.chapterId = chapterId;
            this.totalImages = totalImages;
            this.lastTimestamp = System.currentTimeMillis();
        }

        private boolean markFinalized() {
            return finalized.compareAndSet(false, true);
        }

        @Override
        public void onStateChanged(BaseDownloadTask task, TaskState newState) {
            if (newState.isTerminal() && !markFinalized()) return;

            if (newState == TaskState.COMPLETED) {
                Integer firstSO = FileStorage.getInstance()
                        .getFirstImageSortOrder(albumId, chapterId);
                downloadDb.updateCompleted(ourTaskId, totalImages,
                        firstSO != null ? firstSO : 1);
                pushDownloadProgress(ourTaskId, albumId, chapterId,
                        totalImages, totalImages, STATUS_COMPLETED, null);
                cleanupTaskMapping(ourTaskId);
            } else if (newState == TaskState.FAILED) {
                downloadDb.updateFailed(ourTaskId, 0, "下载失败");
                pushDownloadProgress(ourTaskId, albumId, chapterId,
                        0, totalImages, STATUS_FAILED, "下载失败");
                cleanupTaskMapping(ourTaskId);
            } else if (newState == TaskState.CANCELLED) {
                FileStorage.getInstance().deleteChapter(albumId, chapterId);
                downloadDb.deleteImages(ourTaskId);
                downloadDb.deleteTask(ourTaskId);
                pushDownloadProgress(ourTaskId, albumId, chapterId,
                        0, totalImages, STATUS_FAILED, "已取消");
                cleanupTaskMapping(ourTaskId);
            } else if (newState == TaskState.PAUSED) {
                int downloadedPages = task.getCompletedCount();
                downloadDb.updateProgress(ourTaskId, downloadedPages);
                pushDownloadProgress(ourTaskId, albumId, chapterId,
                        downloadedPages, totalImages, STATUS_PAUSED, null);
            } else if (newState == TaskState.COMPLETED_WITH_ERRORS) {
                DownloadResult result = task.getCurrentDownloadResult();
                int failed = result.getFailedTasks().size();
                int succeeded = totalImages - failed;
                downloadDb.updateFailed(ourTaskId, succeeded,
                        failed + "/" + totalImages + " 张图片下载失败");
                pushDownloadProgress(ourTaskId, albumId, chapterId,
                        succeeded, totalImages, STATUS_FAILED,
                        failed + "/" + totalImages + " 张图片下载失败");
                cleanupTaskMapping(ourTaskId);
            }
        }

        @Override
        public void onProgressUpdate(BaseDownloadTask task, DownloadProgress progress) {
            int completed = progress.completedImages();
            if (completed > 0) {
                long now = System.currentTimeMillis();
                long currentBytes = progress.downloadedBytes();
                long speed = 0;
                if (lastBytes > 0 && now > lastTimestamp) {
                    speed = (currentBytes - lastBytes) * 1000 / (now - lastTimestamp);
                }
                lastBytes = currentBytes;
                lastTimestamp = now;

                downloadDb.updateProgress(ourTaskId, completed);
                pushDownloadProgress(ourTaskId, albumId, chapterId,
                        completed, totalImages, STATUS_DOWNLOADING, null, speed);
            }
        }

        @Override
        public void onFinished(BaseDownloadTask task, DownloadResult result) {
            // 终态处理已在 onStateChanged 中完成
        }

        @Override
        public void onError(BaseDownloadTask task, Exception e) {
            if (!markFinalized()) return;
            downloadDb.updateFailed(ourTaskId, 0, e.getMessage());
            pushDownloadProgress(ourTaskId, albumId, chapterId,
                    0, totalImages, STATUS_FAILED, e.getMessage());
            cleanupTaskMapping(ourTaskId);
        }
    }

    private void cleanupTaskMapping(String ourTaskId) {
        String libTaskId = taskIdMap.remove(ourTaskId);
        if (libTaskId != null) {
            reverseTaskIdMap.remove(libTaskId);
        }
    }

    private void pushDownloadProgress(String taskId, String albumId, String chapterId,
                                      int downloadedPages, int totalPages,
                                      String status, String error) {
        pushDownloadProgress(taskId, albumId, chapterId, downloadedPages, totalPages,
                status, error, 0);
    }

    private void pushDownloadProgress(String taskId, String albumId, String chapterId,
                                      int downloadedPages, int totalPages,
                                      String status, String error, long speed) {
        JSObject data = new JSObject();
        data.put("taskId", taskId);
        data.put("albumId", albumId);
        data.put("chapterId", chapterId);
        data.put("downloadedPages", downloadedPages);
        data.put("totalPages", totalPages);
        data.put("status", status);
        if (error != null) {
            data.put("error", error);
        }
        if (speed > 0) {
            data.put("speed", speed);
        }
        notifyListeners("downloadProgress", data);
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

    private JSObject toFavoritePage(JmFavoritePage page, AbstractJmClient client) {
        JSObject ret = new JSObject();
        ret.put("folderName", page.getFolderName());
        ret.put("folderId", page.getFolderId());
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

        JSObject folderList = new JSObject();
        Map<String, String> folders = page.getFolderList();
        if (folders != null) {
            for (Map.Entry<String, String> entry : folders.entrySet()) {
                folderList.put(entry.getKey(), entry.getValue());
            }
        }
        ret.put("folderList", folderList);
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
