package io.github.jukomu.feature.cache;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ImageCacheThumbnailInstrumentedTest {

    @Test
    public void thumbnailPreservesAverageBrightnessOfFineStripes() {
        for (int factor : new int[]{2, 4, 8, 16}) {
            Bitmap source = Bitmap.createBitmap(300 * factor, 4 * factor, Bitmap.Config.ARGB_8888);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                // 每个采样周期中仅一列白色，缩小后应保留对应比例的平均亮度。
                int[] row = new int[source.getWidth()];
                for (int x = 0; x < row.length; x++) {
                    row[x] = x % factor == 0 ? Color.WHITE : Color.BLACK;
                }
                for (int y = 0; y < source.getHeight(); y++) {
                    source.setPixels(row, 0, row.length, 0, y, row.length, 1);
                }
                assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output));
            } finally {
                source.recycle();
            }

            byte[] bytes = ImageCache.createThumbnail(output.toByteArray());
            Bitmap thumbnail = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            assertNotNull(thumbnail);
            try {
                assertEquals(300, thumbnail.getWidth());
                assertEquals(4, thumbnail.getHeight());
                int expected = Math.round(255f / factor);
                for (int y = 0; y < thumbnail.getHeight(); y++) {
                    for (int x = 0; x < thumbnail.getWidth(); x++) {
                        assertEquals("缩小倍率=" + factor, expected,
                            Color.red(thumbnail.getPixel(x, y)), 3);
                    }
                }
            } finally {
                thumbnail.recycle();
            }
        }
    }
}
