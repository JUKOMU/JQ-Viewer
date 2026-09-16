package io.github.jukomu.desktop.feature.notification;

import io.github.jukomu.desktop.bridge.EventHub;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 保存一次性启动路由，并通过 SSE 通知已就绪的前端。 */
public final class LaunchRouteService implements AutoCloseable {
    private final EventHub events;
    private final AtomicReference<String> pendingRoute = new AtomicReference<>();
    private final AtomicReference<Consumer<String>> routeOpener = new AtomicReference<>();
    private volatile boolean closed;

    public LaunchRouteService(EventHub events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    public void attachRouteOpener(Consumer<String> opener) {
        if (closed) return;
        routeOpener.set(Objects.requireNonNull(opener, "opener"));
    }

    public void detachRouteOpener() {
        routeOpener.set(null);
    }

    public void activate(String route) {
        if (closed || !isSafeRoute(route)) return;
        pendingRoute.set(route);
        events.publish("launchRoute", Map.of("route", route));
        Consumer<String> opener = routeOpener.get();
        if (opener != null) opener.accept(route);
    }

    public Map<String, String> consume() {
        String route = pendingRoute.getAndSet(null);
        return route == null ? Map.of() : Map.of("route", route);
    }

    static boolean isSafeRoute(String route) {
        return route != null && route.startsWith("/") && !route.startsWith("//");
    }

    @Override
    public void close() {
        closed = true;
        pendingRoute.set(null);
        routeOpener.set(null);
    }
}
