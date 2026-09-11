package io.github.jukomu.desktop.bridge.handler;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jukomu.desktop.feature.catalog.JmComicCatalogService;
import io.javalin.http.Context;

import java.util.Objects;
import java.util.concurrent.Executor;

/** 解析目录请求，并把阻塞的远程调用调度到业务线程池。 */
public final class CatalogPluginHandler {
    private final JmComicCatalogService catalog;
    private final Executor executor;

    public CatalogPluginHandler(JmComicCatalogService catalog, Executor executor) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public void search(Context context) {
        handle(context, body -> catalog.search(RequestJson.object(body, "query")));
    }

    public void categories(Context context) {
        handle(context, body -> catalog.categories(RequestJson.object(body, "query")));
    }

    public void getAlbum(Context context) {
        handle(context, body -> catalog.getAlbum(RequestJson.requiredText(body, "id")));
    }

    public void getPhoto(Context context) {
        handle(context, body -> catalog.getPhoto(RequestJson.requiredText(body, "id")));
    }

    public void getComments(Context context) {
        handle(context, body -> catalog.getComments(
                RequestJson.requiredText(body, "albumId"),
                RequestJson.integer(body, "page", 1)
        ));
    }

    private void handle(Context context, RequestOperation operation) {
        try {
            ObjectNode body = RequestJson.body(context);
            AsyncRequestHandler.submitJson(context, executor, () -> operation.call(body));
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }

    @FunctionalInterface
    private interface RequestOperation {
        ObjectNode call(ObjectNode body) throws Exception;
    }
}
