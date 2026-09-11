package io.github.jukomu.desktop.backend;

import io.github.jukomu.desktop.data.DesktopDatabase;
import io.github.jukomu.desktop.data.DesktopPaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopBackendHttpTest {
    @Test
    void servesSpaAssetsAndOnlyTheRegisteredInitMethod() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-backend-");
        DesktopPaths paths = new DesktopPaths(
                root.resolve("program"),
                root.resolve("home"),
                Map.of(),
                "Linux"
        );
        HttpClient client = HttpClient.newHttpClient();

        DesktopBackend backend = testBackend(paths);
        URI base;
        try (backend) {
            URI home = backend.start();
            base = URI.create("http://127.0.0.1:" + backend.port());

            HttpResponse<String> homeResponse = send(client, HttpRequest.newBuilder(home)
                    .header("Accept", "text/html")
                    .GET()
                    .build());
            HttpResponse<String> refreshResponse = send(client, HttpRequest.newBuilder(
                    base.resolve("/category")
            ).header("Accept", "text/html").GET().build());
            HttpResponse<String> assetResponse = send(client, HttpRequest.newBuilder(
                    base.resolve("/assets/test.js")
            ).GET().build());
            HttpResponse<String> initResponse = send(client, HttpRequest.newBuilder(
                    base.resolve("/api/getInitStatus")
            ).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build());
            HttpResponse<String> missingApiResponse = send(client, HttpRequest.newBuilder(
                    base.resolve("/api/notRegistered")
            ).header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build());

            assertEquals("127.0.0.1", backend.host());
            assertTrue(backend.port() > 0);
            assertTrue(backend.isRunning());
            assertTrue(backend.database().isOpen());
            assertEquals(200, homeResponse.statusCode());
            assertTrue(homeResponse.body().contains("desktop-test"));
            assertEquals(200, refreshResponse.statusCode());
            assertEquals(200, assetResponse.statusCode());
            assertTrue(assetResponse.body().contains("window.__desktopTestAsset = true"));
            assertEquals(200, initResponse.statusCode());
            assertEquals("{\"complete\":true}", initResponse.body());
            assertEquals(404, missingApiResponse.statusCode());
        }

        assertFalse(paths.dataDirectory().equals(paths.programDirectory()));
        assertThrows(
                IOException.class,
                () -> send(client, HttpRequest.newBuilder(base.resolve("/home")).GET().build())
        );
        assertTrue(backend.businessExecutor().isShutdown());
    }

    static DesktopBackend testBackend(DesktopPaths paths) {
        return new DesktopBackend(
                paths,
                new DesktopDatabase(paths),
                Executors.newSingleThreadExecutor(),
                new InitOnlyPlugin()
        );
    }

    private static HttpResponse<String> send(
            HttpClient client,
            HttpRequest request
    ) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
