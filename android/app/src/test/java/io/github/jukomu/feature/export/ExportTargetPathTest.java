package io.github.jukomu.feature.export;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ExportTargetPathTest {

    @Test
    public void normalizesSeparatorsAndKeepsNestedSegments() {
        assertEquals("295852/book.pdf", ExportTargetPath.normalize("295852\\book.pdf"));
        assertEquals(Arrays.asList("295852", "book.pdf"),
            ExportTargetPath.segments("295852/book.pdf"));
        assertEquals("295852/book_001-100.pdf",
            ExportTargetPath.withVolumeSuffix("295852/book.pdf", 1, 100));
    }

    @Test
    public void rejectsAbsoluteAndUnsafeSegments() {
        String[] invalid = {
            "/book.pdf", "\\book.pdf", "C:/book.pdf", "book.pdf/",
            "book//chapter.pdf", "./book.pdf", "../book.pdf", "book/../chapter.pdf",
        };
        for (String value : invalid) {
            assertThrows(IllegalArgumentException.class, () -> ExportTargetPath.segments(value));
        }
    }
}
