package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.dto.DesktopDtos;
import io.github.jukomu.desktop.error.DesktopHttpException;
import io.github.jukomu.jmcomic.api.exception.NetworkException;
import io.github.jukomu.jmcomic.api.exception.ResourceNotFoundException;
import io.github.jukomu.jmcomic.api.exception.ResponseException;
import io.github.jukomu.jmcomic.api.exception.JmComicException;
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

/** 统一执行 Desktop 阻塞业务调用，并保证 Javalin 响应只提交一次。 */
public final class AsyncRequestHandler {
    private AsyncRequestHandler() {
    }

    public static <T> void submitJson(
            Context context,
            Executor executor,
            Callable<T> operation
    ) {
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
            response = CompletableFuture.supplyAsync(() -> {
                try {
                    return operation.call();
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            }, executor).thenAccept(result -> respondOnce(responded, () -> writer.accept(result)))
                    .exceptionally(error -> {
                        respondOnce(responded, () -> writeError(context, unwrap(error)));
                        return null;
                    });
        } catch (RejectedExecutionException exception) {
            writeError(context, exception);
            return;
        }

        context.future(() -> response);
    }

    public static <T> T readBody(Context context, Class<T> type) {
        T body = context.bodyAsClass(type);
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        return body;
    }

    public static void writeError(Context context, Throwable error) {
        DesktopHttpException mapped = mapError(error);
        context.status(mapped.status()).json(
                new DesktopDtos.ErrorResponse(mapped.code(), mapped.getMessage())
        );
    }

    private static void respondOnce(AtomicBoolean responded, Runnable response) {
        if (responded.compareAndSet(false, true)) {
            response.run();
        }
    }

    private static DesktopHttpException mapError(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof DesktopHttpException desktopError) {
            return desktopError;
        }
        if (cause instanceof ResourceNotFoundException) {
            return new DesktopHttpException("not-found", 404, message(cause));
        }
        if (cause instanceof NetworkException) {
            return new DesktopHttpException("network", 502, message(cause));
        }
        if (cause instanceof ResponseException) {
            return new DesktopHttpException("internal", 502, message(cause));
        }
        if (cause instanceof IllegalArgumentException) {
            return new DesktopHttpException("internal", 400, message(cause));
        }
        if (cause instanceof RejectedExecutionException) {
            return new DesktopHttpException("internal", 503, "Desktop 业务线程池暂时不可用");
        }
        if (cause instanceof java.util.concurrent.CancellationException
                || cause instanceof InterruptedException) {
            return new DesktopHttpException("cancelled", 499, "Desktop 请求已取消");
        }
        if (cause instanceof JmComicException) {
            return new DesktopHttpException("internal", 502, message(cause));
        }
        return new DesktopHttpException("internal", 500, message(cause));
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
        if (error != null && error.getMessage() != null && !error.getMessage().isBlank()) {
            return error.getMessage();
        }
        return "Desktop 请求失败";
    }
}
