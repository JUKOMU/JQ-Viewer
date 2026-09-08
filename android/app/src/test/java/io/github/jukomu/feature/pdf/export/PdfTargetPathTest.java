package io.github.jukomu.feature.pdf.export;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PdfTargetPathTest {

    @Test
    public void normalizesSeparatorsAndKeepsNestedSegments() {
        assertEquals("295852/book.pdf", PdfTargetPath.normalize("295852\\book.pdf"));
        assertEquals(Arrays.asList("295852", "book.pdf"),
            PdfTargetPath.segments("295852/book.pdf"));
        assertEquals("295852/book_001-100.pdf",
            PdfTargetPath.withVolumeSuffix("295852/book.pdf", 1, 100));
    }

    @Test
    public void rejectsAbsoluteAndUnsafeSegments() {
        String[] invalid = {
            "/book.pdf", "\\book.pdf", "C:/book.pdf", "book.pdf/",
            "book//chapter.pdf", "./book.pdf", "../book.pdf", "book/../chapter.pdf",
        };
        for (String value : invalid) {
            assertThrows(IllegalArgumentException.class, () -> PdfTargetPath.segments(value));
        }
    }
}
