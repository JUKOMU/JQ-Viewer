package io.github.jukomu.desktop.error;

import java.util.Objects;

/** 带稳定错误码和 HTTP 状态的 Desktop 业务异常。 */
public final class DesktopHttpException extends RuntimeException {
    private final String code;
    private final int status;

    public DesktopHttpException(String code, int status, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }
}
