package io.github.jukomu.picacomic;

/**
 * Provider-specific failure used by the injectable client seam.
 *
 * <p>A real adapter can translate its structured exception into this type;
 * the CP2 fake does the same without depending on the PicaComic artifact.</p>
 */
public final class PicacomicRemoteException extends Exception {

    public enum Kind {
        UNAUTHORIZED,
        FORBIDDEN,
        NOT_FOUND,
        RATE_LIMITED,
        NETWORK,
        PARSE,
        SERVER,
        CANCELLED,
        OTHER
    }

    private final Kind kind;
    private final int statusCode;

    public PicacomicRemoteException(Kind kind) {
        this(kind, 0, null);
    }

    public PicacomicRemoteException(Kind kind, int statusCode) {
        this(kind, statusCode, null);
    }

    public PicacomicRemoteException(Kind kind, int statusCode, Throwable cause) {
        super(kind == null ? "unknown" : kind.name(), cause);
        this.kind = kind == null ? Kind.OTHER : kind;
        this.statusCode = statusCode;
    }

    public Kind getKind() {
        return kind;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public static PicacomicRemoteException unauthorized() {
        return new PicacomicRemoteException(Kind.UNAUTHORIZED, 401);
    }

    public static PicacomicRemoteException forbidden() {
        return new PicacomicRemoteException(Kind.FORBIDDEN, 403);
    }

    public static PicacomicRemoteException cancelled() {
        return new PicacomicRemoteException(Kind.CANCELLED);
    }
}
