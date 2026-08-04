package io.github.jukomu.service;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import android.graphics.Bitmap;

import io.github.jukomu.data.CacheCapacityPolicy;
import io.github.jukomu.data.FileStore;
import io.github.jukomu.data.ImageCache;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
    public void repairImageBypassesCorruptMemoryCache() throws Exception {
        byte[] repairedBytes = createPng();
        imageCache.put("repair-photo/1",
            "not-an-image".getBytes(StandardCharsets.UTF_8), "image/jpeg");

        AtomicInteger fetchCount = new AtomicInteger();
        PreloadService service = new PreloadService(
            imageCache, FileStore.getInstance(), null, null,
            imageExecutor, networkExecutor, null, null,
            new CacheCapacityPolicy(), 2,
            image -> {
                fetchCount.incrementAndGet();
                return repairedBytes;
            });

        RepairOutcome outcome = repair(service, "repair-photo", repairImage());

        assertNull(outcome.error);
        assertEquals(Boolean.FALSE, outcome.persisted);
        assertEquals(1, fetchCount.get());
        ImageCache.ImageEntry entry = imageCache.get("repair-photo/1");
        assertNotNull(entry);
        assertArrayEquals(repairedBytes, entry.data);
        assertTrue(ImageCache.isDecodableImage(entry.data));
    }

    @Test
    public void repairImageReportsFetchFailureAndReleasesPermit() throws Exception {
        byte[] repairedBytes = createPng();
        AtomicInteger fetchCount = new AtomicInteger();
        PreloadService service = new PreloadService(
            imageCache, FileStore.getInstance(), null, null,
            imageExecutor, networkExecutor, null, null,
            new CacheCapacityPolicy(), 1,
            image -> {
                if (fetchCount.incrementAndGet() == 1) {
                    throw new IOException("fetch failed");
                }
                return repairedBytes;
            });

        RepairOutcome failed = repair(service, "fetch-failure-photo", repairImage());
        RepairOutcome retried = repair(service, "fetch-failure-photo", repairImage());

        assertNotNull(failed.error);
        assertNull(failed.persisted);
        assertNull(retried.error);
        assertEquals(Boolean.FALSE, retried.persisted);
        assertEquals(2, fetchCount.get());
    }

    @Test
    public void repairImageRejectsUndecodableBytesWithoutReplacingCache() throws Exception {
        byte[] originalBytes = "old-corrupt-cache".getBytes(StandardCharsets.UTF_8);
        imageCache.put("invalid-repair-photo/1", originalBytes, "image/jpeg");
        PreloadService service = new PreloadService(
            imageCache, FileStore.getInstance(), null, null,
            imageExecutor, networkExecutor, null, null,
            new CacheCapacityPolicy(), 1,
            image -> "not-an-image".getBytes(StandardCharsets.UTF_8));

        RepairOutcome outcome = repair(service, "invalid-repair-photo", repairImage());

        assertNotNull(outcome.error);
        assertNull(outcome.persisted);
        assertTrue(outcome.error.getMessage().contains("无法解码"));
        assertArrayEquals(originalBytes, imageCache.get("invalid-repair-photo/1").data);
    }

    @Test
    public void repairImageDoesNotFetchOrWriteDuringCompletePressure() throws Exception {
        byte[] originalBytes = "old-cache".getBytes(StandardCharsets.UTF_8);
        imageCache.put("complete-repair-photo/1", originalBytes, "image/jpeg");
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

        RepairOutcome outcome = repair(service, "complete-repair-photo", repairImage());

        assertNotNull(outcome.error);
        assertNull(outcome.persisted);
        assertEquals(0, fetchCount.get());
        assertArrayEquals(originalBytes, imageCache.get("complete-repair-photo/1").data);
    }

    @Test
    public void repairImageDiscardsInFlightResultDuringCompletePressure() throws Exception {
        byte[] originalBytes = "old-cache".getBytes(StandardCharsets.UTF_8);
        imageCache.put("in-flight-repair-photo/1", originalBytes, "image/jpeg");
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch allowFetchFinish = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Boolean> persisted = new AtomicReference<>();
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

        service.repairImage("in-flight-repair-photo", repairImage(),
            new PreloadService.ImageRepairCallback() {
                @Override
                public void onSuccess(boolean wasPersisted) {
                    persisted.set(wasPersisted);
                    completed.countDown();
                }

                @Override
                public void onError(Exception repairError) {
                    error.set(repairError);
                    completed.countDown();
                }
            });
        assertTrue(fetchStarted.await(2, TimeUnit.SECONDS));

        service.setMemoryPressureLevel(CacheCapacityPolicy.PressureLevel.COMPLETE);
        allowFetchFinish.countDown();

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertNotNull(error.get());
        assertNull(persisted.get());
        assertArrayEquals(originalBytes, imageCache.get("in-flight-repair-photo/1").data);
    }

    private RepairOutcome repair(PreloadService service, String photoId, JSONObject image)
        throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Boolean> persisted = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        service.repairImage(photoId, image, new PreloadService.ImageRepairCallback() {
            @Override
            public void onSuccess(boolean wasPersisted) {
                persisted.set(wasPersisted);
                completed.countDown();
            }

            @Override
            public void onError(Exception repairError) {
                error.set(repairError);
                completed.countDown();
            }
        });
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        return new RepairOutcome(persisted.get(), error.get());
    }

    private static JSONObject repairImage() throws Exception {
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

    private static final class RepairOutcome {
        final Boolean persisted;
        final Exception error;

        RepairOutcome(Boolean persisted, Exception error) {
            this.persisted = persisted;
            this.error = error;
        }
    }
}
