package io.github.jukomu.jqviewer.desktop.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class DesktopEventBroker {
    private final Gson gson = new Gson();
    private final Set<OutputStream> clients = ConcurrentHashMap.newKeySet();

    public void open(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, 0);
        OutputStream output = exchange.getResponseBody();
        clients.add(output);
        write(output, ": connected\n\n");
    }

    public void publishDownloadProgress(JsonObject event) {
        publish("downloadProgress", event);
    }

    public void close() {
        for (OutputStream client : clients) {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
        clients.clear();
    }

    private void publish(String eventName, JsonObject data) {
        String payload = "event: " + eventName + "\n"
            + "data: " + gson.toJson(data) + "\n\n";
        for (OutputStream client : clients) {
            try {
                write(client, payload);
            } catch (IOException e) {
                clients.remove(client);
                try {
                    client.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void write(OutputStream output, String payload) throws IOException {
        output.write(payload.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
}
