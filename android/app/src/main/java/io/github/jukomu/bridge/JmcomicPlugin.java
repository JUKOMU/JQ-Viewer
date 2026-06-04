package io.github.jukomu.bridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Base64;
import androidx.documentfile.provider.DocumentFile;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import io.github.jukomu.data.*;
import io.github.jukomu.jmcomic.api.enums.Category;
import io.github.jukomu.jmcomic.api.enums.OrderBy;
import io.github.jukomu.jmcomic.api.enums.SearchMainTag;
import io.github.jukomu.jmcomic.api.enums.TimeOption;
import io.github.jukomu.jmcomic.api.exception.ResponseException;
import io.github.jukomu.jmcomic.core.JmComic;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import io.github.jukomu.jmcomic.core.config.JmConfiguration;
import io.github.jukomu.service.*;
import io.github.jukomu.service.PermissionState;
import okhttp3.Cookie;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static android.app.Activity.RESULT_OK;

/**
 * @author JUKOMU
 * @Description:
 * @Project: jq-viewer
 * @Date: 2026/4/22
 */
@CapacitorPlugin(name = "Jmcomic")
public class JmcomicPlugin extends Plugin implements ServiceListener {
    private static final String TAG = "JmcomicPlugin";

    private volatile JmApiClient sharedClient;
    private volatile ExecutorService imageExecutor;
    private volatile ExecutorService apiExecutor;
    private volatile ScheduledExecutorService apiTimeoutExecutor;
    private ExecutorService ocrExecutor;
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
    private static volatile String pendingLaunchRoute;
    private PluginCall pendingPermissionCall;
    private PluginCall pendingNotificationPermissionCall;
    private PermissionService permissionService;

    // ---- OCR 相关 ----
    private volatile PluginCall pendingOcrCall;
    private final Object ocrLock = new Object();

    // ---- 文件夹选择 ----
    private volatile PluginCall pendingFolderCall;
    private final Object folderLock = new Object();

    private static final int REQUEST_PICK_IMAGE = 1001;
    private static final int REQUEST_PICK_FOLDER = 1002;

    // ---- 服务 ----
    private ApiService apiService;
    private PreloadService preloadService;
    private SettingsService settingsService;
    private DownloadService downloadService;

    // ---- 下载相关 ----
    private DownloadStore downloadDb;

