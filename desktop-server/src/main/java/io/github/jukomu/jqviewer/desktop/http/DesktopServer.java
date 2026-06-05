package io.github.jukomu.jqviewer.desktop.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.jukomu.jqviewer.desktop.config.DesktopServerConfig;
import io.github.jukomu.jqviewer.desktop.service.DesktopDownloadService;
import io.github.jukomu.jqviewer.desktop.service.DesktopEventBroker;
import io.github.jukomu.jqviewer.desktop.service.DesktopImageCache;
import io.github.jukomu.jqviewer.desktop.service.DesktopJmcomicService;
import io.github.jukomu.jqviewer.desktop.store.DesktopDownloadStore;
import io.github.jukomu.jqviewer.desktop.store.DesktopHistoryStore;
import io.github.jukomu.jqviewer.desktop.store.DesktopSettingsStore;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DesktopServer {
    private final DesktopServerConfig config;
    private final Gson gson = new Gson();
    private final DesktopJmcomicService jmcomicService = new DesktopJmcomicService();
    private final DesktopSettingsStore settingsStore;
    private final DesktopHistoryStore historyStore;
    private final DesktopImageCache imageCache;
    private final DesktopDownloadStore downloadStore;
    private final DesktopEventBroker eventBroker;
    private final DesktopDownloadService downloadService;
    private final HttpServer server;
    private final ExecutorService executor;

    public DesktopServer(DesktopServerConfig config) throws IOException {
        this.config = config;
        Files.createDirectories(config.dataDir());
        Files.createDirectories(config.cacheDir());
        Files.createDirectories(config.logDir());
        Files.createDirectories(config.downloadDir());
        this.settingsStore = new DesktopSettingsStore(config.dataDir());
        this.historyStore = new DesktopHistoryStore(config.dataDir());
        this.imageCache = new DesktopImageCache(jmcomicService, intValue(settingsStore.getAll(), "cacheCapacityMb", 256));
        this.downloadStore = new DesktopDownloadStore(config.dataDir(), config.downloadDir());
        this.eventBroker = new DesktopEventBroker();
        this.downloadService = new DesktopDownloadService(
            jmcomicService,
            downloadStore,
            eventBroker,
            intValue(settingsStore.getAll(), "downloadConcurrency", 6)
        );
        this.server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), 0);
        this.executor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()));
        this.server.setExecutor(executor);
        this.server.createContext("/api", this::handleApi);
        this.server.createContext("/events", this::handleEvents);
        this.server.createContext("/image", this::handleImageResource);
        this.server.createContext("/thumb", this::handleImageResource);
        this.server.createContext("/", this::handleStaticFrontend);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        downloadService.stop();
        eventBroker.close();
        server.stop(0);
        executor.shutdownNow();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handleStaticFrontend(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            if (!method.equals("GET") && !method.equals("HEAD")) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            Path staticDir = config.staticDir();
            if (staticDir == null) {
                sendError(exchange, 404, "Desktop frontend static directory is not configured");
                return;
            }

            Path resource = resolveStaticResource(staticDir, exchange.getRequestURI().getPath());
            if (resource == null) {
                sendError(exchange, 404, "Static resource not found");
                return;
            }

            sendFile(exchange, resource, method.equals("HEAD"));
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage() == null ? "Internal server error" : e.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void handleApi(HttpExchange exchange) throws IOException {
        try {
            if (!isOriginAllowed(exchange)) {
                sendError(exchange, 403, "Origin is not allowed");
                return;
            }
            addCorsHeaders(exchange);

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendEmpty(exchange, 204);
                return;
            }

            if (!hasValidApiToken(exchange)) {
                sendError(exchange, 401, "Missing or invalid desktop token");
                return;
            }

            route(exchange);
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage() == null ? "Internal server error" : e.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void handleImageResource(HttpExchange exchange) throws IOException {
        try {
            if (!isOriginAllowed(exchange)) {
                sendError(exchange, 403, "Origin is not allowed");
                return;
            }
            addCorsHeaders(exchange);

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendEmpty(exchange, 204);
                return;
            }

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            if (!hasValidResourceToken(exchange)) {
                sendError(exchange, 401, "Missing or invalid desktop token");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String type;
            String prefix;
            if (path.startsWith("/thumb/")) {
                type = "thumb";
                prefix = "/thumb/";
            } else if (path.startsWith("/image/")) {
                type = "image";
                prefix = "/image/";
            } else {
                throw new IllegalArgumentException("Invalid image path");
            }
            String[] parts = imagePathParts(path, prefix);
            int sortOrder = Integer.parseInt(parts[1]);
            DesktopImageCache.ImageResource downloaded = downloadService.getDownloadedImage(parts[0], sortOrder);
            if (downloaded != null) {
                sendBytes(exchange, 200, downloaded.mimeType(), downloaded.data());
                return;
            }
            DesktopImageCache.ImageResource resource = imageCache.get(type, parts[0], sortOrder);
            sendBytes(exchange, 200, resource.mimeType(), resource.data());
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage() == null ? "Internal server error" : e.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        try {
            if (!isOriginAllowed(exchange)) {
                sendError(exchange, 403, "Origin is not allowed");
                exchange.close();
                return;
            }
            addCorsHeaders(exchange);

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendEmpty(exchange, 204);
                exchange.close();
                return;
            }

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                exchange.close();
                return;
            }

            if (!hasValidResourceToken(exchange)) {
                sendError(exchange, 401, "Missing or invalid desktop token");
                exchange.close();
                return;
            }

            eventBroker.open(exchange);
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
            exchange.close();
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage() == null ? "Internal server error" : e.getMessage());
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        String path = exchange.getRequestURI().getPath();

        if (method.equals("GET") && path.equals("/api/init-status")) {
            sendJson(exchange, 200, jmcomicService.getInitStatus());
        } else if (method.equals("POST") && path.equals("/api/search")) {
            sendJson(exchange, 200, jmcomicService.search(readJson(exchange)));
        } else if (method.equals("POST") && path.equals("/api/categories")) {
            sendJson(exchange, 200, jmcomicService.categories(readJson(exchange)));
        } else if (method.equals("GET") && path.startsWith("/api/albums/")) {
            sendJson(exchange, 200, jmcomicService.getAlbum(pathPart(path, "/api/albums/")));
        } else if (method.equals("GET") && path.startsWith("/api/photos/")) {
            sendJson(exchange, 200, jmcomicService.getPhoto(pathPart(path, "/api/photos/")));
        } else if (method.equals("GET") && path.equals("/api/comments")) {
            Map<String, String> query = queryParams(exchange.getRequestURI());
            String albumId = query.getOrDefault("albumId", "");
            int page = intParam(query, "page", 1);
            sendJson(exchange, 200, jmcomicService.getComments(albumId, page));
        } else if (method.equals("POST") && path.equals("/api/auth/login")) {
            sendJson(exchange, 200, jmcomicService.login(readJson(exchange)));
        } else if (method.equals("POST") && path.equals("/api/auth/logout")) {
            sendJson(exchange, 200, jmcomicService.logout());
        } else if (method.equals("GET") && path.equals("/api/auth/state")) {
            sendJson(exchange, 200, jmcomicService.authState());
        } else if (method.equals("GET") && path.startsWith("/api/users/")) {
            sendJson(exchange, 200, jmcomicService.getUserProfile(pathPart(path, "/api/users/")));
        } else if (method.equals("GET") && path.equals("/api/history/browse")) {
            Map<String, String> query = queryParams(exchange.getRequestURI());
            sendJson(exchange, 200, historyStore.getBrowseHistory(
                intParam(query, "limit", 50),
                intParam(query, "offset", 0)
            ));
        } else if (method.equals("POST") && path.equals("/api/history/browse")) {
            sendJson(exchange, 200, historyStore.recordBrowse(readJson(exchange)));
        } else if (method.equals("DELETE") && path.equals("/api/history/browse")) {
            sendJson(exchange, 200, historyStore.clearBrowseHistory());
        } else if (method.equals("DELETE") && path.startsWith("/api/history/browse/")) {
            int id = Integer.parseInt(pathPart(path, "/api/history/browse/"));
            sendJson(exchange, 200, historyStore.deleteBrowseItem(id));
        } else if (method.equals("GET") && path.equals("/api/settings")) {
            sendJson(exchange, 200, settingsStore.getAll());
        } else if (method.equals("POST") && path.equals("/api/settings")) {
            sendJson(exchange, 200, settingsStore.update(readJson(exchange)));
        } else if (method.equals("POST") && path.equals("/api/preload-images")) {
            sendJson(exchange, 200, imageCache.preload(readJson(exchange)));
        } else if (method.equals("GET") && path.equals("/api/cache/capacity")) {
            sendJson(exchange, 200, imageCache.capacityInfo());
        } else if (method.equals("POST") && path.equals("/api/cache/capacity")) {
            int mb = intValue(readJson(exchange), "mb", 256);
            imageCache.setCapacityMb(mb);
            int capacityMb = intValue(imageCache.capacityInfo(), "capacityMb", mb);
            JsonObject patch = new JsonObject();
            patch.addProperty("cacheCapacityMb", capacityMb);
            settingsStore.update(patch);
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.addProperty("capacityMb", capacityMb);
            sendJson(exchange, 200, result);
        } else if (method.equals("POST") && path.equals("/api/cache/clear")) {
            imageCache.clear();
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            sendJson(exchange, 200, result);
        } else if (method.equals("POST") && path.equals("/api/downloads")) {
            sendJson(exchange, 200, downloadService.startDownload(readJson(exchange)));
        } else if (method.equals("GET") && path.equals("/api/downloads")) {
            sendJson(exchange, 200, downloadService.getTasks());
        } else if (method.equals("POST") && path.startsWith("/api/downloads/") && path.endsWith("/pause")) {
            sendJson(exchange, 200, downloadService.pause(actionTaskId(path, "/pause")));
        } else if (method.equals("POST") && path.startsWith("/api/downloads/") && path.endsWith("/resume")) {
            sendJson(exchange, 200, downloadService.resume(actionTaskId(path, "/resume")));
        } else if (method.equals("POST") && path.startsWith("/api/downloads/") && path.endsWith("/cancel")) {
            sendJson(exchange, 200, downloadService.cancel(actionTaskId(path, "/cancel")));
        } else if (method.equals("DELETE") && path.startsWith("/api/downloads/")) {
            String[] parts = twoPathParts(path, "/api/downloads/");
            sendJson(exchange, 200, downloadService.deleteDownloaded(parts[0], parts[1]));
        } else if (method.equals("GET") && path.startsWith("/api/downloaded/")) {
            String[] parts = twoPathParts(path, "/api/downloaded/");
            sendJson(exchange, 200, downloadService.getDownloadedPhoto(parts[0], parts[1]));
        } else {
            sendError(exchange, 404, "Route not found");
        }
    }

    private JsonObject readJson(HttpExchange exchange) throws IOException {
        if (exchange.getRequestBody() == null) return new JsonObject();
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            JsonObject parsed = JsonParser.parseReader(reader).getAsJsonObject();
            return parsed == null ? new JsonObject() : parsed;
        } catch (IllegalStateException e) {
            return new JsonObject();
        }
    }

    private void sendJson(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        addCorsHeaders(exchange);
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        sendJson(exchange, status, body);
    }

    private void sendBytes(HttpExchange exchange, int status, String mimeType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", mimeType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void sendFile(HttpExchange exchange, Path file, boolean headOnly) throws IOException {
        String mimeType = mimeType(file);
        exchange.getResponseHeaders().set("Content-Type", mimeType);
        if (file.getFileName().toString().equals("index.html")) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
        }
        long length = Files.size(file);
        exchange.sendResponseHeaders(200, headOnly ? -1 : length);
        if (!headOnly) {
            try (OutputStream output = exchange.getResponseBody()) {
                Files.copy(file, output);
            }
        }
    }

    private void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private boolean hasValidApiToken(HttpExchange exchange) {
        String token = exchange.getRequestHeaders().getFirst("X-JQ-Desktop-Token");
        return config.token().equals(token);
    }

    private boolean hasValidResourceToken(HttpExchange exchange) {
        String headerToken = exchange.getRequestHeaders().getFirst("X-JQ-Desktop-Token");
        if (config.token().equals(headerToken)) return true;
        return config.token().equals(queryParams(exchange.getRequestURI()).get("token"));
    }

    private boolean isOriginAllowed(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        return origin == null
            || origin.isEmpty()
            || isSameLocalServerOrigin(origin)
            || config.allowedOrigins().contains(origin);
    }

    private void addCorsHeaders(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && (isSameLocalServerOrigin(origin) || config.allowedOrigins().contains(origin))) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().set("Vary", "Origin");
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type,X-JQ-Desktop-Token");
    }

    private static String pathPart(String path, String prefix) {
        String raw = path.substring(prefix.length());
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    private static String actionTaskId(String path, String suffix) {
        String raw = path.substring("/api/downloads/".length(), path.length() - suffix.length());
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    private static String[] twoPathParts(String path, String prefix) {
        if (!path.startsWith(prefix)) {
            throw new IllegalArgumentException("Invalid path");
        }
        String rest = path.substring(prefix.length());
        String[] raw = rest.split("/", -1);
        if (raw.length != 2 || raw[0].isEmpty() || raw[1].isEmpty()) {
            throw new IllegalArgumentException("Invalid path");
        }
        return new String[] {
            URLDecoder.decode(raw[0], StandardCharsets.UTF_8),
            URLDecoder.decode(raw[1], StandardCharsets.UTF_8)
        };
    }

    private static Map<String, String> queryParams(URI uri) {
        Map<String, String> params = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return params;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String key = eq >= 0 ? part.substring(0, eq) : part;
            String value = eq >= 0 ? part.substring(eq + 1) : "";
            params.put(
                URLDecoder.decode(key, StandardCharsets.UTF_8),
                URLDecoder.decode(value, StandardCharsets.UTF_8)
            );
        }
        return params;
    }

    private static int intParam(Map<String, String> params, String key, int fallback) {
        try {
            return Integer.parseInt(params.getOrDefault(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int intValue(JsonObject obj, String key, int fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private boolean isSameLocalServerOrigin(String origin) {
        int port = port();
        return origin.equals("http://127.0.0.1:" + port)
            || origin.equals("http://localhost:" + port);
    }

    private static String[] imagePathParts(String path, String prefix) {
        if (!path.startsWith(prefix)) {
            throw new IllegalArgumentException("Invalid image path");
        }
        String rest = path.substring(prefix.length());
        String[] raw = rest.split("/", -1);
        if (raw.length != 2 || raw[0].isEmpty() || raw[1].isEmpty()) {
            throw new IllegalArgumentException("Invalid image path");
        }
        return new String[] {
            URLDecoder.decode(raw[0], StandardCharsets.UTF_8),
            URLDecoder.decode(raw[1], StandardCharsets.UTF_8)
        };
    }

    private static Path resolveStaticResource(Path staticDir, String rawPath) {
        Path index = staticDir.resolve("index.html").normalize();
        String path = rawPath == null || rawPath.isBlank() ? "/" : rawPath;
        if (path.equals("/")) {
            return Files.isRegularFile(index) ? index : null;
        }

        String decoded = URLDecoder.decode(path, StandardCharsets.UTF_8);
        String relative = decoded.startsWith("/") ? decoded.substring(1) : decoded;
        Path candidate = staticDir.resolve(relative).normalize();
        if (!candidate.startsWith(staticDir)) {
            throw new IllegalArgumentException("Invalid static resource path");
        }
        if (Files.isDirectory(candidate)) {
            candidate = candidate.resolve("index.html").normalize();
        }
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }
        return hasFileExtension(relative) || !Files.isRegularFile(index) ? null : index;
    }

    private static boolean hasFileExtension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash;
    }

    private static String mimeType(Path file) throws IOException {
        String detected = Files.probeContentType(file);
        if (detected != null && !detected.isBlank()) return detected;

        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }
}
