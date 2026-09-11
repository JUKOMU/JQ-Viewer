package io.github.jukomu.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jukomu.desktop.backend.DesktopBackend;
import io.github.jukomu.desktop.bridge.DesktopPlugin;
import io.github.jukomu.desktop.bridge.handler.ApiPluginHandler;
import io.github.jukomu.desktop.bridge.handler.AuthPluginHandler;
import io.github.jukomu.desktop.bridge.handler.HistoryPluginHandler;
import io.github.jukomu.desktop.bridge.handler.SettingsPluginHandler;
import io.github.jukomu.desktop.bridge.handler.SystemPluginHandler;
import io.github.jukomu.desktop.data.DesktopDatabase;
import io.github.jukomu.desktop.data.DesktopHistoryStore;
import io.github.jukomu.desktop.data.DesktopPaths;
import io.github.jukomu.desktop.data.DesktopSettingsStore;
import io.github.jukomu.desktop.dto.DesktopDtos;
import io.github.jukomu.desktop.error.DesktopHttpException;
import io.github.jukomu.desktop.service.DesktopAuthService;
import io.github.jukomu.desktop.service.DesktopCatalogService;
import io.github.jukomu.desktop.service.DesktopHistoryService;
import io.github.jukomu.desktop.service.DesktopSettingsService;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopStage2HttpTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void servesStage2ContractsAsynchronousResultsAndImageContentType() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-stage2-http-");
        DesktopPaths paths = paths(root);
        AtomicReference<String> catalogThread = new AtomicReference<>();
        FakeCatalogService catalog = new FakeCatalogService(catalogThread);
        ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "jq-viewer-desktop-business-test");
            thread.setDaemon(true);
            return thread;
        });
        DesktopDatabase database = new DesktopDatabase(paths);
        DesktopPlugin plugin = plugin(catalog, database, executor);
        DesktopBackend backend = new DesktopBackend(
                paths,
                database,
                executor,
                () -> plugin,
                catalog
        );
        HttpClient client = HttpClient.newHttpClient();

        try (backend) {
            URI home = backend.start();
            URI base = URI.create("http://127.0.0.1:" + backend.port());

            HttpResponse<String> init = sendJson(client, base.resolve("/api/getInitStatus"), "{}");
            HttpResponse<String> search = sendJson(client, base.resolve("/api/search"), """
                    {"query":{"keyword":"猫","orderBy":"mr","time":"a","searchMainTag":0,"page":2}}
                    """);
            HttpResponse<String> photo = sendJson(client, base.resolve("/api/getPhoto"), "{\"id\":\"photo-1\"}");
            HttpResponse<String> loginState = sendJson(
                    client,
                    base.resolve("/api/checkLoginState"),
                    "{}"
            );
            HttpResponse<String> settings = sendJson(
                    client,
                    base.resolve("/api/getAllSettings"),
                    "{}"
            );
            HttpResponse<String> historyWrite = sendJson(
                    client,
                    base.resolve("/api/recordBrowse"),
                    """
                    {"albumId":"album-1","albumTitle":"测试本子","coverUrl":"/cover.jpg","authors":"作者","chapterId":"photo-1","chapterTitle":"测试章节"}
                    """
            );
            HttpResponse<String> historyRead = sendJson(
                    client,
                    base.resolve("/api/getBrowseHistory"),
                    "{\"limit\":10,\"offset\":0}"
            );
            HttpResponse<byte[]> image = client.send(
                    HttpRequest.newBuilder(base.resolve("/image/photo-1/1")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()
            );

            assertEquals(200, init.statusCode());
            assertEquals("{\"complete\":true}", init.body());
            assertEquals(200, search.statusCode());
            assertTrue(search.body().contains("\"currentPage\":2"));
            assertEquals(200, photo.statusCode());
            assertTrue(photo.body().contains("\"photo-1\""));
            assertEquals(200, loginState.statusCode());
            assertTrue(loginState.body().contains("\"loggedIn\":false"));
            assertEquals(200, settings.statusCode());
            assertTrue(settings.body().contains("\"readerPreloadPages\":15"));
            assertEquals(200, historyWrite.statusCode());
            assertEquals("{\"success\":true}", historyWrite.body());
            assertEquals(200, historyRead.statusCode());
            assertTrue(historyRead.body().contains("\"totalCount\":1"));
            assertEquals(200, image.statusCode());
            assertEquals("image/png", image.headers().firstValue("content-type").orElse(""));
            assertEquals(2, image.body().length);
            assertTrue(catalogThread.get().startsWith("jq-viewer-desktop-business-test"));
            assertTrue(backend.database().isOpen());
        }

        assertTrue(backend.businessExecutor().isShutdown());
    }

    @Test
    void returnsStructuredBusinessErrorsAndLeavesUnregisteredMethodsUnmatched() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-stage2-errors-");
        DesktopPaths paths = paths(root);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        DesktopDatabase database = new DesktopDatabase(paths);
        ErrorCatalogService errorCatalog = new ErrorCatalogService();
        DesktopPlugin plugin = plugin(errorCatalog, database, executor);
        DesktopBackend backend = new DesktopBackend(
                paths,
                database,
                executor,
                () -> plugin,
                errorCatalog
        );
        HttpClient client = HttpClient.newHttpClient();

        try (backend) {
            URI base = URI.create("http://127.0.0.1:" + backend.start().getPort());
            HttpResponse<String> missing = sendJson(
                    client,
                    base.resolve("/api/getAlbum"),
                    "{\"id\":\"missing\"}"
            );
            HttpResponse<String> autoLogin = sendJson(
                    client,
                    base.resolve("/api/autoLogin"),
                    "{}"
            );

            assertEquals(404, missing.statusCode());
            assertEquals("not-found", mapper.readTree(missing.body()).get("code").asText());
            assertEquals(404, autoLogin.statusCode());
        }
    }

    private static DesktopPlugin plugin(
            DesktopCatalogService catalog,
            DesktopDatabase database,
            ExecutorService executor
    ) {
        DesktopHistoryService history = new DesktopHistoryService(new DesktopHistoryStore(database));
        DesktopSettingsService settings = new DesktopSettingsService(new DesktopSettingsStore(database));
        return new DesktopPlugin(
                new SystemPluginHandler(),
                new ApiPluginHandler(catalog, executor),
                new AuthPluginHandler(new FakeAuthService(), executor),
                new HistoryPluginHandler(history, executor),
                new SettingsPluginHandler(settings, executor)
        );
    }

    private static DesktopPaths paths(Path root) {
        return new DesktopPaths(
                root.resolve("program"),
                root.resolve("home"),
                Map.of(),
                "Linux"
        );
    }

    private static HttpResponse<String> sendJson(
            HttpClient client,
            URI uri,
            String body
    ) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
    }

    private static class FakeCatalogService implements DesktopCatalogService {
        private final AtomicReference<String> catalogThread;

        private FakeCatalogService(AtomicReference<String> catalogThread) {
            this.catalogThread = catalogThread;
        }

        @Override
        public DesktopDtos.SearchResult search(DesktopDtos.SearchQuery query) {
            catalogThread.set(Thread.currentThread().getName());
            return new DesktopDtos.SearchResult(
                    query.page() == null ? 1 : query.page(),
                    1,
                    1,
                    List.of(new DesktopDtos.SearchResultItem(
                            "album-1", "测试本子", "/cover.jpg", List.of("作者"), List.of("标签")
                    ))
            );
        }

        @Override
        public DesktopDtos.SearchResult categories(DesktopDtos.SearchQuery query) {
            return search(query);
        }

        @Override
        public DesktopDtos.AlbumDetail getAlbum(String id) {
            return new DesktopDtos.AlbumDetail(
                    id, "测试本子", "", "", 1, "0", "0", 0, "/cover.jpg",
                    null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    "0", true, false, false, "", ""
            );
        }

        @Override
        public DesktopDtos.PhotoDetail getPhoto(String id) {
            return new DesktopDtos.PhotoDetail(
                    id,
                    "测试章节",
                    "album-1",
                    1,
                    "作者",
                    List.of(),
                    List.of(new DesktopDtos.ImageInfo(id, "scramble", "page.png", "", "", 1)),
                    true
            );
        }

        @Override
        public DesktopDtos.CommentList getComments(String albumId, int page) {
            return new DesktopDtos.CommentList(0, List.of());
        }

        @Override
        public DesktopDtos.ImageResource getImage(String photoId, int sortOrder, String type) {
            return new DesktopDtos.ImageResource(new byte[]{1, 2}, "image/png");
        }
    }

    private static final class ErrorCatalogService extends FakeCatalogService {
        private ErrorCatalogService() {
            super(new AtomicReference<>());
        }

        @Override
        public DesktopDtos.AlbumDetail getAlbum(String id) {
            throw new DesktopHttpException("not-found", 404, "测试资源不存在");
        }
    }

    private static final class FakeAuthService implements DesktopAuthService {
        @Override
        public DesktopDtos.UserInfo login(String username, String password) {
            return new DesktopDtos.UserInfo(
                    "1", username, "", false, "", "", "", "", 0, "", 0, 0, 0, 0, 0, 0
            );
        }

        @Override
        public DesktopDtos.Success logout() {
            return new DesktopDtos.Success(true);
        }

        @Override
        public DesktopDtos.LoginState checkLoginState() {
            return new DesktopDtos.LoginState(false, null, null);
        }

        @Override
        public DesktopDtos.UserProfile getUserProfile(String uid) {
            return new DesktopDtos.UserProfile("", "", "", "", "", "", "", "", "");
        }
    }
}
