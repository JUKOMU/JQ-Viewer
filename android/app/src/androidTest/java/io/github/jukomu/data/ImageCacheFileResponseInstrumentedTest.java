package io.github.jukomu.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.webkit.WebResourceResponse;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class ImageCacheFileResponseInstrumentedTest {

    @Test
    public void decodeValidationRejectsGarbageAndAcceptsPng() {
        assertFalse(ImageCache.isDecodableImage(
            "not-an-image".getBytes(StandardCharsets.UTF_8)));

        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            byte[] png = output.toByteArray();
            assertTrue(ImageCache.isDecodableImage(png));
            assertFalse(ImageCache.isDecodableImage(Arrays.copyOf(png, 33)));

            output.reset();
            assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, output));
            byte[] webp = output.toByteArray();
            assertTrue(ImageCache.isDecodableImage(webp));
            assertFalse(ImageCache.isDecodableImage(Arrays.copyOf(webp, webp.length - 1)));

            output.reset();
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output));
            byte[] jpeg = output.toByteArray();
            assertTrue(ImageCache.isDecodableImage(jpeg));
            assertFalse(ImageCache.isDecodableImage(Arrays.copyOf(jpeg, jpeg.length - 1)));
        } finally {
            bitmap.recycle();
        }
    }

    @Test
    public void interceptedImageResponseDisablesWebViewCaching() throws Exception {
        ImageCache cache = ImageCache.getInstance();
        cache.applyPolicy(new CacheCapacityPolicy().calculate(
            16L, 64L * CacheCapacityPolicy.MIB, false,
            CacheCapacityPolicy.PressureLevel.NORMAL));
        cache.clear();
        try {
            assertTrue(cache.put("cache-header-photo/1", new byte[]{1, 2, 3}, "image/jpeg"));

            WebResourceResponse response = ImageCache.handleRequest(
                "https://jqviewer.local/image/cache-header-photo/1?repair=1");

            assertNotNull(response);
            assertNull(response.getEncoding());
            assertNoCache(response);
            response.getData().close();
        } finally {
            cache.clear();
        }
    }

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
            assertNoCache(response);
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

    private static void assertNoCache(WebResourceResponse response) {
        assertNotNull(response.getResponseHeaders());
        assertEquals("no-store, no-cache, must-revalidate, max-age=0",
            response.getResponseHeaders().get("Cache-Control"));
        assertEquals("no-cache", response.getResponseHeaders().get("Pragma"));
        assertEquals("0", response.getResponseHeaders().get("Expires"));
    }
}
