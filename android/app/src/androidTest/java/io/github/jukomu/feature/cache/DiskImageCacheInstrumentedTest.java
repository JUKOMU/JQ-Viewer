package io.github.jukomu.feature.cache;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DiskImageCacheInstrumentedTest {

    @Test
    public void coldLayerEvictsItsOwnLeastRecentlyUsedEntry() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getCacheDir(), "image-cache-test-" + System.nanoTime());
        DiskImageCache disk = new DiskImageCache(context, directory);
        try {
            disk.recover();
            disk.setCapacity(128L * 1024L);
            assertTrue(disk.put("a", new byte[64 * 1024], "image/jpeg"));
            assertTrue(disk.put("b", new byte[64 * 1024], "image/jpeg"));
            try (DiskImageCache.Entry ignored = disk.get("a")) {
                assertNotNull(ignored);
            }
            assertTrue(disk.put("c", new byte[64 * 1024], "image/jpeg"));

            assertFalse(disk.has("b"));
            assertTrue(disk.has("a"));
            assertTrue(disk.has("c"));
        } finally {
            disk.clear();
            disk.close();
            deleteRecursively(directory);
        }
    }

    @Test
    public void accessOrderIsMergedOnTheNextWrite() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getCacheDir(), "image-cache-test-" + System.nanoTime());
        DiskImageCache disk = new DiskImageCache(context, directory);
        try {
            disk.recover();
            disk.setCapacity(64L * 1024L);
            assertTrue(disk.put("a", new byte[32 * 1024], "image/jpeg"));
            assertTrue(disk.put("b", new byte[32 * 1024], "image/jpeg"));
            try (DiskImageCache.Entry ignored = disk.get("a")) {
                assertNotNull(ignored);
            }
            disk.setCapacity(64L * 1024L);

            DiskImageCache restored = new DiskImageCache(context, directory);
            try {
                restored.recover();
                restored.setCapacity(64L * 1024L);
                assertTrue(restored.put("c", new byte[32 * 1024], "image/jpeg"));
                assertFalse(restored.has("b"));
                assertTrue(restored.has("a"));
                assertTrue(restored.has("c"));
            } finally {
                restored.close();
            }
        } finally {
            disk.clear();
            disk.close();
            deleteRecursively(directory);
        }
    }

    @Test
    public void retiredFileIsDeletedAfterActiveReaderCloses() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getCacheDir(), "image-cache-test-" + System.nanoTime());
        DiskImageCache disk = new DiskImageCache(context, directory);
        try {
            disk.recover();
            disk.setCapacity(128L * 1024L);
            assertTrue(disk.put("leased", new byte[4 * 1024], "image/jpeg"));
            DiskImageCache.Entry entry = disk.get("leased");
            assertNotNull(entry);
            File file = entry.file;
            disk.removeKey("leased");
            assertTrue(file.exists());
            entry.close();
            assertFalse(file.exists());
        } finally {
            disk.clear();
            disk.close();
            deleteRecursively(directory);
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
