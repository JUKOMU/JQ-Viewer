package io.github.jukomu.bridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;
import io.github.jukomu.data.DownloadStore;
import io.github.jukomu.data.FileStore;
import io.github.jukomu.data.ImageCache;
import io.github.jukomu.data.SettingsStore;
import io.github.jukomu.jmcomic.api.enums.Category;
import io.github.jukomu.jmcomic.api.enums.OrderBy;
import io.github.jukomu.jmcomic.api.enums.SearchMainTag;
import io.github.jukomu.jmcomic.api.enums.TimeOption;
import io.github.jukomu.jmcomic.core.JmComic;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import io.github.jukomu.jmcomic.core.config.JmConfiguration;
import io.github.jukomu.service.*;
import io.github.jukomu.service.PermissionState;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author JUKOMU
 * @Description:
 * @Project: jq-viewer
 * @Date: 2026/4/22
 */
@CapacitorPlugin(name = "Jmcomic")
public class JmcomicPlugin extends Plugin implements ServiceListener {
    private volatile JmApiClient sharedClient;
    private volatile ExecutorService imageExecutor;
    private volatile ExecutorService apiExecutor;
    private volatile ScheduledExecutorService apiTimeoutExecutor;
    private static final int API_EXECUTOR_SIZE = 6;
    private int imageConcurrency = 6;
    private int downloadConcurrency = 6;


    // ---- 权限相关 ----
    private static JmcomicPlugin instance;
    private PluginCall pendingPermissionCall;
    private PermissionService permissionService;

    // ---- 服务 ----
    private ApiService apiService;
    private PreloadService preloadService;
    private SettingsService settingsService;
    private DownloadService downloadService;

    // ---- 下载相关 ----
    private DownloadStore downloadDb;

    @Override
    public void load() {
        instance = this;
        this.permissionService = new PermissionService();

        Context ctx = getContext();
        SettingsStore settingsDb = SettingsStore.getInstance(ctx);

        // 读取下载公开设置 → 权限检查 → FileStore
        boolean downloadPublic = settingsDb.getBoolean("download_public", false);
        downloadDb = DownloadStore.getInstance(ctx);

        // API 感知的权限检查：决定实际使用的目录
        boolean usePublicDir = downloadPublic;
        if (downloadPublic) {
            PermissionState state = permissionService.checkState(ctx);
            if (!state.granted) {
                android.util.Log.w("JmcomicPlugin",
                        "公开下载已开启但缺少 " + state.permissionType + "，" +
                                "回退到私有目录。请调用 requestManageStorage 授权。");
                usePublicDir = false;
                settingsDb.putString("download_public", "false");
            }
        }
        FileStore.getInstance().init(ctx, downloadDb, usePublicDir);

        // 清理僵尸任务的部分下载文件（validateOnStartup 标记前）
        List<org.json.JSONObject> zombieTasks = downloadDb.getAllTasks();
        for (org.json.JSONObject t : zombieTasks) {
            String s = t.optString("status");
            if ("queued".equals(s) || "downloading".equals(s) || "paused".equals(s)) {
                FileStore.getInstance().deleteChapter(
                        t.optString("albumId"), t.optString("chapterId"));
            }
        }

        downloadDb.validateOnStartup(FileStore.getInstance().getBaseDir());

        // 读取缓存容量 → 初始化 ImageCache
        long cacheCapacityMb = settingsDb.getLong("cache_capacity_mb", 640);
        ImageCache.getInstance().setCapacity(cacheCapacityMb * 1024 * 1024);

        // 读取并发数设置 → 初始化 imageExecutor 和 downloadConcurrency
        imageConcurrency = settingsDb.getInt("preload_concurrency", 6);
        downloadConcurrency = settingsDb.getInt("download_concurrency", 6);
        imageExecutor = Executors.newFixedThreadPool(imageConcurrency);
        apiExecutor = Executors.newFixedThreadPool(API_EXECUTOR_SIZE);
        apiTimeoutExecutor = Executors.newSingleThreadScheduledExecutor();

        // 预热 client，避免首次 API 调用时的冷启动延迟
        getClient();

        // 初始化服务
        this.apiService = new ApiService(sharedClient, apiExecutor, apiTimeoutExecutor);
        this.settingsService = new SettingsService(settingsDb, downloadDb,
                FileStore.getInstance(), permissionService, ctx, this);
        this.preloadService = new PreloadService(ImageCache.getInstance(),
                FileStore.getInstance(), settingsDb, sharedClient, imageExecutor, this);
        this.downloadService = new DownloadService(downloadDb,
                FileStore.getInstance(), sharedClient, imageExecutor, this);
    }

