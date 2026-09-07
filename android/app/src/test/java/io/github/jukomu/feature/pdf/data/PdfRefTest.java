package io.github.jukomu.feature.pdf.data;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PdfRefTest {

    @Test
    public void parsesAllFourReferencePrefixesWithoutChangingPayload() {
        PdfRef.Parsed filePath = PdfRef.parse("file:path:/storage/emulated/0/a.pdf");
        PdfRef.Parsed fileSaf = PdfRef.parse("file:saf:content://provider/document/1:pdf");
        PdfRef.Parsed folderPath = PdfRef.parse("folder:path:/storage/emulated/0/Download");
        PdfRef.Parsed folderSaf = PdfRef.parse("folder:saf:content://provider/tree/1:root");

        assertEquals(PdfRef.Kind.FILE, filePath.kind);
        assertEquals(PdfRef.Provider.PATH, filePath.provider);
        assertEquals("/storage/emulated/0/a.pdf", filePath.payload);
        assertEquals(PdfRef.Kind.FILE, fileSaf.kind);
        assertEquals("content://provider/document/1:pdf", fileSaf.payload);
        assertEquals(PdfRef.Kind.FOLDER, folderPath.kind);
        assertEquals(PdfRef.Kind.FOLDER, folderSaf.kind);
    }

    @Test
    public void rejectsEmptyPayloadAndKindMismatchAtConsumerBoundary() {
        assertThrows(IllegalArgumentException.class, () -> PdfRef.parse("file:path:"));
        PdfRef.Parsed parsed = PdfRef.parse("file:path:/tmp/a.pdf");
        assertEquals(PdfRef.Kind.FILE, parsed.kind);
        assertThrows(IllegalArgumentException.class, () -> {
            if (parsed.kind != PdfRef.Kind.FOLDER) throw new IllegalArgumentException();
        });
    }

    @Test
    public void canonicalizesPathOnlyWhenCreatingAReference() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "jq-pdf-ref");
        String ref = PdfRef.createPathFileRef(new File(root, "dir/../book.pdf").getPath());
        assertTrue(ref.startsWith("file:path:"));
        assertEquals(new File(root, "book.pdf").getCanonicalPath(), PdfRef.payload(ref));
    }

    @Test
    public void separatesFileAndFolderSafCreation() {
        assertEquals("file:saf:content://provider/document/1",
            PdfRef.createSafFileRef("content://provider/document/1"));
        assertEquals("folder:saf:content://provider/tree/1",
            PdfRef.createSafFolderRef("content://provider/tree/1"));
        assertThrows(IllegalArgumentException.class,
            () -> PdfRef.createSafFileRef("https://example.test/document/1"));
    }
}
