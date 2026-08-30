package io.github.jukomu.picacomic;

import com.getcapacitor.JSObject;

/** Native error after a Picacomic operation has crossed the stable error boundary. */
public final class PicacomicException extends Exception {

    private final PicacomicErrorCode errorCode;
    private final String operation;

    public PicacomicException(PicacomicErrorCode errorCode, String operation) {
        this(errorCode, operation, null);
    }

    public PicacomicException(PicacomicErrorCode errorCode, String operation,
                              Throwable cause) {
        super(errorCode == null ? PicacomicErrorCode.INTERNAL.getMessage()
            : errorCode.getMessage(), cause);
        this.errorCode = errorCode == null ? PicacomicErrorCode.INTERNAL : errorCode;
        this.operation = operation == null || operation.isEmpty() ? "unknown" : operation;
    }

    public PicacomicErrorCode getErrorCode() {
        return errorCode;
    }

    public String getCode() {
        return errorCode.getCode();
    }

    public String getOperation() {
        return operation;
    }

    public boolean isRetryable() {
        return errorCode.isRetryable();
    }

    /** Data safe to cross the Capacitor boundary; it contains no cause or remote payload. */
    public JSObject toJsData() {
        JSObject data = new JSObject();
        data.put("code", errorCode.getCode());
        data.put("operation", operation);
        data.put("retryable", errorCode.isRetryable());
        return data;
    }
}
