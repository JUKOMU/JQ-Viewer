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
import io.github.jukomu.desktop.bridge.handler.HistoryPluginHandler;
import io.github.jukomu.desktop.bridge.handler.SettingsPluginHandler;
import io.github.jukomu.desktop.bridge.handler.SystemPluginHandler;
import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.auth.AuthService;
import io.github.jukomu.desktop.feature.catalog.CatalogService;
import io.github.jukomu.desktop.feature.history.HistoryService;
import io.github.jukomu.desktop.feature.image.ImageService;
import io.github.jukomu.desktop.feature.settings.SettingsService;
import io.github.jukomu.jmcomic.api.client.JmClient;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
    private final ExecutorService businessExecutor;
    private final JmClient providedClient;
    private final Function<String, String> providedAlbumCoverUrl;

    private Javalin app;
    private JmApiClient client;
    private EventHub eventHub;
    private URI homeUrl;
    private boolean running;
    private boolean closed;

    public Backend(Paths paths) {
        this(paths, new Database(paths), createBusinessExecutor(), null, null);
    }

    public Backend(
            Paths paths,
            Database database,
            ExecutorService businessExecutor
    ) {
        this(paths, database, businessExecutor, null, null);
    }

    Backend(
            Paths paths,
            Database database,
            ExecutorService businessExecutor,
            JmClient providedClient,
            Function<String, String> providedAlbumCoverUrl
    ) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.database = Objects.requireNonNull(database, "database");
        this.businessExecutor = Objects.requireNonNull(businessExecutor, "businessExecutor");
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
            SettingsService settingsService = new SettingsService(database);
            int preloadConcurrency = settingsService.preloadConcurrency();
            configureBusinessExecutor(preloadConcurrency);
            startedEventHub = new EventHub(mapper);
            final JmClient serviceClient;
            final Function<String, String> albumCoverUrl;
            if (providedClient == null) {
                startedClient = JmComic.newApiClient(new JmConfiguration.Builder()
                        .executor(businessExecutor)
                        .downloadThreadPoolSize(settingsService.downloadConcurrency())
                        .concurrentImageDownloads(preloadConcurrency)
                        .build());
                JmApiClient ownedClient = startedClient;
                serviceClient = ownedClient;
                albumCoverUrl = id -> ownedClient.getAlbumCoverUrl(id, "_3x4");
            } else {
                serviceClient = providedClient;
                albumCoverUrl = providedAlbumCoverUrl;
            }
            final EventHub eventHub = startedEventHub;
            ImageService imageService = new ImageService(serviceClient, businessExecutor, eventHub);
            RequestExecutor requests = new RequestExecutor(businessExecutor, mapper);
            Plugin plugin = new Plugin(
                    new ApiPluginHandler(requests,
                            new CatalogService(serviceClient, imageService, albumCoverUrl), imageService),
                    new AuthPluginHandler(requests, new AuthService(serviceClient)),
                    new SettingsPluginHandler(requests, settingsService),
                    new HistoryPluginHandler(requests, new HistoryService(database)),
                    new SystemPluginHandler());
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
                registerImageRoute(config, imageService, "image", "/image/{photoId}/{sortOrder}");
                registerImageRoute(config, imageService, "thumb", "/thumb/{photoId}/{sortOrder}");
            });
            candidate.start();
            int port = candidate.port();
            if (port <= 0) {
                throw new IllegalStateException("Javalin did not expose an operating-system port");
            }

            this.app = candidate;
            this.client = startedClient;
            this.eventHub = eventHub;
            this.homeUrl = URI.create("http://" + LOOPBACK_HOST + ":" + port + "/home");
            this.running = true;
            LOGGER.info("本地后端监听于 {}", homeUrl);
            return homeUrl;
        } catch (Exception | Error exception) {
            if (candidate != null) {
                try {
                    candidate.stop();
                } catch (RuntimeException ignored) {
                    // 保留原始启动异常。
                }
            }
            database.close();
            if (startedEventHub != null) startedEventHub.close();
            if (startedClient != null) startedClient.close();
            businessExecutor.shutdownNow();
            throw exception;
        }
    }

    private void registerImageRoute(
            io.javalin.config.JavalinConfig config,
            ImageService imageService,
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
            try {
                CompletableFuture<?> response = CompletableFuture.supplyAsync(
                        () -> imageService.read(photoId, finalSortOrder, type),
                        businessExecutor).handle((entry, failure) -> {
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

    private static ExecutorService createBusinessExecutor() {
        return new ThreadPoolExecutor(
                SettingsService.DEFAULT_CONCURRENCY,
                SettingsService.DEFAULT_CONCURRENCY,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                runnable -> {
                    Thread thread = new Thread(runnable, "jq-viewer-business");
                    thread.setDaemon(false);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private void configureBusinessExecutor(int concurrency) {
        if (!(businessExecutor instanceof ThreadPoolExecutor executor)) return;
        if (concurrency > executor.getMaximumPoolSize()) {
            executor.setMaximumPoolSize(concurrency);
            executor.setCorePoolSize(concurrency);
        } else {
            executor.setCorePoolSize(concurrency);
            executor.setMaximumPoolSize(concurrency);
        }
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

    public ExecutorService businessExecutor() {
        return businessExecutor;
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
        if (eventHub != null) {
            eventHub.close();
            eventHub = null;
        }
        if (current != null) {
            try {
                current.stop();
            } catch (RuntimeException exception) {
                LOGGER.warn("无法正常停止本地后端", exception);
            }
        }

        if (client != null) {
            client.close();
            client = null;
        }
        businessExecutor.shutdown();
        try {
            if (!businessExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                businessExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            businessExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        database.close();
    }
}
