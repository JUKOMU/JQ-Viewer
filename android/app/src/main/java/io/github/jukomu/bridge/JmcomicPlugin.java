package io.github.jukomu.bridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import io.github.jukomu.bridge.handler.*;
import io.github.jukomu.feature.cache.CacheCapacityPolicy;
import io.github.jukomu.feature.cache.ImageCache;
import io.github.jukomu.feature.catalog.ApiService;
import io.github.jukomu.feature.download.DownloadCommandPort;
import io.github.jukomu.feature.download.DownloadCommandRouter;
import io.github.jukomu.feature.download.DownloadService;
import io.github.jukomu.feature.download.data.DownloadStore;
import io.github.jukomu.feature.download.storage.FileStore;
import io.github.jukomu.feature.favorite.data.FavoriteStore;
import io.github.jukomu.feature.history.data.HistoryStore;
import io.github.jukomu.feature.pdf.data.PdfStore;
import io.github.jukomu.feature.pdf.export.PdfExportCommandPort;
import io.github.jukomu.feature.pdf.export.PdfExportCommandRouter;
import io.github.jukomu.feature.pdf.export.PdfExportEventSink;
import io.github.jukomu.feature.pdf.export.PdfExportService;
import io.github.jukomu.feature.preload.PreloadService;
import io.github.jukomu.feature.settings.SettingsService;
import io.github.jukomu.feature.settings.relocation.DownloadRelocationService;
import io.github.jukomu.feature.update.UpdateService;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import io.github.jukomu.platform.permission.PermissionService;
import io.github.jukomu.platform.permission.PermissionState;
import io.github.jukomu.platform.persistence.SettingsStore;
import io.github.jukomu.runtime.JmcomicRuntime;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * @author JUKOMU
 * @Description:
 * @Project: jq-viewer
 * @Date: 2026/4/22
 */
@CapacitorPlugin(name = "Jmcomic")
public class JmcomicPlugin extends Plugin {
    private static final String TAG = "JmcomicPlugin";

    private volatile JmApiClient sharedClient;
    private ExecutorService pdfCommandExecutor;
    private int imageConcurrency = 6;
    private int downloadConcurrency = 6;
    private final CacheCapacityPolicy cacheCapacityPolicy = new CacheCapacityPolicy();
    private JmcomicRuntime runtime;

    // ---- 权限相关 ----
    private static JmcomicPlugin instance;
    private PermissionService permissionService;

    // ---- 服务 ----
    private ApiSession apiSession;
    private PreloadService preloadService;
    private SettingsService settingsService;
    private DownloadService downloadService;
    private ApiPluginHandler apiHandler;
    private AuthPluginHandler authHandler;
    private CachePluginHandler cacheHandler;
    private DownloadCommandPort downloadCommandPort;
    private DownloadPluginHandler downloadHandler;
    private FavoritePluginHandler favoriteHandler;
    private FeatureEventAdapter featureEventAdapter;
    private HistoryPluginHandler historyHandler;
    private ReaderPluginHandler readerHandler;
    private SettingsPluginHandler settingsHandler;
    private SystemPluginHandler systemHandler;
    private PdfPluginHandler pdfHandler;
    private PdfExportEventSink pdfEventSink;
    private PdfExportCommandPort pdfExportCommandPort;
    private UpdateService updateService;
    private Consumer<UpdateService.Snapshot> updateProgressSink;
    private UpdatePluginHandler updateHandler;

    // ---- 下载相关 ----
    private DownloadStore downloadDb;

