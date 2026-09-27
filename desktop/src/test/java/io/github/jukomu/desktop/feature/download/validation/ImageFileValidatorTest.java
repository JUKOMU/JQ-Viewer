package io.github.jukomu.desktop.feature.download.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageFileValidatorTest {

    @Test
    void rejectsUnavailableFiles(@TempDir Path directory) {
        assertFalse(ImageFileValidator.validateFull(null));
        assertFalse(ImageFileValidator.validateQuick((Path) null));
        assertFalse(ImageFileValidator.validateQuick((byte[]) null));
        assertFalse(ImageFileValidator.validateQuick(new byte[0]));
        assertFalse(ImageFileValidator.validateFull(directory.resolve("missing-image.jpg")));
    }

    @Test
    void fullIsSynchronizedWhileQuickIsNot() throws Exception {
        Method full = ImageFileValidator.class.getMethod("validateFull", Path.class);
        Method quick = ImageFileValidator.class.getMethod("validateQuick", Path.class);
        Method quickBytes = ImageFileValidator.class.getMethod("validateQuick", byte[].class);

        assertTrue(Modifier.isStatic(full.getModifiers()));
        assertTrue(Modifier.isSynchronized(full.getModifiers()));
        assertTrue(Modifier.isStatic(quick.getModifiers()));
        assertFalse(Modifier.isSynchronized(quick.getModifiers()));
        assertTrue(Modifier.isStatic(quickBytes.getModifiers()));
        assertFalse(Modifier.isSynchronized(quickBytes.getModifiers()));
    }

    @Test
    void acceptsDecodedJpegWithFullAndQuickValidation(@TempDir Path directory) throws Exception {
        Path image = directory.resolve("page.jpg");
        BufferedImage bitmap = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(bitmap, "jpg", image.toFile()));

        assertTrue(ImageFileValidator.validateQuick(image));
        assertTrue(ImageFileValidator.validateFull(image));
    }

    @Test
    void rejectsMissingEmptyDirectoryAndRandomFiles(@TempDir Path directory) throws Exception {
        assertFalse(ImageFileValidator.validateFull(null));
        assertFalse(ImageFileValidator.validateQuick(directory.resolve("missing.jpg")));

        Path empty = directory.resolve("empty.jpg");
        Files.createFile(empty);
        assertFalse(ImageFileValidator.validateFull(empty));

        Path childDirectory = directory.resolve("directory.jpg");
        Files.createDirectory(childDirectory);
        assertFalse(ImageFileValidator.validateFull(childDirectory));

        Path random = directory.resolve("random.jpg");
        Files.write(random, new byte[]{1, 2, 3, 4});
        assertFalse(ImageFileValidator.validateQuick(random));
        assertFalse(ImageFileValidator.validateFull(random));
    }

    @Test
    void validatesImageBytesWithoutFullDecode() throws Exception {
        BufferedImage bitmap = new BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(bitmap, "png", output));

        assertTrue(ImageFileValidator.validateQuick(output.toByteArray()));
        assertFalse(ImageFileValidator.validateQuick(new byte[]{1, 2, 3, 4}));
    }
}
