package io.github.jukomu.desktop;

import io.github.jukomu.desktop.bridge.DesktopPlugin;
import io.github.jukomu.desktop.bridge.PluginMethod;
import io.github.jukomu.desktop.bridge.PluginMethodRoutes;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginMethodRoutesTest {
    @Test
    void annotationIsRuntimeRetainedOnDesktopPluginMethod() throws Exception {
        Method method = DesktopPlugin.class.getDeclaredMethod("getInitStatus", Context.class);

        assertTrue(method.isAnnotationPresent(PluginMethod.class));
        assertEquals(RetentionPolicy.RUNTIME, PluginMethod.class.getAnnotation(Retention.class).value());
        assertTrue(Modifier.isPublic(method.getModifiers()));
    }

    @Test
    void desktopPluginExposesOnlyTheImplementedMethodSurface() {
        Set<String> methods = java.util.Arrays.stream(DesktopPlugin.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PluginMethod.class))
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "getInitStatus",
                "search", "categories", "getAlbum", "getPhoto", "getComments",
                "login", "logout", "checkLoginState", "getUserProfile",
                "getAllSettings", "setPreloadConcurrency", "setDownloadConcurrency",
                "setReaderPreloadPages", "setReaderDisplayMode",
                "setReaderAutoShowToolbarAtEnd",
                "getBrowseHistory", "getBrowseHistoryOverview", "recordBrowse",
                "clearBrowseHistory", "deleteBrowseItem"
        ), methods);
    }

    @Test
    void discoversMethodNameAsApiRoute() {
        List<PluginMethodRoutes.DiscoveredMethod> methods = PluginMethodRoutes.discover(new ValidPlugin());

        assertEquals(1, methods.size());
        assertEquals("ping", methods.getFirst().method().getName());
        assertEquals("/api/ping", methods.getFirst().path());
    }

    @Test
    void rejectsInvalidSignaturesBeforeRegisteringRoutes() {
        assertThrows(IllegalArgumentException.class, () -> PluginMethodRoutes.discover(new InvalidPlugin()));
    }

    @Test
    void rejectsDuplicateRoutesAcrossPluginInstances() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PluginMethodRoutes.discover(List.of(new ValidPlugin(), new ValidPlugin()))
        );

        assertTrue(exception.getMessage().contains("/api/ping"));
    }

    @Test
    void onlyDeclaredMethodsAreDiscovered() {
        List<PluginMethodRoutes.DiscoveredMethod> methods = PluginMethodRoutes.discover(new ChildPlugin());

        assertEquals(0, methods.size());
    }

    @Test
    void rejectsStaticAndNonContextMethods() {
        IllegalArgumentException staticException = assertThrows(
                IllegalArgumentException.class,
                () -> PluginMethodRoutes.discover(new StaticPlugin())
        );
        IllegalArgumentException parameterException = assertThrows(
                IllegalArgumentException.class,
                () -> PluginMethodRoutes.discover(new ParameterPlugin())
        );

        assertInstanceOf(IllegalArgumentException.class, staticException);
        assertInstanceOf(IllegalArgumentException.class, parameterException);
    }

    static class ValidPlugin {
        @PluginMethod
        public void ping(Context context) {
            context.result("pong");
        }
    }

    static class InvalidPlugin {
        @PluginMethod
        public String ping(Context context) {
            return "invalid";
        }
    }

    static class StaticPlugin {
        @PluginMethod
        public static void ping(Context context) {
        }
    }

    static class ParameterPlugin {
        @PluginMethod
        public void ping(String value) {
        }
    }

    static class ParentPlugin {
        @PluginMethod
        public void inherited(Context context) {
        }
    }

    static class ChildPlugin extends ParentPlugin {
    }
}
