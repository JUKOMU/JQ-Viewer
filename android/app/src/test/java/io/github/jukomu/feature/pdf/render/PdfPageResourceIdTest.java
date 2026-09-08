package io.github.jukomu.feature.pdf.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class PdfPageResourceIdTest {

    @Test
    public void sameInputProducesStableLowercaseSha256Id() {
        String id = PdfPageResourceId.create(
            "file:path:/books/book.pdf", 1, 900, 1234L, 5678L);

        assertEquals(64, id.length());
        assertTrue(id.matches("[0-9a-f]{64}"));
        assertEquals(id, PdfPageResourceId.create(
            "file:path:/books/book.pdf", 1, 900, 1234L, 5678L));
        assertEquals(
            "https://jqviewer.local/pdf-page/" + id + ".png",
            PdfPageResourceId.resourceUrl(id));
    }

    @Test
    public void pageWidthAndSourceStampChangeTheId() {
        String base = PdfPageResourceId.create(
            "file:path:/books/book.pdf", 1, 900, 1234L, 5678L);

        assertNotEquals(base, PdfPageResourceId.create(
            "file:path:/books/book.pdf", 2, 900, 1234L, 5678L));
        assertNotEquals(base, PdfPageResourceId.create(
            "file:path:/books/book.pdf", 1, 901, 1234L, 5678L));
        assertNotEquals(base, PdfPageResourceId.create(
            "file:path:/books/book.pdf", 1, 900, 1235L, 5678L));
        assertNotEquals(base, PdfPageResourceId.create(
            "file:path:/books/book.pdf", 1, 900, 1234L, 5679L));
    }

    @Test
    public void targetWidthIsNormalizedBeforeHashing() {
        assertEquals(
            PdfPageResourceId.create("file:path:/books/book.pdf", 1, 360, 1L, 2L),
            PdfPageResourceId.create("file:path:/books/book.pdf", 1, -10, 1L, 2L));
        assertEquals(
            PdfPageResourceId.create("file:path:/books/book.pdf", 1, 2400, 1L, 2L),
            PdfPageResourceId.create("file:path:/books/book.pdf", 1, 99999, 1L, 2L));
    }
}
