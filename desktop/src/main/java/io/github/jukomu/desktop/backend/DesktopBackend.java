package io.github.jukomu.desktop.backend;

import io.github.jukomu.desktop.bridge.DesktopPlugin;
import io.github.jukomu.desktop.bridge.PluginMethodRoutes;
import io.github.jukomu.desktop.data.DesktopDatabase;
import io.github.jukomu.desktop.data.DesktopPaths;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Hosts the loopback Javalin server and Desktop-owned resources in one JVM. */
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

    private Javalin app;
    private URI homeUrl;
    private boolean running;
    private boolean closed;

    public DesktopBackend(DesktopPaths paths) {
        this(paths, new DesktopDatabase(paths), createBusinessExecutor());
    }

    public DesktopBackend(
            DesktopPaths paths,
            DesktopDatabase database,
            ExecutorService businessExecutor
    ) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.database = Objects.requireNonNull(database, "database");
        this.businessExecutor = Objects.requireNonNull(businessExecutor, "businessExecutor");
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
            DesktopPlugin plugin = new DesktopPlugin();
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
                    // Preserve the original startup failure.
                }
            }
            database.close();
            businessExecutor.shutdownNow();
            throw exception;
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
        database.close();
    }
}
