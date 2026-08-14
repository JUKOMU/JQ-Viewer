package io.github.jukomu.service;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.jukomu.data.DownloadStore;
import io.github.jukomu.data.FileStore;
import io.github.jukomu.data.PdfStore;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

@RunWith(AndroidJUnit4.class)
public class PdfExportPersistenceInstrumentedTest {

    private static final String PDF_DATABASE = "jq_pdf_import.db";

    private Context context;
    private ExecutorService executor;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PdfStore.clearInstanceForTest();
        context.deleteDatabase(PDF_DATABASE);
        FileStore.getInstance().init(context, DownloadStore.getInstance(context), false);
    }

    @After
    public void tearDown() {
        if (executor != null) executor.shutdownNow();
        PdfStore.clearInstanceForTest();
        context.deleteDatabase(PDF_DATABASE);
    }

    @Test
    public void preflightRejectedTaskIsPersistedForLaterExplanation() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        PdfExportService service = new PdfExportService(
            context, executor, (ignoredContext, ignoredSnapshot) -> {}, () -> null);
        service.reconcileOnStartup();
        waitUntilReady(service);

        PdfExportService.ExportJob job = new PdfExportService.ExportJob();
        job.mode = "chapter";
        job.albumId = "100000001";
        job.chapterId = "100000002";
        job.chapterTitle = "缺失章节";
        job.savePath = new File(context.getCacheDir(), "rejected.pdf").getAbsolutePath();
        job.useOriginal = true;
        job.compressionRatio = 1F;

        JSONObject submission = service.submitExport(Collections.singletonList(job));
        JSONObject result = submission.getJSONArray("tasks").getJSONObject(0);
        assertFalse(result.optBoolean("accepted"));
        assertEquals("failed", result.getString("status"));

        JSONObject task = PdfStore.getInstance(context).getExportTask(
            result.getString("exportId"));
        assertNotNull(task);
        assertEquals("failed", task.getString("status"));
        assertEquals(result.getString("errorCode"), task.getString("errorCode"));
        assertFalse(task.optString("errorMessage").isEmpty());
    }

    private static void waitUntilReady(PdfExportService service) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if ("ready".equals(service.getManagementState().optString("recoveryState"))) {
                return;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("PDF recovery did not become ready");
    }
}
