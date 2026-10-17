package io.github.jukomu.desktop.feature.notification;

import io.github.jukomu.desktop.bridge.EventHub;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 按顺序保存待消费的启动路由，并通过 SSE 通知已就绪的前端。
 */
public final class LaunchRouteService implements AutoCloseable {
    private final EventHub events;
    private final Object lifecycleLock = new Object();
    private final Deque<String> pendingRoutes = new ArrayDeque<>();
    private Consumer<String> routeOpener;
    private volatile boolean closed;

    public LaunchRouteService(EventHub events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    public void attachRouteOpener(Consumer<String> opener) {
        synchronized (lifecycleLock) {
            if (closed) return;
            routeOpener = Objects.requireNonNull(opener, "opener");
        }
    }

    public void detachRouteOpener() {
        synchronized (lifecycleLock) {
            routeOpener = null;
        }
    }

    public void activate(String route) {
        Consumer<String> opener;
        synchronized (lifecycleLock) {
            if (closed || !isSafeRoute(route)) return;
            if (!route.equals(pendingRoutes.peekLast())) {
                pendingRoutes.addLast(route);
            }
            opener = routeOpener;
        }
        events.publish("launchRoute", Map.of("route", route));
        if (opener != null) opener.accept(route);
    }

    public Map<String, String> consume() {
        synchronized (lifecycleLock) {
            String route = pendingRoutes.pollFirst();
            return route == null ? Map.of() : Map.of("route", route);
        }
    }

    static boolean isSafeRoute(String route) {
        return route != null && route.startsWith("/") && !route.startsWith("//");
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            closed = true;
            pendingRoutes.clear();
            routeOpener = null;
        }
    }
}
