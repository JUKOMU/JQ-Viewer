package io.github.jukomu.desktop.bridge;

/** 带有稳定错误码和 HTTP 状态的业务异常。 */
public final class ApiException extends RuntimeException {
    private final String code;
    private final int status;

    public ApiException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public static ApiException invalidRequest(String message) {
        return new ApiException("internal", 400, message);
    }

    public static ApiException cancelled(String message) {
        return new ApiException("cancelled", 409, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException("not-found", 404, message);
    }

    public static ApiException permissionDenied(String message) {
        return new ApiException("permission-denied", 403, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException("conflict", 409, message);
    }

    public static ApiException unavailable(String message) {
        return new ApiException("unavailable", 503, message);
    }

    public static ApiException network(String message) {
        return new ApiException("network", 503, message);
    }
}