    @Override
    protected void handleOnDestroy() {
        shutdownGracefully(apiExecutor);
        shutdownGracefully(apiTimeoutExecutor);
        shutdownGracefully(imageExecutor);
    }

    private void shutdownGracefully(ExecutorService executor) {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    private synchronized JmApiClient getClient() {
        if (sharedClient == null) {
            sharedClient = JmComic.newApiClient(new JmConfiguration.Builder().downloadThreadPoolSize(downloadConcurrency).build());
        }
        return sharedClient;
    }

    /**
     * 请求存储权限（根据 API 版本选择最合适的权限）。
     * 返回 { granted: boolean, permissionType: string, apiLevel: int }
     */
    @PluginMethod
    public void requestManageStorage(PluginCall call) {
        PermissionState state = permissionService.checkState(getContext());
        JSObject ret = new JSObject();
        ret.put("granted", state.granted);
        ret.put("permissionType", state.permissionType);
        ret.put("apiLevel", state.apiLevel);

        if (state.apiLevel >= Build.VERSION_CODES.R) {
            if (!state.granted) {
                try {
                    permissionService.openSystemSettings(getContext());
                } catch (Exception e) {
                    call.reject("无法打开存储权限设置页: " + e.getMessage());
                    return;
                }
            }
            call.resolve(ret);
        } else if (state.apiLevel >= Build.VERSION_CODES.Q) {
            call.resolve(ret);
        } else if (state.apiLevel >= Build.VERSION_CODES.M) {
            if (!state.granted) {
                if (pendingPermissionCall != null) {
                    call.reject("权限请求正在进行中，请先完成上一个请求。");
                    return;
                }
                pendingPermissionCall = call;
                call.setKeepAlive(true);
                ActivityCompat.requestPermissions(getActivity(),
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        PermissionService.REQUEST_WRITE_STORAGE);
                return;
            }
            call.resolve(ret);
        } else {
            call.resolve(ret);
        }
    }

    /**
     * 供 MainActivity.onRequestPermissionsResult 调用。
     */
    public void handlePermissionResult(int requestCode, String[] permissions,
                                       int[] grantResults) {
        if (requestCode == PermissionService.REQUEST_WRITE_STORAGE && pendingPermissionCall != null) {
            PluginCall call = pendingPermissionCall;
            pendingPermissionCall = null;
            PermissionState state = permissionService.interpretResult(grantResults);
            JSObject ret = new JSObject();
            ret.put("apiLevel", state.apiLevel);
            ret.put("permissionType", state.permissionType);
            ret.put("granted", state.granted);
            call.resolve(ret);
        }
    }

    public static JmcomicPlugin getInstance() {
        return instance;
    }

    @PluginMethod
    public void search(PluginCall call) {
        try {
            JSObject q = call.getObject("query");
            if (q == null) {
                call.reject("query is required");
                return;
            }
            call.setKeepAlive(true);
            apiService.search(
                    q.getString("keyword", ""),
                    q.getString("category", Category.ALL.getValue()),
                    q.getString("orderBy", OrderBy.LATEST.getValue()),
                    q.getString("time", TimeOption.ALL.getValue()),
                    q.getInteger("searchMainTag", SearchMainTag.SITE_SEARCH.getValue()),
                    q.getInteger("page", 1),
                    bridgeCallback(call));
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void categories(PluginCall call) {
        try {
            JSObject q = call.getObject("query");
            if (q == null) {
                call.reject("query is required");
                return;
            }
            call.setKeepAlive(true);
            apiService.categories(
                    q.getString("keyword", ""),
                    q.getString("category", Category.ALL.getValue()),
                    q.getString("orderBy", OrderBy.LATEST.getValue()),
                    q.getString("time", TimeOption.ALL.getValue()),
                    q.getInteger("searchMainTag", SearchMainTag.SITE_SEARCH.getValue()),
                    q.getInteger("page", 1),
                    bridgeCallback(call));
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
            call.setKeepAlive(true);
            apiService.getAlbum(id, bridgeCallback(call));
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
            call.setKeepAlive(true);
            apiService.getPhoto(id, bridgeCallback(call));
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
            call.setKeepAlive(true);
            apiService.getComments(albumId, call.getInt("page", 1), bridgeCallback(call));
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
            call.setKeepAlive(true);
            apiService.toggleAlbumLike(id, bridgeCallback(call));
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void getFavorites(PluginCall call) {
        try {
            JSObject q = call.getObject("query");
            if (q == null) {
                call.reject("query is required");
                return;
            }
            call.setKeepAlive(true);
            apiService.getFavorites(
                    q.getInteger("folderId", 0),
                    q.getInteger("page", 1),
                    bridgeCallback(call));
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
            call.setKeepAlive(true);
            apiService.toggleAlbumFavorite(id, call.getString("folderId", "0"),
                    bridgeCallback(call));
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    // ---- ApiCallback → PluginCall 适配 ----
    private ApiCallback bridgeCallback(PluginCall call) {
        return new ApiCallback() {
            @Override
            public void onSuccess(JSONObject result) {
                try {
                    call.resolve(JSObject.fromJSONObject(result));
                } catch (Exception e) {
                    call.reject(e.getMessage(), e);
                }
            }

            @Override
            public void onError(String message, Exception e) {
                call.reject(message, e);
            }
        };
    }

    @PluginMethod
    public void preloadImages(PluginCall call) {
        try {
            String photoId = call.getString("photoId");
            String type = call.getString("type", "image");
            JSArray imagesArray = call.getArray("images");
            JSONArray jsonImages = new JSONArray();
            if (imagesArray != null) {
                for (int i = 0; i < imagesArray.length(); i++) {
                    try {
                        jsonImages.put(imagesArray.getJSONObject(i));
                    } catch (Exception ignored) {
                    }
                }
            }
            JSONObject result = preloadService.preloadImages(photoId, type, jsonImages);
            call.resolve(JSObject.fromJSONObject(result));
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
        preloadService.clearPhotoCache(photoId);
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
        preloadService.setCacheCapacity(mb);
        JSObject ret = new JSObject();
        ret.put("success", true);
        ret.put("capacityMb", mb);
        call.resolve(ret);
    }

    @PluginMethod
    public void getCacheCapacityInfo(PluginCall call) {
        try {
            call.resolve(JSObject.fromJSONObject(preloadService.getCacheCapacityInfo()));
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void clearImageCache(PluginCall call) {
        preloadService.clearImageCache();
        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void setDownloadConcurrency(PluginCall call) {
        try {
            Integer n = call.getInt("n");
            if (n == null || n < 1 || n > 12) {
                call.reject("n must be between 1 and 12");
                return;
            }
            settingsService.setDownloadConcurrency(n);
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void setPreloadConcurrency(PluginCall call) {
        try {
            Integer n = call.getInt("n");
            if (n == null || n < 1 || n > 12) {
                call.reject("n must be between 1 and 12");
                return;
            }
            settingsService.setPreloadConcurrency(n);
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void setDownloadPublic(PluginCall call) {
        boolean open = call.getBoolean("open", false);

        String error = settingsService.validateSwitch(open);
        if (error != null) {
            call.reject(error);
            return;
        }

        call.setKeepAlive(true);
        settingsService.relocate(open, new SettingsService.RelocateCallback() {
            @Override
            public void onSuccess(JSONObject result) {
                try {
                    call.resolve(JSObject.fromJSONObject(result));
                } catch (Exception e) {
                    call.reject(e.getMessage(), e);
                }
            }

            @Override
            public void onError(String message, Exception e) {
                call.reject(message, e);
            }
        });
    }

    @PluginMethod
    public void getDownloadPublic(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("downloadPublic", settingsService.getDownloadPublic());
        call.resolve(ret);
    }

    @PluginMethod
    public void getAllSettings(PluginCall call) {
        try {
            call.resolve(JSObject.fromJSONObject(settingsService.getAllSettings()));
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void setReaderPreloadPages(PluginCall call) {
        try {
            Integer n = call.getInt("n");
            if (n == null || n < 5 || n > 50) {
                call.reject("n must be between 5 and 50");
                return;
            }
            settingsService.setReaderPreloadPages(n);
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    // ========== 下载相关 ==========

    @SuppressLint("NewApi")
    @PluginMethod
    public void downloadChapter(PluginCall call) {
        try {
            String albumId = call.getString("albumId");
            String chapterId = call.getString("chapterId");
            if (albumId == null || chapterId == null) {
                call.reject("albumId and chapterId are required");
                return;
            }
            String taskId = downloadService.downloadChapter(albumId, chapterId,
                    call.getString("albumTitle", ""),
                    call.getString("chapterTitle", ""),
                    call.getString("coverUrl", ""));
            JSObject ret = new JSObject();
            ret.put("taskId", taskId);
            call.resolve(ret);
        } catch (IllegalStateException e) {
            call.reject(e.getMessage());
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void getDownloadTasks(PluginCall call) {
        try {
            JSONArray tasks = downloadService.getDownloadTasks();
            JSArray arr = new JSArray();
            for (int i = 0; i < tasks.length(); i++) {
                try {
                    arr.put(JSObject.fromJSONObject(tasks.getJSONObject(i)));
                } catch (Exception ignored) {
                }
            }
            JSObject ret = new JSObject();
            ret.put("tasks", arr);
            ret.put("usedBytes", downloadService.getUsedBytes());
            ret.put("availableBytes", downloadService.getAvailableBytes());
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
            downloadService.cancelDownload(taskId);
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
            downloadService.pauseDownload(taskId);
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (IllegalArgumentException | IllegalStateException e) {
            call.reject(e.getMessage());
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
            downloadService.resumeDownload(taskId);
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (IllegalArgumentException | IllegalStateException e) {
            call.reject(e.getMessage());
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
            downloadService.deleteDownloaded(albumId, chapterId);
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
            JSONObject result = downloadService.getDownloadedPhoto(albumId, chapterId);
            call.resolve(JSObject.fromJSONObject(result));
        } catch (IllegalArgumentException | IllegalStateException e) {
            call.reject(e.getMessage());
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    // ---- ServiceListener 实现 ----

    @Override
    public void onDownloadProgress(DownloadProgressData d) {
        JSObject data = new JSObject();
        data.put("taskId", d.taskId);
        data.put("albumId", d.albumId);
        data.put("chapterId", d.chapterId);
        data.put("downloadedPages", d.downloadedPages);
        data.put("totalPages", d.totalPages);
        data.put("status", d.status);
        if (d.error != null) data.put("error", d.error);
        data.put("speed", d.speed);
        if (d.totalSize > 0) data.put("totalSize", d.totalSize);
        notifyListeners("downloadProgress", data);
    }

    @Override
    public void onImageReady(String photoId, int sortOrder, String type) {
        JSObject data = new JSObject();
        data.put("photoId", photoId);
        data.put("sortOrder", sortOrder);
        data.put("type", type);
        notifyListeners("imageReady", data);
    }

    @Override
    public void onRelocationProgress(int current, int total, String phase, String currentFile) {
        JSObject data = new JSObject();
        data.put("current", current);
        data.put("total", total);
        data.put("phase", phase);
        if (currentFile != null) data.put("currentFile", currentFile);
        notifyListeners("relocationProgress", data);
    }
}
