package io.github.jukomu.desktop.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jukomu.desktop.bridge.model.ErrorResponse;
import io.javalin.http.Context;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/** 将阻塞业务调用放入有界 executor，并将结果转换为一次 HTTP 响应。 */
public final class RequestExecutor {
    private static final long FILE_OPERATION_TIMEOUT = TimeUnit.HOURS.toMillis(24);
    private final Executor executor;
    private final ExecutorService fileOperationExecutor;
    private final ObjectMapper mapper;

    public RequestExecutor(
            Executor executor,
            ExecutorService fileOperationExecutor,
            ObjectMapper mapper
    ) {
        this.executor = executor;
        this.fileOperationExecutor = fileOperationExecutor;
        this.mapper = mapper;
    }

    public <T> void run(Context context, Class<T> requestType, Function<T, ?> task) {
        T request;
        try {
            request = Request.body(context, mapper, requestType);
        } catch (ApiException exception) {
            sendError(context, exception);
            return;
        }

        execute(context, () -> task.apply(request));
    }

    public <T> void runFileOperation(Context context, Class<T> requestType, Function<T, ?> task) {
        T request;
        try {
            request = Request.body(context, mapper, requestType);
        } catch (ApiException exception) {
            sendError(context, exception);
            return;
        }

        executeFileOperation(context, () -> task.apply(request));
    }

    public void runFileOperation(Context context, Supplier<?> task) {
        try {
            Request.requireObject(context, mapper);
        } catch (ApiException exception) {
            sendError(context, exception);
            return;
        }

        executeFileOperation(context, task);
    }

    public void run(Context context, Supplier<?> task) {
        try {
            Request.requireObject(context, mapper);
        } catch (ApiException exception) {
            sendError(context, exception);
            return;
        }

        execute(context, task);
    }

    private void execute(Context context, Supplier<?> task) {
        CompletableFuture<?> response;
        try {
            response = CompletableFuture.supplyAsync(task, executor);
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

    private void executeFileOperation(Context context, Supplier<?> task) {
        try {
            context.async(config -> {
                config.executor = fileOperationExecutor;
                config.timeout = FILE_OPERATION_TIMEOUT;
                config.onTimeout(timeoutContext -> sendError(timeoutContext,
                        ApiException.unavailable("文件操作超时")));
            }, () -> {
                try {
                    context.json(task.get());
                } catch (Throwable failure) {
                    sendError(context, unwrap(failure));
                }
            });
        } catch (RejectedExecutionException exception) {
            sendError(context, new ApiException("internal", 503, "当前请求过多，请稍后重试"));
        }
    }

    private void sendError(Context context, Throwable failure) {
        ApiException error = failure instanceof ApiException apiException
                ? apiException
                : new ApiException("internal", 500, messageOf(failure));
        context.status(error.status()).json(new ErrorResponse(error.code(), error.getMessage()));
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
