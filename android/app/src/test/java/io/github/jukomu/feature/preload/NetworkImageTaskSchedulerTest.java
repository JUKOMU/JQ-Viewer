package io.github.jukomu.feature.preload;

import io.github.jukomu.jmcomic.api.model.JmImage;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NetworkImageTaskSchedulerTest {
    private static final JmImage IMAGE = new JmImage(
        "photo", "scramble", "001.jpg", "https://example.invalid/001.jpg", "", 1);

    @Test
    public void sameImageSharesOneFetchAndCompletesBothDemands() throws Exception {
        NetworkImageTaskScheduler scheduler = new NetworkImageTaskScheduler(1);
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        AtomicInteger fetches = new AtomicInteger();

        NetworkImageTaskScheduler.Fetcher fetcher =
            (image, detached, replaceCache, cacheLookup, networkFetcher) ->
            networkFetcher.fetch(image);
        NetworkImageTaskScheduler.NetworkFetcher networkFetcher = image -> {
            fetches.incrementAndGet();
            fetchStarted.countDown();
            assertTrue(releaseFetch.await(1, TimeUnit.SECONDS));
            return new byte[]{1};
        };

        AtomicInteger cacheWrites = new AtomicInteger();
        NetworkImageTaskScheduler.ResultHandler resultHandler =
            (image, bytes, replaceCache) -> cacheWrites.incrementAndGet();

        scheduler.submit(IMAGE, "preview/thumb", "preview", 100, false,
            fetcher, networkFetcher, resultHandler,
            bytes -> completed.countDown(), error -> { throw new AssertionError(error); });
        assertTrue(fetchStarted.await(1, TimeUnit.SECONDS));
        scheduler.submit(IMAGE, "reader/image", "reader", 300, false,
            fetcher, networkFetcher, resultHandler,
            bytes -> completed.countDown(), error -> { throw new AssertionError(error); });

        releaseFetch.countDown();

        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertEquals(1, fetches.get());
        assertEquals(1, cacheWrites.get());
    }

    @Test
    public void cancelledSessionRejectsLateNetworkSubmission() {
        NetworkImageTaskScheduler scheduler = new NetworkImageTaskScheduler(1);
        scheduler.cancelSession("reader");

        boolean accepted = scheduler.submit(IMAGE, "reader/image", "reader", 300, false,
            (image, detached, replaceCache, cacheLookup, networkFetcher) ->
                networkFetcher.fetch(image),
            image -> new byte[]{1}, (image, bytes, replaceCache) -> { },
            bytes -> { throw new AssertionError("unexpected success"); },
            error -> { throw new AssertionError("unexpected failure"); });

        assertFalse(accepted);
    }

    @Test
    public void highPriorityTaskStartsWhileLowerTaskIsStillRunning() throws Exception {
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
            (image, detached, replaceCache, cacheLookup, networkFetcher) -> {
                lowStarted.countDown();
                assertTrue(releaseLow.await(1, TimeUnit.SECONDS));
                return new byte[]{1};
            },
            image -> new byte[]{1},
            (image, bytes, replaceCache) -> { },
            bytes -> { },
            error -> { throw new AssertionError(error); });
        assertTrue(lowStarted.await(1, TimeUnit.SECONDS));

        JmImage highImage = new JmImage(
            "photo", "scramble", "002.jpg", "https://example.invalid/002.jpg", "", 2);
        scheduler.submit(highImage, "high/image", "high", 300, false,
            (image, detached, replaceCache, cacheLookup, networkFetcher) -> {
                highStarted.countDown();
                return new byte[]{2};
            },
            image -> new byte[]{2},
            (image, bytes, replaceCache) -> { },
            bytes -> { }, error -> { throw new AssertionError(error); });

        assertTrue(highStarted.await(1, TimeUnit.SECONDS));
        releaseLow.countDown();
    }

    @Test
    public void replacementThreadsRespectTotalRunningLimit() throws Exception {
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
                (taskImage, detached, replaceCache, cacheLookup, networkFetcher) -> {
                    firstTwelveStarted.countDown();
                    assertTrue((taskIndex == 0 ? releaseFirst : releaseOthers)
                        .await(3, TimeUnit.SECONDS));
                    return new byte[]{1};
                },
                taskImage -> new byte[]{1}, (taskImage, bytes, replaceCache) -> { },
                bytes -> { }, error -> { throw new AssertionError(error); });
        }
        assertTrue(firstTwelveStarted.await(1, TimeUnit.SECONDS));

        scheduler.submit(image(13), "session-13/image", "session-13", 100, false,
            (taskImage, detached, replaceCache, cacheLookup, networkFetcher) -> {
                thirteenthStarted.countDown();
                return new byte[]{1};
            }, taskImage -> new byte[]{1}, (taskImage, bytes, replaceCache) -> { },
            bytes -> { }, error -> { throw new AssertionError(error); });

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
    public void successCallbackDoesNotHoldSchedulerLock() throws Exception {
        NetworkImageTaskScheduler scheduler = new NetworkImageTaskScheduler(1);
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch submitFinished = new CountDownLatch(1);

        scheduler.submit(IMAGE, "first/image", "first", 100, false,
            (image, detached, replaceCache, cacheLookup, networkFetcher) -> new byte[]{1},
            image -> new byte[]{1}, (image, bytes, replaceCache) -> { }, bytes -> {
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
                (image, detached, replaceCache, cacheLookup, networkFetcher) -> new byte[]{2},
                image -> new byte[]{2}, (image, bytes, replaceCache) -> { },
                bytes -> { }, error -> { throw new AssertionError(error); });
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
    public void abandonedTaskStopsBeforeStartingNetworkRequest() throws Exception {
        NetworkImageTaskScheduler scheduler = new NetworkImageTaskScheduler(1);
        CountDownLatch fetchEntered = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        CountDownLatch replacementStarted = new CountDownLatch(1);
        AtomicInteger networkFetches = new AtomicInteger();

        scheduler.submit(IMAGE, "cancelled/image", "cancelled", 100, false,
            (image, abandoned, replaceCache, cacheLookup, networkFetcher) -> {
                fetchEntered.countDown();
                releaseFetch.await(1, TimeUnit.SECONDS);
                if (abandoned.getAsBoolean()) throw new CancellationException();
                return networkFetcher.fetch(image);
            },
            image -> {
                networkFetches.incrementAndGet();
                return new byte[]{1};
            },
            (image, bytes, replaceCache) -> { },
            bytes -> { throw new AssertionError("cancelled demand received success"); },
            error -> { throw new AssertionError("cancelled demand received failure"); });
        assertTrue(fetchEntered.await(1, TimeUnit.SECONDS));
        scheduler.cancelSession("cancelled");

        scheduler.submit(image(14), "replacement/image", "replacement", 300, false,
            (image, abandoned, replaceCache, cacheLookup, networkFetcher) -> {
                replacementStarted.countDown();
                return new byte[]{2};
            },
            image -> new byte[]{2}, (image, bytes, replaceCache) -> { },
            bytes -> { }, error -> { throw new AssertionError(error); });
        assertTrue(replacementStarted.await(1, TimeUnit.SECONDS));
        releaseFetch.countDown();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (networkFetches.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10L);
        assertEquals(0, networkFetches.get());
    }

    @Test
    public void fatalFetchErrorCleansTaskAndReleasesRunningCount() throws Exception {
        NetworkImageTaskScheduler scheduler = new NetworkImageTaskScheduler(1);
        CountDownLatch failed = new CountDownLatch(1);
        CountDownLatch retried = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();

        scheduler.submit(IMAGE, "first/image", "first", 100, false,
            (image, abandoned, replaceCache, cacheLookup, networkFetcher) ->
                networkFetcher.fetch(image),
            image -> {
                if (attempts.incrementAndGet() == 1) throw new AssertionError("fatal fetch error");
                return new byte[]{2};
            },
            (image, bytes, replaceCache) -> { },
            bytes -> { throw new AssertionError("expected fetch failure"); },
            error -> failed.countDown());
        assertTrue(failed.await(1, TimeUnit.SECONDS));

        scheduler.submit(IMAGE, "second/image", "second", 100, false,
            (image, abandoned, replaceCache, cacheLookup, networkFetcher) ->
                networkFetcher.fetch(image),
            image -> {
                attempts.incrementAndGet();
                return new byte[]{3};
            },
            (image, bytes, replaceCache) -> { },
            bytes -> retried.countDown(), error -> { throw new AssertionError(error); });

        assertTrue(retried.await(1, TimeUnit.SECONDS));
        assertEquals(2, attempts.get());
    }

    @Test
    public void callbackFailureDoesNotSkipOtherDemands() throws Exception {
        NetworkImageTaskScheduler scheduler = new NetworkImageTaskScheduler(1);
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        CountDownLatch secondCallback = new CountDownLatch(1);

        NetworkImageTaskScheduler.Fetcher fetcher =
            (image, abandoned, replaceCache, cacheLookup, networkFetcher) -> {
                fetchStarted.countDown();
                releaseFetch.await(1, TimeUnit.SECONDS);
                return new byte[]{1};
            };
        NetworkImageTaskScheduler.NetworkFetcher networkFetcher = image -> new byte[]{1};
        NetworkImageTaskScheduler.ResultHandler resultHandler = (image, bytes, replaceCache) -> { };

        scheduler.submit(IMAGE, "first/image", "first", 100, false,
            fetcher, networkFetcher, resultHandler,
            bytes -> { throw new IllegalStateException("callback failure"); },
            error -> { throw new AssertionError(error); });
        assertTrue(fetchStarted.await(1, TimeUnit.SECONDS));
        scheduler.submit(IMAGE, "second/image", "second", 100, false,
            fetcher, networkFetcher, resultHandler,
            bytes -> secondCallback.countDown(), error -> { throw new AssertionError(error); });
        releaseFetch.countDown();

        assertTrue(secondCallback.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void cancelledRunningDemandStillCachesFetchedResult() throws Exception {
        NetworkImageTaskScheduler scheduler = new NetworkImageTaskScheduler(1);
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        CountDownLatch cached = new CountDownLatch(1);

        scheduler.submit(IMAGE, "reader/image", "reader", 300, false,
            (image, detached, replaceCache, cacheLookup, networkFetcher) ->
                networkFetcher.fetch(image),
            image -> {
                fetchStarted.countDown();
                assertTrue(releaseFetch.await(1, TimeUnit.SECONDS));
                return new byte[]{1};
            },
            (image, bytes, replaceCache) -> cached.countDown(),
            bytes -> { throw new AssertionError("cancelled demand received success"); },
            error -> { throw new AssertionError("cancelled demand received failure"); });

        assertTrue(fetchStarted.await(1, TimeUnit.SECONDS));
        scheduler.cancelSession("reader");
        releaseFetch.countDown();

        assertTrue(cached.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void retryDoesNotJoinResultAlreadyResolvedFromCache() throws Exception {
        NetworkImageTaskScheduler scheduler = new NetworkImageTaskScheduler(1);
        CountDownLatch firstResultHandling = new CountDownLatch(1);
        CountDownLatch releaseFirstResult = new CountDownLatch(1);
        CountDownLatch retryFetchStarted = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger cacheReads = new AtomicInteger();

        scheduler.submit(IMAGE, "reader/image", "reader", 200, false,
            (image, detached, replaceCache, cacheLookup, networkFetcher) ->
                cacheLookup.read(() -> {
                    cacheReads.incrementAndGet();
                    return new byte[]{1};
                }),
            image -> { throw new AssertionError("cache result must not start network"); },
            (image, bytes, replaceCache) -> {
                if (!replaceCache) {
                    firstResultHandling.countDown();
                    assertTrue(releaseFirstResult.await(1, TimeUnit.SECONDS));
                }
            },
            bytes -> completed.countDown(), error -> { throw new AssertionError(error); });

        assertTrue(firstResultHandling.await(1, TimeUnit.SECONDS));
        scheduler.submit(IMAGE, "retry/image", "retry", 300, true,
            (image, detached, replaceCache, cacheLookup, networkFetcher) -> {
                assertTrue(replaceCache.getAsBoolean());
                assertEquals(null, cacheLookup.read(() -> {
                    cacheReads.incrementAndGet();
                    return new byte[]{1};
                }));
                return networkFetcher.fetch(image);
            },
            image -> {
                fetches.incrementAndGet();
                retryFetchStarted.countDown();
                return new byte[]{2};
            },
            (image, bytes, replaceCache) -> { },
            bytes -> completed.countDown(), error -> { throw new AssertionError(error); });

        try {
            assertTrue(retryFetchStarted.await(1, TimeUnit.SECONDS));
        } finally {
            releaseFirstResult.countDown();
        }

        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertEquals(1, fetches.get());
        assertEquals(1, cacheReads.get());
    }

    @Test
    public void retryJoinsCacheMissWaitingForNetworkPermit() throws Exception {
        NetworkImageTaskScheduler scheduler = new NetworkImageTaskScheduler(1);
        CountDownLatch cacheMissResolved = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        AtomicInteger networkFetches = new AtomicInteger();

        scheduler.submit(IMAGE, "reader/image", "reader", 200, false,
            (image, detached, replaceCache, cacheLookup, networkFetcher) -> {
                assertEquals(null, cacheLookup.read(() -> null));
                cacheMissResolved.countDown();
                assertTrue(releaseFetch.await(1, TimeUnit.SECONDS));
                assertTrue(replaceCache.getAsBoolean());
                return networkFetcher.fetch(image);
            },
            image -> {
                networkFetches.incrementAndGet();
                return new byte[]{1};
            },
            (image, bytes, replaceCache) -> { },
            bytes -> completed.countDown(), error -> { throw new AssertionError(error); });

        assertTrue(cacheMissResolved.await(1, TimeUnit.SECONDS));
        scheduler.submit(IMAGE, "retry/image", "retry", 300, true,
            (image, detached, replaceCache, cacheLookup, networkFetcher) -> {
                throw new AssertionError("cache miss task should be shared");
            },
            image -> { throw new AssertionError("network task should be shared"); },
            (image, bytes, replaceCache) -> { },
            bytes -> completed.countDown(), error -> { throw new AssertionError(error); });

        releaseFetch.countDown();
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertEquals(1, networkFetches.get());
    }
}
