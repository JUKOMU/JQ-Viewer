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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

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

        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger fetchCount = new AtomicInteger();
        AtomicReference<Exception> error = new AtomicReference<>();
        PreloadService service = new PreloadService(
            imageCache, FileStore.getInstance(), null, null,
            imageExecutor, networkExecutor, null, null,
            new CacheCapacityPolicy(), 2,
            image -> {
                fetchCount.incrementAndGet();
                return repairedBytes;
            });

        JSONObject image = new JSONObject()
            .put("sortOrder", 1)
            .put("scrambleId", "scramble")
            .put("filename", "page.png")
            .put("url", "https://example.invalid/page.png");
        service.repairImage("repair-photo", image, new PreloadService.ImageRepairCallback() {
            @Override
            public void onSuccess(boolean persisted) {
                completed.countDown();
            }

            @Override
            public void onError(Exception repairError) {
                error.set(repairError);
                completed.countDown();
            }
        });

        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertNull(error.get());
        assertEquals(1, fetchCount.get());
        ImageCache.ImageEntry entry = imageCache.get("repair-photo/1");
        assertNotNull(entry);
        assertArrayEquals(repairedBytes, entry.data);
        assertTrue(ImageCache.isDecodableImage(entry.data));
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
}
