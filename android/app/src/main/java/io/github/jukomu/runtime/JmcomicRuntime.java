package io.github.jukomu.runtime;

import android.content.Context;
import io.github.jukomu.feature.cache.CacheCapacityPolicy;
import io.github.jukomu.feature.cache.ImageCache;
import io.github.jukomu.feature.download.DownloadEventSink;
import io.github.jukomu.feature.download.DownloadService;
import io.github.jukomu.feature.download.data.DownloadStore;
import io.github.jukomu.feature.download.storage.FileStore;
import io.github.jukomu.feature.preload.PreloadEventSink;
import io.github.jukomu.feature.preload.PreloadService;
import io.github.jukomu.feature.settings.relocation.RelocationEventSink;
import io.github.jukomu.jmcomic.core.JmComic;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import io.github.jukomu.jmcomic.core.config.JmConfiguration;
import io.github.jukomu.platform.persistence.SettingsStore;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 进程级 Jmcomic 运行时。
 *
 * <p>Activity 或 Capacitor Plugin 重建时复用客户端、下载管理器和预载任务，
 * 仅重新绑定事件监听器。进程被系统回收时这些资源随进程一起释放。</p>
 */
public final class JmcomicRuntime {

    private static final int DOWNLOAD_PREPARE_EXECUTOR_SIZE = 2;
    private static JmcomicRuntime instance;

    private final RuntimeEventRouter eventRouter = new RuntimeEventRouter();
    private final JmApiClient client;
    private final ExecutorService imageExecutor;
    private final ExecutorService networkExecutor;
    private final ExecutorService downloadPrepareExecutor;
    private final PreloadService preloadService;
    private final DownloadService downloadService;

    private JmcomicRuntime(Context context, SettingsStore settingsDb,
                           DownloadStore downloadDb, FileStore fileStore,
                           ImageCache imageCache, CacheCapacityPolicy cachePolicy,
                           int preloadConcurrency, int downloadConcurrency) {
        Context applicationContext = context.getApplicationContext();
        client = JmComic.newApiClient(new JmConfiguration.Builder()
            .downloadThreadPoolSize(downloadConcurrency).build());
        imageExecutor = Executors.newFixedThreadPool(preloadConcurrency);
        networkExecutor = Executors.newFixedThreadPool(preloadConcurrency);
        downloadPrepareExecutor = Executors.newFixedThreadPool(
            DOWNLOAD_PREPARE_EXECUTOR_SIZE);
        preloadService = new PreloadService(imageCache, fileStore, settingsDb, client,
            imageExecutor, networkExecutor, eventRouter, applicationContext, cachePolicy,
            preloadConcurrency);
        downloadService = new DownloadService(downloadDb, fileStore, client,
            downloadPrepareExecutor, eventRouter, applicationContext);
    }

    public static synchronized JmcomicRuntime getOrCreate(
        Context context, SettingsStore settingsDb, DownloadStore downloadDb,
        FileStore fileStore, ImageCache imageCache, CacheCapacityPolicy cachePolicy,
        int preloadConcurrency, int downloadConcurrency) {
        if (instance == null) {
            instance = new JmcomicRuntime(context, settingsDb, downloadDb, fileStore,
                imageCache, cachePolicy, preloadConcurrency, downloadConcurrency);
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
    }

    public void detachEventSinks(DownloadEventSink downloadSink,
                                 PreloadEventSink preloadSink,
                                 RelocationEventSink relocationSink) {
        eventRouter.detach(downloadSink, preloadSink, relocationSink);
    }

    public RelocationEventSink getRelocationEventSink() {
        return eventRouter;
    }

    public JmApiClient getClient() {
        return client;
    }

    public PreloadService getPreloadService() {
        return preloadService;
    }

    public DownloadService getDownloadService() {
        return downloadService;
    }
}
