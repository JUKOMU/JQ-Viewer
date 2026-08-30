package io.github.jukomu.picacomic;

import io.github.jukomu.feature.cache.ImageCache;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PicacomicImageLoaderTest {

    private final ImageCache cache = ImageCache.getInstance();
    private FakePicacomicRemoteClient fake;
    private PicacomicImageRegistry registry;
    private PicacomicImageRef image;

    @Before
    public void setUp() throws Exception {
        PicacomicCacheNamespace.clear(cache);
        fake = new FakePicacomicRemoteClient();
        registry = new PicacomicImageRegistry();
        PicacomicRemoteModels.Photo photo = FakePicacomicRemoteClient.initialAlbum().photos.get(1);
        ChapterRef ref = ChapterRef.of(photo.albumId, photo.id, photo.order);
        PicacomicChapterDetail detail = PicacomicMapper.mapChapterDetail(photo, ref, registry);
        image = detail.images.get(0);
    }

    @After
    public void tearDown() {
        PicacomicCacheNamespace.clear(cache);
    }

    @Test
    public void loaderKeepsLocatorNativeAndCachesOnlyOpaqueBytes() throws Exception {
        PicacomicImageSource source = registry.resolve(image.imageKey);
        assertEquals("/album-1/one/page-1.png", source.path);
        assertFalse(image.cacheUrl.contains("img.example.invalid"));

        PicacomicImageLoader loader = new PicacomicImageLoader(fake, registry, cache);
        assertTrue(loader.load(image, new PicacomicCancellationToken()));
        assertTrue(cache.has(image.cacheKey()));
        assertFalse(loader.load(image, new PicacomicCancellationToken()));
    }

    @Test
    public void invalidMimeBytesAreStableImageErrors() throws Exception {
        fake.returnInvalidImageBytes = true;
        PicacomicImageLoader loader = new PicacomicImageLoader(fake, registry, cache);

        try {
            loader.load(image, new PicacomicCancellationToken());
            fail("invalid bytes must fail");
        } catch (PicacomicException error) {
            assertEquals(PicacomicErrorCode.INVALID_RESPONSE, error.getErrorCode());
        }
        assertFalse(cache.has(image.cacheKey()));
    }

    @Test
    public void emptyBytesAreStableImageErrors() throws Exception {
        fake.imageBytes = new byte[0];
        PicacomicImageLoader loader = new PicacomicImageLoader(fake, registry, cache);

        try {
            loader.load(image, new PicacomicCancellationToken());
            fail("empty bytes must fail");
        } catch (PicacomicException error) {
            assertEquals(PicacomicErrorCode.INVALID_RESPONSE, error.getErrorCode());
        }
        assertFalse(cache.has(image.cacheKey()));
    }

    @Test
    public void oversizedImageIsRejectedBeforeCacheInsertion() throws Exception {
        fake.imageBytes = new byte[PicacomicImageLoader.MAX_IMAGE_BYTES + 1];
        fake.imageBytes[0] = (byte) 0xff;
        fake.imageBytes[1] = (byte) 0xd8;
        fake.imageBytes[2] = (byte) 0xff;
        PicacomicImageLoader loader = new PicacomicImageLoader(fake, registry, cache);

        try {
            loader.load(image, new PicacomicCancellationToken());
            fail("oversized bytes must fail");
        } catch (PicacomicException error) {
            assertEquals(PicacomicErrorCode.INVALID_RESPONSE, error.getErrorCode());
        }
        assertFalse(cache.has(image.cacheKey()));
    }

    @Test
    public void picaNamespaceClearLeavesLegacyJmKeyUntouched() {
        String jmKey = "same-id/1";
        cache.put(jmKey, new byte[]{9}, "image/jpeg");
        cache.put(image.cacheKey(), new byte[]{8}, "image/png");

        assertTrue(cache.has(jmKey));
        assertTrue(cache.has(image.cacheKey()));
        PicacomicCacheNamespace.clear(cache);
        assertTrue(cache.has(jmKey));
        assertFalse(cache.has(image.cacheKey()));
    }
}
