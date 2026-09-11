package io.github.jukomu.desktop.backend;

import io.github.jukomu.desktop.bridge.DesktopPlugin;
import io.github.jukomu.desktop.bridge.PluginMethodRoutes;
import io.github.jukomu.desktop.bridge.handler.ApiPluginHandler;
import io.github.jukomu.desktop.bridge.handler.AsyncRequestHandler;
import io.github.jukomu.desktop.bridge.handler.AuthPluginHandler;
import io.github.jukomu.desktop.bridge.handler.HistoryPluginHandler;
import io.github.jukomu.desktop.bridge.handler.SettingsPluginHandler;
import io.github.jukomu.desktop.bridge.handler.SystemPluginHandler;
import io.github.jukomu.desktop.data.DesktopDatabase;
import io.github.jukomu.desktop.data.DesktopHistoryStore;
import io.github.jukomu.desktop.data.DesktopPaths;
import io.github.jukomu.desktop.data.DesktopSettingsStore;
import io.github.jukomu.desktop.service.DesktopHistoryService;
import io.github.jukomu.desktop.service.DesktopCatalogService;
import io.github.jukomu.desktop.service.DesktopSettingsService;
import io.github.jukomu.desktop.service.JmComicAuthService;
import io.github.jukomu.desktop.service.JmComicCatalogService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.github.jukomu.jmcomic.core.JmComic;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import io.github.jukomu.jmcomic.core.config.JmConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 在同一 JVM 内承载 loopback Javalin 服务与 Desktop 资源。 */
public final class DesktopBackend implements AutoCloseable {
    public static final String LOOPBACK_HOST = "127.0.0.1";
    private static final Logger LOGGER = LoggerFactory.getLogger(DesktopBackend.class);
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

    private final DesktopPaths paths;
    private final DesktopDatabase database;
    private final ExecutorService businessExecutor;
    private final Supplier<?> pluginFactory;

    private Javalin app;
    private URI homeUrl;
    private JmApiClient client;
    private DesktopCatalogService catalogService;
    private boolean running;
    private boolean closed;

    public DesktopBackend(DesktopPaths paths) {
        this(paths, new DesktopDatabase(paths), createBusinessExecutor(), null);
    }

    /** 仅供测试注入固定 bridge，生产启动始终创建真实 JmApiClient。 */
    public DesktopBackend(DesktopPaths paths, Object plugin) {
        this(paths, new DesktopDatabase(paths), createBusinessExecutor(), () -> plugin);
    }

    /** 供 HTTP contract test 注入固定 bridge 和图片资源服务。 */
    public DesktopBackend(
            DesktopPaths paths,
            Object plugin,
            DesktopCatalogService catalogService
    ) {
        this(
                paths,
                new DesktopDatabase(paths),
                createBusinessExecutor(),
                () -> plugin,
                catalogService
        );
    }

    public DesktopBackend(
            DesktopPaths paths,
            DesktopDatabase database,
            ExecutorService businessExecutor
    ) {
        this(paths, database, businessExecutor, null);
    }

    public DesktopBackend(
            DesktopPaths paths,
            DesktopDatabase database,
            ExecutorService businessExecutor,
            Supplier<?> pluginFactory
    ) {
        this(paths, database, businessExecutor, pluginFactory, null);
    }

    public DesktopBackend(
            DesktopPaths paths,
            DesktopDatabase database,
            ExecutorService businessExecutor,
            Supplier<?> pluginFactory,
            DesktopCatalogService catalogService
    ) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.database = Objects.requireNonNull(database, "database");
        this.businessExecutor = Objects.requireNonNull(businessExecutor, "businessExecutor");
        this.pluginFactory = pluginFactory;
        this.catalogService = catalogService;
    }

    public synchronized URI start() throws Exception {
        if (closed) {
            throw new IllegalStateException("Desktop backend is closed");
        }
        if (running) {
            return homeUrl;
        }

        Javalin candidate = null;
        try {
            paths.ensureDirectories();
            if (DesktopBackend.class.getResource("/static/index.html") == null) {
                throw new IllegalStateException(
                        "Desktop static resources are missing; run npm run desktop:sync first"
                );
            }

            database.open();
            Object plugin = createPlugin();
            DesktopCatalogService resources = catalogService;
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
                if (resources != null) {
                    config.routes.get(
                            "/image/{photoId}/{sortOrder}",
                            context -> serveImage(context, resources, "image")
                    );
                    config.routes.get(
                            "/thumb/{photoId}/{sortOrder}",
                            context -> serveImage(context, resources, "thumb")
                    );
                }
            });
            candidate.start();
            int port = candidate.port();
            if (port <= 0) {
                throw new IllegalStateException("Javalin did not expose an operating-system port");
            }

            this.app = candidate;
            this.homeUrl = URI.create("http://" + LOOPBACK_HOST + ":" + port + "/home");
            this.running = true;
            LOGGER.info("Desktop backend listening at {}", homeUrl);
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
            businessExecutor.shutdownNow();
            closeClient();
            throw exception;
        }
    }

    private Object createPlugin() {
        if (pluginFactory != null) {
            Object plugin = pluginFactory.get();
            if (plugin == null) {
                throw new IllegalStateException("Desktop plugin factory returned null");
            }
            return plugin;
        }

        client = JmComic.newApiClient(new JmConfiguration.Builder().build());
        catalogService = new JmComicCatalogService(client);
        DesktopSettingsService settingsService = new DesktopSettingsService(
                new DesktopSettingsStore(database)
        );
        DesktopHistoryService historyService = new DesktopHistoryService(
                new DesktopHistoryStore(database)
        );
        return new DesktopPlugin(
                new SystemPluginHandler(() -> client != null),
                new ApiPluginHandler(catalogService, businessExecutor),
                new AuthPluginHandler(new JmComicAuthService(client), businessExecutor),
                new HistoryPluginHandler(historyService, businessExecutor),
                new SettingsPluginHandler(settingsService, businessExecutor)
        );
    }

    private void serveImage(Context context, DesktopCatalogService resources, String type) {
        try {
            String photoId = context.pathParam("photoId");
            int sortOrder = Integer.parseInt(context.pathParam("sortOrder"));
            AsyncRequestHandler.submit(
                    context,
                    businessExecutor,
                    () -> resources.getImage(photoId, sortOrder, type),
                    resource -> context.contentType(resource.mediaType()).result(resource.bytes())
            );
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }

    private static ExecutorService createBusinessExecutor() {
        return new ThreadPoolExecutor(
                2,
                8,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                runnable -> {
                    Thread thread = new Thread(runnable, "jq-viewer-desktop-business");
                    thread.setDaemon(false);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
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

    public DesktopDatabase database() {
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
        if (current != null) {
            try {
                current.stop();
            } catch (RuntimeException exception) {
                LOGGER.warn("Unable to stop Desktop backend cleanly", exception);
            }
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
        closeClient();
        database.close();
    }

    private void closeClient() {
        JmApiClient current = client;
        client = null;
        catalogService = null;
        if (current != null) {
            current.close();
        }
    }
}
