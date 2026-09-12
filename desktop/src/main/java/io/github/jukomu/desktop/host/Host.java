package io.github.jukomu.desktop.host;

import io.github.jukomu.desktop.backend.Backend;
import io.github.jukomu.desktop.data.Paths;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 管理单进程生命周期及其操作系统入口。 */
public final class Host implements AutoCloseable {
    private final Backend backend;
    private final SingleInstanceGuard instanceGuard;
    private final BrowserLauncher browserLauncher;
    private final AtomicReference<URI> homeUrl = new AtomicReference<>();
    private final CountDownLatch backendReady = new CountDownLatch(1);

    private Tray tray;
    private boolean primary;
    private boolean started;
    private boolean closed;

    public Host(
            Backend backend,
            SingleInstanceGuard instanceGuard,
            BrowserLauncher browserLauncher
    ) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.instanceGuard = Objects.requireNonNull(instanceGuard, "instanceGuard");
        this.browserLauncher = Objects.requireNonNull(browserLauncher, "browserLauncher");
    }

    public static Host createDefault() {
        Paths paths = Paths.current();
        return new Host(
                new Backend(paths),
                new SingleInstanceGuard(paths),
                new BrowserLauncher()
        );
    }

    /** 启动主实例；已有实例存在时发送信号并返回 false。 */
    public synchronized boolean start() throws Exception {
        if (closed) {
            throw new IllegalStateException("本地主机已关闭");
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

            tray = Tray.tryCreate(this::openHome, this::close).orElse(null);
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
            throw new IllegalStateException("本地主机尚未启动");
        }
        return url;
    }

    public synchronized boolean isPrimary() {
        return primary;
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public Backend backend() {
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
