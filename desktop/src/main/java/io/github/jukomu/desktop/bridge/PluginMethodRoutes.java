package io.github.jukomu.desktop.bridge;

import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 发现显式 bridge 方法，并将其映射为 POST 路由。 */
public final class PluginMethodRoutes {
    private PluginMethodRoutes() {
    }

    public static void register(JavalinDefaultRoutingApi routes, Object plugin) {
        List<DiscoveredMethod> methods = discover(plugin);
        for (DiscoveredMethod discovered : methods) {
            routes.post(discovered.path(), context -> invoke(discovered, context));
        }
    }

    public static List<DiscoveredMethod> discover(Object plugin) {
        List<DiscoveredMethod> discovered = new ArrayList<>();
        Set<String> routes = new HashSet<>();

        if (plugin == null) {
            throw new IllegalArgumentException("Plugin instance must not be null");
        }

        for (Method method : plugin.getClass().getDeclaredMethods()) {
            if (!method.isAnnotationPresent(PluginMethod.class)) {
                continue;
            }
            validate(method);
            String path = "/api/" + method.getName();
            if (!routes.add(path)) {
                throw new IllegalArgumentException("Duplicate plugin route: " + path);
            }
            discovered.add(new DiscoveredMethod(plugin, method, path));
        }

        return List.copyOf(discovered);
    }

    private static void validate(Method method) {
        if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException(
                    "@PluginMethod must be a public instance method: " + method
            );
        }
        if (method.getReturnType() != void.class
                || method.getParameterCount() != 1
                || method.getParameterTypes()[0] != Context.class) {
            throw new IllegalArgumentException(
                    "@PluginMethod must have signature void method(Context): " + method
            );
        }
    }

    private static void invoke(DiscoveredMethod discovered, Context context) throws Exception {
        try {
            discovered.method().invoke(discovered.plugin(), context);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checkedException) {
                throw checkedException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }

    public record DiscoveredMethod(Object plugin, Method method, String path) {
    }
}
