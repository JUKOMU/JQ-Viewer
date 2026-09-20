package io.github.jukomu.desktop.backend;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.desktop.bridge.Plugin;
import io.github.jukomu.desktop.bridge.PluginMethodRoutes;
import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.bridge.model.ErrorResponse;
import io.github.jukomu.desktop.bridge.handler.ApiPluginHandler;
import io.github.jukomu.desktop.bridge.handler.AuthPluginHandler;
import io.github.jukomu.desktop.bridge.handler.CachePluginHandler;
import io.github.jukomu.desktop.bridge.handler.DownloadPluginHandler;
import io.github.jukomu.desktop.bridge.handler.FilePluginHandler;
import io.github.jukomu.desktop.bridge.handler.HistoryPluginHandler;
import io.github.jukomu.desktop.bridge.handler.OfflineFavoritePluginHandler;
import io.github.jukomu.desktop.bridge.handler.OcrPluginHandler;
import io.github.jukomu.desktop.bridge.handler.PdfPluginHandler;
import io.github.jukomu.desktop.bridge.handler.SettingsPluginHandler;
import io.github.jukomu.desktop.bridge.handler.SystemPluginHandler;
import io.github.jukomu.desktop.bridge.handler.UpdatePluginHandler;
import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.auth.AuthService;
import io.github.jukomu.desktop.feature.auth.CredentialStore;
import io.github.jukomu.desktop.feature.auth.CredentialStores;
import io.github.jukomu.desktop.feature.catalog.CatalogService;
import io.github.jukomu.desktop.feature.download.DownloadFiles;
import io.github.jukomu.desktop.feature.download.DownloadLocationService;
import io.github.jukomu.desktop.feature.download.DownloadService;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.diagnostics.DiagnosticsService;
import io.github.jukomu.desktop.feature.favorite.OfflineFavoriteService;
import io.github.jukomu.desktop.feature.favorite.data.OfflineFavoriteStore;
import io.github.jukomu.desktop.feature.history.HistoryService;
import io.github.jukomu.desktop.feature.image.CacheService;
import io.github.jukomu.desktop.feature.image.ImageCache;
import io.github.jukomu.desktop.feature.image.ImageService;
import io.github.jukomu.desktop.feature.files.FileService;
import io.github.jukomu.desktop.feature.network.NetworkService;
import io.github.jukomu.desktop.feature.notification.DesktopNotificationSink;
import io.github.jukomu.desktop.feature.notification.DesktopTaskNotificationService;
import io.github.jukomu.desktop.feature.notification.LaunchRouteService;
import io.github.jukomu.desktop.feature.ocr.OcrService;
import io.github.jukomu.desktop.feature.pdf.data.PdfStore;
import io.github.jukomu.desktop.feature.pdf.export.PdfExportService;
import io.github.jukomu.desktop.feature.pdf.export.PdfExportStore;
import io.github.jukomu.desktop.feature.pdf.management.PdfManagementService;
import io.github.jukomu.desktop.feature.pdf.render.PdfDocumentService;
import io.github.jukomu.desktop.feature.pdf.render.PdfPageCache;
import io.github.jukomu.desktop.feature.pdf.render.PdfResourceService;
import io.github.jukomu.desktop.feature.settings.SettingsService;
import io.github.jukomu.desktop.feature.update.DesktopUpdateConfiguration;
import io.github.jukomu.desktop.feature.update.DesktopUpdateService;
import io.github.jukomu.desktop.lifecycle.CloseSequence;
import io.github.jukomu.jmcomic.api.client.JmClient;
import io.github.jukomu.jmcomic.api.client.JmDownloadClient;
import io.github.jukomu.jmcomic.core.JmComic;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import io.github.jukomu.jmcomic.core.config.JmConfiguration;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

/** 在同一 JVM 内承载 loopback Javalin 服务与本地资源。 */
public final class Backend implements AutoCloseable {
    public static final String LOOPBACK_HOST = "127.0.0.1";
    private static final Logger LOGGER = LoggerFactory.getLogger(Backend.class);
    private static final List<String> SPA_PATHS = List.of(
            "/home",
            "/category",
            "/search",
            "/favorite",
            "/download",
            "/setting",
            "/cache",
            "/history",
            "/album",
            "/login",
            "/user",
            "/network-status",
            "/about",
            "/pdf-template-help",
            "/batch-parse",
            "/import-review",
            "/pdf-reader"
    );

