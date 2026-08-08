package io.github.jukomu.data;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ImageCacheTest {

    private static final int MIB = 1024 * 1024;

    private final ImageCache cache = ImageCache.createIsolated();

    @Before
    public void setUp() {
        CacheCapacityPolicy.Result result = new CacheCapacityPolicy().calculate(
            16, 64L * MIB, false, CacheCapacityPolicy.PressureLevel.NORMAL);
        cache.applyPolicy(result);
        cache.clear();
    }

    @After
    public void tearDown() {
        cache.clear();
    }

    @Test
    public void rejectsSingleObjectLargerThanEffectiveCapacity() {
        assertFalse(cache.canAccept(17L * MIB));
        assertNull(cache.prepareForIncomingBytes(17L * MIB));
    }

    @Test
    public void admissionEvictsOldEntriesBeforeReading() {
        cache.put("old", new byte[10 * MIB], "image/jpeg");

        try (ImageCache.IncomingReservation reservation =
                 cache.prepareForIncomingBytes(10L * MIB)) {
            assertTrue(reservation != null);
            assertFalse(cache.has("old"));
            assertTrue(cache.getStats().reservedBytes == 10L * MIB);
        }

        assertTrue(cache.getStats().reservedBytes == 0L);
    }

    @Test
    public void failedHeapMarginCheckDoesNotEvictExistingEntries() {
        cache.put("old", new byte[]{1, 2, 3, 4}, "image/jpeg");
        long sizeBefore = cache.getCurrentSize();

        assertFalse(cache.evictForHeapMargin(100L, 100L, 100L));

        assertTrue(cache.has("old"));
        assertTrue(cache.getCurrentSize() == sizeBefore);
    }

    @Test
    public void concurrentReservationsAndWritesNeverExceedCapacity() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = writer("first", start, failure);
        Thread second = writer("second", start, failure);

        first.start();
        second.start();
        start.countDown();
        first.join();
        second.join();

        if (failure.get() != null) throw new AssertionError(failure.get());
        assertTrue(cache.getCurrentSize() <= cache.getCapacity());
        assertTrue(cache.getStats().reservedBytes == 0L);
    }

    @Test
    public void pressureTrimOnlyRemovesCurrentEntries() {
        cache.put("one", new byte[4 * MIB], "image/jpeg");
        cache.put("two", new byte[4 * MIB], "image/jpeg");
        cache.put("three", new byte[4 * MIB], "image/jpeg");

        cache.trimToFraction(0.5);

        assertTrue(cache.getCurrentSize() <= 8L * MIB);
        assertTrue(cache.getCapacity() == 16L * MIB);
    }

    @Test
    public void entriesSnapshotReturnsKeysAndMetadataWithoutChangingEntries() {
        cache.put("20/2", new byte[]{1, 2}, "image/jpeg");
        cache.put("20/2/thumb", new byte[]{3}, "image/png");

        java.util.List<ImageCache.CacheEntryInfo> entries = cache.getEntriesSnapshot();

        assertEquals(2, entries.size());
        assertEquals("20/2", entries.get(0).key);
        assertEquals(2L, entries.get(0).sizeBytes);
        assertEquals("image/jpeg", entries.get(0).mimeType);
        assertEquals("20/2/thumb", entries.get(1).key);
    }

    private Thread writer(String key, CountDownLatch start, AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                start.await();
                try (ImageCache.IncomingReservation reservation =
                         cache.prepareForIncomingBytes(8L * MIB)) {
                    if (reservation != null) {
                        cache.put(key, new byte[8 * MIB], "image/jpeg", reservation);
                    }
                }
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
    }
}
