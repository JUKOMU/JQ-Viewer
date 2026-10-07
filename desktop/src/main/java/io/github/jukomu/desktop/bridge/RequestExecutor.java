package io.github.jukomu.desktop.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jukomu.desktop.bridge.model.ErrorResponse;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 将单一服务的阻塞调用放入有界 executor，并将结果转换为一次 HTTP 响应。
 */
public final class RequestExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestExecutor.class);
    private static final long LONG_OPERATION_TIMEOUT = TimeUnit.HOURS.toMillis(24);
    private final ExecutorService executor;
    private final ObjectMapper mapper;

    public RequestExecutor(ExecutorService executor, ObjectMapper mapper) {
        this.executor = executor;
        this.mapper = mapper;
    }

    public <T> void run(Context context, Class<T> requestType, Function<T, ?> task) {
        RequestTrace trace = begin(context, requestType);
        T request;
        try {
            request = Request.body(context, mapper, requestType);
        } catch (ApiException exception) {
            sendError(context, exception, trace);
            return;
        }

        execute(context, () -> task.apply(request), trace);
    }

    public <T> void runLongOperation(Context context, Class<T> requestType, Function<T, ?> task) {
        RequestTrace trace = begin(context, requestType);
        T request;
        try {
            request = Request.body(context, mapper, requestType);
        } catch (ApiException exception) {
            sendError(context, exception, trace);
            return;
        }

        executeLongOperation(context, () -> task.apply(request), trace);
    }

    public void runLongOperation(Context context, Supplier<?> task) {
        RequestTrace trace = begin(context, Object.class);
        try {
            Request.requireObject(context, mapper);
        } catch (ApiException exception) {
            sendError(context, exception, trace);
            return;
        }

        executeLongOperation(context, task, trace);
    }

    public void run(Context context, Supplier<?> task) {
        RequestTrace trace = begin(context, Object.class);
        try {
            Request.requireObject(context, mapper);
        } catch (ApiException exception) {
            sendError(context, exception, trace);
            return;
        }

        execute(context, task, trace);
    }

    private void execute(Context context, Supplier<?> task, RequestTrace trace) {
        CompletableFuture<?> response;
        try {
            response = CompletableFuture.supplyAsync(task, executor);
        } catch (RejectedExecutionException exception) {
            sendError(context, new ApiException("internal", 503, "当前请求过多，请稍后重试"), trace);
            return;
        }
        LOGGER.debug("bridge request submitted route={} requestType={}", trace.route, trace.requestType);

        try {
            context.future(() -> response.handle((value, failure) -> {
                if (failure == null) {
                    try {
                        context.json(value);
                        logCompleted(trace);
                    } catch (RuntimeException responseFailure) {
                        logFailed(trace, responseFailure, null);
                        throw responseFailure;
                    }
                } else {
                    sendError(context, unwrap(failure), trace);
                }
                return null;
            }));
        } catch (RuntimeException registrationFailure) {
            LOGGER.warn("bridge response scheduling failed route={} errorType={}",
                trace.route, registrationFailure.getClass().getSimpleName());
            throw registrationFailure;
        }
    }

    private void executeLongOperation(Context context, Supplier<?> task, RequestTrace trace) {
        try {
            context.async(config -> {
                config.executor = executor;
                config.timeout = LONG_OPERATION_TIMEOUT;
                config.onTimeout(timeoutContext -> sendError(timeoutContext,
                    ApiException.unavailable("操作超时"), trace));
            }, () -> {
                try {
                    context.json(task.get());
                    logCompleted(trace);
                } catch (Throwable failure) {
                    sendError(context, unwrap(failure), trace);
                }
            });
            LOGGER.debug("bridge long request submitted route={} requestType={}",
                trace.route, trace.requestType);
        } catch (RejectedExecutionException exception) {
            sendError(context, new ApiException("internal", 503, "当前请求过多，请稍后重试"), trace);
        } catch (RuntimeException schedulingFailure) {
            LOGGER.warn("bridge long request scheduling failed route={} errorType={}",
                trace.route, schedulingFailure.getClass().getSimpleName());
            throw schedulingFailure;
        }
    }

    private void sendError(Context context, Throwable failure, RequestTrace trace) {
        ApiException error = failure instanceof ApiException apiException
            ? apiException
            : new ApiException("internal", 500, messageOf(failure));
        logFailed(trace, failure, error);
        try {
            context.status(error.status()).json(new ErrorResponse(error.code(), error.getMessage()));
        } catch (RuntimeException responseFailure) {
            LOGGER.warn("bridge error response failed route={} errorType={}",
                trace.route, responseFailure.getClass().getSimpleName());
            throw responseFailure;
        }
    }

    private RequestTrace begin(Context context, Class<?> requestType) {
        RequestTrace trace = new RequestTrace(
            safePath(context), String.valueOf(context.method()), requestType.getSimpleName(),
            System.nanoTime());
        LOGGER.debug("bridge request started method={} route={} requestType={} bodyBytes={}",
            trace.method, trace.route, trace.requestType, bodyLength(context));
        return trace;
    }

    private void logCompleted(RequestTrace trace) {
        LOGGER.info("bridge request completed method={} route={} requestType={} status=success elapsedMs={}",
            trace.method, trace.route, trace.requestType, elapsedMs(trace.startedAtNanos));
    }

    private void logFailed(RequestTrace trace, Throwable failure, ApiException error) {
        LOGGER.warn("bridge request failed method={} route={} requestType={} status={} error={} errorType={} elapsedMs={}",
            trace.method, trace.route, trace.requestType,
            error == null ? "response" : error.status(),
            error == null ? "response" : error.code(),
            failure.getClass().getSimpleName(), elapsedMs(trace.startedAtNanos));
    }

    private static String safePath(Context context) {
        String path = context.path();
        return path == null || path.isBlank() ? "<unknown>" : path;
    }

    private static int bodyLength(Context context) {
        try {
            String body = context.body();
            return body == null ? 0 : body.length();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private static final class RequestTrace {
        private final String route;
        private final String method;
        private final String requestType;
        private final long startedAtNanos;

        private RequestTrace(String route, String method, String requestType, long startedAtNanos) {
            this.route = route;
            this.method = method;
            this.requestType = requestType;
            this.startedAtNanos = startedAtNanos;
        }
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