    private final Paths paths;
    private final Database database;
    private final ServiceExecutors executors;
    private final FileService fileService;
    private final JmClient providedClient;
    private final Function<String, String> providedAlbumCoverUrl;
    private final CredentialStore providedCredentialStore;
    private final NetworkService.Operations providedNetworkOperations;

    private Javalin app;
    private JmApiClient client;
    private DownloadService downloadService;
    private PdfExportService pdfExportService;
    private NetworkService networkService;
    private OcrService ocrService;
    private EventHub eventHub;
    private LaunchRouteService launchRouteService;
    private DesktopTaskNotificationService taskNotifications;
    private DesktopUpdateService updateService;
    private URI homeUrl;
    private boolean running;
    private boolean closed;

    public Backend(Paths paths) {
        this(paths, new Database(paths), new ServiceExecutors(), null, null,
                new FileService(paths), null, null);
    }

    public Backend(
            Paths paths,
            Database database,
            ExecutorService apiExecutor
    ) {
        this(paths, database, new ServiceExecutors(apiExecutor), null, null,
                new FileService(paths), null, null);
    }

    Backend(
            Paths paths,
            Database database,
            ExecutorService apiExecutor,
            JmClient providedClient,
            Function<String, String> providedAlbumCoverUrl
    ) {
        this(paths, database, new ServiceExecutors(apiExecutor), providedClient, providedAlbumCoverUrl,
                new FileService(paths), CredentialStores.unavailable(), null);
    }

    Backend(
            Paths paths,
            Database database,
            ExecutorService apiExecutor,
            JmClient providedClient,
            Function<String, String> providedAlbumCoverUrl,
            FileService fileService
    ) {
        this(paths, database, new ServiceExecutors(apiExecutor), providedClient, providedAlbumCoverUrl,
                fileService, CredentialStores.unavailable(), null);
    }

    Backend(
            Paths paths,
            Database database,
            ExecutorService apiExecutor,
            JmClient providedClient,
            Function<String, String> providedAlbumCoverUrl,
            FileService fileService,
            CredentialStore credentialStore
    ) {
        this(paths, database, new ServiceExecutors(apiExecutor), providedClient, providedAlbumCoverUrl,
                fileService, credentialStore, null);
    }

    Backend(
            Paths paths,
            Database database,
            ExecutorService apiExecutor,
            JmClient providedClient,
            Function<String, String> providedAlbumCoverUrl,
            FileService fileService,
            CredentialStore credentialStore,
            NetworkService.Operations networkOperations
    ) {
        this(paths, database, new ServiceExecutors(apiExecutor), providedClient, providedAlbumCoverUrl,
                fileService, credentialStore, networkOperations);
    }

