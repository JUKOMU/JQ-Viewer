package io.github.jukomu.jqviewer.desktop.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class DesktopEventBroker {
    private final Gson gson = new Gson();
    private final Set<OutputStream> clients = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "desktop-event-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public DesktopEventBroker() {
        heartbeat.scheduleAtFixedRate(this::publishHeartbeat, 15, 15, TimeUnit.SECONDS);
    }

    public void open(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, 0);
        OutputStream output = exchange.getResponseBody();
        try {
            write(output, ": connected\n\n");
            clients.add(output);
        } catch (IOException e) {
            closeClient(output);
            throw e;
        }
    }

    public void publishDownloadProgress(JsonObject event) {
        publish("downloadProgress", event);
    }

    public void publishNetworkProbe(JsonObject event) {
        publish("networkProbe", event);
    }

    public void close() {
        heartbeat.shutdownNow();
        for (OutputStream client : clients) {
            closeClient(client);
        }
        clients.clear();
    }

    private void publish(String eventName, JsonObject data) {
        String payload = "event: " + eventName + "\n"
            + "data: " + gson.toJson(data) + "\n\n";
        for (OutputStream client : clients) {
            writeToClient(client, payload);
        }
    }

    private void publishHeartbeat() {
        for (OutputStream client : clients) {
            writeToClient(client, ": keepalive\n\n");
        }
    }

    private void writeToClient(OutputStream client, String payload) {
        try {
            write(client, payload);
        } catch (IOException e) {
            clients.remove(client);
            closeClient(client);
        }
    }

    private static void write(OutputStream output, String payload) throws IOException {
        output.write(payload.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void closeClient(OutputStream output) {
        try {
            output.close();
        } catch (IOException ignored) {
        }
    }
}
