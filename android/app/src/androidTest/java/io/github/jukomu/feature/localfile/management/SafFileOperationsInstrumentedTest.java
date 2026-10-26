package io.github.jukomu.feature.localfile.management;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.jukomu.bridge.FixtureDocumentsProvider;
import io.github.jukomu.feature.localfile.data.LocalFileRef;
import io.github.jukomu.feature.localfile.data.LocalFileRefResolver;
import io.github.jukomu.feature.localfile.data.LocalFileStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.UUID;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SafFileOperationsInstrumentedTest {
    private Context context;
    private LocalFileStore store;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LocalFileManagementService.clearInstanceForTest();
        LocalFileStore.clearInstanceForTest();
        context.deleteDatabase("jq_pdf_import.db");
        store = LocalFileStore.getInstance(context);
    }

    @After
    public void tearDown() {
        LocalFileManagementService.clearInstanceForTest();
        LocalFileStore.clearInstanceForTest();
        context.deleteDatabase("jq_pdf_import.db");
    }

    @Test
    public void documentDeleteUsesProviderProtocolAndRemovesRecord() throws Exception {
        String ref = ref("file-" + UUID.randomUUID());
        long id = record(ref);
        assertEquals("deleted", LocalFileManagementService.getInstance(context)
            .deleteFile(id).getString("result"));
        assertFalse(LocalFileRefResolver.exists(context, ref));
        assertNull(store.getFile(id));
    }

    @Test
    public void failedQueriesAndDeniedDeleteRetainRecordsButMissingCanBeRemoved() throws Exception {
        for (String prefix : new String[]{"query-failed-", "null-query-", "denied-"}) {
            String ref = ref(prefix + UUID.randomUUID());
            if (!"denied-".equals(prefix)) {
                assertThrows(IOException.class, () -> LocalFileRefResolver.exists(context, ref));
            }
            long id = record(ref);
            assertThrows(IOException.class,
                () -> LocalFileManagementService.getInstance(context).deleteFile(id));
            assertNotNull(store.getFile(id));
        }
        long missing = record(ref("missing-" + UUID.randomUUID()));
        assertEquals("already_missing", LocalFileManagementService.getInstance(context)
            .deleteFile(missing).getString("result"));
        assertNull(store.getFile(missing));
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    public void parentComesFromProviderPathAndUnknownPathFailsExplicitly() throws Exception {
        Uri tree = DocumentsContract.buildTreeDocumentUri(FixtureDocumentsProvider.AUTHORITY, "root");
        Uri file = DocumentsContract.buildDocumentUriUsingTree(tree, "file-" + UUID.randomUUID());
        assertEquals("opaque-parent", DocumentsContract.getDocumentId(
            LocalFileRefResolver.parentDocumentUri(context, file)));
        Uri unknown = DocumentsContract.buildDocumentUriUsingTree(tree, "unknown-" + UUID.randomUUID());
        assertThrows(IOException.class, () -> LocalFileRefResolver.parentDocumentUri(context, unknown));
    }

    private static String ref(String id) {
        return LocalFileRef.createSafFileRef(DocumentsContract.buildDocumentUri(
            FixtureDocumentsProvider.AUTHORITY, id).toString());
    }

    private long record(String ref) throws IOException {
        return store.insertImportedFile("pdf", ref, "fixture.pdf", "fixture.pdf",
            "", "", "", "", "", "", 0, 0, System.currentTimeMillis(), null, 0L, 1);
    }
}
