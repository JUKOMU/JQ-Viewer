package io.github.jukomu.picacomic;

/**
 * Stable error vocabulary exposed by the experimental Picacomic bridge.
 *
 * <p>The enum deliberately contains no upstream exception text.  Upstream
 * details stay native and are mapped to one of these codes before a bridge
 * call is rejected.</p>
 */
public enum PicacomicErrorCode {
    INVALID_ARGUMENT("PICACOMIC_INVALID_ARGUMENT", "Invalid Picacomic argument", false),
    AUTH_REQUIRED("PICACOMIC_AUTH_REQUIRED", "Picacomic login is required", false),
    AUTH_EXPIRED("PICACOMIC_AUTH_EXPIRED", "Picacomic login has expired", false),
    NOT_FOUND("PICACOMIC_NOT_FOUND", "Picacomic resource was not found", false),
    RATE_LIMITED("PICACOMIC_RATE_LIMITED", "Picacomic rate limit reached", true),
    NETWORK("PICACOMIC_NETWORK", "Picacomic network request failed", true),
    INVALID_RESPONSE("PICACOMIC_INVALID_RESPONSE", "Picacomic response was invalid", false),
    STALE_RESOURCE("PICACOMIC_STALE_RESOURCE", "Picacomic chapter is stale", false),
    CANCELLED("PICACOMIC_CANCELLED", "Picacomic request was cancelled", false),
    UPSTREAM("PICACOMIC_UPSTREAM", "Picacomic service failed", true),
    INTERNAL("PICACOMIC_INTERNAL", "Picacomic operation failed", false);

    private final String code;
    private final String message;
    private final boolean retryable;

    PicacomicErrorCode(String code, String message, boolean retryable) {
        this.code = code;
        this.message = message;
        this.retryable = retryable;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
