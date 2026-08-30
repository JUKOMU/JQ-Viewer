package io.github.jukomu.picacomic;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.github.jukomu.feature.cache.ImageCache;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Android-side contract smoke test; it uses only an in-process fake client. */
@RunWith(AndroidJUnit4.class)
public class PicacomicAndroidContractTest {

    private final ImageCache cache = ImageCache.getInstance();
    private PicacomicRuntime runtime;

    @Before
    public void setUp() {
        PicacomicCacheNamespace.clear(cache);
        runtime = PicacomicRuntime.createIsolated(AndroidFakeClient::new, cache);
    }

    @After
    public void tearDown() {
        runtime.close();
        PicacomicCacheNamespace.clear(cache);
    }

    @Test
    public void androidRuntimeMapsFakeChapterAndKeepsPicaCacheIsolated() throws Exception {
        runtime.login("fixture-user", "fixture-password", new PicacomicCancellationToken());
        PicacomicChapterDetail detail = runtime.getPhoto(
            ChapterRef.of("album-android", "chapter-android", 1),
            new PicacomicCancellationToken());
        PicacomicImageRef image = detail.images.get(0);
        cache.put(image.cacheKey(), new byte[]{1, 2, 3}, "image/jpeg");
        cache.put("20/1", new byte[]{4, 5, 6}, "image/jpeg");

        assertNotNull(ImageCache.handleRequest(image.cacheUrl));
        assertNull(ImageCache.handleRequest(
            "https://jqviewer.local/picacomic/20/1"));
        runtime.logout();

        assertTrue(!cache.has(image.cacheKey()));
        assertTrue(cache.has("20/1"));
    }

    private static final class AndroidFakeClient implements PicacomicRemoteClient {
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public PicacomicRemoteModels.User login(String usernameOrEmail, String password,
                                                PicacomicCancellationToken cancellation)
            throws Exception {
            cancellation.throwIfCancelled();
            return new PicacomicRemoteModels.User("android-user", "android-fixture", "", "");
        }

        @Override
        public PicacomicRemoteModels.Page search(String query, String order, int page,
                                                 PicacomicCancellationToken cancellation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PicacomicRemoteModels.Page categories(String category, String order, int page,
                                                     PicacomicCancellationToken cancellation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PicacomicRemoteModels.Album getAlbum(String albumId,
                                                    PicacomicCancellationToken cancellation)
            throws Exception {
            cancellation.throwIfCancelled();
            return album();
        }

        @Override
        public PicacomicRemoteModels.Photo getPhoto(String albumId, int order,
                                                    PicacomicCancellationToken cancellation)
            throws Exception {
            cancellation.throwIfCancelled();
            return album().photos.get(0);
        }

        @Override
        public byte[] fetchImageBytes(PicacomicImageSource source,
                                      PicacomicCancellationToken cancellation)
            throws Exception {
            cancellation.throwIfCancelled();
            return new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff};
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }

        private static PicacomicRemoteModels.Album album() {
            PicacomicRemoteModels.Image image = new PicacomicRemoteModels.Image(
                "android.jpg", "https://img.example.invalid", "/android/page.jpg", null);
            PicacomicRemoteModels.Photo photo = new PicacomicRemoteModels.Photo(
                "album-android", "chapter-android", "Android chapter", "2026-01-01", 1,
                true, Collections.singletonList(image));
            return new PicacomicRemoteModels.Album(
                "album-android", "Android fixture", null, null, null, null, null,
                null, 1, 1, false, null, null, Collections.singletonList(photo));
        }
    }
}
