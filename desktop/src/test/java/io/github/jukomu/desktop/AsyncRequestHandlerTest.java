package io.github.jukomu.desktop;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.jukomu.desktop.bridge.handler.AsyncRequestHandler;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncRequestHandlerTest {
    @Test
    void runsBusinessWorkOffTheRequestThreadAndMapsFailures() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "desktop-contract-worker"));
        Javalin app = Javalin.create(config -> {
            config.jetty.host = "127.0.0.1";
            config.jetty.port = 0;
            config.routes.post("/ok", context -> AsyncRequestHandler.submitJson(
                    context,
                    worker,
                    () -> JsonNodeFactory.instance.objectNode()
                            .put("thread", Thread.currentThread().getName())
            ));
            config.routes.post("/invalid", context -> AsyncRequestHandler.submitJson(
                    context,
                    worker,
                    () -> {
                        throw new IllegalArgumentException("bad request");
                    }
            ));
            config.routes.post("/rejected", context -> AsyncRequestHandler.submitJson(
                    context,
                    command -> {
                        throw new RejectedExecutionException();
                    },
                    () -> JsonNodeFactory.instance.objectNode()
            ));
        }).start();

        try {
            URI base = URI.create("http://127.0.0.1:" + app.port());
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> ok = post(client, base.resolve("/ok"));
            HttpResponse<String> invalid = post(client, base.resolve("/invalid"));
            HttpResponse<String> rejected = post(client, base.resolve("/rejected"));

            assertEquals(200, ok.statusCode());
            assertTrue(ok.body().contains("desktop-contract-worker"));
            assertEquals(400, invalid.statusCode());
            assertTrue(invalid.body().contains("bad request"));
            assertEquals(503, rejected.statusCode());
            assertTrue(rejected.body().contains("业务线程池暂时不可用"));
        } finally {
            app.stop();
            worker.shutdownNow();
        }
    }

    private static HttpResponse<String> post(HttpClient client, URI uri) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }
}