    // ---- 阅读器状态（供 MainActivity 音量键拦截查询） ----
    private volatile boolean readerActive = false;
    private volatile boolean readerVertical = true;

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
                if (state.apiLevel >= Build.VERSION_CODES.M && state.apiLevel < Build.VERSION_CODES.Q) {
                    // API 23-28：启动时主动请求 WRITE_EXTERNAL_STORAGE
                    Log.w("JmcomicPlugin",
                        "公开下载已开启但缺少 " + state.permissionType + "，启动时请求权限");
                    ActivityCompat.requestPermissions(getActivity(),
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        PermissionService.REQUEST_WRITE_STORAGE);
                } else {
                    // API 30+：不自动跳转系统设置，静默回退
                    Log.w("JmcomicPlugin",
                        "公开下载已开启但缺少 " + state.permissionType + "，回退到私有目录");
                }
                usePublicDir = false;
                settingsDb.putString("download_public", "false");
            }
        }
        FileStore.getInstance().init(ctx, downloadDb, usePublicDir);

        // 初始化历史记录数据库
        HistoryStore.getInstance(ctx);
        FavoriteStore.getInstance(ctx);
        PdfImportStore.getInstance(ctx);

        // 清理僵尸任务的部分下载文件（validateOnStartup 标记前）
        List<JSONObject> zombieTasks = downloadDb.getAllTasks();
        for (JSONObject t : zombieTasks) {
            String s = t.optString("status");
            if ("queued".equals(s) || "downloading".equals(s) || "paused".equals(s)) {
                FileStore.getInstance().deleteChapter(
                    t.optString("albumId"), t.optString("chapterId"));
            }
        }

        downloadDb.validateOnStartup(FileStore.getInstance().getBaseDir());

        // 读取缓存容量 → 计算自适应上限 → 初始化 ImageCache
        long userCacheMb = settingsDb.getLong("cache_capacity_mb", 256);
        long effectiveCacheMb = computeAdaptiveCacheCapacity(userCacheMb);
        ImageCache.getInstance().setCapacity(effectiveCacheMb * 1024 * 1024);

        // 读取并发数设置 → 初始化 imageExecutor 和 downloadConcurrency
        imageConcurrency = settingsDb.getInt("preload_concurrency", 6);
        downloadConcurrency = settingsDb.getInt("download_concurrency", 6);
        imageExecutor = Executors.newFixedThreadPool(imageConcurrency);
        apiExecutor = Executors.newFixedThreadPool(API_EXECUTOR_SIZE);
        apiTimeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        ocrExecutor = Executors.newSingleThreadExecutor();

        // 注册网络变化监听，网络切换时主动触发域名探活
        registerNetworkCallback();

        // 预热 client，避免首次 API 调用时的冷启动延迟
        getClient();

        // 初始化服务
        this.apiService = new ApiService(sharedClient, apiExecutor, apiTimeoutExecutor);
        this.settingsService = new SettingsService(settingsDb, downloadDb,
            FileStore.getInstance(), permissionService, ctx, this);
        this.preloadService = new PreloadService(ImageCache.getInstance(),
            FileStore.getInstance(), settingsDb, sharedClient, imageExecutor, this, ctx);
        this.downloadService = new DownloadService(downloadDb,
            FileStore.getInstance(), sharedClient, imageExecutor, this, ctx);

        // 清除登录态缓存，强制通过凭据重新登录
        clearAuthState(settingsDb);
    }

    private void clearAuthState(SettingsStore settingsDb) {
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
            } catch (IllegalArgumentException e) {
                Log.d(TAG, "取消注册网络回调失败", e);
            }
        }
        shutdownGracefully(domainProbeExecutor);
        shutdownGracefully(ocrExecutor);

        shutdownGracefully(apiTimeoutExecutor);
        shutdownGracefully(apiExecutor, 10);
        shutdownGracefully(imageExecutor, 2);
    }

    private void shutdownGracefully(ExecutorService executor) {
        shutdownGracefully(executor, 2);
    }

    private void shutdownGracefully(ExecutorService executor, int timeoutSeconds) {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
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
            Log.w("JmcomicPlugin", "ConnectivityManager 不可用，跳过网络监听");
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
        Log.i("JmcomicPlugin", "网络变化监听已注册");
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

                    Log.i("JmcomicPlugin", "重新探活域名...");
                    client.reprobeDomains();
                    Log.i("JmcomicPlugin", "域名重新探活完成");

                    try {
                        Map<String, Integer> states = client.getDomainStates();

                        JSONObject result = new JSONObject();
                        result.put("phase", "result");
                        int alive = 0;
                        JSONArray domains = new JSONArray();
                        for (Map.Entry<String, Integer> e : states.entrySet()) {
                            JSONObject d = new JSONObject();
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
                    Log.w("JmcomicPlugin", "域名重新探活失败", e);
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
            Map<String, Integer> states = client.getDomainStates();

            JSObject result = new JSObject();
            int alive = 0;
            JSONArray domains = new JSONArray();
            for (Map.Entry<String, Integer> e : states.entrySet()) {
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
            Log.e("JmcomicPlugin", "获取域名状态失败", e);
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
            Map<String, Integer> latency = client.getDomainLatency();

            JSObject ret = new JSObject();
            JSONArray results = new JSONArray();
            for (Map.Entry<String, Integer> e : latency.entrySet()) {
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
            Log.e("JmcomicPlugin", "测速失败", e);
            call.reject("测速失败，请稍后重试");
        }
    }

    @PluginMethod
    public void getInitStatus(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("complete", sharedClient != null);
        call.resolve(ret);
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
        if (requestCode == PermissionService.REQUEST_WRITE_STORAGE) {
            if (pendingPermissionCall != null) {
                PluginCall call = pendingPermissionCall;
                pendingPermissionCall = null;
                PermissionState state = permissionService.interpretResult(grantResults);
                JSObject ret = new JSObject();
                ret.put("apiLevel", state.apiLevel);
                ret.put("permissionType", state.permissionType);
                ret.put("granted", state.granted);
                call.resolve(ret);
            } else {
                boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
                Log.i("JmcomicPlugin", "启动时存储权限请求结果: granted=" + granted);
            }
        } else if (requestCode == PermissionService.REQUEST_POST_NOTIFICATIONS) {
            boolean granted = permissionService.interpretNotificationResult(grantResults);
            if (pendingNotificationPermissionCall != null) {
                PluginCall call = pendingNotificationPermissionCall;
                pendingNotificationPermissionCall = null;
                JSObject ret = new JSObject();
                ret.put("granted", granted);
                call.resolve(ret);
            } else {
                Log.i("JmcomicPlugin", "通知权限请求结果: granted=" + granted);
            }
        }
    }

    /**
     * 供 MainActivity.onActivityResult 调用，处理图片选择结果并执行 OCR。
     */
    public void handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_PICK_FOLDER) {
            handleFolderResult(resultCode, data);
            return;
        }
        if (requestCode != REQUEST_PICK_IMAGE) return;

        PluginCall call;
        synchronized (ocrLock) {
            if (pendingOcrCall == null) return;
            call = pendingOcrCall;
            pendingOcrCall = null;
        }

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            JSObject ret = new JSObject();
            ret.put("text", "");
            ret.put("error", "");
            call.resolve(ret);
            return;
        }

        ocrExecutor.execute(() -> {
            try {
                InputImage inputImage = InputImage.fromFilePath(
                    getContext(), data.getData());

                TextRecognizer recognizer = TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build());

                CountDownLatch latch =
                    new CountDownLatch(1);
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

                boolean completed = latch.await(30, TimeUnit.SECONDS);
                recognizer.close();

                JSObject ret = new JSObject();
                if (!completed) {
                    ret.put("text", "");
                    ret.put("error", "识别超时，请重试");
                } else if (errorHolder[0] != null) {
                    ret.put("text", "");
                    Log.e("JmcomicPlugin", "OCR识别失败", errorHolder[0]);
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
                Log.e("JmcomicPlugin", "OCR调用失败", e);
                ret.put("error", "识别失败，请重试");
                call.resolve(ret);
            }
        });
    }

    public static JmcomicPlugin getInstance() {
        return instance;
    }

    public static void setPendingLaunchRoute(String route) {
        pendingLaunchRoute = route;
        JmcomicPlugin plugin = instance;
        if (plugin != null) {
            plugin.notifyLaunchRoute(route);
        }
    }

    public static void handleDownloadNotificationAction(String action, String taskId) {
        JmcomicPlugin plugin = instance;
        if (plugin == null || plugin.downloadService == null) {
            Log.w(TAG, "下载通知操作到达时服务未就绪: " + action + ", " + taskId);
            return;
        }
        try {
            if (DownloadNotificationActionReceiver.ACTION_PAUSE.equals(action)) {
                plugin.downloadService.pauseDownload(taskId);
            } else if (DownloadNotificationActionReceiver.ACTION_RESUME.equals(action)) {
                plugin.downloadService.resumeDownload(taskId);
            } else if (DownloadNotificationActionReceiver.ACTION_CANCEL.equals(action)) {
                plugin.downloadService.cancelDownload(taskId);
            }
        } catch (Exception e) {
            Log.w(TAG, "处理下载通知操作失败: " + action + ", " + taskId, e);
        }
    }

    // ---- 自适应缓存容量 ----

    private long computeAdaptiveCacheCapacity(long userMb) {
        ActivityManager am = (ActivityManager) getContext()
            .getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return userMb;

        int heapLimitMb = Math.max(am.getMemoryClass(), am.getLargeMemoryClass());

        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(memInfo);
        long availMemMb = memInfo.availMem / (1024 * 1024);

        long systemLimit = (long)(availMemMb * 0.35);
        long heapLimit = (long)(heapLimitMb * 0.80);

        long effective = userMb;
        if (systemLimit < effective) effective = systemLimit;
        if (heapLimit < effective) effective = heapLimit;
        if (effective < 16) effective = 16;

        Log.i(TAG, "自适应缓存: 用户=" + userMb + "MB, 可用内存限制="
            + systemLimit + "MB, 堆限制=" + heapLimit + "MB, 生效=" + effective + "MB");
        return effective;
    }

    /**
     * 由 MainActivity.onTrimMemory/onLowMemory 调用，根据系统内存压力缩减缓存。
     */
    public void onMemoryPressure(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            ImageCache.getInstance().clear();
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            ImageCache.getInstance().trimToFraction(0.2);
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            ImageCache.getInstance().trimToFraction(0.3);
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            ImageCache.getInstance().trimToFraction(0.5);
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            ImageCache.getInstance().trimToFraction(0.25);
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            ImageCache.getInstance().trimToFraction(0.5);
        }
        // RUNNING_MODERATE (5): 不干预，信任自适应淘汰
    }

    @PluginMethod
    public void pickImageAndOcr(PluginCall call) {
        synchronized (ocrLock) {
            if (pendingOcrCall != null) {
                call.reject("另一个 OCR 请求正在进行中");
                return;
            }
            pendingOcrCall = call;
        }
        Intent intent = new Intent(Intent.ACTION_PICK,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        try {
            getActivity().startActivityForResult(intent, REQUEST_PICK_IMAGE);
        } catch (Exception e) {
            synchronized (ocrLock) {
                pendingOcrCall = null;
            }
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void pickFolder(PluginCall call) {
        synchronized (folderLock) {
            if (pendingFolderCall != null) {
                call.reject("另一个文件夹选择请求正在进行中");
                return;
            }
            pendingFolderCall = call;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            getActivity().startActivityForResult(intent, REQUEST_PICK_FOLDER);
        } catch (Exception e) {
            synchronized (folderLock) {
                pendingFolderCall = null;
            }
            call.reject(e.getMessage(), e);
        }
    }

    private void handleFolderResult(int resultCode, Intent data) {
        PluginCall call;
        synchronized (folderLock) {
            if (pendingFolderCall == null) return;
            call = pendingFolderCall;
            pendingFolderCall = null;
        }

        JSObject ret = new JSObject();
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            ret.put("path", "");
            ret.put("cancelled", true);
            call.resolve(ret);
            return;
        }

        android.net.Uri treeUri = data.getData();
        // 持久化权限，以便后续写入
        try {
            getContext().getContentResolver().takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            Log.w(TAG, "takePersistableUriPermission failed", e);
        }

        String path = treeUriToPath(treeUri);
        ret.put("path", path != null ? path : "");
        ret.put("treeUri", treeUri.toString());
        ret.put("cancelled", false);
        call.resolve(ret);
    }

    /**
     * 将 content:// 树 URI 转为可读的文件系统路径。
     * 例如: content://com.android.externalstorage.documents/tree/primary%3ADownload
     *    → /storage/emulated/0/Download
     */
    private String treeUriToPath(android.net.Uri treeUri) {
        if (treeUri == null) return null;
        String docId;
        try {
            docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception e) {
            // 尝试从 URI path 中提取
            String uriPath = treeUri.getPath();
            if (uriPath == null) return null;
            String[] parts = uriPath.split("/tree/", 2);
            if (parts.length < 2) return null;
            docId = parts[1];
        }
        if (docId == null) return null;

        // docId 格式: "primary:Download/JQ-Viewer" 或 "home:Download"
        String[] split = docId.split(":", 2);
        if (split.length < 2) return null;
        String volume = split[0];
        String subPath = split[1];

        // 去掉开头的 / 如果存在
        if (subPath.startsWith("/")) {
            subPath = subPath.substring(1);
        }

        if ("primary".equals(volume)) {
            java.io.File external = android.os.Environment.getExternalStorageDirectory();
            return external.getAbsolutePath() + "/" + subPath;
        }
        // 非 primary 卷: /storage/{volume}/{subPath}
        return "/storage/" + volume + "/" + subPath;
    }

    @PluginMethod
    public void checkNotificationPermission(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", permissionService.checkNotificationPermission(getContext()));
        call.resolve(ret);
    }

    @PluginMethod
    public void requestNotificationPermission(PluginCall call) {
        if (permissionService.checkNotificationPermission(getContext())) {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            call.resolve(ret);
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            JSObject ret = new JSObject();
            ret.put("granted", false);
            call.resolve(ret);
            return;
        }

        if (pendingNotificationPermissionCall != null) {
            call.reject("通知权限请求正在进行中，请先完成上一个请求。");
            return;
        }

        pendingNotificationPermissionCall = call;
        call.setKeepAlive(true);
        ActivityCompat.requestPermissions(getActivity(),
            new String[]{Manifest.permission.POST_NOTIFICATIONS},
            PermissionService.REQUEST_POST_NOTIFICATIONS);
    }

    @PluginMethod
    public void checkFilesExist(PluginCall call) {
        try {
            JSArray pathsJson = call.getArray("paths");
            if (pathsJson == null) {
                call.reject("paths is required");
                return;
            }
            android.content.ContentResolver resolver = getContext().getContentResolver();
            java.io.File externalRoot = android.os.Environment.getExternalStorageDirectory();
            JSArray existing = new JSArray();
            for (int i = 0; i < pathsJson.length(); i++) {
                String p = pathsJson.getString(i);
                if (p == null) continue;
                boolean exists;
                if (p.startsWith("content://")) {
                    exists = checkContentUriExists(resolver, p);
                } else if (p.startsWith("/")) {
                    exists = new java.io.File(p).exists();
                } else {
                    exists = new java.io.File(externalRoot, p).exists();
                }
                if (exists) {
                    existing.put(p);
                }
            }
            JSObject ret = new JSObject();
            ret.put("existing", existing);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    private boolean checkContentUriExists(android.content.ContentResolver resolver, String uriStr) {
        Uri uri = Uri.parse(uriStr);
        android.database.Cursor cursor = null;
        try {
            cursor = resolver.query(uri, null, null, null, null);
            return cursor != null && cursor.getCount() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    @PluginMethod
    public void getExternalStoragePath(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("path", android.os.Environment.getExternalStorageDirectory().getAbsolutePath());
        call.resolve(ret);
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
            int folderId = Integer.parseInt(q.getString("folderId", "0"));
            apiService.getFavorites(
                folderId,
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
            boolean replacePending = call.getBoolean("replacePending", false);
            JSArray imagesArray = call.getArray("images");
            JSONArray jsonImages = new JSONArray();
            if (imagesArray != null) {
                for (int i = 0; i < imagesArray.length(); i++) {
                    try {
                        jsonImages.put(imagesArray.getJSONObject(i));
                    } catch (Exception e) {
                        Log.d(TAG, "跳过无效图片条目", e);
                    }
                }
            }
            JSONObject result = preloadService.preloadImages(photoId, type, jsonImages, replacePending);
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
        try {
            Integer mb = call.getInt("mb");
            if (mb == null || mb <= 0) {
                call.reject("mb must be a positive number");
                return;
            }
            preloadService.setCacheCapacity(mb);
            JSObject ret = new JSObject();
            ret.put("success", true);
            ret.put("capacityMb", mb);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
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

    // ========== 阅读器设置 ==========

    @PluginMethod
    public void setReaderDisplayMode(PluginCall call) {
        try {
            String mode = call.getString("mode");
            if (mode == null || (!"vertical".equals(mode) && !"horizontal".equals(mode))) {
                call.reject("mode must be vertical or horizontal");
                return;
            }
            settingsService.setReaderDisplayMode(mode);
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void setReaderScreenOrientation(PluginCall call) {
        try {
            String orientation = call.getString("orientation");
            if (orientation == null) {
                call.reject("orientation is required");
                return;
            }
            settingsService.setReaderScreenOrientation(orientation);
            applyScreenOrientation(orientation);
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void setReaderBrightness(PluginCall call) {
        try {
            Float brightness = call.getFloat("brightness");
            if (brightness == null) {
                call.reject("brightness is required");
                return;
            }
            settingsService.setReaderBrightness(brightness);
            applyBrightness(brightness);
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void setReaderKeepScreenOn(PluginCall call) {
        try {
            Boolean enabled = call.getBoolean("enabled");
            if (enabled == null) {
                call.reject("enabled is required");
                return;
            }
            settingsService.setReaderKeepScreenOn(enabled);
            applyKeepScreenOn(enabled);
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void setReaderVolumeNavigation(PluginCall call) {
        try {
            Boolean enabled = call.getBoolean("enabled");
            if (enabled == null) {
                call.reject("enabled is required");
                return;
            }
            settingsService.setReaderVolumeNavigation(enabled);
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void setReaderFullscreen(PluginCall call) {
        try {
            Boolean enabled = call.getBoolean("enabled");
            if (enabled == null) {
                call.reject("enabled is required");
                return;
            }
            applyFullscreen(enabled);
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void setReaderState(PluginCall call) {
        Boolean isActive = call.getBoolean("isActive");
        Boolean isVertical = call.getBoolean("isVertical");
        if (isActive == null || isVertical == null) {
            call.reject("isActive and isVertical are required");
            return;
        }
        readerActive = isActive;
        readerVertical = isVertical;
        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    // ---- 应用设置到 Activity（需在主线程调用） ----

    private void applyBrightness(float brightness) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            android.view.WindowManager.LayoutParams lp = getActivity().getWindow().getAttributes();
            lp.screenBrightness = brightness;
            getActivity().getWindow().setAttributes(lp);
        });
    }

    private void applyKeepScreenOn(boolean enabled) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (enabled) {
                getActivity().getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getActivity().getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }

    private void applyFullscreen(boolean enabled) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.Window window = getActivity().getWindow();
                android.view.WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    if (enabled) {
                        controller.hide(android.view.WindowInsets.Type.statusBars()
                            | android.view.WindowInsets.Type.navigationBars());
                        controller.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    } else {
                        controller.show(android.view.WindowInsets.Type.statusBars()
                            | android.view.WindowInsets.Type.navigationBars());
                    }
                }
            } else {
                android.view.View decorView = getActivity().getWindow().getDecorView();
                if (enabled) {
                    decorView.setSystemUiVisibility(
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                } else {
                    decorView.setSystemUiVisibility(0);
                }
            }
        });
    }

    private void applyScreenOrientation(String orientation) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            switch (orientation) {
                case "portrait":
                    getActivity().setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    break;
                case "landscape":
                    getActivity().setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                    break;
                default:
                    getActivity().setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    break;
            }
        });
    }

    // ---- 音量键通知（供 MainActivity 调用） ----

    public boolean isReaderActive() {
        return readerActive;
    }

    public boolean isVolumeNavigationEnabled() {
        return settingsService.getReaderVolumeNavigation();
    }

    public boolean isReaderVertical() {
        return readerVertical;
    }

    public void notifyVolumeKey(String direction) {
        JSObject data = new JSObject();
        data.put("direction", direction);
        notifyListeners("volumeKey", data);
    }

    public void notifyLaunchRoute(String route) {
        JSObject data = new JSObject();
        data.put("route", route);
        notifyListeners("launchRoute", data);
    }

    @PluginMethod
    public void consumeLaunchRoute(PluginCall call) {
        JSObject ret = new JSObject();
        String route = pendingLaunchRoute;
        pendingLaunchRoute = null;
        if (route != null && !route.isEmpty()) {
            ret.put("route", route);
        }
        call.resolve(ret);
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
                } catch (Exception e) {
                    Log.d(TAG, "跳过无效下载任务条目", e);
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

    @PluginMethod
    public void deleteBrowseItem(PluginCall call) {
        try {
            HistoryStore.getInstance(getContext()).deleteBrowseItem(
                call.getInt("id", 0)
            );
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
                call.getString("text", ""),
                call.getString("mode", "single-mode")
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

    @PluginMethod
    public void deleteParseItem(PluginCall call) {
        try {
            HistoryStore.getInstance(getContext()).deleteParseItem(
                call.getInt("id", 0)
            );
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
        if (d.downloadedBytes > 0) data.put("downloadedBytes", d.downloadedBytes);
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

        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            call.reject("自动登录失败：无保存的凭据");
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
                call.reject("自动登录失败：凭据无效或已过期");
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
            } catch (JSONException e) {
                Log.d(TAG, "跳过无效cookie条目", e);
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
            } catch (Exception e) {
                Log.d(TAG, "跳过损坏的cookie条目", e);
            }
        }
        return cookies;
    }

    // ========== PDF 导出 ==========

    // ========== PDF 导入 ==========

    @PluginMethod
    public void scanPdfFiles(PluginCall call) {
        String treeUriStr = call.getString("treeUri");
        if (treeUriStr != null && !treeUriStr.isEmpty()) {
            scanPdfFilesViaSaf(call, Uri.parse(treeUriStr));
        } else {
            scanPdfFilesViaFile(call);
        }
    }

    private void scanPdfFilesViaSaf(PluginCall call, Uri treeUri) {
        DocumentFile root = DocumentFile.fromTreeUri(getContext(), treeUri);
        if (root == null || !root.isDirectory()) {
            // SAF 失败时回退到 java.io.File
            scanPdfFilesViaFile(call);
            return;
        }
        DocumentFile[] children = root.listFiles();
        JSArray arr = new JSArray();
        if (children != null) {
            for (DocumentFile child : children) {
                if (child.isFile() && child.getName() != null
                    && child.getName().toLowerCase().endsWith(".pdf")) {
                    JSObject obj = new JSObject();
                    obj.put("fileName", child.getName());
                    obj.put("filePath", child.getUri().toString());
                    arr.put(obj);
                }
            }
        }
        JSObject ret = new JSObject();
        ret.put("files", arr);
        call.resolve(ret);
    }

    private void scanPdfFilesViaFile(PluginCall call) {
        String path = call.getString("path");
        if (path == null || path.isEmpty()) {
            call.reject("path is required");
            return;
        }
        File dir = new File(path);
        if (!dir.isDirectory()) {
            call.reject("Not a directory: " + path);
            return;
        }
        File[] pdfFiles = dir.listFiles((d, name) ->
            name.toLowerCase().endsWith(".pdf"));
        JSArray arr = new JSArray();
        if (pdfFiles != null) {
            for (File f : pdfFiles) {
                JSObject obj = new JSObject();
                obj.put("fileName", f.getName());
                obj.put("filePath", f.getAbsolutePath());
                arr.put(obj);
            }
        }
        JSObject ret = new JSObject();
        ret.put("files", arr);
        call.resolve(ret);
    }

    @PluginMethod
    public void importPdfs(PluginCall call) {
        JSArray items = call.getArray("items");
        if (items == null || items.length() == 0) {
            call.reject("items is required and must not be empty");
            return;
        }
        PdfImportStore store = PdfImportStore.getInstance(getContext());
        int imported = 0;
        int skipped = 0;
        int duplicateCount = 0;
        int errorCount = 0;
        for (int i = 0; i < items.length(); i++) {
            try {
                JSONObject item = items.getJSONObject(i);
                long id = store.insertPdf(
                    item.getString("filePath"),
                    item.getString("fileName"),
                    item.getString("albumId"),
                    item.optString("albumTitle", ""),
                    item.optString("coverUrl", ""),
                    item.optString("authors", ""),
                    item.optString("chapterId", ""),
                    item.optString("chapterTitle", ""),
                    item.optInt("chapterSortOrder", 0),
                    item.has("isSingleEpisode")
                        ? (item.optBoolean("isSingleEpisode", false) ? 1 : 0)
                        : -1,
                    System.currentTimeMillis(),
                    item.optString("folderId", null)
                );
                if (id != -1) imported++;
                else { skipped++; duplicateCount++; }
            } catch (Exception e) {
                skipped++;
                errorCount++;
                Log.w(TAG, "跳过无效的 PDF 导入项", e);
            }
        }
        JSObject ret = new JSObject();
        ret.put("imported", imported);
        ret.put("skipped", skipped);
        ret.put("duplicateCount", duplicateCount);
        ret.put("errorCount", errorCount);
        call.resolve(ret);
    }

    @PluginMethod
    public void getImportedPdfs(PluginCall call) {
        PdfImportStore store = PdfImportStore.getInstance(getContext());
        JSONArray pdfs = store.getAllPdfs();
        JSObject ret = new JSObject();
        ret.put("pdfs", pdfs);
        call.resolve(ret);
    }

    @PluginMethod
    public void updateLocalEpisodeType(PluginCall call) {
        String albumId = call.getString("albumId");
        Boolean isSingleEpisode = call.getBoolean("isSingleEpisode");
        if (albumId == null || albumId.isEmpty() || isSingleEpisode == null) {
            call.reject("albumId and isSingleEpisode are required");
            return;
        }
        int updatedDownloads = downloadDb.updateAlbumEpisodeType(albumId, isSingleEpisode);
        int updatedPdfs = PdfImportStore.getInstance(getContext())
            .updateAlbumEpisodeType(albumId, isSingleEpisode);
        JSObject ret = new JSObject();
        ret.put("success", true);
        ret.put("updatedDownloads", updatedDownloads);
        ret.put("updatedPdfs", updatedPdfs);
        call.resolve(ret);
    }

    @PluginMethod
    public void deleteImportedPdf(PluginCall call) {
        int id = call.getInt("id", -1);
        if (id < 0) {
            call.reject("id is required");
            return;
        }
        boolean ok = PdfImportStore.getInstance(getContext()).deletePdf(id);
        JSObject ret = new JSObject();
        ret.put("success", ok);
        call.resolve(ret);
    }

    @PluginMethod
    public void openPdf(PluginCall call) {
        String filePath = call.getString("filePath");
        if (filePath == null || filePath.isEmpty()) {
            call.reject("filePath is required");
            return;
        }
        try {
            Uri uri;
            if (filePath.startsWith("content://")) {
                uri = Uri.parse(filePath);
            } else {
                File file = new File(filePath);
                if (!file.exists()) {
                    call.reject("File not found: " + filePath);
                    return;
                }
                uri = FileProvider.getUriForFile(
                    getContext(),
                    getContext().getPackageName() + ".fileprovider",
                    file);
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("无法打开 PDF: " + e.getMessage());
        }
    }

    @PluginMethod
    public void getPdfInfo(PluginCall call) {
        String filePath = call.getString("filePath");
        if (filePath == null || filePath.isEmpty()) {
            call.reject("filePath is required");
            return;
        }

        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        try {
            pfd = openPdfDescriptor(filePath);
            renderer = new PdfRenderer(pfd);
            JSObject ret = new JSObject();
            ret.put("pageCount", renderer.getPageCount());
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("PDF 信息读取失败: " + e.getMessage(), e);
        } finally {
            if (renderer != null) {
                try { renderer.close(); } catch (Exception ignored) {}
            }
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) {}
            }
        }
    }

    @PluginMethod
    public void renderPdfPage(PluginCall call) {
        String filePath = call.getString("filePath");
        int pageNumber = call.getInt("page", 1);
        int targetWidth = call.getInt("targetWidth", 1080);
        if (filePath == null || filePath.isEmpty()) {
            call.reject("filePath is required");
            return;
        }

        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        PdfRenderer.Page page = null;
        try {
            pfd = openPdfDescriptor(filePath);
            renderer = new PdfRenderer(pfd);
            int pageCount = renderer.getPageCount();
            if (pageNumber < 1 || pageNumber > pageCount) {
                call.reject("page out of range");
                return;
            }

            page = renderer.openPage(pageNumber - 1);
            int width = Math.max(360, Math.min(targetWidth, 2400));
            int height = Math.max(1, Math.round(width * (page.getHeight() / (float) page.getWidth())));
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            bitmap.recycle();

            String encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            JSObject ret = new JSObject();
            ret.put("imageUrl", "data:image/png;base64," + encoded);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("PDF 页面渲染失败: " + e.getMessage(), e);
        } finally {
            if (page != null) {
                try { page.close(); } catch (Exception ignored) {}
            }
            if (renderer != null) {
                try { renderer.close(); } catch (Exception ignored) {}
            }
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) {}
            }
        }
    }

    private ParcelFileDescriptor openPdfDescriptor(String filePath) throws Exception {
        if (filePath.startsWith("content://")) {
            ParcelFileDescriptor pfd = getContext().getContentResolver()
                .openFileDescriptor(Uri.parse(filePath), "r");
            if (pfd == null) {
                throw new java.io.FileNotFoundException("content uri not readable");
            }
            return pfd;
        }

        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            throw new java.io.FileNotFoundException(filePath);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @PluginMethod
    public void exportPdfBatch(PluginCall call) {
        try {
            JSArray tasksJson = call.getArray("tasks");
            if (tasksJson == null || tasksJson.length() == 0) {
                call.reject("tasks is required and must not be empty");
                return;
            }

            List<PdfExportService.ExportJob> jobs = new ArrayList<>();
            for (int i = 0; i < tasksJson.length(); i++) {
                JSONObject t = tasksJson.getJSONObject(i);
                PdfExportService.ExportJob job = new PdfExportService.ExportJob();
                job.albumId = t.getString("albumId");
                job.chapterId = t.getString("chapterId");
                job.chapterTitle = t.optString("chapterTitle", job.chapterId);
                job.savePath = t.getString("savePath");
                job.useOriginal = t.optBoolean("useOriginal", true);
                double cr = t.optDouble("compressionRatio", 1.0);
                job.compressionRatio = (float) Math.max(0.1, Math.min(1.0, cr));
                job.splitPages = t.optInt("splitPages", 0);
                jobs.add(job);
            }

            PdfExportService pdfService = PdfExportService.getInstance(getContext());
            pdfService.submitExport(jobs);

            JSObject ret = new JSObject();
            ret.put("accepted", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }
}
