package io.github.jukomu.picacomic;

import io.github.jukomu.feature.cache.ImageCache;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PicacomicRuntimeContractTest {

    private final ImageCache cache = ImageCache.getInstance();
    private FakePicacomicRemoteClient fake;
    private PicacomicRuntime runtime;

    @Before
    public void setUp() {
        PicacomicCacheNamespace.clear(cache);
        fake = new FakePicacomicRemoteClient();
        runtime = PicacomicRuntime.createIsolated(
            () -> fake, cache);
    }

    @After
    public void tearDown() {
        runtime.close();
        PicacomicCacheNamespace.clear(cache);
    }

    @Test
    public void runtimeAndClientAreLazyUntilLogin() throws Exception {
        AtomicInteger factoryCalls = new AtomicInteger();
        PicacomicRuntime lazy = PicacomicRuntime.createIsolated(() -> {
            factoryCalls.incrementAndGet();
            return fake;
        }, cache);

        assertEquals(AuthSnapshot.State.SIGNED_OUT, lazy.getAuthState().state);
        assertEquals(0, factoryCalls.get());

        lazy.login("fixture-user", "fixture-password", new PicacomicCancellationToken());

        assertEquals(1, factoryCalls.get());
        assertEquals(AuthSnapshot.State.SIGNED_IN, lazy.getAuthState().state);
        lazy.close();
    }

    @Test
    public void orderDriftRefreshesByStableChapterIdBeforeReading() throws Exception {
        login();
        PicacomicRemoteModels.Album original = FakePicacomicRemoteClient.initialAlbum();
        PicacomicRemoteModels.Photo chapterOne = original.photos.get(1);
        PicacomicRemoteModels.Photo chapterTwo = original.photos.get(0);
        PicacomicRemoteModels.Album reordered = new PicacomicRemoteModels.Album(
            original.id, original.title, original.author, original.chineseTeam,
            original.categories, original.tags, original.thumb, original.description,
            original.pagesCount, original.epsCount, original.finished, original.createdAt,
            original.updatedAt, Arrays.asList(
                new PicacomicRemoteModels.Photo(chapterTwo.albumId, chapterTwo.id,
                    chapterTwo.title, chapterTwo.updatedAt, 2, chapterTwo.isSingleAlbum,
                    chapterTwo.images),
                new PicacomicRemoteModels.Photo(chapterOne.albumId, chapterOne.id,
                    chapterOne.title, chapterOne.updatedAt, 3, chapterOne.isSingleAlbum,
                    chapterOne.images)));
        fake.setAlbum(reordered);
        fake.returnWrongFirstChapter = true;

        PicacomicChapterDetail detail = runtime.getPhoto(
            ChapterRef.of("album-1", "chapter-1", 1), new PicacomicCancellationToken());

        assertEquals("chapter-1", detail.ref.chapterId);
        assertEquals(3, detail.ref.order);
        assertEquals(2, fake.getPhotoCalls.get());
        assertEquals(1, fake.getAlbumCalls.get());
    }

    @Test
    public void missingStableChapterReturnsStaleResourceWithoutWrongContent() throws Exception {
        login();
        fake.returnWrongFirstChapter = true;
        fake.omitRequestedChapter = true;

        try {
            runtime.getPhoto(ChapterRef.of("album-1", "chapter-1", 1),
                new PicacomicCancellationToken());
            fail("missing chapter must be stale");
        } catch (PicacomicException error) {
            assertEquals(PicacomicErrorCode.STALE_RESOURCE, error.getErrorCode());
        }
    }

    @Test
    public void authFailuresExpireButNetworkAndParseFailuresDoNotClearSession() throws Exception {
        login();
        fake.chapterFailure = FakePicacomicRemoteClient.Failure.UNAUTHORIZED;
        try {
            runtime.getAlbum("album-1", new PicacomicCancellationToken());
            fail("401 must fail");
        } catch (PicacomicException error) {
            assertEquals(PicacomicErrorCode.AUTH_EXPIRED, error.getErrorCode());
        }
        assertEquals(AuthSnapshot.State.EXPIRED, runtime.getAuthState().state);

        fake.chapterFailure = FakePicacomicRemoteClient.Failure.NONE;
        login();
        fake.chapterFailure = FakePicacomicRemoteClient.Failure.FORBIDDEN;
        try {
            runtime.getAlbum("album-1", new PicacomicCancellationToken());
            fail("403 must fail");
        } catch (PicacomicException error) {
            assertEquals(PicacomicErrorCode.AUTH_EXPIRED, error.getErrorCode());
        }
        assertEquals(AuthSnapshot.State.EXPIRED, runtime.getAuthState().state);

        fake.chapterFailure = FakePicacomicRemoteClient.Failure.NONE;
        login();
        fake.chapterFailure = FakePicacomicRemoteClient.Failure.NETWORK;
        try {
            runtime.getAlbum("album-1", new PicacomicCancellationToken());
            fail("network failure must fail");
        } catch (PicacomicException error) {
            assertEquals(PicacomicErrorCode.NETWORK, error.getErrorCode());
        }
        assertEquals(AuthSnapshot.State.SIGNED_IN, runtime.getAuthState().state);

        fake.chapterFailure = FakePicacomicRemoteClient.Failure.NONE;
        fake.catalogFailure = FakePicacomicRemoteClient.Failure.PARSE;
        try {
            runtime.search("fixture", "latest", 1, new PicacomicCancellationToken());
            fail("parse failure must fail");
        } catch (PicacomicException error) {
            assertEquals(PicacomicErrorCode.INVALID_RESPONSE, error.getErrorCode());
        }
        assertEquals(AuthSnapshot.State.SIGNED_IN, runtime.getAuthState().state);
    }

    @Test
    public void cancellationIsStableAndDoesNotExpireSession() throws Exception {
        login();
        PicacomicCancellationToken cancellation = new PicacomicCancellationToken();
        cancellation.cancel();

        try {
            runtime.search("fixture", "latest", 1, cancellation);
            fail("cancelled operation must fail");
        } catch (PicacomicException error) {
            assertEquals(PicacomicErrorCode.CANCELLED, error.getErrorCode());
        }
        assertEquals(AuthSnapshot.State.SIGNED_IN, runtime.getAuthState().state);
    }

    @Test
    public void logoutClearsOnlyPicaRegistryPendingAndCacheAndCloseIsIdempotent() throws Exception {
        login();
        PicacomicChapterDetail detail = runtime.getPhoto(
            ChapterRef.of("album-1", "chapter-1", 1), new PicacomicCancellationToken());
        PicacomicImageRef image = detail.images.get(0);
        String jmKey = "20/1";
        cache.put(jmKey, new byte[]{1, 2, 3}, "image/jpeg");

        AtomicReference<PicacomicImageEvent> event = new AtomicReference<>();
        PicacomicImageRequestResult accepted = runtime.requestImages(
            Collections.singletonList(image.imageKey), false, event::set);
        assertTrue(accepted.pending.contains(image.imageKey));
        await(() -> cache.has(image.cacheKey()));
        await(() -> event.get() != null);
        assertEquals(PicacomicImageEvent.Type.READY, event.get().type);

        runtime.logout();

        assertFalse(cache.has(image.cacheKey()));
        assertTrue(cache.has(jmKey));
        assertEquals(0, runtime.getImageRegistry().size());
        assertEquals(1, fake.closeCalls.get());
        runtime.close();
        assertEquals(1, fake.closeCalls.get());
    }

    @Test
    public void logoutCancelsInFlightImageWithoutLateReadyEvent() throws Exception {
        login();
        PicacomicChapterDetail detail = runtime.getPhoto(
            ChapterRef.of("album-1", "chapter-1", 1), new PicacomicCancellationToken());
        PicacomicImageRef image = detail.images.get(0);
        fake.blockImage = true;
        AtomicReference<PicacomicImageEvent> event = new AtomicReference<>();

        runtime.requestImages(Collections.singletonList(image.imageKey), false, event::set);
        assertTrue(fake.imageStarted.await(1, TimeUnit.SECONDS));
        runtime.logout();
        fake.releaseImage.countDown();
        await(() -> runtime.pendingImageCount() == 0);

        assertFalse(cache.has(image.cacheKey()));
        assertTrue(event.get() == null || event.get().type != PicacomicImageEvent.Type.READY);
        assertEquals(1, fake.closeCalls.get());
    }

    private void login() throws Exception {
        runtime.login("fixture-user", "fixture-password", new PicacomicCancellationToken());
    }

    private static void await(Check condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.value() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue("condition was not met", condition.value());
    }

    private interface Check {
        boolean value();
    }
}
