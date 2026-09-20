package io.github.jukomu.bridge;

import android.content.Intent;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class JmcomicPluginTest {

    @Test
    public void exportedPdfFolderDoesNotGrantSyntheticDocumentUri() {
        assertEquals(0, JmcomicPlugin.pdfFolderGrantFlags(false));
    }

    @Test
    public void importedPdfFolderKeepsReadAndPrefixGrantFlags() {
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            JmcomicPlugin.pdfFolderGrantFlags(true));
    }
}
