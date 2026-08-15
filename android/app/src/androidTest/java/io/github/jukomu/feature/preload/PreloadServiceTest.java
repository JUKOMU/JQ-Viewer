package io.github.jukomu.feature.preload;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.github.jukomu.feature.cache.CacheCapacityPolicy;
import io.github.jukomu.feature.cache.ImageCache;
import io.github.jukomu.feature.download.storage.FileStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PreloadServiceTest {

    private final ImageCache imageCache = ImageCache.getInstance();
    private ExecutorService imageExecutor;
    private ExecutorService networkExecutor;

    @Before
    public void setUp() {
        imageCache.applyPolicy(new CacheCapacityPolicy().calculate(
            16, 64L * CacheCapacityPolicy.MIB, false,
            CacheCapacityPolicy.PressureLevel.NORMAL));
        imageCache.clear();
        imageExecutor = Executors.newSingleThreadExecutor();
        networkExecutor = Executors.newSingleThreadExecutor();
    }

    @After
    public void tearDown() {
        imageExecutor.shutdownNow();
        networkExecutor.shutdownNow();
        imageCache.clear();
    }

    @Test
    public void criticalPressureStillAllowsOneNetworkRequest() throws Exception {
        CountDownLatch fetched = new CountDownLatch(1);
        AtomicInteger fetchCount = new AtomicInteger();
        CacheCapacityPolicy policy = new CacheCapacityPolicy();
        PreloadService service = new PreloadService(
            imageCache, FileStore.getInstance(), null, null,
            imageExecutor, networkExecutor, null, null, policy, 2,
            image -> {
                fetchCount.incrementAndGet();
                fetched.countDown();
                return new byte[]{1, 2, 3, 4};
            });
        service.setMemoryPressureLevel(CacheCapacityPolicy.PressureLevel.RUNNING_CRITICAL);

        JSONArray images = new JSONArray().put(new JSONObject()
            .put("sortOrder", 1)
            .put("scrambleId", "scramble")
            .put("filename", "page.jpg")
            .put("url", "https://example.invalid/page.jpg"));
        JSONObject result = service.preloadImages("photo", "image", images);

        assertEquals(1, result.getJSONArray("pending").length());
        assertTrue(fetched.await(1, TimeUnit.SECONDS));
        networkExecutor.submit(() -> {
        }).get(1, TimeUnit.SECONDS);
        assertEquals(1, fetchCount.get());
    }

    @Test
    public void completePressureDropsRequestUntilCallerRetries() throws Exception {
        CountDownLatch fetched = new CountDownLatch(1);
        AtomicInteger fetchCount = new AtomicInteger();
        CacheCapacityPolicy policy = new CacheCapacityPolicy();
        PreloadService service = new PreloadService(
            imageCache, FileStore.getInstance(), null, null,
            imageExecutor, networkExecutor, null, null, policy, 2,
            image -> {
                fetchCount.incrementAndGet();
                fetched.countDown();
                return new byte[]{1, 2, 3, 4};
            });
        service.setMemoryPressureLevel(CacheCapacityPolicy.PressureLevel.COMPLETE);

        JSONArray images = new JSONArray().put(new JSONObject()
            .put("sortOrder", 1)
            .put("scrambleId", "scramble")
            .put("filename", "page.jpg")
            .put("url", "https://example.invalid/page.jpg"));
        JSONObject result = service.preloadImages("complete-photo", "image", images);

        assertEquals(1, result.getJSONArray("pending").length());
        networkExecutor.submit(() -> {
        }).get(1, TimeUnit.SECONDS);
        assertEquals(0, fetchCount.get());
        assertFalse(fetched.await(100, TimeUnit.MILLISECONDS));

        service.setMemoryPressureLevel(CacheCapacityPolicy.PressureLevel.NORMAL);
        service.preloadImages("complete-photo", "image", images);
        assertTrue(fetched.await(1, TimeUnit.SECONDS));
        networkExecutor.submit(() -> {
        }).get(1, TimeUnit.SECONDS);
        assertEquals(1, fetchCount.get());
    }

    @Test
    public void completePressureDiscardsResultOfInFlightRequest() throws Exception {
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch allowFetchFinish = new CountDownLatch(1);
        CacheCapacityPolicy policy = new CacheCapacityPolicy();
        PreloadService service = new PreloadService(
            imageCache, FileStore.getInstance(), null, null,
            imageExecutor, networkExecutor, null, null, policy, 2,
            image -> {
                fetchStarted.countDown();
                allowFetchFinish.await(1, TimeUnit.SECONDS);
                return new byte[]{1, 2, 3, 4};
            });

        JSONArray images = new JSONArray().put(new JSONObject()
            .put("sortOrder", 1)
            .put("scrambleId", "scramble")
            .put("filename", "page.jpg")
            .put("url", "https://example.invalid/page.jpg"));
        service.preloadImages("in-flight-complete-photo", "image", images);
        assertTrue(fetchStarted.await(1, TimeUnit.SECONDS));

        service.setMemoryPressureLevel(CacheCapacityPolicy.PressureLevel.COMPLETE);
        allowFetchFinish.countDown();
        networkExecutor.submit(() -> {
        }).get(1, TimeUnit.SECONDS);

        assertFalse(imageCache.has("in-flight-complete-photo/1"));
    }

    @Test
    public void imageCacheContentsReturnsStructuredEntries() throws Exception {
        PreloadService service = new PreloadService(
            imageCache, FileStore.getInstance(), null, null,
            imageExecutor, networkExecutor, null, null,
            new CacheCapacityPolicy(), 2,
            image -> new byte[]{1, 2, 3});
        imageCache.put("20/49", new byte[]{1, 2}, "image/jpeg");
        imageCache.put("20/50/thumb", new byte[]{3}, "image/jpeg");

        JSONArray entries = service.getImageCacheContents().getJSONArray("entries");

        assertEquals(2, entries.length());
        assertEquals("20", entries.getJSONObject(0).getString("photoId"));
        assertEquals(49, entries.getJSONObject(0).getInt("sortOrder"));
        assertEquals("image", entries.getJSONObject(0).getString("type"));
        assertEquals(50, entries.getJSONObject(1).getInt("sortOrder"));
        assertEquals("thumb", entries.getJSONObject(1).getString("type"));
    }
}
