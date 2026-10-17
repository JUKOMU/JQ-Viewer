package io.github.jukomu.desktop.feature.ocr;

import io.github.jukomu.desktop.feature.ocr.model.OcrResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TesseractOcrEngineTest {
    @TempDir
    Path root;

    @Test
    void recognizesTextWithBundledFastModel() throws Exception {
        Path imagePath = root.resolve("ocr.png");
        BufferedImage image = new BufferedImage(800, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 72));
            graphics.drawString("OCR FAST", 80, 125);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", imagePath.toFile());

        try (TesseractOcrEngine engine = new TesseractOcrEngine(root.resolve("ocr"))) {
            OcrResponse result = engine.recognize(imagePath);

            assertEquals("", result.error());
            assertTrue(result.text().contains("OCR"), result.text());
        }
    }

    @Test
    void rejectsOversizedImageBeforeStartingNativeProcess() throws Exception {
        Path imagePath = root.resolve("oversized.png");
        try (var channel = Files.newByteChannel(
                imagePath,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            channel.position(TesseractOcrEngine.MAX_IMAGE_BYTES);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }

        try (TesseractOcrEngine engine = new TesseractOcrEngine(root.resolve("ocr"))) {
            OcrResponse result = engine.recognize(imagePath);

            assertEquals("", result.text());
            assertEquals("图片文件过大", result.error());
        }
    }
}
