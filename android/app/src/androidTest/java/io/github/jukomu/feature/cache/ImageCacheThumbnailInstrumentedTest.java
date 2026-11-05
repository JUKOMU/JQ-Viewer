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
        Bitmap source = Bitmap.createBitmap(1200, 16, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            // 每四列中一列白色，缩小四倍后应保留约四分之一白色的平均亮度。
            int[] row = new int[source.getWidth()];
            for (int x = 0; x < row.length; x++) {
                row[x] = x % 4 == 0 ? Color.WHITE : Color.BLACK;
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
            for (int y = 0; y < thumbnail.getHeight(); y++) {
                for (int x = 0; x < thumbnail.getWidth(); x++) {
                    assertEquals(64, Color.red(thumbnail.getPixel(x, y)), 3);
                }
            }
        } finally {
            thumbnail.recycle();
        }
    }
}
