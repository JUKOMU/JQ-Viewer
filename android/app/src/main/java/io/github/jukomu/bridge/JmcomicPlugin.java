package io.github.jukomu.bridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;
import io.github.jukomu.data.CredentialStore;
import io.github.jukomu.data.DownloadStore;
import io.github.jukomu.data.FileStore;
import io.github.jukomu.data.FavoriteStore;
import io.github.jukomu.data.HistoryStore;
import io.github.jukomu.data.ImageCache;
import io.github.jukomu.data.SettingsStore;
import io.github.jukomu.jmcomic.api.enums.Category;
import io.github.jukomu.jmcomic.api.enums.OrderBy;
import io.github.jukomu.jmcomic.api.enums.SearchMainTag;
import io.github.jukomu.jmcomic.api.enums.TimeOption;
import io.github.jukomu.jmcomic.core.JmComic;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import io.github.jukomu.jmcomic.api.exception.ResponseException;
import io.github.jukomu.jmcomic.core.config.JmConfiguration;
import io.github.jukomu.service.*;
import io.github.jukomu.service.PermissionState;
import okhttp3.Cookie;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
    private static final int API_EXECUTOR_SIZE = 12;
    private int imageConcurrency = 6;
    private int downloadConcurrency = 6;

    // ---- 网络变化监听 ----
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ScheduledExecutorService domainProbeExecutor;
    private ScheduledFuture<?> pendingProbe;
    private final Object probeLock = new Object();
    private static final long PROBE_DEBOUNCE_MS = 2000;

    // ---- 权限相关 ----
    private static JmcomicPlugin instance;
    private PluginCall pendingPermissionCall;
    private PermissionService permissionService;

    // ---- OCR 相关 ----
    private PluginCall pendingOcrCall;
    private static final int REQUEST_PICK_IMAGE = 1001;

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

        // 初始化历史记录数据库
        HistoryStore.getInstance(ctx);
        FavoriteStore.getInstance(ctx);

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

        // 注册网络变化监听，网络切换时主动触发域名探活
        registerNetworkCallback();

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

        // 恢复登录态（Cookie + username + userInfo）
        restoreAuthState(settingsDb);
    }

    private void restoreAuthState(SettingsStore settingsDb) {
        settingsDb.deleteKey("auth_cookies_json");
        settingsDb.deleteKey("auth_username");
        settingsDb.deleteKey("auth_user_info_json");
    }

    @Override
    protected void handleOnDestroy() {
        // 取消待处理的域名探活
        synchronized (probeLock) {
            if (pendingProbe != null) {
                pendingProbe.cancel(false);
                pendingProbe = null;
            }
        }
        // 取消注册网络回调
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException ignored) {
            }
        }
        shutdownGracefully(domainProbeExecutor);

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

    /**
     * 注册默认网络变化监听。当默认网络变为可用时（WiFi/移动数据/VPN 切换），
     * 经过去抖动后触发域名重新探活，使库的域名拦截器能及时发现新网络下的可达域名。
     */
    private void registerNetworkCallback() {
        Context ctx = getContext();
        connectivityManager = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            android.util.Log.w("JmcomicPlugin", "ConnectivityManager 不可用，跳过网络监听");
            return;
        }

        domainProbeExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "domain-probe-debounce");
            t.setDaemon(true);
            return t;
        });

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                JSObject event = new JSObject();
                event.put("phase", "network_changed");
                event.put("message", "网络已切换，即将重新探活");
                event.put("timestamp", System.currentTimeMillis());
                notifyListeners("networkProbe", event);
                scheduleDomainProbe();
            }

            @Override
            public void onLost(@NonNull Network network) {
                JSObject event = new JSObject();
                event.put("phase", "network_lost");
                event.put("message", "网络已断开");
                event.put("timestamp", System.currentTimeMillis());
                notifyListeners("networkProbe", event);
            }
        };

        connectivityManager.registerDefaultNetworkCallback(networkCallback);
        android.util.Log.i("JmcomicPlugin", "网络变化监听已注册");
    }

    /**
     * 经过去抖动后触发域名重新探活。
     * 若在去抖动窗口内多次调用，仅最后一次生效。
     */
    private void scheduleDomainProbe() {
        if (domainProbeExecutor == null) return;
        final JmApiClient client = sharedClient;
        if (client == null) return;

        synchronized (probeLock) {
            if (pendingProbe != null) {
                pendingProbe.cancel(false);
            }
            pendingProbe = domainProbeExecutor.schedule(() -> {
                try {
                    JSObject startEvent = new JSObject();
                    startEvent.put("phase", "probing");
                    startEvent.put("message", "正在探测域名连通性...");
                    startEvent.put("timestamp", System.currentTimeMillis());
                    notifyListeners("networkProbe", startEvent);

                    android.util.Log.i("JmcomicPlugin", "重新探活域名...");
                    client.reprobeDomains();
                    android.util.Log.i("JmcomicPlugin", "域名重新探活完成");

                    try {
                        java.util.Map<String, Integer> states = client.getDomainStates();

                        org.json.JSONObject result = new org.json.JSONObject();
                        result.put("phase", "result");
                        int alive = 0;
                        org.json.JSONArray domains = new org.json.JSONArray();
                        for (java.util.Map.Entry<String, Integer> e : states.entrySet()) {
                            org.json.JSONObject d = new org.json.JSONObject();
                            d.put("domain", e.getKey());
                            d.put("reachable", e.getValue() < (Integer.MAX_VALUE / 2));
                            if (d.getBoolean("reachable")) alive++;
                            domains.put(d);
                        }
                        boolean allDeadFallback = states.size() > 0
                                && states.values().stream().allMatch(v -> v == -1);
                        result.put("allDeadFallback", allDeadFallback);
                        result.put("domains", domains);
                        result.put("alive", alive);
                        result.put("total", states.size());
                        result.put("timestamp", System.currentTimeMillis());

                        if (allDeadFallback) {
                            result.put("message", "探活完成 · 全部不可达");
                        } else {
                            result.put("message", "探活完成 · " + alive + "/" + states.size() + " 可达");
                        }

                        notifyListeners("networkProbe", JSObject.fromJSONObject(result));
                    } catch (Exception ignored) {
                        JSObject errEvent = new JSObject();
                        errEvent.put("phase", "result");
                        errEvent.put("message", "探活完成");
                        errEvent.put("timestamp", System.currentTimeMillis());
                        notifyListeners("networkProbe", errEvent);
                    }
                } catch (Exception e) {
                    android.util.Log.w("JmcomicPlugin", "域名重新探活失败", e);
                    JSObject errEvent = new JSObject();
                    errEvent.put("phase", "error");
                    String errMsg = e.getMessage();
                    errEvent.put("message", "探活异常" + (errMsg != null ? " · " + errMsg : ""));
                    errEvent.put("timestamp", System.currentTimeMillis());
                    notifyListeners("networkProbe", errEvent);
                }
            }, PROBE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 读取 domainManager 中已有的域名状态（同步返回，不触发探活）。
     * 用于前端初始化时展示 AbstractJmClient 构造时已完成的首轮探活结果。
     */
    @PluginMethod
    public void getDomainStates(PluginCall call) {
        try {
            JmApiClient client = sharedClient;
            if (client == null) {
                call.reject("client 尚未初始化");
                return;
            }
            java.util.Map<String, Integer> states = client.getDomainStates();

            JSObject result = new JSObject();
            int alive = 0;
            JSONArray domains = new JSONArray();
            for (java.util.Map.Entry<String, Integer> e : states.entrySet()) {
                JSONObject d = new JSONObject();
                d.put("domain", e.getKey());
                boolean reachable = e.getValue() < (Integer.MAX_VALUE / 2);
                d.put("reachable", reachable);
                if (reachable) alive++;
                domains.put(d);
            }
            boolean allDeadFallback = states.size() > 0
                    && states.values().stream().allMatch(v -> v == -1);

            result.put("domains", domains);
            result.put("alive", alive);
            result.put("total", states.size());
            result.put("allDeadFallback", allDeadFallback);
            call.resolve(result);
        } catch (Exception e) {
            android.util.Log.e("JmcomicPlugin", "获取域名状态失败", e);
            call.reject("获取域名状态失败，请稍后重试");
        }
    }

    /**
     * 手动触发域名重新探活（结果通过 networkProbe 事件推送）。
     * 用于网络状态页的刷新按钮。
     */
    @PluginMethod
    public void reprobeDomains(PluginCall call) {
        scheduleDomainProbe();
        call.resolve();
    }

    /**
     * 对可达域名进行延迟测试（HEAD 请求计时）。
     * 不可达域名直接跳过，前端自行填充固定值。
     */
    @PluginMethod
    public void measureLatency(PluginCall call) {
        try {
            JmApiClient client = sharedClient;
            if (client == null) {
                call.reject("client 尚未初始化");
                return;
            }
            java.util.Map<String, Integer> latency = client.getDomainLatency();

            JSObject ret = new JSObject();
            JSONArray results = new JSONArray();
            for (java.util.Map.Entry<String, Integer> e : latency.entrySet()) {
                JSONObject r = new JSONObject();
                r.put("domain", e.getKey());
                int ms = e.getValue();
                r.put("latencyMs", ms == -1 ? 0 : ms);
                r.put("timedOut", ms == -1);
                results.put(r);
            }
            ret.put("results", results);
            call.resolve(ret);
        } catch (Exception e) {
            android.util.Log.e("JmcomicPlugin", "测速失败", e);
            call.reject("测速失败，请稍后重试");
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

    /**
     * 供 MainActivity.onActivityResult 调用，处理图片选择结果并执行 OCR。
     */
    public void handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK_IMAGE || pendingOcrCall == null) return;

        PluginCall call = pendingOcrCall;
        pendingOcrCall = null;

        if (resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) {
            JSObject ret = new JSObject();
            ret.put("text", "");
            ret.put("error", "");
            call.resolve(ret);
            return;
        }

        ExecutorService ocrExecutor = Executors.newSingleThreadExecutor();
        ocrExecutor.execute(() -> {
            try {
                InputImage inputImage = InputImage.fromFilePath(
                    getContext(), data.getData());

                TextRecognizer recognizer = TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build());

                java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
                Text[] resultHolder = new Text[1];
                Exception[] errorHolder = new Exception[1];

                recognizer.process(inputImage)
                    .addOnSuccessListener(text -> {
                        resultHolder[0] = text;
                        latch.countDown();
                    })
                    .addOnFailureListener(e -> {
                        errorHolder[0] = e;
                        latch.countDown();
                    });

                boolean completed = latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
                recognizer.close();

                JSObject ret = new JSObject();
                if (!completed) {
                    ret.put("text", "");
                    ret.put("error", "识别超时，请重试");
                } else if (errorHolder[0] != null) {
                    ret.put("text", "");
                    android.util.Log.e("JmcomicPlugin", "OCR识别失败", errorHolder[0]);
                    ret.put("error", "识别失败，请重试");
                } else if (resultHolder[0] != null) {
                    ret.put("text", resultHolder[0].getText());
                    ret.put("error", "");
                } else {
                    ret.put("text", "");
                    ret.put("error", "未识别到文字");
                }
                call.resolve(ret);
            } catch (Exception e) {
                JSObject ret = new JSObject();
                ret.put("text", "");
                android.util.Log.e("JmcomicPlugin", "OCR调用失败", e);
                ret.put("error", "识别失败，请重试");
                call.resolve(ret);
            } finally {
                ocrExecutor.shutdown();
            }
        });
    }

    public static JmcomicPlugin getInstance() {
        return instance;
    }

    @PluginMethod
    public void pickImageAndOcr(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_PICK,
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        pendingOcrCall = call;
        try {
            getActivity().startActivityForResult(intent, REQUEST_PICK_IMAGE);
        } catch (Exception e) {
            pendingOcrCall = null;
            call.reject(e.getMessage(), e);
        }
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
    public void manageFavoriteFolder(PluginCall call) {
        try {
            String type = call.getString("type");
            if (type == null || type.isEmpty()) {
                call.reject("type is required (add/edit/move/del)");
                return;
            }
            String folderId = call.getString("folderId", "");
            // "add" 操作 folderId 可为空（默认 "0"）；edit/del 禁止 "0"（"全部"不可改名/删除）；move 允许 "0"（移到"全部"）
            if ("edit".equals(type) || "del".equals(type)) {
                if (folderId.isEmpty() || "0".equals(folderId)) {
                    call.reject("folderId is required for edit/del operations");
                    return;
                }
            } else if (folderId.isEmpty()) {
                folderId = "0";
            }
            call.setKeepAlive(true);
            apiService.manageFavoriteFolder(
                    type,
                    folderId,
                    call.getString("folderName", ""),
                    call.getString("albumId", ""),
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

    @PluginMethod
    public void setOcrEnabled(PluginCall call) {
        try {
            Boolean enabled = call.getBoolean("enabled");
            if (enabled == null) {
                call.reject("enabled is required");
                return;
            }
            settingsService.setOcrEnabled(enabled);
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

    // ========== 浏览历史 ==========

    @PluginMethod
    public void getBrowseHistory(PluginCall call) {
        try {
            int limit = call.getInt("limit", 0);
            int offset = call.getInt("offset", 0);
            JSObject ret = new JSObject();
            ret.put("items", HistoryStore.getInstance(getContext()).getBrowseHistory(limit, offset));
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void recordBrowse(PluginCall call) {
        try {
            HistoryStore.getInstance(getContext()).recordBrowse(
                    call.getString("albumId", ""),
                    call.getString("albumTitle", ""),
                    call.getString("coverUrl", ""),
                    call.getString("authors", ""),
                    call.getString("chapterId", ""),
                    call.getString("chapterTitle", "")
            );
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void clearBrowseHistory(PluginCall call) {
        try {
            HistoryStore.getInstance(getContext()).clearBrowseHistory();
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    // ========== 解析历史 ==========

    @PluginMethod
    public void getParseHistory(PluginCall call) {
        try {
            int limit = call.getInt("limit", 0);
            int offset = call.getInt("offset", 0);
            JSObject ret = new JSObject();
            ret.put("items", HistoryStore.getInstance(getContext()).getParseHistory(limit, offset));
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void addParseHistory(PluginCall call) {
        try {
            HistoryStore.getInstance(getContext()).addParseHistory(
                    call.getString("text", "")
            );
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void clearParseHistory(PluginCall call) {
        try {
            HistoryStore.getInstance(getContext()).clearParseHistory();
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    // ========== 离线收藏夹 ==========

    @PluginMethod
    public void getOfflineFolders(PluginCall call) {
        try {
            JSObject ret = new JSObject();
            ret.put("folders", FavoriteStore.getInstance(getContext()).getFolders());
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void createOfflineFolder(PluginCall call) {
        try {
            String name = call.getString("name", "");
            String folderId = FavoriteStore.getInstance(getContext()).createFolder(name);
            JSObject ret = new JSObject();
            ret.put("folderId", folderId);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void renameOfflineFolder(PluginCall call) {
        try {
            boolean ok = FavoriteStore.getInstance(getContext()).renameFolder(
                    call.getString("folderId", ""),
                    call.getString("name", ""));
            JSObject ret = new JSObject();
            ret.put("success", ok);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void deleteOfflineFolder(PluginCall call) {
        try {
            boolean ok = FavoriteStore.getInstance(getContext()).deleteFolder(
                    call.getString("folderId", ""));
            JSObject ret = new JSObject();
            ret.put("success", ok);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void addOfflineFavorite(PluginCall call) {
        try {
            String folderId = call.getString("folderId", "");
            JSObject item = call.getObject("item");
            boolean ok = FavoriteStore.getInstance(getContext()).addItem(folderId, item);
            JSObject ret = new JSObject();
            ret.put("success", ok);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void removeOfflineFavorite(PluginCall call) {
        try {
            boolean ok = FavoriteStore.getInstance(getContext()).removeItem(
                    call.getString("folderId", ""),
                    call.getString("albumId", ""));
            JSObject ret = new JSObject();
            ret.put("success", ok);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void getOfflineFavorites(PluginCall call) {
        try {
            JSONObject data = FavoriteStore.getInstance(getContext()).getItems(
                    call.getString("folderId", ""),
                    call.getString("keyword", null),
                    call.getInt("page", 1),
                    call.getInt("pageSize", 20));
            JSObject ret = new JSObject();
            ret.put("totalItems", data.optInt("totalItems", 0));
            ret.put("totalPages", data.optInt("totalPages", 1));
            ret.put("currentPage", data.optInt("currentPage", 1));
            ret.put("content", data.optJSONArray("content"));
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void getAllOfflineFavorites(PluginCall call) {
        try {
            JSObject ret = new JSObject();
            ret.put("items", FavoriteStore.getInstance(getContext()).getAllItems(
                    call.getString("folderId", "")));
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void getOfflineFavoritesTotalCount(PluginCall call) {
        try {
            JSObject ret = new JSObject();
            ret.put("count", FavoriteStore.getInstance(getContext()).getTotalCount());
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void getAllOfflineFavoritesMerged(PluginCall call) {
        try {
            JSObject ret = new JSObject();
            ret.put("items", FavoriteStore.getInstance(getContext()).getAllItemsMerged());
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void moveAllOfflineFavorites(PluginCall call) {
        try {
            boolean ok = FavoriteStore.getInstance(getContext()).moveAllItems(
                    call.getString("sourceId", ""),
                    call.getString("targetId", ""));
            JSObject ret = new JSObject();
            ret.put("success", ok);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void copyOfflineFolder(PluginCall call) {
        try {
            String newId = FavoriteStore.getInstance(getContext()).copyFolder(
                    call.getString("sourceId", ""),
                    call.getString("name", ""));
            JSObject ret = new JSObject();
            ret.put("folderId", newId);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void addOfflineFavoritesBatch(PluginCall call) {
        try {
            int count = FavoriteStore.getInstance(getContext()).addItemsBatch(
                    call.getString("folderId", ""),
                    call.getArray("items"));
            JSObject ret = new JSObject();
            ret.put("count", count);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void mergeOfflineAllToFolder(PluginCall call) {
        try {
            boolean ok = FavoriteStore.getInstance(getContext()).mergeAllToFolder(
                    call.getString("targetId", ""));
            JSObject ret = new JSObject();
            ret.put("success", ok);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    // ========== 离线收藏夹容灾备份 ==========

    @PluginMethod
    public void saveOfflineBackup(PluginCall call) {
        try {
            FavoriteStore.getInstance(getContext()).saveBackup(
                    call.getString("key", ""),
                    call.getArray("items"));
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void loadOfflineBackup(PluginCall call) {
        try {
            JSONArray items = FavoriteStore.getInstance(getContext()).loadBackup(
                    call.getString("key", ""));
            JSObject ret = new JSObject();
            ret.put("items", items != null ? items : JSONObject.NULL);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void deleteOfflineBackup(PluginCall call) {
        try {
            boolean ok = FavoriteStore.getInstance(getContext()).deleteBackup(
                    call.getString("key", ""));
            JSObject ret = new JSObject();
            ret.put("success", ok);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void listOfflineBackupKeys(PluginCall call) {
        try {
            JSObject ret = new JSObject();
            ret.put("keys", FavoriteStore.getInstance(getContext()).listBackupKeys());
            call.resolve(ret);
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

    // ========== 用户认证 ==========

    @PluginMethod
    public void login(PluginCall call) {
        try {
            String username = call.getString("username");
            String password = call.getString("password");
            if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
                call.reject("username and password are required");
                return;
            }
            call.setKeepAlive(true);
            apiService.login(username, password, new ApiCallback() {
                @Override
                public void onSuccess(JSONObject userInfo) {
                    try {
                        // 持久化 cookies + username + userInfo + 凭据（用于重启自动登录）
                        SettingsStore settingsDb = SettingsStore.getInstance(getContext());
                        List<Cookie> cookies = sharedClient.getCookies();
                        settingsDb.putString("auth_cookies_json", cookiesToJson(cookies).toString());
                        settingsDb.putString("auth_username", userInfo.getString("username"));
                        settingsDb.putString("auth_user_info_json", userInfo.toString());
                        CredentialStore.getInstance(getContext()).save(username, password);
                        call.resolve(JSObject.fromJSONObject(userInfo));
                    } catch (Exception e) {
                        call.reject(e.getMessage(), e);
                    }
                }

                @Override
                public void onError(String message, Exception e) {
                    call.reject(message, e);
                }
            });
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void logout(PluginCall call) {
        try {
            call.setKeepAlive(true);
            apiService.logout(new ApiCallback() {
                @Override
                public void onSuccess(JSONObject result) {
                    try {
                        SettingsStore settingsDb = SettingsStore.getInstance(getContext());
                        settingsDb.deleteKey("auth_cookies_json");
                        settingsDb.deleteKey("auth_username");
                        settingsDb.deleteKey("auth_user_info_json");
                        CredentialStore.getInstance(getContext()).clear();
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
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void getUserProfile(PluginCall call) {
        try {
            String uid = call.getString("uid");
            if (uid == null || uid.isEmpty()) {
                call.reject("uid is required");
                return;
            }
            call.setKeepAlive(true);
            apiService.getUserProfile(uid, new ApiCallback() {
                @Override
                public void onSuccess(JSONObject result) {
                    try {
                        call.resolve(JSObject.fromJSONObject(result));
                    } catch (JSONException e) {
                        call.reject(e.getMessage(), e);
                    }
                }

                @Override
                public void onError(String message, Exception e) {
                    call.reject(message, e);
                }
            });
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void checkLoginState(PluginCall call) {
        SettingsStore settingsDb = SettingsStore.getInstance(getContext());
        String username = settingsDb.getString("auth_username");
        String userInfoJson = settingsDb.getString("auth_user_info_json");

        JSObject ret = new JSObject();
        if (username != null && !username.isEmpty() && userInfoJson != null && !userInfoJson.isEmpty()) {
            try {
                ret.put("userInfo", JSObject.fromJSONObject(new JSONObject(userInfoJson)));
                ret.put("loggedIn", true);
                ret.put("username", username);
            } catch (JSONException e) {
                // userInfo 损坏 → 清除全部 auth 数据，视为未登录
                settingsDb.deleteKey("auth_cookies_json");
                settingsDb.deleteKey("auth_username");
                settingsDb.deleteKey("auth_user_info_json");
                ret.put("loggedIn", false);
            }
        } else {
            ret.put("loggedIn", false);
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void autoLogin(PluginCall call) {
        CredentialStore credentialStore = CredentialStore.getInstance(getContext());
        String username = credentialStore.getUsername();
        String password = credentialStore.getPassword();

        JSObject ret = new JSObject();
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            ret.put("success", false);
            ret.put("reason", "no_saved_credentials");
            call.resolve(ret);
            return;
        }

        call.setKeepAlive(true);
        apiService.login(username, password, new ApiCallback() {
            @Override
            public void onSuccess(JSONObject userInfo) {
                try {
                    SettingsStore db = SettingsStore.getInstance(getContext());
                    List<Cookie> cookies = sharedClient.getCookies();
                    db.putString("auth_cookies_json", cookiesToJson(cookies).toString());
                    db.putString("auth_username", userInfo.getString("username"));
                    db.putString("auth_user_info_json", userInfo.toString());
                    JSObject r = new JSObject();
                    r.put("success", true);
                    r.put("userInfo", JSObject.fromJSONObject(userInfo));
                    call.resolve(r);
                } catch (Exception e) {
                    call.reject(e.getMessage(), e);
                }
            }

            @Override
            public void onError(String message, Exception e) {
                // 仅在认证失败（如密码错误）时清除凭据，网络错误保留供下次重试
                if (e instanceof ResponseException) {
                    credentialStore.clear();
                }
                JSObject r = new JSObject();
                r.put("success", false);
                r.put("reason", "login_failed");
                call.resolve(r);
            }
        });
    }

    // ---- Cookie 序列化 ----

    private JSONArray cookiesToJson(List<Cookie> cookies) {
        JSONArray arr = new JSONArray();
        for (Cookie c : cookies) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("name", c.name());
                obj.put("value", c.value());
                obj.put("domain", c.domain());
                obj.put("path", c.path());
                obj.put("expiresAt", c.expiresAt());
                obj.put("secure", c.secure());
                obj.put("httpOnly", c.httpOnly());
                obj.put("persistent", c.persistent());
                arr.put(obj);
            } catch (JSONException ignored) {
            }
        }
        return arr;
    }

    private List<Cookie> parseCookiesFromJson(JSONArray arr) {
        List<Cookie> cookies = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject obj = arr.getJSONObject(i);
                long expiresAt = obj.optLong("expiresAt", 0);
                // 跳过已过期的 cookie（expiresAt 为 0 表示会话 cookie，保留）
                if (expiresAt > 0 && expiresAt < now) continue;

                Cookie.Builder builder = new Cookie.Builder()
                        .name(obj.getString("name"))
                        .value(obj.getString("value"))
                        .domain(obj.getString("domain"))
                        .path(obj.optString("path", "/"))
                        .expiresAt(expiresAt);
                if (obj.optBoolean("secure", false)) builder.secure();
                if (obj.optBoolean("httpOnly", false)) builder.httpOnly();
                if (obj.optBoolean("persistent", false)) {
                    cookies.add(builder.build());
                } else {
                    // non-persistent cookie: 用 hostOnly + 不设 expiresAt
                    cookies.add(builder.hostOnlyDomain(obj.getString("domain")).build());
                }
            } catch (Exception ignored) {
            }
        }
        return cookies;
    }
}