    @Override
    public void load() {
        this.featureEventAdapter = new FeatureEventAdapter(
            (eventName, event) -> notifyListeners(eventName, event));
        this.readerHandler = new ReaderPluginHandler(
            this::getActivity,
            () -> getBridge() == null ? null : getBridge().getWebView(),
            () -> settingsService,
            event -> notifyListeners("volumeKey", event),
            event -> notifyListeners("launchRoute", event));
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
        HistoryStore historyStore = HistoryStore.getInstance(ctx);
        historyHandler = new HistoryPluginHandler(historyStore);
        FavoriteStore favoriteStore = FavoriteStore.getInstance(ctx);
        favoriteHandler = new FavoritePluginHandler(favoriteStore);
        PdfStore.getInstance(ctx);
        PdfExportService pdfExportService = PdfExportService.getInstance(ctx);
        this.pdfEventSink = snapshot -> {
            if (snapshot == null) return;
            try {
                notifyListeners("pdfExportProgress", JSObject.fromJSONObject(snapshot));
            } catch (Exception error) {
                Log.w(TAG, "发布 PDF 导出进度失败", error);
            }
        };
        pdfExportService.attachEventSink(pdfEventSink);
        pdfExportService.reconcileOnStartup();

        boolean runtimeExists = JmcomicRuntime.exists();
        if (!runtimeExists) {
            // 仅进程首次初始化时清理上次进程遗留的下载任务。
            List<JSONObject> zombieTasks = downloadDb.getAllTasks();
            for (JSONObject t : zombieTasks) {
                String s = t.optString("status");
                if ("queued".equals(s) || "downloading".equals(s)
                    || "paused".equals(s) || "verifying".equals(s)) {
                    String albumId = t.optString("albumId");
                    String chapterId = t.optString("chapterId");
                    try {
                        FileStore.validateChapterIds(albumId, chapterId);
                    } catch (IllegalArgumentException error) {
                        Log.w(TAG, "跳过章节标识无效的遗留任务: "
                            + t.optString("taskId"), error);
                        continue;
                    }
                    FileStore.getInstance().deleteChapter(albumId, chapterId);
                }
            }

            downloadDb.validateOnStartup();
        }

        // 读取用户期望容量，通过统一策略初始化实际缓存上限
        long userCacheMb = settingsDb.getLong("cache_capacity_mb",
            CacheCapacityPolicy.DEFAULT_REQUESTED_MB);
        applyCachePolicy(userCacheMb, CacheCapacityPolicy.PressureLevel.NORMAL);
        logCachePolicy("initialize");

        // 读取并发数设置并初始化进程级运行时
        imageConcurrency = SettingsService.normalizeConcurrency(
            settingsDb.getInt("preload_concurrency", SettingsService.DEFAULT_CONCURRENCY));
        downloadConcurrency = SettingsService.normalizeConcurrency(
            settingsDb.getInt("download_concurrency", SettingsService.DEFAULT_CONCURRENCY));
        pdfCommandExecutor = createPdfCommandExecutor();
        pdfHandler = new PdfPluginHandler(ctx, downloadDb, pdfCommandExecutor);
        pdfExportCommandPort = exportId ->
            PdfExportService.getInstance(ctx).cancelExport(exportId);
        PdfExportCommandRouter.getInstance().attach(pdfExportCommandPort);

        runtime = JmcomicRuntime.getOrCreate(ctx, settingsDb, downloadDb,
            FileStore.getInstance(), ImageCache.getInstance(), cacheCapacityPolicy,
            imageConcurrency, downloadConcurrency);
        runtime.attachEventSinks(
            featureEventAdapter, featureEventAdapter, featureEventAdapter);
        sharedClient = runtime.getClient();

        // 注册网络变化监听，网络切换时主动触发域名探活
        this.systemHandler = new SystemPluginHandler(
            ctx,
            this::getActivity,
            permissionService,
            () -> sharedClient,
            event -> notifyListeners("networkProbe", event),
            (permission, requestCode) -> ActivityCompat.requestPermissions(
                getActivity(), new String[]{permission}, requestCode));
        systemHandler.registerNetworkCallback();

        // 初始化服务
        this.apiSession = new ApiSession(sharedClient);
        ApiService apiService = apiSession.getApiService();
        this.apiHandler = new ApiPluginHandler(apiService);
        this.authHandler = new AuthPluginHandler(ctx, apiService, sharedClient::getCookies);
        DownloadRelocationService relocationService = new DownloadRelocationService(
            ctx, settingsDb, FileStore.getInstance());
        this.settingsService = new SettingsService(settingsDb, downloadDb,
            relocationService, permissionService, ctx,
            runtime.getRelocationEventSink(), ImageCache.getInstance());
        this.preloadService = runtime.getPreloadService();
        this.cacheHandler = new CachePluginHandler(preloadService);
        this.settingsHandler = new SettingsPluginHandler(settingsService);
        this.downloadService = runtime.getDownloadService();
        this.updateService = runtime.getUpdateService();
        this.updateProgressSink = snapshot ->
            notifyListeners("updateProgress", snapshot.toJson());
        this.updateService.setProgressSink(updateProgressSink);
        this.updateHandler = new UpdatePluginHandler(updateService, this::getActivity);
        this.downloadCommandPort = new DownloadCommandAdapter(downloadService);
        DownloadCommandRouter.getInstance().attach(downloadCommandPort);
        this.downloadHandler = new DownloadPluginHandler(downloadService);
        this.preloadService.setMemoryPressureLevel(CacheCapacityPolicy.PressureLevel.NORMAL);

        // 清除登录态缓存，强制通过凭据重新登录
        authHandler.clearAuthState(settingsDb);
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        if (updateService != null) {
            updateService.onHostResume(getActivity());
        }
        if (preloadService == null) return;
        long requestedMb = SettingsStore.getInstance(getContext()).getLong(
            "cache_capacity_mb", CacheCapacityPolicy.DEFAULT_REQUESTED_MB);
        applyCachePolicy(requestedMb, CacheCapacityPolicy.PressureLevel.NORMAL);
        logCachePolicy("foreground-resume");
    }

