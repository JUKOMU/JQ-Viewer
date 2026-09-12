package io.github.jukomu.desktop;

import io.github.jukomu.desktop.feature.image.ImageCache;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ImageCacheTest {
    @Test
    void evictsLeastRecentlyUsedEntriesByByteCapacity() {
        ImageCache cache = new ImageCache(6);
        cache.put("photo-a/1/image", new byte[]{1, 2, 3}, "image/jpeg");
        cache.put("photo-b/1/image", new byte[]{4, 5, 6}, "image/jpeg");
        cache.get("photo-a/1/image");
        cache.put("photo-c/1/thumb", new byte[]{7, 8, 9}, "image/jpeg");

        assertEquals(6, cache.usedBytes());
        assertNull(cache.get("photo-b/1/image"));
        List<ImageCache.Info> entries = cache.snapshot();
        assertEquals(2, entries.size());
        assertEquals("photo-a", entries.get(0).photoId());
        assertEquals("thumb", entries.get(1).type());
        assertEquals(3, cache.get("photo-a/1/image").bytes().length);
    }

    @Test
    void ignoresAnEntryLargerThanTheConfiguredCapacity() {
        ImageCache cache = new ImageCache(2);
        cache.put("photo-a/1/image", new byte[]{1, 2, 3}, "image/jpeg");

        assertEquals(0, cache.usedBytes());
        assertEquals(0, cache.snapshot().size());
    }
}
