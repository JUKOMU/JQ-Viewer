package io.github.jukomu.feature.preload;

import io.github.jukomu.jmcomic.api.model.JmImage;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
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
