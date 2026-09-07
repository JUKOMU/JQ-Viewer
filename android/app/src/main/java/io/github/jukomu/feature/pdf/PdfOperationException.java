package io.github.jukomu.feature.pdf;

import java.io.IOException;

/**
 * PDF 领域操作的稳定失败原因。
 *
 * <p>该异常只在 PDF service 与对应 Plugin handler 之间传递机器可读原因，
 * 不承担跨 Plugin 的通用错误枚举职责。</p>
 */
public final class PdfOperationException extends IOException {
    public static final String NOT_FOUND = "not-found";
    public static final String PERMISSION_DENIED = "permission-denied";
    public static final String CONFLICT = "conflict";

    public final String code;

    public PdfOperationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public PdfOperationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public static PdfOperationException notFound(String message) {
        return new PdfOperationException(NOT_FOUND, message);
    }

    public static PdfOperationException permissionDenied(String message, Throwable cause) {
        return new PdfOperationException(PERMISSION_DENIED, message, cause);
    }

    public static PdfOperationException conflict(String message) {
        return new PdfOperationException(CONFLICT, message);
    }
}
