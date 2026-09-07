package io.github.jukomu.bridge;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import com.getcapacitor.JSObject;

import io.github.jukomu.bridge.handler.PdfPluginHandler;
import io.github.jukomu.feature.download.data.DownloadStore;
import io.github.jukomu.feature.pdf.data.PdfStore;

import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PdfPluginContractInstrumentedTest {

    private PdfPluginHandler handler;
    private PdfStore pdfStore;
    private Context context;
    private File missingPdf;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        pdfStore = PdfStore.getInstance(context);
        handler = new PdfPluginHandler(context, DownloadStore.getInstance(context), Runnable::run);
        missingPdf = new File(context.getCacheDir(), "missing-a1-pdf.pdf");
        if (missingPdf.exists()) assertTrue(missingPdf.delete());
    }

    @Test
    public void scopedPdfFailuresKeepMessagesAndExposeCodes() {
        RecordingPluginCall scan = call("scanPdfFiles", "path", missingPdf.getAbsolutePath());
        handler.scanPdfFiles(scan);
        assertRejected(scan, "Not a directory: " + missingPdf.getAbsolutePath(), "not-found");

        RecordingPluginCall info = call("getPdfInfo", "filePath", missingPdf.getAbsolutePath());
        handler.getPdfInfo(info);
        assertEquals("not-found", info.rejectionCode);
        assertTrue(info.rejectionMessage.startsWith("PDF 信息读取失败: "));

        RecordingPluginCall inspect = call("inspectPdfFileForDeletion", "id", Integer.MAX_VALUE);
        handler.inspectPdfFileForDeletion(inspect);
        assertRejected(inspect, "PDF 文件记录不存在", "not-found");

        RecordingPluginCall delete = call("deletePdfFile", "id", Integer.MAX_VALUE);
        handler.deletePdfFile(delete);
        assertRejected(delete, "PDF 文件记录不存在", "not-found");

        RecordingPluginCall getTask = call("getPdfExportTask", "exportId", "missing-a1-task");
        handler.getPdfExportTask(getTask);
        assertRejected(getTask, "PDF 导出任务不存在", "not-found");

        RecordingPluginCall cancelTask = call("cancelPdfExport", "exportId", "missing-a1-task");
        handler.cancelPdfExport(cancelTask);
        assertRejected(cancelTask, "PDF 导出任务不存在", "not-found");

        RecordingPluginCall retryTask = call("retryPdfExport", "exportId", "missing-a1-task");
        handler.retryPdfExport(retryTask);
        assertRejected(retryTask, "PDF 导出任务不存在", "not-found");
    }

    @Test
    public void methodsOutsideA1DoNotRequireErrorCodes() {
        RecordingPluginCall importCall = call("importPdfs");
        handler.importPdfs(importCall);

        assertEquals("items is required and must not be empty", importCall.rejectionMessage);
        assertNull(importCall.rejectionCode);
        assertEquals(1, importCall.completionCount);
        assertFalse(importCall.isKeptAlive());
    }

    @Test
    public void missingPhysicalFileKeepsAlreadyMissingSuccessContract() throws Exception {
        String locator = new File(context.getCacheDir(),
            "already-missing-" + System.nanoTime() + ".pdf").getAbsolutePath();
        long id = pdfStore.insertImportedPdf(
            locator, "missing.pdf", "album", "", "", "", "chapter", "", 0,
            -1, System.currentTimeMillis(), null, 0, 1);

        RecordingPluginCall delete = call("deletePdfFile", "id", (int) id);
        handler.deletePdfFile(delete);

        assertEquals("already_missing", delete.resolvedData.getString("result"));
        assertNull(delete.rejectionCode);
        assertEquals(1, delete.completionCount);
    }

    private static RecordingPluginCall call(String methodName, Object... values) {
        JSObject data = new JSObject();
        for (int index = 0; index < values.length; index += 2) {
            data.put((String) values[index], values[index + 1]);
        }
        return new RecordingPluginCall(methodName, data);
    }

    private static void assertRejected(RecordingPluginCall call, String message, String code) {
        assertEquals(message, call.rejectionMessage);
        assertEquals(code, call.rejectionCode);
        assertEquals(1, call.completionCount);
        assertNull(call.resolvedData);
        assertFalse(call.isKeptAlive());
    }
}
