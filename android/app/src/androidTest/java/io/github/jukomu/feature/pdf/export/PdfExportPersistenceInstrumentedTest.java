package io.github.jukomu.feature.pdf.export;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.jukomu.feature.download.data.DownloadStore;
import io.github.jukomu.feature.download.storage.FileStore;
import io.github.jukomu.feature.pdf.data.PdfStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PdfExportPersistenceInstrumentedTest {

    private static final String PDF_DATABASE = "jq_pdf_import.db";

    private Context context;
    private ExecutorService executor;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PdfStore.clearInstanceForTest();
        context.deleteDatabase(PDF_DATABASE);
        cleanupRecoveryFiles();
        FileStore.getInstance().init(context, DownloadStore.getInstance(context), false);
    }

    @After
    public void tearDown() throws Exception {
        if (executor != null) executor.shutdownNow();
        PdfStore.clearInstanceForTest();
        context.deleteDatabase(PDF_DATABASE);
        cleanupRecoveryFiles();
    }

    @Test
    public void preflightRejectedTaskIsPersistedForLaterExplanation() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        PdfExportService service = new PdfExportService(
            context, executor, (ignoredContext, ignoredSnapshot) -> {
        }, () -> null);
        service.reconcileOnStartup();

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

    @Test
    public void startupRecoveryInterruptsTaskAndRemovesOnlyTemporaryArtifacts() throws Exception {
        PdfStore store = PdfStore.getInstance(context);
        File finalFile = new File(context.getCacheDir(), "recovery.pdf");
        File tempFile = PdfBoxExportWriter.getTempFile(finalFile);
        File workDirectory = PdfBoxExportWriter.getWorkDirectory(finalFile);
        assertTrue(workDirectory.mkdirs());
        writeByte(finalFile, 1);
        writeByte(tempFile, 2);
        writeByte(new File(workDirectory, "chunk-00000.pdf"), 3);

        JSONObject task = new JSONObject()
            .put("exportId", "recovery-export")
            .put("batchId", "recovery-batch")
            .put("mode", "chapter")
            .put("albumId", "album-1")
            .put("chapterId", "chapter-1")
            .put("displayTitle", "第一话")
            .put("savePath", finalFile.getAbsolutePath())
            .put("status", "running")
            .put("phase", "writing")
            .put("totalPages", 10);
        JSONObject volume = new JSONObject()
            .put("volumeIndex", 1)
            .put("startPage", 0)
            .put("endPage", 10)
            .put("expectedPageCount", 10)
            .put("finalPath", finalFile.getAbsolutePath())
            .put("tempPath", tempFile.getAbsolutePath())
            .put("workDir", workDirectory.getAbsolutePath());
        store.reserveExport(task, new JSONArray(), new JSONArray().put(volume));

        executor = Executors.newSingleThreadExecutor();
        PdfExportService service = new PdfExportService(
            context, executor, (ignoredContext, ignoredSnapshot) -> {
        }, () -> null);
        service.reconcileOnStartup();

        JSONObject recovered = store.getExportTask("recovery-export");
        assertEquals("interrupted", recovered.optString("status"));
        assertEquals("PROCESS_INTERRUPTED", recovered.optString("errorCode"));
        assertFalse(tempFile.exists());
        assertFalse(workDirectory.exists());
        assertTrue(finalFile.exists());
    }

    private static void writeByte(File file, int value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value);
        }
    }

    private void cleanupRecoveryFiles() throws Exception {
        File finalFile = new File(context.getCacheDir(), "recovery.pdf");
        PdfBoxExportWriter.cleanStaleArtifacts(finalFile);
        if (finalFile.exists() && !finalFile.delete()) {
            throw new IllegalStateException("无法清理恢复测试 PDF");
        }
    }
}
