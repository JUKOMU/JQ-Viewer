package io.github.jukomu.service;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import android.graphics.Bitmap;

import io.github.jukomu.data.CacheCapacityPolicy;
import io.github.jukomu.data.FileStore;
import io.github.jukomu.data.ImageCache;
import io.github.jukomu.data.ImageValidator;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
        networkExecutor.submit(() -> {}).get(1, TimeUnit.SECONDS);
        assertEquals(0, fetchCount.get());
        assertFalse(fetched.await(100, TimeUnit.MILLISECONDS));

        service.setMemoryPressureLevel(CacheCapacityPolicy.PressureLevel.NORMAL);
        service.preloadImages("complete-photo", "image", images);
        assertTrue(fetched.await(1, TimeUnit.SECONDS));
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
        networkExecutor.submit(() -> {}).get(1, TimeUnit.SECONDS);

        assertFalse(imageCache.has("in-flight-complete-photo/1"));
    }

    @Test
    public void retryImageBypassesCorruptMemoryCache() throws Exception {
        byte[] retriedBytes = createPng();
        imageCache.put("retry-photo/1",
            "not-an-image".getBytes(StandardCharsets.UTF_8), "image/jpeg");

        AtomicInteger fetchCount = new AtomicInteger();
        PreloadService service = new PreloadService(
            imageCache, FileStore.getInstance(), null, null,
            imageExecutor, networkExecutor, null, null,
            new CacheCapacityPolicy(), 2,
            image -> {
                fetchCount.incrementAndGet();
                return retriedBytes;
            });

        RetryOutcome outcome = retry(service, "retry-photo", retryImage());

        assertNull(outcome.error);
        assertTrue(outcome.success);
        assertEquals(1, fetchCount.get());
        ImageCache.ImageEntry entry = imageCache.get("retry-photo/1");
        assertNotNull(entry);
        assertArrayEquals(retriedBytes, entry.data);
        assertEquals(ImageValidator.Status.VALID, ImageValidator.validate(entry.data));
    }

    @Test
    public void retryImageReportsFetchFailureAndReleasesPermit() throws Exception {
        byte[] retriedBytes = createPng();
        AtomicInteger fetchCount = new AtomicInteger();
        PreloadService service = new PreloadService(
            imageCache, FileStore.getInstance(), null, null,
            imageExecutor, networkExecutor, null, null,
            new CacheCapacityPolicy(), 1,
            image -> {
                if (fetchCount.incrementAndGet() == 1) {
                    throw new IOException("fetch failed");
                }
                return retriedBytes;
            });

        RetryOutcome failed = retry(service, "fetch-failure-photo", retryImage());
        RetryOutcome retried = retry(service, "fetch-failure-photo", retryImage());

        assertNotNull(failed.error);
        assertFalse(failed.success);
        assertNull(retried.error);
        assertTrue(retried.success);
        assertEquals(2, fetchCount.get());
    }

    @Test
    public void retryImageRejectsUndecodableBytesWithoutReplacingCache() throws Exception {
        byte[] originalBytes = "old-corrupt-cache".getBytes(StandardCharsets.UTF_8);
        imageCache.put("invalid-retry-photo/1", originalBytes, "image/jpeg");
        PreloadService service = new PreloadService(
            imageCache, FileStore.getInstance(), null, null,
            imageExecutor, networkExecutor, null, null,
            new CacheCapacityPolicy(), 1,
            image -> "not-an-image".getBytes(StandardCharsets.UTF_8));

        RetryOutcome outcome = retry(service, "invalid-retry-photo", retryImage());

        assertNotNull(outcome.error);
        assertFalse(outcome.success);
        assertTrue(outcome.error.getMessage().contains("无法解码"));
        assertArrayEquals(originalBytes, imageCache.get("invalid-retry-photo/1").data);
    }

    @Test
    public void retryImageKeepsExistingCacheWhenReplacementCannotFit() throws Exception {
        byte[] originalBytes = "old-cache".getBytes(StandardCharsets.UTF_8);
        byte[] retriedBytes = createPng();
        imageCache.put("oversized-retry-photo/1", originalBytes, "image/jpeg");
        setCacheCapacity(retriedBytes.length - 1L);
        PreloadService service = new PreloadService(
            imageCache, FileStore.getInstance(), null, null,
            imageExecutor, networkExecutor, null, null,
            new CacheCapacityPolicy(), 1,
            image -> retriedBytes);

        RetryOutcome outcome = retry(service, "oversized-retry-photo", retryImage());

        assertNotNull(outcome.error);
        assertFalse(outcome.success);
        assertTrue(outcome.error.getMessage().contains("无法写入内存缓存"));
        assertArrayEquals(originalBytes, imageCache.get("oversized-retry-photo/1").data);
    }

    @Test
    public void retryImageDoesNotFetchOrWriteDuringCompletePressure() throws Exception {
        byte[] originalBytes = "old-cache".getBytes(StandardCharsets.UTF_8);
        imageCache.put("complete-retry-photo/1", originalBytes, "image/jpeg");
        AtomicInteger fetchCount = new AtomicInteger();
        PreloadService service = new PreloadService(
            imageCache, FileStore.getInstance(), null, null,
            imageExecutor, networkExecutor, null, null,
            new CacheCapacityPolicy(), 1,
            image -> {
                fetchCount.incrementAndGet();
                return createPng();
            });
        service.setMemoryPressureLevel(CacheCapacityPolicy.PressureLevel.COMPLETE);

        RetryOutcome outcome = retry(service, "complete-retry-photo", retryImage());

        assertNotNull(outcome.error);
        assertFalse(outcome.success);
        assertEquals(0, fetchCount.get());
        assertArrayEquals(originalBytes, imageCache.get("complete-retry-photo/1").data);
    }

    @Test
    public void retryImageDiscardsInFlightResultDuringCompletePressure() throws Exception {
        byte[] originalBytes = "old-cache".getBytes(StandardCharsets.UTF_8);
        imageCache.put("in-flight-retry-photo/1", originalBytes, "image/jpeg");
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch allowFetchFinish = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Boolean> success = new AtomicReference<>(false);
        AtomicReference<Exception> error = new AtomicReference<>();
        PreloadService service = new PreloadService(
            imageCache, FileStore.getInstance(), null, null,
            imageExecutor, networkExecutor, null, null,
            new CacheCapacityPolicy(), 1,
            image -> {
                fetchStarted.countDown();
                allowFetchFinish.await(2, TimeUnit.SECONDS);
                return createPng();
            });

        service.retryImage("in-flight-retry-photo", retryImage(),
            new PreloadService.ImageRetryCallback() {
                @Override
                public void onSuccess() {
                    success.set(true);
                    completed.countDown();
                }

                @Override
                public void onError(Exception retryError) {
                    error.set(retryError);
                    completed.countDown();
                }
            });
        assertTrue(fetchStarted.await(2, TimeUnit.SECONDS));

        service.setMemoryPressureLevel(CacheCapacityPolicy.PressureLevel.COMPLETE);
        allowFetchFinish.countDown();

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertNotNull(error.get());
        assertFalse(success.get());
        assertArrayEquals(originalBytes, imageCache.get("in-flight-retry-photo/1").data);
    }

    private RetryOutcome retry(PreloadService service, String photoId, JSONObject image)
        throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Boolean> success = new AtomicReference<>(false);
        AtomicReference<Exception> error = new AtomicReference<>();
        service.retryImage(photoId, image, new PreloadService.ImageRetryCallback() {
            @Override
            public void onSuccess() {
                success.set(true);
                completed.countDown();
            }

            @Override
            public void onError(Exception retryError) {
                error.set(retryError);
                completed.countDown();
            }
        });
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        return new RetryOutcome(success.get(), error.get());
    }

    private static JSONObject retryImage() throws Exception {
        return new JSONObject()
            .put("sortOrder", 1)
            .put("scrambleId", "scramble")
            .put("filename", "page.png")
            .put("url", "https://example.invalid/page.png");
    }

    private static byte[] createPng() {
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("Failed to create PNG fixture");
            }
            return output.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private void setCacheCapacity(long capacity) throws Exception {
        Field field = ImageCache.class.getDeclaredField("capacity");
        field.setAccessible(true);
        field.setLong(imageCache, capacity);
    }

    private static final class RetryOutcome {
        final boolean success;
        final Exception error;

        RetryOutcome(boolean success, Exception error) {
            this.success = success;
            this.error = error;
        }
    }
}