    @Override
    protected void handleOnDestroy() {
        if (readerHandler != null) {
            readerHandler.destroy();
        }

        if (systemHandler != null) {
            systemHandler.destroy();
        }
        if (updateService != null && updateProgressSink != null) {
            updateService.clearProgressSink(updateProgressSink);
            updateProgressSink = null;
        }
        if (runtime != null) {
            runtime.detachEventSinks(
                featureEventAdapter, featureEventAdapter, featureEventAdapter);
        }
        DownloadCommandRouter.getInstance().detach(downloadCommandPort);
        PdfExportCommandRouter.getInstance().detach(pdfExportCommandPort);
        if (pdfEventSink != null) {
            PdfExportService.getInstance(getContext()).detachEventSink(pdfEventSink);
        }
        if (instance == this) {
            instance = null;
        }

        shutdownGracefully(pdfCommandExecutor);
        if (apiSession != null) {
            apiSession.destroy();
        }
        // 图片、网络和下载准备 executor 由 JmcomicRuntime 持有。
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
            Thread.currentThread().interrupt();
        }
    }

    static ExecutorService createPdfCommandExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pdf-command");
            thread.setDaemon(true);
            return thread;
        });
    }

    static void dispatchPdfCommand(Executor executor, Runnable command) {
        executor.execute(command);
    }

    static void dispatchPdfFileOperation(Executor executor, Runnable operation) {
        executor.execute(operation);
    }

    static int pdfFolderGrantFlags(boolean canGrantUri) {
        return PdfPluginHandler.pdfFolderGrantFlags(canGrantUri);
    }

    /**
     * 读取 domainManager 中已有的域名状态（同步返回，不触发探活）。
     * 用于前端初始化时展示 AbstractJmClient 构造时已完成的首轮探活结果。
     */
    @PluginMethod
    public void getDomainStates(PluginCall call) {
        systemHandler.getDomainStates(call);
    }

    /**
     * 手动触发域名重新探活（结果通过 networkProbe 事件推送）。
     * 用于网络状态页的刷新按钮。
     */
    @PluginMethod
    public void reprobeDomains(PluginCall call) {
        systemHandler.reprobeDomains(call);
    }

    /**
     * 对可达域名进行延迟测试（HEAD 请求计时）。
     * 不可达域名直接跳过，前端自行填充固定值。
     */
    @PluginMethod
    public void measureLatency(PluginCall call) {
        systemHandler.measureLatency(call);
    }

    @PluginMethod
    public void getInitStatus(PluginCall call) {
        systemHandler.getInitStatus(call);
    }

    /**
     * 请求存储权限（根据 API 版本选择最合适的权限）。
     * 返回 { granted: boolean, permissionType: string, apiLevel: int }
     */
    @PluginMethod
    public void requestManageStorage(PluginCall call) {
        systemHandler.requestManageStorage(call);
    }

    /**
     * 供 MainActivity.onRequestPermissionsResult 调用。
     */
    public void handlePermissionResult(int requestCode, String[] permissions,
                                       int[] grantResults) {
        systemHandler.handlePermissionResult(requestCode, permissions, grantResults);
    }

    /**
     * 供 MainActivity.onActivityResult 调用，处理图片选择结果并执行 OCR。
     */
    public void handleActivityResult(int requestCode, int resultCode, Intent data) {
        systemHandler.handleActivityResult(requestCode, resultCode, data);
    }

    public static JmcomicPlugin getInstance() {
        return instance;
    }

    public static void setPendingLaunchRoute(String route) {
        ReaderPluginHandler.setPendingLaunchRoute(route);
        JmcomicPlugin plugin = instance;
        if (plugin != null) {
            plugin.notifyLaunchRoute(route);
        }
    }

    // ---- 自适应缓存容量 ----

    private CacheCapacityPolicy.Result calculateCacheCapacity(long requestedMb,
                                                              CacheCapacityPolicy.PressureLevel pressureLevel) {
        ActivityManager am = (ActivityManager) getContext()
            .getSystemService(Context.ACTIVITY_SERVICE);
        boolean lowRam = am != null && am.isLowRamDevice();
        return cacheCapacityPolicy.calculate(requestedMb, Runtime.getRuntime().maxMemory(),
            lowRam, pressureLevel);
    }

    private void applyCachePolicy(long requestedMb,
                                  CacheCapacityPolicy.PressureLevel pressureLevel) {
        ImageCache.getInstance().applyPolicy(calculateCacheCapacity(requestedMb, pressureLevel));
        if (preloadService != null) preloadService.setMemoryPressureLevel(pressureLevel);
    }

    private void logCachePolicy(String event) {
        ImageCache.CacheStats stats = ImageCache.getInstance().getStats();
        Log.i(TAG, "缓存策略: requestedMb=" + stats.requestedMb
            + ", effectiveMb=" + stats.effectiveMb
            + ", currentMb=" + Math.round(stats.currentBytes / (1024.0 * 1024.0))
            + ", maxHeapMb=" + stats.maxHeapMb
            + ", safeRatio=" + stats.safeRatio
            + ", pressureLevel=" + stats.pressureLevel
            + ", temporaryClamp=" + stats.temporaryClamp
            + ", reason=" + stats.reason + ", event=" + event);
    }

    /**
     * 由 MainActivity.onTrimMemory/onLowMemory 调用，根据系统内存压力缩减缓存。
     */
    public void onMemoryPressure(int level) {
        CacheCapacityPolicy.PressureLevel pressureLevel;
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            pressureLevel = CacheCapacityPolicy.PressureLevel.COMPLETE;
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            pressureLevel = CacheCapacityPolicy.PressureLevel.MODERATE;
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            pressureLevel = CacheCapacityPolicy.PressureLevel.BACKGROUND;
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            pressureLevel = CacheCapacityPolicy.PressureLevel.UI_HIDDEN;
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            pressureLevel = CacheCapacityPolicy.PressureLevel.RUNNING_CRITICAL;
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            pressureLevel = CacheCapacityPolicy.PressureLevel.RUNNING_LOW;
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
            pressureLevel = CacheCapacityPolicy.PressureLevel.RUNNING_MODERATE;
        } else {
            pressureLevel = CacheCapacityPolicy.PressureLevel.NORMAL;
        }

        long requestedMb = SettingsStore.getInstance(getContext()).getLong(
            "cache_capacity_mb", CacheCapacityPolicy.DEFAULT_REQUESTED_MB);
        applyCachePolicy(requestedMb, pressureLevel);

        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            ImageCache.getInstance().clear();
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            ImageCache.getInstance().trimToFraction(0.2);
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            ImageCache.getInstance().trimToFraction(0.2);
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            ImageCache.getInstance().trimToFraction(0.5);
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            ImageCache.getInstance().trimToFraction(0.2);
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            ImageCache.getInstance().trimToFraction(0.5);
        }
        logCachePolicy("trim-memory-" + level);
    }

    @PluginMethod
    public void pickImageAndOcr(PluginCall call) {
        systemHandler.pickImageAndOcr(call);
    }

    @PluginMethod
    public void pickFolder(PluginCall call) {
        systemHandler.pickFolder(call);
    }

    @PluginMethod
    public void checkNotificationPermission(PluginCall call) {
        systemHandler.checkNotificationPermission(call);
    }

    @PluginMethod
    public void requestNotificationPermission(PluginCall call) {
        systemHandler.requestNotificationPermission(call);
    }

    @PluginMethod
    public void openNotificationSettings(PluginCall call) {
        systemHandler.openNotificationSettings(call);
    }

    @PluginMethod
    public void checkUpdate(PluginCall call) {
        updateHandler.checkUpdate(call);
    }

    @PluginMethod
    public void startUpdate(PluginCall call) {
        updateHandler.startUpdate(call);
    }

    @PluginMethod
    public void cancelUpdate(PluginCall call) {
        updateHandler.cancelUpdate(call);
    }

    @PluginMethod
    public void getUpdateState(PluginCall call) {
        updateHandler.getUpdateState(call);
    }

    @PluginMethod
    public void installUpdate(PluginCall call) {
        updateHandler.installUpdate(call);
    }

    @PluginMethod
    public void requestInstallPermission(PluginCall call) {
        updateHandler.requestInstallPermission(call);
    }

    @PluginMethod
    public void checkFilesExist(PluginCall call) {
        systemHandler.checkFilesExist(call);
    }

    @PluginMethod
    public void getExternalStoragePath(PluginCall call) {
        systemHandler.getExternalStoragePath(call);
    }

    @PluginMethod
    public void search(PluginCall call) {
        apiHandler.search(call);
    }

    @PluginMethod
    public void categories(PluginCall call) {
        apiHandler.categories(call);
    }

    @PluginMethod
    public void getAlbum(PluginCall call) {
        apiHandler.getAlbum(call);
    }

    @PluginMethod
    public void getPhoto(PluginCall call) {
        apiHandler.getPhoto(call);
    }

    @PluginMethod
    public void getComments(PluginCall call) {
        apiHandler.getComments(call);
    }

    @PluginMethod
    public void toggleAlbumLike(PluginCall call) {
        apiHandler.toggleAlbumLike(call);
    }

    @PluginMethod
    public void getFavorites(PluginCall call) {
        apiHandler.getFavorites(call);
    }

    @PluginMethod
    public void manageFavoriteFolder(PluginCall call) {
        apiHandler.manageFavoriteFolder(call);
    }

    @PluginMethod
    public void toggleAlbumFavorite(PluginCall call) {
        apiHandler.toggleAlbumFavorite(call);
    }

    @PluginMethod
    public void preloadImages(PluginCall call) {
        cacheHandler.preloadImages(call);
    }

    @PluginMethod
    public void retryImage(PluginCall call) {
        cacheHandler.retryImage(call);
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
        cacheHandler.setCacheCapacity(call);
    }

    @PluginMethod
    public void getCacheCapacityInfo(PluginCall call) {
        cacheHandler.getCacheCapacityInfo(call);
    }

    @PluginMethod
    public void getImageCacheContents(PluginCall call) {
        cacheHandler.getImageCacheContents(call);
    }

    @PluginMethod
    public void clearImageCache(PluginCall call) {
        cacheHandler.clearImageCache(call);
    }

    @PluginMethod
    public void setDownloadConcurrency(PluginCall call) {
        settingsHandler.setDownloadConcurrency(call);
    }

    @PluginMethod
    public void setPreloadConcurrency(PluginCall call) {
        settingsHandler.setPreloadConcurrency(call);
    }

    @PluginMethod
    public void setDownloadPublic(PluginCall call) {
        settingsHandler.setDownloadPublic(call);
    }

    @PluginMethod
    public void getDownloadPublic(PluginCall call) {
        settingsHandler.getDownloadPublic(call);
    }

    @PluginMethod
    public void getAllSettings(PluginCall call) {
        settingsHandler.getAllSettings(call);
    }

    @PluginMethod
    public void setReaderPreloadPages(PluginCall call) {
        settingsHandler.setReaderPreloadPages(call);
    }

    @PluginMethod
    public void setOcrEnabled(PluginCall call) {
        settingsHandler.setOcrEnabled(call);
    }

    // ========== 阅读器设置 ==========

    @PluginMethod
    public void setReaderDisplayMode(PluginCall call) {
        readerHandler.setReaderDisplayMode(call);
    }

    @PluginMethod
    public void setReaderScreenOrientation(PluginCall call) {
        readerHandler.setReaderScreenOrientation(call);
    }

    @PluginMethod
    public void setReaderBrightness(PluginCall call) {
        readerHandler.setReaderBrightness(call);
    }

    @PluginMethod
    public void setReaderKeepScreenOn(PluginCall call) {
        readerHandler.setReaderKeepScreenOn(call);
    }

    @PluginMethod
    public void setReaderVolumeNavigation(PluginCall call) {
        readerHandler.setReaderVolumeNavigation(call);
    }

    @PluginMethod
    public void setReaderAutoShowToolbarAtEnd(PluginCall call) {
        readerHandler.setReaderAutoShowToolbarAtEnd(call);
    }

    @PluginMethod
    public void setReaderFullscreen(PluginCall call) {
        readerHandler.setReaderFullscreen(call);
    }

    @PluginMethod
    public void setReaderState(PluginCall call) {
        readerHandler.setReaderState(call);
    }

    public boolean isReaderActive() {
        return readerHandler.isReaderActive();
    }

    public boolean isVolumeNavigationEnabled() {
        return readerHandler.isVolumeNavigationEnabled();
    }

    public boolean isReaderVertical() {
        return readerHandler.isReaderVertical();
    }

    public void notifyVolumeKey(String direction) {
        readerHandler.notifyVolumeKey(direction);
    }

    public void notifyLaunchRoute(String route) {
        readerHandler.notifyLaunchRoute(route);
    }

    @PluginMethod
    public void consumeLaunchRoute(PluginCall call) {
        readerHandler.consumeLaunchRoute(call);
    }

    // ========== 下载相关 ==========

    @SuppressLint("NewApi")
    @PluginMethod
    public void downloadChapter(PluginCall call) {
        downloadHandler.downloadChapter(call);
    }

    @PluginMethod
    public void getDownloadTasks(PluginCall call) {
        downloadHandler.getDownloadTasks(call);
    }

    @PluginMethod
    public void cancelDownload(PluginCall call) {
        downloadHandler.cancelDownload(call);
    }

    @PluginMethod
    public void pauseDownload(PluginCall call) {
        downloadHandler.pauseDownload(call);
    }

    @PluginMethod
    public void resumeDownload(PluginCall call) {
        downloadHandler.resumeDownload(call);
    }

    @PluginMethod
    public void deleteDownloaded(PluginCall call) {
        downloadHandler.deleteDownloaded(call);
    }

    @PluginMethod
    public void getDownloadedPhoto(PluginCall call) {
        downloadHandler.getDownloadedPhoto(call);
    }

    // ========== 浏览历史 ==========

    @PluginMethod
    public void getBrowseHistory(PluginCall call) {
        historyHandler.getBrowseHistory(call);
    }

    @PluginMethod
    public void getBrowseHistoryOverview(PluginCall call) {
        historyHandler.getBrowseHistoryOverview(call);
    }

    @PluginMethod
    public void recordBrowse(PluginCall call) {
        historyHandler.recordBrowse(call);
    }

    @PluginMethod
    public void clearBrowseHistory(PluginCall call) {
        historyHandler.clearBrowseHistory(call);
    }

    @PluginMethod
    public void deleteBrowseItem(PluginCall call) {
        historyHandler.deleteBrowseItem(call);
    }

    // ========== 解析历史 ==========

    @PluginMethod
    public void getParseHistory(PluginCall call) {
        historyHandler.getParseHistory(call);
    }

    @PluginMethod
    public void addParseHistory(PluginCall call) {
        historyHandler.addParseHistory(call);
    }

    @PluginMethod
    public void clearParseHistory(PluginCall call) {
        historyHandler.clearParseHistory(call);
    }

    @PluginMethod
    public void deleteParseItem(PluginCall call) {
        historyHandler.deleteParseItem(call);
    }

    // ========== 离线收藏夹 ==========

    @PluginMethod
    public void getOfflineFolders(PluginCall call) {
        favoriteHandler.getOfflineFolders(call);
    }

    @PluginMethod
    public void createOfflineFolder(PluginCall call) {
        favoriteHandler.createOfflineFolder(call);
    }

    @PluginMethod
    public void renameOfflineFolder(PluginCall call) {
        favoriteHandler.renameOfflineFolder(call);
    }

    @PluginMethod
    public void deleteOfflineFolder(PluginCall call) {
        favoriteHandler.deleteOfflineFolder(call);
    }

    @PluginMethod
    public void addOfflineFavorite(PluginCall call) {
        favoriteHandler.addOfflineFavorite(call);
    }

    @PluginMethod
    public void removeOfflineFavorite(PluginCall call) {
        favoriteHandler.removeOfflineFavorite(call);
    }

    @PluginMethod
    public void getOfflineFavorites(PluginCall call) {
        favoriteHandler.getOfflineFavorites(call);
    }

    @PluginMethod
    public void getAllOfflineFavorites(PluginCall call) {
        favoriteHandler.getAllOfflineFavorites(call);
    }

    @PluginMethod
    public void getOfflineFavoritesTotalCount(PluginCall call) {
        favoriteHandler.getOfflineFavoritesTotalCount(call);
    }

    @PluginMethod
    public void getAllOfflineFavoritesMerged(PluginCall call) {
        favoriteHandler.getAllOfflineFavoritesMerged(call);
    }

    @PluginMethod
    public void moveAllOfflineFavorites(PluginCall call) {
        favoriteHandler.moveAllOfflineFavorites(call);
    }

    @PluginMethod
    public void copyOfflineFolder(PluginCall call) {
        favoriteHandler.copyOfflineFolder(call);
    }

    @PluginMethod
    public void addOfflineFavoritesBatch(PluginCall call) {
        favoriteHandler.addOfflineFavoritesBatch(call);
    }

    @PluginMethod
    public void mergeOfflineAllToFolder(PluginCall call) {
        favoriteHandler.mergeOfflineAllToFolder(call);
    }

    // ========== 离线收藏夹容灾备份 ==========

    @PluginMethod
    public void saveOfflineBackup(PluginCall call) {
        favoriteHandler.saveOfflineBackup(call);
    }

    @PluginMethod
    public void loadOfflineBackup(PluginCall call) {
        favoriteHandler.loadOfflineBackup(call);
    }

    @PluginMethod
    public void deleteOfflineBackup(PluginCall call) {
        favoriteHandler.deleteOfflineBackup(call);
    }

    @PluginMethod
    public void listOfflineBackupKeys(PluginCall call) {
        favoriteHandler.listOfflineBackupKeys(call);
    }

    // ========== 用户认证 ==========

    @PluginMethod
    public void login(PluginCall call) {
        authHandler.login(call);
    }

    @PluginMethod
    public void logout(PluginCall call) {
        authHandler.logout(call);
    }

    @PluginMethod
    public void getUserProfile(PluginCall call) {
        authHandler.getUserProfile(call);
    }

    @PluginMethod
    public void checkLoginState(PluginCall call) {
        authHandler.checkLoginState(call);
    }

    @PluginMethod
    public void autoLogin(PluginCall call) {
        authHandler.autoLogin(call);
    }

    // ========== PDF 导出 ==========

    // ========== PDF 导入 ==========

    @PluginMethod
    public void scanPdfFiles(PluginCall call) {
        pdfHandler.scanPdfFiles(call);
    }

    @PluginMethod
    public void importPdfs(PluginCall call) {
        pdfHandler.importPdfs(call);
    }

    @PluginMethod
    public void getImportedPdfs(PluginCall call) {
        pdfHandler.getImportedPdfs(call);
    }

    @PluginMethod
    public void updateLocalEpisodeType(PluginCall call) {
        pdfHandler.updateLocalEpisodeType(call);
    }

    @PluginMethod
    public void deleteImportedPdf(PluginCall call) {
        pdfHandler.deleteImportedPdf(call);
    }

    @PluginMethod
    public void getPdfFiles(PluginCall call) {
        pdfHandler.getPdfFiles(call);
    }

    @PluginMethod
    public void refreshPdfFileAvailability(PluginCall call) {
        pdfHandler.refreshPdfFileAvailability(call);
    }

    @PluginMethod
    public void inspectPdfFileForDeletion(PluginCall call) {
        pdfHandler.inspectPdfFileForDeletion(call);
    }

    @PluginMethod
    public void verifyPdfFile(PluginCall call) {
        pdfHandler.verifyPdfFile(call);
    }

    @PluginMethod
    public void removePdfFromLibrary(PluginCall call) {
        pdfHandler.removePdfFromLibrary(call);
    }

    @PluginMethod
    public void deletePdfFile(PluginCall call) {
        pdfHandler.deletePdfFile(call);
    }

    @PluginMethod
    public void getPdfManagementState(PluginCall call) {
        pdfHandler.getPdfManagementState(call);
    }

    @PluginMethod
    public void acknowledgePdfDatabaseReset(PluginCall call) {
        pdfHandler.acknowledgePdfDatabaseReset(call);
    }

    @PluginMethod
    public void openPdf(PluginCall call) {
        pdfHandler.openPdf(call);
    }

    @PluginMethod
    public void openPdfFolder(PluginCall call) {
        pdfHandler.openPdfFolder(call);
    }

    @PluginMethod
    public void getPdfInfo(PluginCall call) {
        pdfHandler.getPdfInfo(call);
    }

    @PluginMethod
    public void renderPdfPage(PluginCall call) {
        pdfHandler.renderPdfPage(call);
    }

    @PluginMethod
    public void exportPdfBatch(PluginCall call) {
        pdfHandler.exportPdfBatch(call);
    }

    @PluginMethod
    public void getPdfExportTasks(PluginCall call) {
        pdfHandler.getPdfExportTasks(call);
    }

    @PluginMethod
    public void getPdfExportTask(PluginCall call) {
        pdfHandler.getPdfExportTask(call);
    }

    @PluginMethod
    public void cancelPdfExport(PluginCall call) {
        pdfHandler.cancelPdfExport(call);
    }

    @PluginMethod
    public void retryPdfExport(PluginCall call) {
        pdfHandler.retryPdfExport(call);
    }

    @PluginMethod
    public void deletePdfExportTask(PluginCall call) {
        pdfHandler.deletePdfExportTask(call);
    }
}
