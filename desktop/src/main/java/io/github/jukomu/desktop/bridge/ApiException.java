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
}
