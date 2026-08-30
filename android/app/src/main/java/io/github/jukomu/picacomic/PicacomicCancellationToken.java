package io.github.jukomu.picacomic;

import java.util.concurrent.atomic.AtomicBoolean;

/** Cancellation scope owned by one bridge operation or one pending image. */
public final class PicacomicCancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    public void throwIfCancelled() throws PicacomicException {
        if (isCancelled()) {
            throw new PicacomicException(PicacomicErrorCode.CANCELLED, "cancel");
        }
    }
}
