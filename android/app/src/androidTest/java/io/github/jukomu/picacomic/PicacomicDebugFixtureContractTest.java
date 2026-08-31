package io.github.jukomu.picacomic;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.jukomu.feature.cache.ImageCache;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Verifies that a debug Android flow uses the CP2 native fake boundary. */
@RunWith(AndroidJUnit4.class)
public class PicacomicDebugFixtureContractTest {

    private PicacomicRuntime runtime;

    @After
    public void tearDown() {
        if (runtime != null) runtime.close();
        PicacomicRuntime.resetProcessForTests();
        PicacomicCacheNamespace.clear(ImageCache.getInstance());
    }

    @Test
    public void debugRuntimeProvidesFakeCatalogChapterAndNativeImageBoundary() throws Exception {
        runtime = PicacomicRuntime.getOrCreate(
            InstrumentationRegistry.getInstrumentation().getTargetContext());
        runtime.login("fixture-user", "fixture-password", new PicacomicCancellationToken());

        PicacomicCatalogPage page = runtime.search(
            "fixture", "latest", 1, new PicacomicCancellationToken());
        assertEquals(1, page.items.size());
        assertTrue(page.items.get(0).ref.albumId.startsWith("fixture-album-"));

        PicacomicAlbumDetail album = runtime.getAlbum(
            page.items.get(0).ref.albumId, new PicacomicCancellationToken());
        PicacomicChapterDetail chapter = runtime.getPhoto(
            album.chapters.get(0).ref, new PicacomicCancellationToken());
        PicacomicImageRef image = chapter.images.get(0);
        assertTrue(image.imageKey.startsWith("pica-"));
        assertTrue(image.cacheUrl.contains("/picacomic/"));

        CountDownLatch ready = new CountDownLatch(1);
        PicacomicImageRequestResult request = runtime.requestImages(
            java.util.Collections.singletonList(image.imageKey), false, event -> ready.countDown(),
            new PicacomicCancellationToken());
        assertEquals(1, request.pending.size());
        assertTrue(ready.await(2, TimeUnit.SECONDS));
        assertTrue(ImageCache.getInstance().has(image.cacheKey()));
        assertFalse(image.cacheUrl.contains("debug-fixture"));
    }
}
