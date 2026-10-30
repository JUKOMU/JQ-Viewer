package io.github.jukomu.desktop.feature.image;

import io.github.jukomu.jmcomic.api.model.JmImage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkImageTaskSchedulerTest {
    private static final JmImage IMAGE = new JmImage(
        "photo", "scramble", "001.jpg", "https://example.invalid/001.jpg", "", 1);

    @Test
    void sameImageSharesOneFetchAndCompletesBothDemands() throws Exception {
        NetworkImageTaskScheduler scheduler = new NetworkImageTaskScheduler(1);
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        AtomicInteger fetches = new AtomicInteger();

        NetworkImageTaskScheduler.Fetcher fetcher = image -> {
            fetches.incrementAndGet();
            fetchStarted.countDown();
            assertTrue(releaseFetch.await(1, TimeUnit.SECONDS));
            return new byte[]{1};
        };

        scheduler.submit(IMAGE, "preview/thumb", "preview", 100, fetcher,
            bytes -> completed.countDown(), error -> { throw new AssertionError(error); });
        assertTrue(fetchStarted.await(1, TimeUnit.SECONDS));
        scheduler.submit(IMAGE, "reader/image", "reader", 300, fetcher,
            bytes -> completed.countDown(), error -> { throw new AssertionError(error); });

        releaseFetch.countDown();

        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertEquals(1, fetches.get());
    }

    @Test
    void cancelledSessionRejectsLateNetworkSubmission() {
        NetworkImageTaskScheduler scheduler = new NetworkImageTaskScheduler(1);
        scheduler.cancelSession("reader");

        boolean accepted = scheduler.submit(IMAGE, "reader/image", "reader", 300,
            image -> new byte[]{1}, bytes -> { throw new AssertionError("unexpected success"); },
            error -> { throw new AssertionError("unexpected failure"); });

        assertFalse(accepted);
    }

    @Test
    void highPriorityTaskStartsWhileLowerTaskIsStillRunning() throws Exception {
        NetworkImageTaskScheduler scheduler = new NetworkImageTaskScheduler(1);
        CountDownLatch lowStarted = new CountDownLatch(1);
        CountDownLatch releaseLow = new CountDownLatch(1);
        CountDownLatch highStarted = new CountDownLatch(1);

        scheduler.submit(
            IMAGE,
            "low/image",
            "low",
            100,
            image -> {
                lowStarted.countDown();
                assertTrue(releaseLow.await(1, TimeUnit.SECONDS));
                return new byte[]{1};
            },
            bytes -> { },
            error -> { throw new AssertionError(error); });
        assertTrue(lowStarted.await(1, TimeUnit.SECONDS));

        JmImage highImage = new JmImage(
            "photo", "scramble", "002.jpg", "https://example.invalid/002.jpg", "", 2);
        scheduler.submit(highImage, "high/image", "high", 300,
            image -> {
                highStarted.countDown();
                return new byte[]{2};
            },
            bytes -> { }, error -> { throw new AssertionError(error); });

        assertTrue(highStarted.await(1, TimeUnit.SECONDS));
        releaseLow.countDown();
    }
}
