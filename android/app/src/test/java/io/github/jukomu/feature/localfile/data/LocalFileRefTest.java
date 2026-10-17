package io.github.jukomu.feature.localfile.data;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LocalFileRefTest {

    @Test
    public void parsesAllFourReferencePrefixesWithoutChangingPayload() {
        LocalFileRef.Parsed filePath = LocalFileRef.parse("file:path:/storage/emulated/0/a.pdf");
        LocalFileRef.Parsed fileSaf = LocalFileRef.parse("file:saf:content://provider/document/1:pdf");
        LocalFileRef.Parsed folderPath = LocalFileRef.parse("folder:path:/storage/emulated/0/Download");
        LocalFileRef.Parsed folderSaf = LocalFileRef.parse("folder:saf:content://provider/tree/1:root");

        assertEquals(LocalFileRef.Kind.FILE, filePath.kind);
        assertEquals(LocalFileRef.Provider.PATH, filePath.provider);
        assertEquals("/storage/emulated/0/a.pdf", filePath.payload);
        assertEquals(LocalFileRef.Kind.FILE, fileSaf.kind);
        assertEquals("content://provider/document/1:pdf", fileSaf.payload);
        assertEquals(LocalFileRef.Kind.FOLDER, folderPath.kind);
        assertEquals(LocalFileRef.Kind.FOLDER, folderSaf.kind);
    }

    @Test
    public void rejectsEmptyPayloadAndKindMismatchAtConsumerBoundary() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> LocalFileRef.parse("file:path:"));
        String folderRef = LocalFileRef.createPathFolderRef("/tmp");
        assertThrows(IllegalArgumentException.class, () -> LocalFileRef.fileName(folderRef));
    }

    @Test
    public void canonicalizesPathOnlyWhenCreatingAReference() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "jq-pdf-ref");
        String ref = LocalFileRef.createPathFileRef(new File(root, "dir/../book.pdf").getPath());
        assertTrue(ref.startsWith("file:path:"));
        assertEquals(new File(root, "book.pdf").getCanonicalPath(), LocalFileRef.payload(ref));
    }

    @Test
    public void separatesFileAndFolderSafCreation() {
        assertEquals("file:saf:content://provider/document/1",
            LocalFileRef.createSafFileRef("content://provider/document/1"));
        assertEquals("folder:saf:content://provider/tree/1",
            LocalFileRef.createSafFolderRef("content://provider/tree/1"));
        assertThrows(IllegalArgumentException.class,
            () -> LocalFileRef.createSafFileRef("https://example.test/document/1"));
    }
}
