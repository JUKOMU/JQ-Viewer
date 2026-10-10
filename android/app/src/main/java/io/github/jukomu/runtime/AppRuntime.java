package io.github.jukomu.runtime;

import android.content.Context;
import io.github.jukomu.feature.cache.CacheCapacityPolicy;
import io.github.jukomu.feature.cache.ImageCache;
import io.github.jukomu.feature.download.DownloadEventSink;
import io.github.jukomu.feature.download.DownloadService;
import io.github.jukomu.feature.download.data.DownloadStore;
import io.github.jukomu.feature.download.storage.FileStore;
import io.github.jukomu.feature.export.ExportService;
import io.github.jukomu.feature.preload.PreloadEventSink;
import io.github.jukomu.feature.preload.PreloadService;
import io.github.jukomu.feature.settings.relocation.RelocationEventSink;
import io.github.jukomu.feature.update.UpdateService;
import io.github.jukomu.platform.persistence.SettingsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 进程级本地应用运行时。
 *
 * <p>Activity 或 Capacitor Plugin 重建时复用下载管理器和预载任务，
 * 仅重新绑定事件监听器。进程被系统回收时这些资源随进程一起释放。</p>
 */
public final class AppRuntime {

    private static final int DOWNLOAD_PREPARE_EXECUTOR_SIZE = 2;
    private static final Logger LOGGER = LoggerFactory.getLogger(AppRuntime.class);
    private static AppRuntime instance;

    private final RuntimeEventRouter eventRouter = new RuntimeEventRouter();
    private final ExecutorService imageExecutor;
    private final ExecutorService imageFileExecutor;
    private final ExecutorService networkExecutor;
    private final ExecutorService downloadPrepareExecutor;
    private final PreloadService preloadService;
    private final DownloadService downloadService;
    private final UpdateService updateService;

    private AppRuntime(Context context, SettingsStore settingsDb,
                       DownloadStore downloadDb, FileStore fileStore,
                       ImageCache imageCache, CacheCapacityPolicy cachePolicy,
                       int preloadConcurrency,
                       JmcomicSessionManager sessionManager) {
        long startedAt = System.nanoTime();
        Context applicationContext = context.getApplicationContext();
        LOGGER.info("app_runtime event=create phase=start preloadConcurrency={}",
            preloadConcurrency);
        imageExecutor = ServiceExecutors.fixed("image", preloadConcurrency);
        imageFileExecutor = ServiceExecutors.fixed("image-file", preloadConcurrency);
        networkExecutor = ServiceExecutors.fixed("image-network", preloadConcurrency);
        downloadPrepareExecutor = ServiceExecutors.fixed(
            "download-prepare", DOWNLOAD_PREPARE_EXECUTOR_SIZE);
        preloadService = new PreloadService(
            imageCache, fileStore, settingsDb, sessionManager::getClient,
            imageExecutor, imageFileExecutor, networkExecutor, eventRouter,
            applicationContext, cachePolicy, preloadConcurrency);
        downloadService = new DownloadService(
            downloadDb, fileStore, sessionManager::getClient,
            downloadPrepareExecutor, eventRouter, applicationContext);
        ExportService.getInstance(applicationContext)
            .setMetadataResolver(downloadService::prepareExportMetadata);
        updateService = new UpdateService(applicationContext);
        LOGGER.info("app_runtime event=create phase=complete pools=4 durationMs={} status=ready",
            elapsedMs(startedAt));
    }

    public static synchronized AppRuntime getOrCreate(
        Context context, SettingsStore settingsDb, DownloadStore downloadDb,
        FileStore fileStore, ImageCache imageCache, CacheCapacityPolicy cachePolicy,
        int preloadConcurrency, JmcomicSessionManager sessionManager) {
        if (instance == null) {
            LOGGER.info("app_runtime event=get_or_create result=create");
            instance = new AppRuntime(context, settingsDb, downloadDb, fileStore,
                imageCache, cachePolicy, preloadConcurrency, sessionManager);
        } else {
            LOGGER.debug("app_runtime event=get_or_create result=reuse");
        }
        return instance;
    }

    public static synchronized boolean exists() {
        return instance != null;
    }

    public void attachEventSinks(DownloadEventSink downloadSink,
                                 PreloadEventSink preloadSink,
                                 RelocationEventSink relocationSink) {
        eventRouter.attach(downloadSink, preloadSink, relocationSink);
        LOGGER.info("app_runtime event=event_sinks phase=attach status=ready");
    }

    public void detachEventSinks(DownloadEventSink downloadSink,
                                 PreloadEventSink preloadSink,
                                 RelocationEventSink relocationSink) {
        eventRouter.detach(downloadSink, preloadSink, relocationSink);
        LOGGER.info("app_runtime event=event_sinks phase=detach status=complete");
    }

    public RelocationEventSink getRelocationEventSink() {
        return eventRouter;
    }

    public PreloadService getPreloadService() {
        return preloadService;
    }

    public DownloadService getDownloadService() {
        return downloadService;
    }

    public UpdateService getUpdateService() {
        return updateService;
    }

    private static long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
