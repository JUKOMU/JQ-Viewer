package io.github.jukomu.desktop.feature.download.validation;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/** 图片文件的快速校验和完整校验。 */
public final class ImageFileValidator {

    static {
        ImageIO.scanForPlugins();
    }

    private ImageFileValidator() {
    }

    /** 只读取图片边界，判断文件是否能被图片解码器识别。 */
    public static boolean validateQuick(Path imageFile) {
        if (!isNonEmptyFile(imageFile)) {
            return false;
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(imageFile.toFile())) {
            return input != null && validateQuick(input);
        } catch (IOException | RuntimeException | OutOfMemoryError error) {
            return false;
        }
    }

    /** 只读取图片边界，判断字节是否能被图片解码器识别。 */
    public static boolean validateQuick(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return false;
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(imageBytes))) {
            return input != null && validateQuick(input);
        } catch (IOException | RuntimeException | OutOfMemoryError error) {
            return false;
        }
    }

    /** 实际解码图片并检查尺寸。 */
    public static synchronized boolean validateFull(Path imageFile) {
        if (!isNonEmptyFile(imageFile)) {
            return false;
        }

        try {
            BufferedImage image = ImageIO.read(imageFile.toFile());
            return image != null && image.getWidth() > 0 && image.getHeight() > 0;
        } catch (IOException | RuntimeException error) {
            return false;
        } catch (OutOfMemoryError error) {
            throw error;
        }
    }

    private static boolean validateQuick(ImageInputStream input) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) {
            return false;
        }

        ImageReader reader = readers.next();
        try {
            reader.setInput(input, false, true);
            return reader.getWidth(0) > 0 && reader.getHeight(0) > 0;
        } finally {
            reader.dispose();
        }
    }

    private static boolean isNonEmptyFile(Path imageFile) {
        if (imageFile == null || !Files.isRegularFile(imageFile)) {
            return false;
        }
        try {
            return Files.size(imageFile) > 0L;
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }
}
