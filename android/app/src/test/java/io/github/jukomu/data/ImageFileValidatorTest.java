package io.github.jukomu.data;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ImageFileValidatorTest {

    @Test
    public void rejectsUnavailableFiles() {
        assertFalse(ImageFileValidator.validateFull(null));
        assertFalse(ImageFileValidator.validateQuick(null));
        assertFalse(ImageFileValidator.validateFull(
            new File("missing-image-" + System.nanoTime() + ".jpg")));
    }

    @Test
    public void fullIsSynchronizedWhileQuickIsNot() throws Exception {
        Method full = ImageFileValidator.class.getMethod("validateFull", File.class);
        Method quick = ImageFileValidator.class.getMethod("validateQuick", File.class);

        assertTrue(Modifier.isStatic(full.getModifiers()));
        assertTrue(Modifier.isSynchronized(full.getModifiers()));
        assertTrue(Modifier.isStatic(quick.getModifiers()));
        assertFalse(Modifier.isSynchronized(quick.getModifiers()));
    }
}
