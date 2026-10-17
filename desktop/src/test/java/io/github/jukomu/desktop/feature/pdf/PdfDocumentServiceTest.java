package io.github.jukomu.desktop.feature.pdf;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.feature.files.FileReferences;
import io.github.jukomu.desktop.feature.pdf.render.PdfDocumentService;
import io.github.jukomu.desktop.feature.pdf.render.PdfPageCache;
import io.github.jukomu.desktop.feature.pdf.render.PdfResourceService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfDocumentServiceTest {
    @Test
    void rendersCachedPngAndReportsPageCount() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-pdf-render-");
        Path pdf = root.resolve("sample.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }
        PdfPageCache cache = new PdfPageCache(root.resolve("cache"));
        PdfDocumentService service = new PdfDocumentService(cache);
        String fileRef = FileReferences.fileRef(pdf);

        assertEquals(2, service.getInfo(fileRef).pageCount());
        String first = service.renderPage(fileRef, 1, 10_000).resourceUrl();
        String second = service.renderPage(fileRef, 1, 10_000).resourceUrl();
        String resourceId = first.substring("/pdf-page/".length(), first.length() - 4);
        var image = ImageIO.read(cache.fileFor(resourceId).toFile());

        assertEquals(first, second);
        assertTrue(image.getWidth() * (long) image.getHeight() <= 8_000_000L);
        assertThrows(ApiException.class, () -> service.renderPage(fileRef, 3, 1080));
    }

    @Test
    void streamsValidPdfAndRejectsInvalidContent() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-pdf-resource-");
        Path pdf = root.resolve("sample.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }
        PdfResourceService resources = new PdfResourceService();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                FileReferences.fileRef(pdf).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        try (var input = resources.open(encoded).input()) {
            assertTrue(new String(input.readNBytes(8),
                    java.nio.charset.StandardCharsets.ISO_8859_1).contains("%PDF-"));
        }

        Path invalid = root.resolve("invalid.pdf");
        Files.writeString(invalid, "not a pdf");
        String invalidEncoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                FileReferences.fileRef(invalid).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        PdfResourceService.ResourceException error = assertThrows(
                PdfResourceService.ResourceException.class,
                () -> resources.open(invalidEncoded));
        assertEquals("invalid-content", error.code());
        assertEquals(400, error.status());
    }
}
