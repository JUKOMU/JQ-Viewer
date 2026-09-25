package io.github.jukomu.bridge;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import android.webkit.WebResourceResponse;

import androidx.test.platform.app.InstrumentationRegistry;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import io.github.jukomu.bridge.handler.LocalFilePluginHandler;
import io.github.jukomu.feature.download.data.DownloadStore;
import io.github.jukomu.feature.localfile.data.LocalFileStore;
import io.github.jukomu.feature.localfile.data.LocalFileRef;
import io.github.jukomu.feature.pdf.render.PdfPageCache;
import io.github.jukomu.feature.pdf.render.PdfPageSizing;
import io.github.jukomu.feature.pdf.web.PdfServer;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PdfPluginContractInstrumentedTest {

    private LocalFilePluginHandler handler;
    private LocalFileStore localFileStore;
    private Context context;
    private File missingPdf;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        localFileStore = LocalFileStore.getInstance(context);
        handler = new LocalFilePluginHandler(context, DownloadStore.getInstance(context), Runnable::run);
        PdfPageCache.getInstance(context).clear();
        missingPdf = new File(context.getCacheDir(), "missing-a1-pdf.pdf");
        if (missingPdf.exists()) assertTrue(missingPdf.delete());
    }

    @Test
    public void scopedPdfFailuresKeepMessagesAndExposeCodes() throws Exception {
        RecordingPluginCall scan = call("scanImportableFiles", "folderRef",
            LocalFileRef.createPathFolderRef(missingPdf.getAbsolutePath()));
        handler.scanImportableFiles(scan);
        assertRejected(scan, "PDF 文件夹不存在", "not-found");

        RecordingPluginCall info = call("getPdfInfo", "fileRef",
            LocalFileRef.createPathFileRef(missingPdf.getAbsolutePath()));
        handler.getPdfInfo(info);
        assertEquals("not-found", info.rejectionCode);
        assertTrue(info.rejectionMessage.startsWith("PDF 信息读取失败: "));

        RecordingPluginCall render = call("renderPdfPage", "fileRef",
            LocalFileRef.createPathFileRef(missingPdf.getAbsolutePath()), "page", 1,
            "targetWidth", 900);
        handler.renderPdfPage(render);
        assertEquals("not-found", render.rejectionCode);
        assertTrue(render.rejectionMessage.startsWith("PDF 页面渲染失败: "));

        RecordingPluginCall inspect = call("inspectLocalFileForDeletion", "id", Integer.MAX_VALUE);
        handler.inspectLocalFileForDeletion(inspect);
        assertRejected(inspect, "本地文件记录不存在", "not-found");

        RecordingPluginCall delete = call("deleteLocalFile", "id", Integer.MAX_VALUE);
        handler.deleteLocalFile(delete);
        assertRejected(delete, "本地文件记录不存在", "not-found");

        RecordingPluginCall getTask = call("getExportTask", "exportId", "missing-a1-task");
        handler.getExportTask(getTask);
        assertRejected(getTask, "导出任务不存在", "not-found");

        RecordingPluginCall cancelTask = call("cancelExport", "exportId", "missing-a1-task");
        handler.cancelExport(cancelTask);
        assertRejected(cancelTask, "导出任务不存在", "not-found");

        RecordingPluginCall retryTask = call("retryExport", "exportId", "missing-a1-task");
        handler.retryExport(retryTask);
        assertRejected(retryTask, "导出任务不存在", "not-found");
    }

    @Test
    public void scanAndInfoRunOnPdfCommandExecutor() throws Exception {
        Deque<Runnable> commands = new ArrayDeque<>();
        LocalFilePluginHandler queuedHandler = new LocalFilePluginHandler(
            context, DownloadStore.getInstance(context), commands::addLast);
        RecordingPluginCall scan = call("scanImportableFiles", "folderRef",
            LocalFileRef.createPathFolderRef(missingPdf.getAbsolutePath()));
        RecordingPluginCall info = call("getPdfInfo", "fileRef",
            LocalFileRef.createPathFileRef(missingPdf.getAbsolutePath()));

        queuedHandler.scanImportableFiles(scan);
        queuedHandler.getPdfInfo(info);

        assertEquals(0, scan.completionCount);
        assertEquals(0, info.completionCount);
        assertEquals(2, commands.size());
        commands.removeFirst().run();
        commands.removeFirst().run();
        assertRejected(scan, "PDF 文件夹不存在", "not-found");
        assertEquals("not-found", info.rejectionCode);
        assertTrue(info.rejectionMessage.startsWith("PDF 信息读取失败: "));
    }

    @Test
    public void destroyRejectsQueuedPdfCallAndNewSubmissions() throws Exception {
        Deque<Runnable> commands = new ArrayDeque<>();
        LocalFilePluginHandler queuedHandler = new LocalFilePluginHandler(
            context, DownloadStore.getInstance(context), commands::addLast);
        RecordingPluginCall queued = call("scanImportableFiles", "folderRef",
            LocalFileRef.createPathFolderRef(missingPdf.getAbsolutePath()));

        queuedHandler.scanImportableFiles(queued);
        assertEquals(0, queued.completionCount);
        assertEquals(1, commands.size());

        queuedHandler.destroy();
        assertEquals(PluginCallSession.SESSION_ENDED_MESSAGE, queued.rejectionMessage);
        assertEquals(1, queued.completionCount);

        commands.removeFirst().run();
        assertEquals(1, queued.completionCount);

        RecordingPluginCall afterDestroy = call("scanImportableFiles", "folderRef",
            LocalFileRef.createPathFolderRef(missingPdf.getAbsolutePath()));
        queuedHandler.scanImportableFiles(afterDestroy);
        assertEquals(PluginCallSession.SESSION_ENDED_MESSAGE,
            afterDestroy.rejectionMessage);
        assertEquals(1, afterDestroy.completionCount);
    }

    @Test
    public void methodsOutsideA1DoNotRequireErrorCodes() {
        RecordingPluginCall importCall = call("importLocalFiles");
        handler.importLocalFiles(importCall);

        assertEquals("items is required and must not be empty", importCall.rejectionMessage);
        assertNull(importCall.rejectionCode);
        assertEquals(1, importCall.completionCount);
        assertFalse(importCall.isKeptAlive());
    }

    @Test
    public void importRejectsPersistenceFailuresInsteadOfReportingInvalidItems() throws Exception {
        File pdf = new File(context.getCacheDir(), "import-failure-" + System.nanoTime() + ".pdf");
        createPdf(pdf);
        SQLiteDatabase database = localFileStore.getWritableDatabase();
        database.execSQL("DROP TRIGGER IF EXISTS fail_pdf_import_for_test");
        database.execSQL("CREATE TRIGGER fail_pdf_import_for_test "
            + "BEFORE INSERT ON local_files BEGIN "
            + "SELECT RAISE(ABORT, 'forced import failure'); END");
        try {
            JSObject item = new JSObject();
            item.put("fileRef", LocalFileRef.createPathFileRef(pdf.getCanonicalPath()));
            item.put("displayPath", pdf.getCanonicalPath());
            item.put("fileName", pdf.getName());
            item.put("albumId", "album-import-failure");
            item.put("chapterId", "chapter-import-failure");
            item.put("chapterTitle", "第一话");
            RecordingPluginCall importCall = call("importLocalFiles", "items", new JSArray().put(item));

            handler.importLocalFiles(importCall);

            assertNull(importCall.resolvedData);
            assertTrue(importCall.rejectionException instanceof SQLiteException);
            assertTrue(importCall.rejectionMessage.contains("forced import failure"));
            assertEquals(1, importCall.completionCount);
        } finally {
            database.execSQL("DROP TRIGGER IF EXISTS fail_pdf_import_for_test");
            assertTrue(pdf.delete() || !pdf.exists());
        }
    }

    @Test
    public void refreshRejectsPersistenceFailuresAtPluginBoundary() throws Exception {
        File pdf = new File(context.getCacheDir(), "refresh-failure-" + System.nanoTime() + ".pdf");
        createPdf(pdf);
        long id = localFileStore.insertImportedFile("pdf",
            LocalFileRef.createPathFileRef(pdf.getCanonicalPath()),
            pdf.getCanonicalPath(), pdf.getName(), "album-refresh-failure", "", "", "",
            "chapter-refresh-failure", "第一话", 0, -1, System.currentTimeMillis(), null,
            pdf.length(), 1);
        SQLiteDatabase database = localFileStore.getWritableDatabase();
        database.execSQL("DROP TRIGGER IF EXISTS fail_pdf_refresh_handler_for_test");
        database.execSQL("CREATE TRIGGER fail_pdf_refresh_handler_for_test "
            + "BEFORE UPDATE ON local_files BEGIN "
            + "SELECT RAISE(ABORT, 'forced handler refresh failure'); END");
        try {
            RecordingPluginCall refreshCall = call(
                "refreshLocalFileAvailability", "ids", new JSArray().put(id));

            handler.refreshLocalFileAvailability(refreshCall);

            assertNull(refreshCall.resolvedData);
            assertTrue(refreshCall.rejectionException instanceof SQLiteException);
            assertTrue(refreshCall.rejectionMessage.contains("forced handler refresh failure"));
            assertEquals(1, refreshCall.completionCount);
        } finally {
            database.execSQL("DROP TRIGGER IF EXISTS fail_pdf_refresh_handler_for_test");
            localFileStore.removeFileFromLibrary(id);
            assertTrue(pdf.delete() || !pdf.exists());
        }
    }

    @Test
    public void missingPhysicalFileKeepsAlreadyMissingSuccessContract() throws Exception {
        String locator = new File(context.getCacheDir(),
            "already-missing-" + System.nanoTime() + ".pdf").getAbsolutePath();
        long id = localFileStore.insertImportedFile("pdf",
            LocalFileRef.createPathFileRef(locator), locator, "missing.pdf", "album", "", "", "", "chapter", "", 0,
            -1, System.currentTimeMillis(), null, 0, 1);

        RecordingPluginCall delete = call("deleteLocalFile", "id", (int) id);
        handler.deleteLocalFile(delete);

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
                "scanImportableFiles",
                "folderRef", LocalFileRef.createPathFolderRef(folder.getCanonicalPath())
            );
            handler.scanImportableFiles(scan);

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
    public void scanSupportsPdfAndCbzButNeverZip() throws Exception {
        File folder = new File(context.getCacheDir(), "mixed-scan-" + System.nanoTime());
        File pdf = new File(folder, "book.pdf");
        File cbz = new File(folder, "book.cbz");
        File zip = new File(folder, "book.zip");
        assertTrue(folder.mkdirs());
        assertTrue(pdf.createNewFile());
        assertTrue(cbz.createNewFile());
        assertTrue(zip.createNewFile());
        try {
            RecordingPluginCall scan = call(
                "scanImportableFiles",
                "folderRef", LocalFileRef.createPathFolderRef(folder.getCanonicalPath()),
                "formats", new JSArray().put("pdf").put("cbz")
            );

            handler.scanImportableFiles(scan);

            assertEquals(2, scan.resolvedData.getJSONArray("files").length());
            assertEquals("cbz", scan.resolvedData.getJSONArray("files")
                .getJSONObject(0).getString("format"));
            assertEquals("pdf", scan.resolvedData.getJSONArray("files")
                .getJSONObject(1).getString("format"));
        } finally {
            assertTrue(zip.delete() || !zip.exists());
            assertTrue(cbz.delete() || !cbz.exists());
            assertTrue(pdf.delete() || !pdf.exists());
            assertTrue(folder.delete() || !folder.exists());
        }
    }

    @Test
    public void renderPdfPageReturnsStableResourceUrlAndPdfServerStreamsPng() throws Exception {
        File pdf = new File(context.getCacheDir(), "a4-render-" + System.nanoTime() + ".pdf");
        createPdf(pdf);
        try {
            String fileRef = LocalFileRef.createPathFileRef(pdf.getCanonicalPath());
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
    public void cbzInfoAndPageRouteShareTheIndexedArchive() throws Exception {
        File cbz = new File(context.getCacheDir(), "cbz-server-" + System.nanoTime() + ".cbz");
        createCbz(cbz);
        try {
            String fileRef = LocalFileRef.createPathFileRef(cbz.getCanonicalPath());
            RecordingPluginCall info = call("getCbzInfo", "fileRef", fileRef);

            handler.getCbzInfo(info);

            assertNull(info.rejectionMessage);
            assertEquals(2, info.resolvedData.getInt("pageCount"));
            assertEquals("Bridge CBZ", info.resolvedData.getString("title"));

            WebResourceResponse response = PdfServer.handleCbzPageRequest(cbzPageUrl(fileRef, 2), context);
            assertEquals(200, response.getStatusCode());
            assertEquals("image/png", response.getMimeType());
            try (InputStream input = response.getData()) {
                assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), readAll(input));
            }
        } finally {
            assertTrue(cbz.delete() || !cbz.exists());
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
                pdfUrl(LocalFileRef.createPathFileRef(pdf.getCanonicalPath())), context);
            assertPdfResponse(pathResponse);

            WebResourceResponse safResponse = PdfServer.handleRequest(
                pdfUrl(LocalFileRef.createSafFileRef(
                    "content://io.github.jukomu.test.pdf/document/fixture")), context);
            assertPdfResponse(safResponse);

            WebResourceResponse folderResponse = PdfServer.handleRequest(
                pdfUrl(LocalFileRef.createPathFolderRef(context.getCacheDir().getCanonicalPath())), context);
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

    private static void createCbz(File file) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
            output.putNextEntry(new ZipEntry("ComicInfo.xml"));
            output.write("<ComicInfo><Title>Bridge CBZ</Title></ComicInfo>"
                .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("001.jpg"));
            output.write("first".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("002.png"));
            output.write("second".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static String pdfUrl(String fileRef) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
            fileRef.getBytes(StandardCharsets.UTF_8));
        return "https://jqviewer.local/pdf/" + encoded;
    }

    private static String cbzPageUrl(String fileRef, int page) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
            fileRef.getBytes(StandardCharsets.UTF_8));
        return "https://jqviewer.local/cbz-page/" + encoded + "/" + page;
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

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count > 0) output.write(buffer, 0, count);
        }
        return output.toByteArray();
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
