package io.github.jukomu.desktop;

import io.github.jukomu.desktop.backend.Backend;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.settings.SettingsService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendHttpTest {
    @Test
    void servesSpaAssetsAndRegisteredJsonMethods() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-backend-");
        Paths paths = new Paths(
                root.resolve("program"),
                root.resolve("home"),
                Map.of(),
                "Linux"
        );
        HttpClient client = HttpClient.newHttpClient();

        Backend backend = new Backend(paths);
        backend.database().open();
        new SettingsService(backend.database()).setConcurrency("preload_concurrency", 4);
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
            HttpResponse<String> settingsResponse = send(client, post(base, "/api/getAllSettings", "{}"));
            HttpResponse<String> historyResponse = send(client, post(
                    base, "/api/getBrowseHistory", "{\"limit\":10,\"offset\":0}"));
            HttpResponse<String> malformedResponse = send(client, post(base, "/api/getAllSettings", "[]"));
            HttpResponse<String> unsupportedResponse = send(client, post(base, "/api/autoLogin", "{}"));
            HttpResponse<String> invalidSettingResponse = send(client, post(
                    base, "/api/setReaderPreloadPages", "{\"n\":1}"));
            HttpResponse<String> invalidImageResponse = send(client, HttpRequest.newBuilder(
                    base.resolve("/image/photo/not-a-number")
            ).GET().build());

            assertEquals("127.0.0.1", backend.host());
            assertTrue(backend.port() > 0);
            assertTrue(backend.isRunning());
            assertTrue(backend.database().isOpen());
            assertEquals(4, ((ThreadPoolExecutor) backend.businessExecutor()).getCorePoolSize());
            assertEquals(200, homeResponse.statusCode());
            assertTrue(homeResponse.body().contains(">test<"));
            assertEquals(200, refreshResponse.statusCode());
            assertEquals(200, assetResponse.statusCode());
            assertTrue(assetResponse.body().contains("window.__testAsset = true"));
            assertEquals(200, initResponse.statusCode());
            assertEquals("{\"complete\":true}", initResponse.body());
            assertEquals(404, missingApiResponse.statusCode());
            assertEquals(200, settingsResponse.statusCode());
            assertTrue(settingsResponse.body().contains("\"readerPreloadPages\""));
            assertTrue(settingsResponse.body().contains("\"preloadConcurrency\":4"));
            assertEquals(200, historyResponse.statusCode());
            assertTrue(historyResponse.body().contains("\"totalCount\":0"));
            assertEquals(400, malformedResponse.statusCode());
            assertTrue(malformedResponse.body().contains("\"code\":\"bad-request\""));
            assertEquals(404, unsupportedResponse.statusCode());
            assertEquals(400, invalidSettingResponse.statusCode());
            assertTrue(invalidSettingResponse.body().contains("\"code\":\"bad-request\""));
            assertEquals(400, invalidImageResponse.statusCode());
            assertTrue(invalidImageResponse.body().contains("\"code\":\"bad-request\""));
        }

        assertFalse(paths.dataDirectory().equals(paths.programDirectory()));
        assertThrows(
                IOException.class,
                () -> send(client, HttpRequest.newBuilder(base.resolve("/home")).GET().build())
        );
        assertTrue(backend.businessExecutor().isShutdown());
    }

    private static HttpResponse<String> send(
            HttpClient client,
            HttpRequest request
    ) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest post(URI base, String path, String body) {
        return HttpRequest.newBuilder(base.resolve(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }
}
