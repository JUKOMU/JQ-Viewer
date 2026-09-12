package io.github.jukomu.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.http.Context;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

/** 将阻塞业务调用放入有界 executor，并将结果转换为一次 HTTP 响应。 */
public final class RequestExecutor {
    private final Executor executor;
    private final ObjectMapper mapper;

    public RequestExecutor(Executor executor, ObjectMapper mapper) {
        this.executor = executor;
        this.mapper = mapper;
    }

    public void run(Context context, Function<ObjectNode, JsonNode> task) {
        ObjectNode request;
        try {
            request = Request.object(context, mapper);
        } catch (ApiException exception) {
            sendError(context, exception);
            return;
        }

        CompletableFuture<JsonNode> response;
        try {
            response = CompletableFuture.supplyAsync(() -> task.apply(request), executor);
        } catch (RejectedExecutionException exception) {
            sendError(context, new ApiException("internal", 503, "当前请求过多，请稍后重试"));
            return;
        }

        context.future(() -> response.handle((value, failure) -> {
            if (failure == null) {
                context.json(value);
            } else {
                sendError(context, unwrap(failure));
            }
            return null;
        }));
    }

    private void sendError(Context context, Throwable failure) {
        ApiException error = failure instanceof ApiException apiException
                ? apiException
                : new ApiException("internal", 500, messageOf(failure));
        ObjectNode body = mapper.createObjectNode();
        body.put("code", error.code());
        body.put("message", error.getMessage());
        context.status(error.status()).json(body);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String messageOf(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? "请求处理失败" : message;
    }
}
