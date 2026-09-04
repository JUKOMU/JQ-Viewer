package io.github.jukomu.feature.preload;

import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.jukomu.feature.cache.CacheCapacityPolicy;
import io.github.jukomu.feature.cache.ImageCache;
import io.github.jukomu.feature.download.storage.FileStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PreloadServiceTest {

    private final ImageCache imageCache = ImageCache.getInstance();
    private final FileStore fileStore = FileStore.getInstance();
    private ExecutorService imageExecutor;
    private ExecutorService networkExecutor;
    private Field baseDirField;
    private File originalBaseDir;
    private File testBaseDir;
    private Map<String, String> chapterMappings;
    private Map<String, String> filenameMappings;
    private Map<String, String> originalChapterMappings;
    private Map<String, String> originalFilenameMappings;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        imageCache.applyPolicy(new CacheCapacityPolicy().calculate(
            16, 64L * CacheCapacityPolicy.MIB, false,
            CacheCapacityPolicy.PressureLevel.NORMAL));
        imageCache.clear();
        imageExecutor = Executors.newSingleThreadExecutor();
        networkExecutor = Executors.newSingleThreadExecutor();

        testBaseDir = new File(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
            "preload-service-test-" + System.nanoTime());
        if (!testBaseDir.mkdirs()) {
            throw new IOException("无法创建预载测试目录");
        }
        baseDirField = FileStore.class.getDeclaredField("baseDir");
        baseDirField.setAccessible(true);
        originalBaseDir = (File) baseDirField.get(fileStore);
        baseDirField.set(fileStore, testBaseDir);

        Field chapterMappingsField = FileStore.class.getDeclaredField("chapterIdToAlbumId");
        chapterMappingsField.setAccessible(true);
        chapterMappings = (Map<String, String>) chapterMappingsField.get(fileStore);
        originalChapterMappings = new HashMap<>(chapterMappings);
        chapterMappings.clear();

        Field filenameMappingsField = FileStore.class.getDeclaredField("sortOrderToFilename");
        filenameMappingsField.setAccessible(true);
        filenameMappings = (Map<String, String>) filenameMappingsField.get(fileStore);
        originalFilenameMappings = new HashMap<>(filenameMappings);
        filenameMappings.clear();
    }

    @After
    public void tearDown() throws Exception {
        imageExecutor.shutdownNow();
        networkExecutor.shutdownNow();
        imageCache.clear();
        chapterMappings.clear();
        chapterMappings.putAll(originalChapterMappings);
        filenameMappings.clear();
        filenameMappings.putAll(originalFilenameMappings);
        baseDirField.set(fileStore, originalBaseDir);
        deleteRecursive(testBaseDir);
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

    @Test
    public void validLocalImageIsCachedWithoutNetworkRequest() throws Exception {
        prepareLocalImage("local-valid", createPng());
        AtomicInteger fetchCount = new AtomicInteger();
        PreloadService service = createService(image -> {
            fetchCount.incrementAndGet();
            return createPng();
        });

        JSONObject result = service.preloadImages("local-valid", "image", imageArray());

        assertEquals(1, result.getJSONArray("cached").length());
        assertEquals(0, fetchCount.get());
        assertTrue(imageCache.has("local-valid/1"));
    }

    @Test
    public void invalidLocalImageFallsThroughToNetworkRequest() throws Exception {
        prepareLocalImage("local-invalid", new byte[]{1, 2, 3, 4});
        CountDownLatch fetched = new CountDownLatch(1);
        PreloadService service = createService(image -> {
            fetched.countDown();
            return createPng();
        });

        JSONObject result = service.preloadImages("local-invalid", "image", imageArray());

        assertEquals(1, result.getJSONArray("pending").length());
        assertTrue(fetched.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void networkThumbnailNotifiesOriginalAndThumbReadyAfterCachingBoth() throws Exception {
        List<String> readyTypes = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(2);
        PreloadService service = createService(image -> createPng(), new PreloadEventSink() {
            @Override
            public void onImageReady(String photoId, int sortOrder, String type) {
                assertEquals("network-thumb", photoId);
                assertEquals(1, sortOrder);
                if ("image".equals(type)) {
                    assertTrue(imageCache.has("network-thumb/1"));
                    assertTrue(imageCache.has("network-thumb/1/thumb"));
                }
                readyTypes.add(type);
                ready.countDown();
            }

            @Override
            public void onImageFailed(String photoId, int sortOrder, String type) {
                fail("有效缩略图不应发送 imageFailed");
            }
        });

        JSONObject result = service.preloadImages("network-thumb", "thumb", imageArray());

        assertEquals(1, result.getJSONArray("pending").length());
        assertTrue(ready.await(1, TimeUnit.SECONDS));
        assertEquals(java.util.Arrays.asList("image", "thumb"), readyTypes);
        assertTrue(imageCache.has("network-thumb/1"));
        assertTrue(imageCache.has("network-thumb/1/thumb"));
    }

    @Test
    public void localThumbnailNotifiesOriginalAndThumbReadyAfterCachingBoth() throws Exception {
        prepareLocalImage("local-thumb", createPng());
        List<String> readyTypes = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(2);
        PreloadService service = createService(image -> createPng(), new PreloadEventSink() {
            @Override
            public void onImageReady(String photoId, int sortOrder, String type) {
                assertEquals("local-thumb", photoId);
                assertEquals(1, sortOrder);
                if ("image".equals(type)) {
                    assertTrue(imageCache.has("local-thumb/1"));
                    assertTrue(imageCache.has("local-thumb/1/thumb"));
                }
                readyTypes.add(type);
                ready.countDown();
            }

            @Override
            public void onImageFailed(String photoId, int sortOrder, String type) {
                fail("有效本地缩略图不应发送 imageFailed");
            }
        });

        JSONObject result = service.preloadImages("local-thumb", "thumb", imageArray());

        assertEquals(1, result.getJSONArray("pending").length());
        assertTrue(ready.await(1, TimeUnit.SECONDS));
        assertEquals(java.util.Arrays.asList("image", "thumb"), readyTypes);
        assertTrue(imageCache.has("local-thumb/1"));
        assertTrue(imageCache.has("local-thumb/1/thumb"));
    }

    @Test
    public void localImageCacheAdmissionFailureNotifiesImageFailure() throws Exception {
        byte[] oversizedImage = new byte[17 * 1024 * 1024];
        byte[] png = createPng();
        System.arraycopy(png, 0, oversizedImage, 0, png.length);
        prepareLocalImage("local-cache-admission-failed", oversizedImage);

        CountDownLatch failed = new CountDownLatch(1);
        PreloadService service = createService(image -> createPng(), new PreloadEventSink() {
            @Override
            public void onImageReady(String photoId, int sortOrder, String type) {
            }

            @Override
            public void onImageFailed(String photoId, int sortOrder, String type) {
                assertEquals("local-cache-admission-failed", photoId);
                assertEquals(1, sortOrder);
                assertEquals("image", type);
                failed.countDown();
            }
        });

        JSONObject result = service.preloadImages(
            "local-cache-admission-failed", "image", imageArray());

        assertEquals(0, result.getJSONArray("cached").length());
        assertEquals(0, result.getJSONArray("pending").length());
        assertTrue(failed.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void networkFailureNotifiesCurrentImageFailure() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<String> failedPhotoId = new AtomicReference<>();
        AtomicInteger failedSortOrder = new AtomicInteger();
        PreloadService service = createService(image -> {
            throw new IOException("network failed");
        }, new PreloadEventSink() {
            @Override
            public void onImageReady(String photoId, int sortOrder, String type) {
            }

            @Override
            public void onImageFailed(String photoId, int sortOrder, String type) {
                failedPhotoId.set(photoId);
                failedSortOrder.set(sortOrder);
                failed.countDown();
            }
        });

        service.preloadImages("network-failed", "image", imageArray());

        assertTrue(failed.await(1, TimeUnit.SECONDS));
        assertEquals("network-failed", failedPhotoId.get());
        assertEquals(1, failedSortOrder.get());
    }

    @Test
    public void invalidNetworkImageBytesNotifyFailureWithoutCaching() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        PreloadService service = createService(image -> new byte[]{1, 2, 3, 4},
            new PreloadEventSink() {
                @Override
                public void onImageReady(String photoId, int sortOrder, String type) {
                    fail("无效网络图片不应发送 imageReady");
                }

                @Override
                public void onImageFailed(String photoId, int sortOrder, String type) {
                    assertEquals("invalid-network-image", photoId);
                    assertEquals(1, sortOrder);
                    assertEquals("image", type);
                    failed.countDown();
                }
            });

        service.preloadImages("invalid-network-image", "image", imageArray());

        assertTrue(failed.await(1, TimeUnit.SECONDS));
        networkExecutor.submit(() -> {
        }).get(1, TimeUnit.SECONDS);
        assertFalse(imageCache.has("invalid-network-image/1"));
    }

    @Test
    public void retryImageBypassesExistingCacheAndRejectsInvalidReplacement() throws Exception {
        byte[] oldBytes = new byte[]{9, 8, 7};
        imageCache.put("retry-photo/1", oldBytes, "image/jpeg");
        AtomicInteger fetchCount = new AtomicInteger();
        PreloadService validService = createService(image -> {
            fetchCount.incrementAndGet();
            return createPng();
        });

        RetryOutcome valid = retry(validService, "retry-photo");

        assertNull(valid.error);
        assertEquals(1, fetchCount.get());
        assertFalse(java.util.Arrays.equals(oldBytes, imageCache.get("retry-photo/1").data));

        byte[] validBytes = imageCache.get("retry-photo/1").data;
        PreloadService invalidService = createService(image -> new byte[]{1, 2, 3, 4});
        RetryOutcome invalid = retry(invalidService, "retry-photo");

        assertNotNull(invalid.error);
        assertArrayEquals(validBytes, imageCache.get("retry-photo/1").data);
    }

    private PreloadService createService(PreloadService.ImageFetcher imageFetcher) {
        return createService(imageFetcher, null);
    }

    private PreloadService createService(PreloadService.ImageFetcher imageFetcher,
                                         PreloadEventSink eventSink) {
        return new PreloadService(
            imageCache, fileStore, null, null,
            imageExecutor, networkExecutor, eventSink, null,
            new CacheCapacityPolicy(), 2, imageFetcher);
    }

    private void prepareLocalImage(String photoId, byte[] bytes) throws Exception {
        String albumId = "album";
        String filename = "page.png";
        chapterMappings.put(photoId, albumId);
        filenameMappings.put(albumId + "_" + photoId + "_1", filename);
        File chapterDir = new File(testBaseDir, albumId + File.separator + photoId);
        if (!chapterDir.mkdirs()) {
            throw new IOException("无法创建章节测试目录");
        }
        try (FileOutputStream output = new FileOutputStream(new File(chapterDir, filename))) {
            output.write(bytes);
        }
    }

    private RetryOutcome retry(PreloadService service, String photoId) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        service.retryImage(photoId, imageArray().getJSONObject(0),
            new PreloadService.ImageRetryCallback() {
                @Override
                public void onSuccess() {
                    completed.countDown();
                }

                @Override
                public void onError(Exception retryError) {
                    error.set(retryError);
                    completed.countDown();
                }
            });
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        return new RetryOutcome(error.get());
    }

    private static JSONArray imageArray() throws Exception {
        return new JSONArray().put(new JSONObject()
            .put("sortOrder", 1)
            .put("scrambleId", "scramble")
            .put("filename", "page.png")
            .put("url", "https://example.invalid/page.png")
            .put("queryParams", ""));
    }

    private static byte[] createPng() {
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("无法创建 PNG 测试图片");
            }
            return output.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        file.delete();
    }

    private static final class RetryOutcome {
        private final Exception error;

        private RetryOutcome(Exception error) {
            this.error = error;
        }
    }
}
