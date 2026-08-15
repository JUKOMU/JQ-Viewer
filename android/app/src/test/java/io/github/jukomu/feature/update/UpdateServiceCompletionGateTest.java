package io.github.jukomu.feature.update;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UpdateServiceCompletionGateTest {

    @Test
    public void cancellationBeforeCommitPreventsReadyCommit() {
        UpdateService.CompletionGate gate = new UpdateService.CompletionGate();

        assertTrue(gate.cancel());
        assertFalse(gate.beginCommit());
        assertTrue(gate.isCancelled());
    }

    @Test
    public void commitBeforeCancellationMakesCancellationTooLate() {
        UpdateService.CompletionGate gate = new UpdateService.CompletionGate();

        assertTrue(gate.beginCommit());
        assertFalse(gate.cancel());
        assertFalse(gate.isCancelled());
    }

    @Test
    public void concurrentCancellationAndCommitHaveOneWinner() throws Exception {
        UpdateService.CompletionGate gate = new UpdateService.CompletionGate();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        Thread cancel = new Thread(() -> runTogether(ready, start, () -> {
            if (gate.cancel()) winners.incrementAndGet();
        }));
        Thread commit = new Thread(() -> runTogether(ready, start, () -> {
            if (gate.beginCommit()) winners.incrementAndGet();
        }));

        cancel.start();
        commit.start();
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        cancel.join(5_000L);
        commit.join(5_000L);

        assertFalse(cancel.isAlive());
        assertFalse(commit.isAlive());
        assertEquals(1, winners.get());
    }

    private static void runTogether(CountDownLatch ready, CountDownLatch start, Runnable action) {
        ready.countDown();
        try {
            if (start.await(5, TimeUnit.SECONDS)) {
                action.run();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
