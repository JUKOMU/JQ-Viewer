package io.github.jukomu.jqviewer.desktop.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.jukomu.jqviewer.desktop.config.DesktopServerConfig;
import io.github.jukomu.jqviewer.desktop.service.DesktopJmcomicService;
import io.github.jukomu.jqviewer.desktop.store.DesktopHistoryStore;
import io.github.jukomu.jqviewer.desktop.store.DesktopSettingsStore;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DesktopServer {
    private final DesktopServerConfig config;
    private final Gson gson = new Gson();
    private final DesktopJmcomicService jmcomicService = new DesktopJmcomicService();
    private final DesktopSettingsStore settingsStore;
    private final DesktopHistoryStore historyStore;
    private final HttpServer server;
    private final ExecutorService executor;

    public DesktopServer(DesktopServerConfig config) throws IOException {
        this.config = config;
        this.settingsStore = new DesktopSettingsStore(config.dataDir());
        this.historyStore = new DesktopHistoryStore(config.dataDir());
        this.server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), 0);
        this.executor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()));
        this.server.setExecutor(executor);
        this.server.createContext("/api", this::handleApi);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    public int port() {
        return server.getAddress().getPort();
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

            if (!hasValidToken(exchange)) {
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

    private void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private boolean hasValidToken(HttpExchange exchange) {
        String token = exchange.getRequestHeaders().getFirst("X-JQ-Desktop-Token");
        return config.token().equals(token);
    }

    private boolean isOriginAllowed(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        return origin == null || origin.isEmpty() || config.allowedOrigins().contains(origin);
    }

    private void addCorsHeaders(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && config.allowedOrigins().contains(origin)) {
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
}