    private Backend(
            Paths paths,
            Database database,
            ServiceExecutors executors,
            JmClient providedClient,
            Function<String, String> providedAlbumCoverUrl,
            FileService fileService,
            CredentialStore providedCredentialStore,
            NetworkService.Operations providedNetworkOperations
    ) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.database = Objects.requireNonNull(database, "database");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.fileService = Objects.requireNonNull(fileService, "fileService");
        this.providedCredentialStore = providedCredentialStore;
        this.providedNetworkOperations = providedNetworkOperations;
        if ((providedClient == null) != (providedAlbumCoverUrl == null)) {
            throw new IllegalArgumentException("客户端和封面地址解析器必须同时提供");
        }
        this.providedClient = providedClient;
        this.providedAlbumCoverUrl = providedAlbumCoverUrl;
    }

    public synchronized URI start() throws Exception {
        if (closed) {
            throw new IllegalStateException("本地后端已关闭");
        }
        if (running) {
            return homeUrl;
        }

        Javalin candidate = null;
        EventHub startedEventHub = null;
        JmApiClient startedClient = null;
        DownloadService startedDownloadService = null;
        PdfExportService startedPdfExportService = null;
        NetworkService startedNetworkService = null;
        OcrService startedOcrService = null;
        LaunchRouteService startedLaunchRoutes = null;
        DesktopTaskNotificationService startedTaskNotifications = null;
        DesktopUpdateService startedUpdateService = null;
        try {
            paths.ensureDirectories();
            if (Backend.class.getResource("/static/index.html") == null) {
                throw new IllegalStateException(
                        "静态资源缺失；请先运行 npm run desktop:sync"
                );
            }

            database.open();
            ObjectMapper mapper = JsonMapper.builder()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .build();
            SettingsService settingsService = new SettingsService(database, mapper);
            startedOcrService = OcrService.createDefault(settingsService, paths.ocrDirectory());
            int preloadConcurrency = settingsService.preloadConcurrency();
            executors.configureImagePreload(preloadConcurrency);
            startedEventHub = new EventHub(mapper);
            startedUpdateService = new DesktopUpdateService(
                    DesktopUpdateConfiguration.fromSystemProperties(), mapper, startedEventHub, paths);
            final JmClient serviceClient;
            final Function<String, String> albumCoverUrl;
            final NetworkService.Operations networkOperations;
            if (providedClient == null) {
                startedClient = JmComic.newApiClient(new JmConfiguration.Builder()
                        .downloadThreadPoolSize(settingsService.downloadConcurrency())
                        .concurrentImageDownloads(preloadConcurrency)
                        .build());
                JmApiClient ownedClient = startedClient;
                serviceClient = ownedClient;
                albumCoverUrl = id -> ownedClient.getAlbumCoverUrl(id, "_3x4");
                networkOperations = NetworkService.Operations.from(ownedClient);
            } else {
                serviceClient = providedClient;
                albumCoverUrl = providedAlbumCoverUrl;
                networkOperations = providedNetworkOperations;
            }
            if (!(serviceClient instanceof JmDownloadClient downloadClient)) {
                throw new IllegalStateException("JMComic 客户端不支持下载任务控制");
            }
            final EventHub eventHub = startedEventHub;
            if (networkOperations != null) {
                startedNetworkService = new NetworkService(
                        networkOperations, executors.networkProbe(), eventHub);
            }
            ImageService imageService = new ImageService(
                    serviceClient, executors.imagePreload(), eventHub);
            DownloadStore downloadStore = new DownloadStore(database);
            PdfExportStore pdfExportStore = new PdfExportStore(database);
            startedLaunchRoutes = new LaunchRouteService(eventHub);
            startedTaskNotifications = new DesktopTaskNotificationService(
                    downloadStore, pdfExportStore, startedLaunchRoutes, eventHub);
            DesktopTaskNotificationService taskNotifications = startedTaskNotifications;
            DownloadFiles downloadFiles = new DownloadFiles(
                    settingsService.downloadRoot(paths.downloadsDirectory()));
            DownloadLocationService downloadLocationService = new DownloadLocationService(
                    paths, settingsService, downloadStore, downloadFiles, pdfExportStore,
                    fileService, eventHub);
            downloadLocationService.reconcileOnStartup();
            startedDownloadService = new DownloadService(
                    downloadStore,
                    downloadFiles,
                    serviceClient,
                    downloadClient,
                    executors.downloadPrepare(),
                    eventHub,
                    mapper
            );
            startedDownloadService.reconcileOnStartup();
            final DownloadService downloadService = startedDownloadService;
            RequestExecutor apiRequests = new RequestExecutor(executors.api(), mapper);
            RequestExecutor imageRequests = new RequestExecutor(executors.imageCommand(), mapper);
            RequestExecutor settingsRequests = new RequestExecutor(executors.settings(), mapper);
            RequestExecutor historyRequests = new RequestExecutor(executors.history(), mapper);
            RequestExecutor favoriteRequests = new RequestExecutor(
                    executors.offlineFavorite(), mapper);
            RequestExecutor diagnosticsRequests = new RequestExecutor(
                    executors.diagnostics(), mapper);
            RequestExecutor fileRequests = new RequestExecutor(executors.fileIo(), mapper);
            RequestExecutor dialogRequests = new RequestExecutor(executors.fileDialog(), mapper);
            RequestExecutor relocationRequests = new RequestExecutor(executors.relocation(), mapper);
            RequestExecutor downloadRequests = new RequestExecutor(
                    executors.downloadCommand(), mapper);
            RequestExecutor pdfRequests = new RequestExecutor(executors.pdfCommand(), mapper);
            RequestExecutor networkRequests = new RequestExecutor(
                    executors.networkCommand(), mapper);
            RequestExecutor ocrRequests = new RequestExecutor(executors.ocr(), mapper);
            RequestExecutor updateRequests = new RequestExecutor(executors.updateCommand(), mapper);
            CredentialStore credentialStore = providedCredentialStore == null
                    ? CredentialStores.system()
                    : providedCredentialStore;
            PdfPageCache pdfPageCache = new PdfPageCache(paths.cacheDirectory());
            CacheService cacheService = new CacheService(
                    settingsService, imageService.cache(), pdfPageCache);
            DiagnosticsService diagnosticsService = new DiagnosticsService(
                    paths, downloadStore, pdfExportStore, cacheService);
            PdfManagementService pdfManagementService = new PdfManagementService(
                    new PdfStore(database),
                    downloadStore,
                    fileService,
                    new PdfDocumentService(pdfPageCache)
            );
            startedPdfExportService = new PdfExportService(
                    pdfExportStore, downloadStore, downloadFiles,
                    executors.pdfExport(), eventHub);
            startedPdfExportService.reconcileOnStartup();
            taskNotifications.start();
            Plugin plugin = new Plugin(
                    new ApiPluginHandler(apiRequests, imageRequests,
                            new CatalogService(serviceClient, imageService, albumCoverUrl), imageService),
                    new AuthPluginHandler(apiRequests, new AuthService(serviceClient, credentialStore)),
                    new CachePluginHandler(imageRequests, cacheService),
                    new SettingsPluginHandler(
                            settingsRequests, relocationRequests,
                            settingsService, downloadLocationService),
                    new HistoryPluginHandler(historyRequests, new HistoryService(database)),
                    new OfflineFavoritePluginHandler(favoriteRequests, new OfflineFavoriteService(
                            new OfflineFavoriteStore(database, mapper))),
                    new FilePluginHandler(fileRequests, dialogRequests, fileService),
                    new DownloadPluginHandler(downloadRequests, downloadService),
                    new PdfPluginHandler(pdfRequests, pdfManagementService, startedPdfExportService),
                    new SystemPluginHandler(
                            networkRequests, diagnosticsRequests,
                            startedNetworkService, startedLaunchRoutes, diagnosticsService),
                    new OcrPluginHandler(ocrRequests, startedOcrService),
                    new UpdatePluginHandler(updateRequests, startedUpdateService));
            PdfResourceService pdfResources = new PdfResourceService();
            candidate = Javalin.create(config -> {
                config.jetty.host = LOOPBACK_HOST;
                config.jetty.port = 0;
                config.http.asyncTimeout = 30_000;
                config.staticFiles.add("/static", Location.CLASSPATH);
                for (String path : SPA_PATHS) {
                    config.spaRoot.addFile(path, "static/index.html", Location.CLASSPATH);
                }
                config.routes.get("/", context -> context.redirect("/home"));
                PluginMethodRoutes.register(config.routes, plugin);
                config.routes.sse("/events", eventHub::connect);
                registerImageRoute(config, imageService, downloadService,
                        "image", "/image/{photoId}/{sortOrder}");
                registerImageRoute(config, imageService, downloadService,
                        "thumb", "/thumb/{photoId}/{sortOrder}");
                registerPdfRoutes(config, pdfResources, pdfPageCache);
            });
            candidate.start();
            int port = candidate.port();
            if (port <= 0) {
                throw new IllegalStateException("Javalin did not expose an operating-system port");
            }

            this.app = candidate;
            this.client = startedClient;
            this.downloadService = downloadService;
            this.pdfExportService = startedPdfExportService;
            this.networkService = startedNetworkService;
            this.ocrService = startedOcrService;
            this.eventHub = eventHub;
            this.launchRouteService = startedLaunchRoutes;
            this.taskNotifications = startedTaskNotifications;
            this.updateService = startedUpdateService;
            this.homeUrl = URI.create("http://" + LOOPBACK_HOST + ":" + port + "/home");
            this.running = true;
            LOGGER.info("本地后端监听于 {}", homeUrl);
            return homeUrl;
        } catch (Exception | Error exception) {
            Javalin failedApp = candidate;
            DownloadService failedDownloadService = startedDownloadService;
            PdfExportService failedPdfExportService = startedPdfExportService;
            NetworkService failedNetworkService = startedNetworkService;
            OcrService failedOcrService = startedOcrService;
            DesktopTaskNotificationService failedTaskNotifications = startedTaskNotifications;
            DesktopUpdateService failedUpdateService = startedUpdateService;
            LaunchRouteService failedLaunchRoutes = startedLaunchRoutes;
            EventHub failedEventHub = startedEventHub;
            JmApiClient failedClient = startedClient;
            CloseSequence.run(LOGGER,
                    step("启动中的本地后端", () -> {
                        if (failedApp != null) failedApp.stop();
                    }),
                    step("任务通知", () -> {
                        if (failedTaskNotifications != null) failedTaskNotifications.close();
                    }),
                    step("更新服务", () -> {
                        if (failedUpdateService != null) failedUpdateService.close();
                    }),
                    step("启动路由", () -> {
                        if (failedLaunchRoutes != null) failedLaunchRoutes.close();
                    }),
                    step("下载服务", () -> {
                        if (failedDownloadService != null) failedDownloadService.close();
                    }),
                    step("PDF 导出服务", () -> {
                        if (failedPdfExportService != null) failedPdfExportService.close();
                    }),
                    step("网络服务", () -> {
                        if (failedNetworkService != null) failedNetworkService.close();
                    }),
                    step("OCR 服务", () -> {
                        if (failedOcrService != null) failedOcrService.close();
                    }),
                    step("事件中心", () -> {
                        if (failedEventHub != null) failedEventHub.close();
                    }),
                    step("JMComic 客户端", () -> {
                        if (failedClient != null) failedClient.close();
                    }),
                    step("服务执行器", executors::close),
                    step("数据库", database::close)
            );
            throw exception;
        }
    }

    private void registerPdfRoutes(
            io.javalin.config.JavalinConfig config,
            PdfResourceService pdfResources,
            PdfPageCache pdfPageCache
    ) {
        config.routes.get("/pdf/{encodedFileRef}", context -> {
            try {
                PdfResourceService.Resource resource = pdfResources.open(
                        context.pathParam("encodedFileRef"));
                context.header("Content-Length", String.valueOf(resource.length()));
                context.contentType("application/pdf").result(resource.input());
            } catch (PdfResourceService.ResourceException exception) {
                context.status(exception.status());
                context.header("X-JQViewer-Pdf-Error", exception.code());
                context.contentType("text/plain; charset=utf-8").result(exception.getMessage());
            }
        });
        config.routes.get("/pdf-page/{resourceFile}", context -> {
            String resourceFile = context.pathParam("resourceFile");
            if (!resourceFile.endsWith(".png")) {
                context.status(400).result("PDF 页面资源路径无效");
                return;
            }
            String resourceId = resourceFile.substring(0, resourceFile.length() - 4);
            if (!PdfPageCache.isResourceId(resourceId)) {
                context.status(400).result("PDF 页面资源路径无效");
                return;
            }
            try {
                context.header("Cache-Control", "private, max-age=31536000, immutable");
                context.contentType("image/png").result(pdfPageCache.open(resourceId));
            } catch (java.nio.file.NoSuchFileException exception) {
                context.status(404).result("PDF 页面资源不存在");
            }
        });
    }

    private void registerImageRoute(
            io.javalin.config.JavalinConfig config,
            ImageService imageService,
            DownloadService downloadService,
            String type,
            String path
    ) {
        config.routes.get(path, context -> {
            String photoId = context.pathParam("photoId");
            int sortOrder;
            try {
                sortOrder = Integer.parseInt(context.pathParam("sortOrder"));
            } catch (NumberFormatException exception) {
                sendImageError(context, ApiException.invalidRequest("sortOrder必须是整数"));
                return;
            }
            int finalSortOrder = sortOrder;
            ImageCache.Entry cached = imageService.readCached(photoId, finalSortOrder, type);
            if (cached != null) {
                context.contentType(cached.mimeType()).result(cached.bytes());
                return;
            }
            try {
                CompletableFuture<?> response = CompletableFuture
                        .supplyAsync(
                                () -> downloadService.findCompletedImage(photoId, finalSortOrder)
                                        .map(local -> imageService.readLocal(
                                                photoId, finalSortOrder, type, local))
                                        .orElse(null),
                                executors.imageResource())
                        .thenCompose(entry -> entry != null
                                ? CompletableFuture.completedFuture(entry)
                                : CompletableFuture.supplyAsync(
                                        () -> imageService.read(
                                                photoId, finalSortOrder, type),
                                        executors.imageOnDemand()))
                        .handle((entry, failure) -> {
                    if (failure == null) {
                        context.contentType(entry.mimeType()).result(entry.bytes());
                    } else {
                        sendImageError(context, unwrap(failure));
                    }
                    return null;
                });
                context.future(() -> response);
            } catch (RejectedExecutionException exception) {
                sendImageError(context, new ApiException("internal", 503, "图片任务队列已满"));
            }
        });
    }

    private static void sendImageError(io.javalin.http.Context context, Throwable failure) {
        ApiException error = failure instanceof ApiException apiException
                ? apiException
                : failure instanceof RejectedExecutionException
                ? new ApiException("internal", 503, "图片任务队列已满")
                : new ApiException("internal", 500, messageOf(failure));
        context.status(error.status()).json(new ErrorResponse(error.code(), error.getMessage()));
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String messageOf(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? "图片请求失败" : message;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized String host() {
        return LOOPBACK_HOST;
    }

    public synchronized int port() {
        if (!running || app == null) {
            return -1;
        }
        return app.port();
    }

    public Database database() {
        return database;
    }

    public ExecutorService apiExecutor() {
        return executors.api();
    }

    public ExecutorService imagePreloadExecutor() {
        return executors.imagePreload();
    }

    public boolean serviceExecutorsShutdown() {
        return executors.allShutdown();
    }

    ServiceExecutors serviceExecutors() {
        return executors;
    }

    public synchronized void attachDesktopHost(
            DesktopNotificationSink notificationSink,
            Consumer<String> routeOpener,
            Runnable updateExitRequest
    ) {
        if (!running || taskNotifications == null || launchRouteService == null
                || updateService == null) {
            throw new IllegalStateException("本地后端尚未启动");
        }
        launchRouteService.attachRouteOpener(routeOpener);
        if (notificationSink != null) taskNotifications.attach(notificationSink);
        updateService.attachExitRequest(updateExitRequest);
    }

    public synchronized void detachDesktopHost() {
        if (taskNotifications != null) taskNotifications.detach();
        if (launchRouteService != null) launchRouteService.detachRouteOpener();
        if (updateService != null) updateService.detachExitRequest();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        running = false;

        Javalin current = app;
        app = null;
        homeUrl = null;
        DesktopTaskNotificationService closingTaskNotifications = taskNotifications;
        taskNotifications = null;
        DesktopUpdateService closingUpdateService = updateService;
        updateService = null;
        LaunchRouteService closingLaunchRoutes = launchRouteService;
        launchRouteService = null;
        PdfExportService closingPdfExportService = pdfExportService;
        pdfExportService = null;
        NetworkService closingNetworkService = networkService;
        networkService = null;
        OcrService closingOcrService = ocrService;
        ocrService = null;
        EventHub closingEventHub = eventHub;
        eventHub = null;
        DownloadService closingDownloadService = downloadService;
        downloadService = null;
        JmApiClient closingClient = client;
        client = null;

        CloseSequence.run(LOGGER,
                step("任务通知", () -> {
                    if (closingTaskNotifications != null) closingTaskNotifications.close();
                }),
                step("更新服务", () -> {
                    if (closingUpdateService != null) closingUpdateService.close();
                }),
                step("启动路由", () -> {
                    if (closingLaunchRoutes != null) closingLaunchRoutes.close();
                }),
                step("PDF 导出服务", () -> {
                    if (closingPdfExportService != null) closingPdfExportService.close();
                }),
                step("网络服务", () -> {
                    if (closingNetworkService != null) closingNetworkService.close();
                }),
                step("OCR 服务", () -> {
                    if (closingOcrService != null) closingOcrService.close();
                }),
                step("事件中心", () -> {
                    if (closingEventHub != null) closingEventHub.close();
                }),
                step("本地后端", () -> {
                    if (current != null) current.stop();
                }),
                step("下载服务", () -> {
                    if (closingDownloadService != null) closingDownloadService.close();
                }),
                step("JMComic 客户端", () -> {
                    if (closingClient != null) closingClient.close();
                }),
                step("服务执行器", executors::close),
                step("数据库", database::close)
        );
    }

    private static CloseSequence.Step step(String name, Runnable action) {
        return new CloseSequence.Step(name, action);
    }

}
