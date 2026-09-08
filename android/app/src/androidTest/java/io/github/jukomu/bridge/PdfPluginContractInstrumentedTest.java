package io.github.jukomu.bridge;

import android.content.Context;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import android.webkit.WebResourceResponse;

import androidx.test.platform.app.InstrumentationRegistry;

import com.getcapacitor.JSObject;

import io.github.jukomu.bridge.handler.PdfPluginHandler;
import io.github.jukomu.feature.download.data.DownloadStore;
import io.github.jukomu.feature.pdf.data.PdfStore;
import io.github.jukomu.feature.pdf.data.PdfRef;
import io.github.jukomu.feature.pdf.render.PdfPageCache;
import io.github.jukomu.feature.pdf.render.PdfPageSizing;
import io.github.jukomu.feature.pdf.web.PdfServer;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

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
        PdfPageCache.getInstance(context).clear();
        missingPdf = new File(context.getCacheDir(), "missing-a1-pdf.pdf");
        if (missingPdf.exists()) assertTrue(missingPdf.delete());
    }

    @Test
    public void scopedPdfFailuresKeepMessagesAndExposeCodes() throws Exception {
        RecordingPluginCall scan = call("scanPdfFiles", "folderRef",
            PdfRef.createPathFolderRef(missingPdf.getAbsolutePath()));
        handler.scanPdfFiles(scan);
        assertRejected(scan, "PDF 文件夹不存在", "not-found");

        RecordingPluginCall info = call("getPdfInfo", "fileRef",
            PdfRef.createPathFileRef(missingPdf.getAbsolutePath()));
        handler.getPdfInfo(info);
        assertEquals("not-found", info.rejectionCode);
        assertTrue(info.rejectionMessage.startsWith("PDF 信息读取失败: "));

        RecordingPluginCall render = call("renderPdfPage", "fileRef",
            PdfRef.createPathFileRef(missingPdf.getAbsolutePath()), "page", 1,
            "targetWidth", 900);
        handler.renderPdfPage(render);
        assertEquals("not-found", render.rejectionCode);
        assertTrue(render.rejectionMessage.startsWith("PDF 页面渲染失败: "));

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
            PdfRef.createPathFileRef(locator), locator, "missing.pdf", "album", "", "", "", "chapter", "", 0,
            -1, System.currentTimeMillis(), null, 0, 1);

        RecordingPluginCall delete = call("deletePdfFile", "id", (int) id);
        handler.deletePdfFile(delete);

        assertEquals("already_missing", delete.resolvedData.getString("result"));
        assertNull(delete.rejectionCode);
        assertEquals(1, delete.completionCount);
    }

    @Test
    public void pathScanIgnoresDirectoriesWhoseNamesEndWithPdf() throws Exception {
        File folder = new File(context.getCacheDir(), "pdf-scan-" + System.nanoTime());
        File pdfFile = new File(folder, "book.pdf");
        File pdfDirectory = new File(folder, "archive.pdf");
        assertTrue(folder.mkdirs());
        assertTrue(pdfFile.createNewFile());
        assertTrue(pdfDirectory.mkdirs());
        try {
            RecordingPluginCall scan = call(
                "scanPdfFiles",
                "folderRef", PdfRef.createPathFolderRef(folder.getCanonicalPath())
            );
            handler.scanPdfFiles(scan);

            assertEquals(1, scan.resolvedData.getJSONArray("files").length());
            assertEquals("book.pdf",
                scan.resolvedData.getJSONArray("files").getJSONObject(0)
                    .getString("fileName"));
        } finally {
            assertTrue(pdfDirectory.delete() || !pdfDirectory.exists());
            assertTrue(pdfFile.delete() || !pdfFile.exists());
            assertTrue(folder.delete() || !folder.exists());
        }
    }

    @Test
    public void renderPdfPageReturnsStableResourceUrlAndPdfServerStreamsPng() throws Exception {
        File pdf = new File(context.getCacheDir(), "a4-render-" + System.nanoTime() + ".pdf");
        createPdf(pdf);
        try {
            String fileRef = PdfRef.createPathFileRef(pdf.getCanonicalPath());
            RecordingPluginCall first = call("renderPdfPage",
                "fileRef", fileRef, "page", 1, "targetWidth", 900);
            handler.renderPdfPage(first);
            assertEquals(1, first.completionCount);
            assertNull(first.rejectionMessage);
            String firstUrl = first.resolvedData.getString("resourceUrl");
            assertTrue(firstUrl.matches("https://jqviewer\\.local/pdf-page/[0-9a-f]{64}\\.png"));
            assertTrue(PdfServer.isPdfPageUrl(firstUrl));
            assertEquals(1, first.resolvedData.length());

            RecordingPluginCall second = call("renderPdfPage",
                "fileRef", fileRef, "page", 1, "targetWidth", 900);
            handler.renderPdfPage(second);
            assertEquals(firstUrl, second.resolvedData.getString("resourceUrl"));

            WebResourceResponse response = PdfServer.handlePdfPageRequest(firstUrl, context);
            assertEquals(200, response.getStatusCode());
            assertEquals("image/png", response.getMimeType());
            assertFalse(response.getResponseHeaders().containsKey("X-JQViewer-Pdf-Error"));
            byte[] header = new byte[8];
            try (InputStream input = response.getData()) {
                assertEquals(8, input.read(header));
            }
            assertEquals((byte) 0x89, header[0]);
            assertEquals((byte) 0x50, header[1]);
            assertEquals((byte) 0x4e, header[2]);
            assertEquals((byte) 0x47, header[3]);
        } finally {
            assertTrue(pdf.delete() || !pdf.exists());
            PdfPageCache.getInstance(context).clear();
        }
    }

    @Test
    public void pdfPageRouteRejectsInvalidAndMissingResources() {
        WebResourceResponse invalid = PdfServer.handlePdfPageRequest(
            "https://jqviewer.local/pdf-page/not-a-resource.png", context);
        assertEquals(400, invalid.getStatusCode());
        assertFalse(PdfServer.isPdfPageUrl(
            "https://other.example/pdf-page/" + "a".repeat(64) + ".png"));

        WebResourceResponse missing = PdfServer.handlePdfPageRequest(
            "https://jqviewer.local/pdf-page/" + "a".repeat(64) + ".png", context);
        assertEquals(404, missing.getStatusCode());
    }

    @Test
    public void pdfRouteStreamsPathAndSafFileRefsWithoutRawPathFallback() throws Exception {
        File pdf = new File(context.getCacheDir(), "pdf-server-path-" + System.nanoTime() + ".pdf");
        createPdf(pdf);
        try {
            WebResourceResponse pathResponse = PdfServer.handleRequest(
                pdfUrl(PdfRef.createPathFileRef(pdf.getCanonicalPath())), context);
            assertPdfResponse(pathResponse);

            WebResourceResponse safResponse = PdfServer.handleRequest(
                pdfUrl(PdfRef.createSafFileRef(
                    "content://io.github.jukomu.test.pdf/document/fixture")), context);
            assertPdfResponse(safResponse);

            WebResourceResponse folderResponse = PdfServer.handleRequest(
                pdfUrl(PdfRef.createPathFolderRef(context.getCacheDir().getCanonicalPath())), context);
            assertEquals(400, folderResponse.getStatusCode());
            assertEquals("invalid-path",
                folderResponse.getResponseHeaders().get("X-JQViewer-Pdf-Error"));

            WebResourceResponse rawPathResponse = PdfServer.handleRequest(
                pdfUrl(pdf.getCanonicalPath()), context);
            assertEquals(400, rawPathResponse.getStatusCode());
            assertEquals("invalid-path",
                rawPathResponse.getResponseHeaders().get("X-JQViewer-Pdf-Error"));
        } finally {
            assertTrue(pdf.delete() || !pdf.exists());
        }
    }

    @Test
    public void largePdfPagesUseTheFixedPixelBudget() {
        PdfPageSizing.Size size = PdfPageSizing.calculate(2400, 1000, 10_000);
        assertTrue(size.pixels <= PdfPageSizing.MAX_RENDER_PIXELS);
    }

    private static void createPdf(File file) throws Exception {
        PdfDocument document = new PdfDocument();
        PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(
            600, 800, 1).create());
        page.getCanvas().drawColor(Color.WHITE);
        document.finishPage(page);
        try (FileOutputStream output = new FileOutputStream(file)) {
            document.writeTo(output);
        } finally {
            document.close();
        }
    }

    private static String pdfUrl(String fileRef) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
            fileRef.getBytes(StandardCharsets.UTF_8));
        return "https://jqviewer.local/pdf/" + encoded;
    }

    private static void assertPdfResponse(WebResourceResponse response) throws Exception {
        assertEquals(200, response.getStatusCode());
        assertEquals("application/pdf", response.getMimeType());
        byte[] header = new byte[5];
        try (InputStream input = response.getData()) {
            assertEquals(5, input.read(header));
        }
        assertEquals('%', header[0]);
        assertEquals('P', header[1]);
        assertEquals('D', header[2]);
        assertEquals('F', header[3]);
        assertEquals('-', header[4]);
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
