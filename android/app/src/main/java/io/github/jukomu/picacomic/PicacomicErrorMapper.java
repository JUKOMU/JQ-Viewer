package io.github.jukomu.picacomic;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Maps provider/native failures to the small bridge error vocabulary. */
public final class PicacomicErrorMapper {

    private PicacomicErrorMapper() {
    }

    public static PicacomicException map(String operation, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof PicacomicException) {
            PicacomicException mapped = (PicacomicException) cause;
            if (operation == null || operation.isEmpty()
                || operation.equals(mapped.getOperation())) {
                return mapped;
            }
            return new PicacomicException(mapped.getErrorCode(), operation, mapped);
        }
        if (cause instanceof PicacomicRemoteException) {
            return mapRemote(operation, (PicacomicRemoteException) cause);
        }
        if (cause instanceof CancellationException || cause instanceof InterruptedException) {
            return new PicacomicException(PicacomicErrorCode.CANCELLED, operation, cause);
        }
        if (cause instanceof IOException || cause instanceof TimeoutException) {
            return new PicacomicException(PicacomicErrorCode.NETWORK, operation, cause);
        }
        if (cause instanceof IllegalArgumentException) {
            return new PicacomicException(PicacomicErrorCode.INVALID_ARGUMENT, operation, cause);
        }
        return new PicacomicException(PicacomicErrorCode.INTERNAL, operation, cause);
    }

    private static PicacomicException mapRemote(String operation,
                                                PicacomicRemoteException failure) {
        PicacomicErrorCode code;
        switch (failure.getKind()) {
            case UNAUTHORIZED:
            case FORBIDDEN:
                code = PicacomicErrorCode.AUTH_EXPIRED;
                break;
            case NOT_FOUND:
                code = PicacomicErrorCode.NOT_FOUND;
                break;
            case RATE_LIMITED:
                code = PicacomicErrorCode.RATE_LIMITED;
                break;
            case NETWORK:
                code = PicacomicErrorCode.NETWORK;
                break;
            case PARSE:
                code = PicacomicErrorCode.INVALID_RESPONSE;
                break;
            case CANCELLED:
                code = PicacomicErrorCode.CANCELLED;
                break;
            case SERVER:
            case OTHER:
            default:
                code = PicacomicErrorCode.UPSTREAM;
                break;
        }
        return new PicacomicException(code, operation, failure);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure == null ? new NullPointerException("failure") : failure;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
