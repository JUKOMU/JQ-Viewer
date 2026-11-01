package io.github.jukomu.desktop.feature.image;

import io.github.jukomu.jmcomic.api.model.JmImage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

        NetworkImageTaskScheduler.Fetcher fetcher = (image, replaceCache, cacheLookup, networkFetcher) ->
            networkFetcher.fetch(image);
        NetworkImageTaskScheduler.NetworkFetcher networkFetcher = image -> {
            fetches.incrementAndGet();
            fetchStarted.countDown();
            assertTrue(releaseFetch.await(1, TimeUnit.SECONDS));
            return new byte[]{1};
        };

        scheduler.submit(IMAGE, "preview/thumb", "preview", 100, false, fetcher, networkFetcher,
            bytes -> completed.countDown(), error -> { throw new AssertionError(error); });
        assertTrue(fetchStarted.await(1, TimeUnit.SECONDS));
        scheduler.submit(IMAGE, "reader/image", "reader", 300, false, fetcher, networkFetcher,
            bytes -> completed.countDown(), error -> { throw new AssertionError(error); });

        releaseFetch.countDown();

        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertEquals(1, fetches.get());
    }

    @Test
    void cancelledSessionRejectsLateNetworkSubmission() {
        NetworkImageTaskScheduler scheduler = new NetworkImageTaskScheduler(1);
        scheduler.cancelSession("reader");

        boolean accepted = scheduler.submit(IMAGE, "reader/image", "reader", 300, false,
            (image, replaceCache, cacheLookup, networkFetcher) -> networkFetcher.fetch(image),
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
            false,
            (image, replaceCache, cacheLookup, networkFetcher) -> {
                lowStarted.countDown();
                assertTrue(releaseLow.await(1, TimeUnit.SECONDS));
                return new byte[]{1};
            },
            image -> new byte[]{1},
            bytes -> { },
            error -> { throw new AssertionError(error); });
        assertTrue(lowStarted.await(1, TimeUnit.SECONDS));

        JmImage highImage = new JmImage(
            "photo", "scramble", "002.jpg", "https://example.invalid/002.jpg", "", 2);
        scheduler.submit(highImage, "high/image", "high", 300, false,
            (image, replaceCache, cacheLookup, networkFetcher) -> {
                highStarted.countDown();
                return new byte[]{2};
            },
            image -> new byte[]{2},
            bytes -> { }, error -> { throw new AssertionError(error); });

        assertTrue(highStarted.await(1, TimeUnit.SECONDS));
        releaseLow.countDown();
    }

    @Test
    void replacementThreadsRespectTotalRunningLimit() throws Exception {
        NetworkImageTaskScheduler scheduler = new NetworkImageTaskScheduler(1);
        CountDownLatch firstTwelveStarted = new CountDownLatch(12);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseOthers = new CountDownLatch(1);
        CountDownLatch thirteenthStarted = new CountDownLatch(1);

        for (int index = 0; index < 12; index++) {
            int taskIndex = index;
            JmImage image = image(index + 1);
            scheduler.submit(image, "session-" + index + "/image", "session-" + index,
                index, false,
                (taskImage, replaceCache, cacheLookup, networkFetcher) -> {
                    firstTwelveStarted.countDown();
                    assertTrue((taskIndex == 0 ? releaseFirst : releaseOthers)
                        .await(3, TimeUnit.SECONDS));
                    return new byte[]{1};
                }, taskImage -> new byte[]{1}, bytes -> { },
                error -> { throw new AssertionError(error); });
        }
        assertTrue(firstTwelveStarted.await(1, TimeUnit.SECONDS));

        scheduler.submit(image(13), "session-13/image", "session-13", 100, false,
            (taskImage, replaceCache, cacheLookup, networkFetcher) -> {
                thirteenthStarted.countDown();
                return new byte[]{1};
            }, taskImage -> new byte[]{1}, bytes -> { },
            error -> { throw new AssertionError(error); });

        try {
            assertFalse(thirteenthStarted.await(100, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            assertTrue(thirteenthStarted.await(1, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            releaseOthers.countDown();
        }
    }

    @Test
    void successCallbackDoesNotHoldSchedulerLock() throws Exception {
        NetworkImageTaskScheduler scheduler = new NetworkImageTaskScheduler(1);
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch submitFinished = new CountDownLatch(1);

        scheduler.submit(IMAGE, "first/image", "first", 100, false,
            (image, replaceCache, cacheLookup, networkFetcher) -> new byte[]{1},
            image -> new byte[]{1}, bytes -> {
                callbackStarted.countDown();
                try {
                    assertTrue(releaseCallback.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }, error -> { throw new AssertionError(error); });
        assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));

        Thread submitter = new Thread(() -> {
            scheduler.submit(image(2), "second/image", "second", 100, false,
                (image, replaceCache, cacheLookup, networkFetcher) -> new byte[]{2},
                image -> new byte[]{2}, bytes -> { },
                error -> { throw new AssertionError(error); });
            submitFinished.countDown();
        });
        submitter.start();

        try {
            assertTrue(submitFinished.await(1, TimeUnit.SECONDS));
        } finally {
            releaseCallback.countDown();
            submitter.join(1_000L);
        }
    }

    private static JmImage image(int sortOrder) {
        String filename = String.format("%03d.jpg", sortOrder);
        return new JmImage("photo", "scramble", filename,
            "https://example.invalid/" + filename, "", sortOrder);
    }

    @Test
    void fatalFetchErrorCleansTaskAndReleasesRunningCount() throws Exception {
        NetworkImageTaskScheduler scheduler = new NetworkImageTaskScheduler(1);
        CountDownLatch failed = new CountDownLatch(1);
        CountDownLatch retried = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();

        scheduler.submit(IMAGE, "first/image", "first", 100, false,
            (image, replaceCache, cacheLookup, networkFetcher) -> networkFetcher.fetch(image),
            image -> {
                if (attempts.incrementAndGet() == 1) throw new AssertionError("fatal fetch error");
                return new byte[]{2};
            },
            bytes -> { },
            error -> failed.countDown());
        assertTrue(failed.await(1, TimeUnit.SECONDS));

        scheduler.submit(IMAGE, "second/image", "second", 100, false,
            (image, replaceCache, cacheLookup, networkFetcher) -> networkFetcher.fetch(image),
            image -> {
                attempts.incrementAndGet();
                return new byte[]{3};
            },
            bytes -> retried.countDown(), error -> { throw new AssertionError(error); });

        assertTrue(retried.await(1, TimeUnit.SECONDS));
        assertEquals(2, attempts.get());
    }

    @Test
    void callbackFailureDoesNotSkipOtherDemands() throws Exception {
        NetworkImageTaskScheduler scheduler = new NetworkImageTaskScheduler(1);
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        CountDownLatch secondCallback = new CountDownLatch(1);

        NetworkImageTaskScheduler.Fetcher fetcher = (image, replaceCache, cacheLookup, networkFetcher) -> {
            fetchStarted.countDown();
            assertTrue(releaseFetch.await(1, TimeUnit.SECONDS));
            return new byte[]{1};
        };
        NetworkImageTaskScheduler.NetworkFetcher networkFetcher = image -> new byte[]{1};

        scheduler.submit(IMAGE, "first/image", "first", 100, false, fetcher, networkFetcher,
            bytes -> { throw new IllegalStateException("callback failure"); },
            error -> { throw new AssertionError(error); });
        assertTrue(fetchStarted.await(1, TimeUnit.SECONDS));
        scheduler.submit(IMAGE, "second/image", "second", 100, false, fetcher, networkFetcher,
            bytes -> secondCallback.countDown(), error -> { throw new AssertionError(error); });
        releaseFetch.countDown();

        assertTrue(secondCallback.await(1, TimeUnit.SECONDS));
    }

    @Test
    void retryDoesNotReuseResolvedCacheResult() throws Exception {
        NetworkImageTaskScheduler scheduler = new NetworkImageTaskScheduler(1);
        CountDownLatch cacheResolved = new CountDownLatch(1);
        CountDownLatch releaseCacheResult = new CountDownLatch(1);
        CountDownLatch retryFetchStarted = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        AtomicInteger fetches = new AtomicInteger();

        scheduler.submit(IMAGE, "reader/image", "reader", 200, false,
            (image, replaceCache, cacheLookup, networkFetcher) -> {
                byte[] cached = cacheLookup.read(() -> new byte[]{1});
                cacheResolved.countDown();
                assertTrue(releaseCacheResult.await(1, TimeUnit.SECONDS));
                return cached;
            },
            image -> { throw new AssertionError("cache hit must not start a network request"); },
            bytes -> completed.countDown(), error -> { throw new AssertionError(error); });
        assertTrue(cacheResolved.await(1, TimeUnit.SECONDS));

        scheduler.submit(IMAGE, "retry/image", "retry", 300, true,
            (image, replaceCache, cacheLookup, networkFetcher) -> {
                assertTrue(replaceCache.getAsBoolean());
                assertNull(cacheLookup.read(() -> new byte[]{1}));
                return networkFetcher.fetch(image);
            },
            image -> {
                fetches.incrementAndGet();
                retryFetchStarted.countDown();
                return new byte[]{2};
            },
            bytes -> completed.countDown(), error -> { throw new AssertionError(error); });

        try {
            assertTrue(retryFetchStarted.await(1, TimeUnit.SECONDS));
        } finally {
            releaseCacheResult.countDown();
        }

        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertEquals(1, fetches.get());
    }

    @Test
    void cancelledSessionDoesNotReceiveDetachedCacheResultAfterRetrySplit() throws Exception {
        NetworkImageTaskScheduler scheduler = new NetworkImageTaskScheduler(1);
        CountDownLatch cacheResolved = new CountDownLatch(1);
        CountDownLatch releaseCacheResult = new CountDownLatch(1);
        CountDownLatch oldTaskCompleted = new CountDownLatch(1);
        CountDownLatch retryFetchStarted = new CountDownLatch(1);
        CountDownLatch releaseRetryFetch = new CountDownLatch(1);
        CountDownLatch retryCompleted = new CountDownLatch(1);
        AtomicInteger oldDemandCallbacks = new AtomicInteger();

        scheduler.submit(IMAGE, "reader/image", "reader", 200, false,
            (image, replaceCache, cacheLookup, networkFetcher) -> {
                byte[] cached = cacheLookup.read(() -> new byte[]{1});
                cacheResolved.countDown();
                assertTrue(releaseCacheResult.await(1, TimeUnit.SECONDS));
                return cached;
            },
            image -> { throw new AssertionError("cache hit must not start a network request"); },
            bytes -> oldDemandCallbacks.incrementAndGet(),
            error -> oldDemandCallbacks.incrementAndGet());
        assertTrue(cacheResolved.await(1, TimeUnit.SECONDS));
        scheduler.submit(IMAGE, "observer/image", "observer", 150, false,
            (image, replaceCache, cacheLookup, networkFetcher) -> {
                throw new AssertionError("observer must join resolved cache result");
            },
            image -> { throw new AssertionError("observer must not start another fetch"); },
            bytes -> oldTaskCompleted.countDown(), error -> { throw new AssertionError(error); });

        scheduler.submit(IMAGE, "retry/image", "retry", 300, true,
            (image, replaceCache, cacheLookup, networkFetcher) -> {
                assertTrue(replaceCache.getAsBoolean());
                assertNull(cacheLookup.read(() -> new byte[]{1}));
                return networkFetcher.fetch(image);
            },
            image -> {
                retryFetchStarted.countDown();
                assertTrue(releaseRetryFetch.await(1, TimeUnit.SECONDS));
                return new byte[]{2};
            },
            bytes -> retryCompleted.countDown(), error -> { throw new AssertionError(error); });
        assertTrue(retryFetchStarted.await(1, TimeUnit.SECONDS));

        scheduler.cancelSession("reader");
        releaseCacheResult.countDown();
        assertTrue(oldTaskCompleted.await(1, TimeUnit.SECONDS));
        releaseRetryFetch.countDown();

        assertTrue(retryCompleted.await(1, TimeUnit.SECONDS));
        assertEquals(0, oldDemandCallbacks.get());
    }
}
