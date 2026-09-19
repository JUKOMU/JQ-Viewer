package io.github.jukomu.desktop.feature.image;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebpImageIoTest {
    @Test
    void providesWebpReaderAndWriterForJmImageDecryption() throws Exception {
        ImageIO.scanForPlugins();
        assertTrue(ImageIO.getImageReadersByFormatName("webp").hasNext(),
                "JM 图片解密需要 WebP 解码器");
        assertTrue(ImageIO.getImageWritersByFormatName("webp").hasNext(),
                "JM 图片解密后需要 WebP 编码器");

        BufferedImage source = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = source.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
        graphics.dispose();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "webp", output));
        assertTrue(output.size() > 0);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(output.toByteArray()));
        assertNotNull(decoded);
        assertEquals(source.getWidth(), decoded.getWidth());
        assertEquals(source.getHeight(), decoded.getHeight());
    }
}
