package io.github.jukomu.desktop.bridge.handler;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jukomu.desktop.error.DesktopHttpException;
import io.github.jukomu.jmcomic.api.exception.JmComicException;
import io.github.jukomu.jmcomic.api.exception.NetworkException;
import io.github.jukomu.jmcomic.api.exception.ResourceNotFoundException;
import io.github.jukomu.jmcomic.api.exception.ResponseException;
import io.javalin.http.Context;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 在业务线程池执行阻塞调用，并保证每个请求只写入一次响应。 */
public final class AsyncRequestHandler {
    private AsyncRequestHandler() {
    }

    public static <T> void submitJson(Context context, Executor executor, Callable<T> operation) {
        submit(context, executor, operation, context::json);
    }

    public static <T> void submit(
            Context context,
            Executor executor,
            Callable<T> operation,
            Consumer<T> writer
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(writer, "writer");

        AtomicBoolean responded = new AtomicBoolean();
        CompletableFuture<Void> response;
        try {
            response = CompletableFuture.supplyAsync(() -> call(operation), executor)
                    .thenAccept(result -> respondOnce(responded, () -> writer.accept(result)))
                    .exceptionally(error -> {
                        respondOnce(responded, () -> writeError(context, error));
                        return null;
                    });
        } catch (RejectedExecutionException error) {
            writeError(context, error);
            return;
        }
        context.future(() -> response);
    }

    public static void writeError(Context context, Throwable error) {
        DesktopHttpException mapped = mapError(unwrap(error));
        ObjectNode body = JsonNodeFactory.instance.objectNode()
                .put("code", mapped.code())
                .put("message", mapped.getMessage());
        context.status(mapped.status()).json(body);
    }

    private static <T> T call(Callable<T> operation) {
        try {
            return operation.call();
        } catch (Exception error) {
            throw new CompletionException(error);
        }
    }

    private static void respondOnce(AtomicBoolean responded, Runnable response) {
        if (responded.compareAndSet(false, true)) response.run();
    }

    private static DesktopHttpException mapError(Throwable error) {
        if (error instanceof DesktopHttpException desktopError) return desktopError;
        if (error instanceof ResourceNotFoundException) {
            return new DesktopHttpException("not-found", 404, message(error));
        }
        if (error instanceof NetworkException) {
            return new DesktopHttpException("network", 502, message(error));
        }
        if (error instanceof ResponseException || error instanceof JmComicException) {
            return new DesktopHttpException("internal", 502, message(error));
        }
        if (error instanceof IllegalArgumentException) {
            return new DesktopHttpException("internal", 400, message(error));
        }
        if (error instanceof RejectedExecutionException) {
            return new DesktopHttpException("internal", 503, "Desktop 业务线程池暂时不可用");
        }
        if (error instanceof java.util.concurrent.CancellationException
                || error instanceof InterruptedException) {
            return new DesktopHttpException("cancelled", 499, "Desktop 请求已取消");
        }
        return new DesktopHttpException("internal", 500, message(error));
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String message(Throwable error) {
        return error != null && error.getMessage() != null && !error.getMessage().isBlank()
                ? error.getMessage()
                : "Desktop 请求失败";
    }
}
