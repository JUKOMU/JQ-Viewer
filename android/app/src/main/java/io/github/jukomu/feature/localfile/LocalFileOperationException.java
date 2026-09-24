package io.github.jukomu.feature.localfile;

import java.io.IOException;

/**
 * 本地文件操作的稳定失败原因。
 *
 * <p>该异常只在本地文件 service 与对应 Plugin handler 之间传递机器可读原因，
 * 不承担跨 Plugin 的通用错误枚举职责。</p>
 */
public final class LocalFileOperationException extends IOException {
    public static final String NOT_FOUND = "not-found";
    public static final String PERMISSION_DENIED = "permission-denied";
    public static final String CONFLICT = "conflict";

    public final String code;

    public LocalFileOperationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public LocalFileOperationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public static LocalFileOperationException notFound(String message) {
        return new LocalFileOperationException(NOT_FOUND, message);
    }

    public static LocalFileOperationException permissionDenied(String message, Throwable cause) {
        return new LocalFileOperationException(PERMISSION_DENIED, message, cause);
    }

    public static LocalFileOperationException conflict(String message) {
        return new LocalFileOperationException(CONFLICT, message);
    }
}
