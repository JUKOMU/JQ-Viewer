package io.github.jukomu.feature.cache;

import android.content.Context;
import android.webkit.WebResourceResponse;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ImageCacheFileResponseInstrumentedTest {

    @Test
    public void fullImageFallsBackToFileStreamWhenReservationIsUnavailable() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File image = File.createTempFile("image-cache-fallback-", ".jpg", context.getCacheDir());
        try {
            try (RandomAccessFile output = new RandomAccessFile(image, "rw")) {
                output.write(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
                output.setLength(17L * CacheCapacityPolicy.MIB);
            }

            ImageCache cache = ImageCache.createIsolated();
            cache.applyPolicy(new CacheCapacityPolicy().calculate(
                16L, 64L * CacheCapacityPolicy.MIB, false,
                CacheCapacityPolicy.PressureLevel.NORMAL));
            assertNull(cache.prepareForIncomingBytes(image.length()));

            WebResourceResponse response = ImageCache.createFileResponse(image);
            assertEquals("image/jpeg", response.getMimeType());
            try (InputStream input = response.getData()) {
                byte[] header = new byte[3];
                assertEquals(3, input.read(header));
                assertArrayEquals(
                    new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
                    header
                );
            }
        } finally {
            image.delete();
        }
    }
}
