package io.github.jukomu.desktop.host;

import io.github.jukomu.desktop.backend.DesktopBackend;
import io.github.jukomu.desktop.data.DesktopPaths;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 管理 Desktop 单进程生命周期及其操作系统入口。 */
public final class DesktopHost implements AutoCloseable {
    private final DesktopBackend backend;
    private final SingleInstanceGuard instanceGuard;
    private final BrowserLauncher browserLauncher;
    private final AtomicReference<URI> homeUrl = new AtomicReference<>();
    private final CountDownLatch backendReady = new CountDownLatch(1);

    private DesktopTray tray;
    private boolean primary;
    private boolean started;
    private boolean closed;

    public DesktopHost(
            DesktopBackend backend,
            SingleInstanceGuard instanceGuard,
            BrowserLauncher browserLauncher
    ) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.instanceGuard = Objects.requireNonNull(instanceGuard, "instanceGuard");
        this.browserLauncher = Objects.requireNonNull(browserLauncher, "browserLauncher");
    }

    public static DesktopHost createDefault() {
        DesktopPaths paths = DesktopPaths.current();
        return new DesktopHost(
                new DesktopBackend(paths),
                new SingleInstanceGuard(paths),
                new BrowserLauncher()
        );
    }

    /** 启动主实例；已有实例存在时发送信号并返回 false。 */
    public synchronized boolean start() throws Exception {
        if (closed) {
            throw new IllegalStateException("Desktop host is closed");
        }
        if (started) {
            return primary;
        }

        if (!instanceGuard.tryAcquire(this::openHomeWhenReady)) {
            instanceGuard.notifyExistingInstance();
            return false;
        }

        primary = true;
        try {
            URI startedHomeUrl = backend.start();
            homeUrl.set(startedHomeUrl);
            backendReady.countDown();

            tray = DesktopTray.tryCreate(this::openHome, this::close).orElse(null);
            openHome();
            started = true;
            return true;
        } catch (Exception | Error exception) {
            close();
            throw exception;
        }
    }

    private void openHomeWhenReady() {
        try {
            backendReady.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        openHome();
    }

    private void openHome() {
        URI url = homeUrl.get();
        if (url != null) {
            browserLauncher.open(url);
        }
    }

    public synchronized URI homeUrl() {
        URI url = homeUrl.get();
        if (url == null) {
            throw new IllegalStateException("Desktop host has not started");
        }
        return url;
    }

    public synchronized boolean isPrimary() {
        return primary;
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public DesktopBackend backend() {
        return backend;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        backendReady.countDown();
        try {
            if (tray != null) {
                tray.close();
                tray = null;
            }
        } finally {
            try {
                backend.close();
            } finally {
                instanceGuard.close();
            }
        }
        homeUrl.set(null);
        primary = false;
        started = false;
    }
}
