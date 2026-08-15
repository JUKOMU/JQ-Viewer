package io.github.jukomu.feature.pdf.export;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PdfExportExecutorTest {

    @Test
    public void acceptsMoreThanSixteenQueuedBatchesAndRunsSerially() throws Exception {
        ExecutorService executor = PdfExportService.createExecutor();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(21);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        try {
            executor.execute(() -> {
                int current = running.incrementAndGet();
                maxRunning.accumulateAndGet(current, Math::max);
                firstStarted.countDown();
                await(releaseFirst);
                running.decrementAndGet();
                completed.countDown();
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

            for (int index = 0; index < 20; index++) {
                executor.execute(() -> {
                    int current = running.incrementAndGet();
                    maxRunning.accumulateAndGet(current, Math::max);
                    running.decrementAndGet();
                    completed.countDown();
                });
            }

            releaseFirst.countDown();
            assertTrue(completed.await(3, TimeUnit.SECONDS));
            assertEquals(1, maxRunning.get());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
