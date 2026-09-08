package io.github.jukomu.feature.pdf.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PdfPageSizingTest {

    @Test
    public void keepsNormalPageDimensionsAtNormalizedWidth() {
        PdfPageSizing.Size size = PdfPageSizing.calculate(900, 600, 800);

        assertEquals(900, size.width);
        assertEquals(1200, size.height);
        assertEquals(1_080_000L, size.pixels);
    }

    @Test
    public void proportionallyShrinksLargePagesWithinFixedPixelBudget() {
        PdfPageSizing.Size size = PdfPageSizing.calculate(2400, 1000, 10_000);

        assertTrue(size.width >= 1);
        assertTrue(size.height >= 1);
        assertTrue(size.pixels <= PdfPageSizing.MAX_RENDER_PIXELS);
    }

    @Test
    public void saturatesIntermediateMultiplicationForHugeDimensions() {
        PdfPageSizing.Size size = PdfPageSizing.calculate(
            2400, 1L, Long.MAX_VALUE);

        assertTrue(size.width >= 1);
        assertTrue(size.height >= 1);
        assertTrue(size.pixels <= PdfPageSizing.MAX_RENDER_PIXELS);
    }
}
