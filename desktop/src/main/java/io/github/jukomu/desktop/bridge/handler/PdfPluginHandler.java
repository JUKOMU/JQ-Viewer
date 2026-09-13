package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.Request;
import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.feature.pdf.management.PdfManagementService;
import io.github.jukomu.desktop.feature.pdf.model.ImportPdfsRequest;
import io.github.jukomu.desktop.feature.pdf.model.ImportedPdfsResponse;
import io.github.jukomu.desktop.feature.pdf.model.PdfFileRefRequest;
import io.github.jukomu.desktop.feature.pdf.model.PdfFilesRequest;
import io.github.jukomu.desktop.feature.pdf.model.PdfIdRequest;
import io.github.jukomu.desktop.feature.pdf.model.PdfIdsRequest;
import io.github.jukomu.desktop.feature.pdf.model.PdfRenderPageRequest;
import io.github.jukomu.desktop.feature.pdf.model.UpdateLocalEpisodeTypeRequest;
import io.javalin.http.Context;

/** 处理 Desktop PDF 文件库与阅读 bridge 请求。 */
public final class PdfPluginHandler {
    private final RequestExecutor requests;
    private final PdfManagementService pdfs;

    public PdfPluginHandler(RequestExecutor requests, PdfManagementService pdfs) {
        this.requests = requests;
        this.pdfs = pdfs;
    }

    public void importPdfs(Context context) {
        requests.run(context, ImportPdfsRequest.class, request -> pdfs.importPdfs(request.items()));
    }

    public void getImportedPdfs(Context context) {
        requests.run(context, () -> new ImportedPdfsResponse(pdfs.getImportedPdfs().files()));
    }

    public void getPdfFiles(Context context) {
        requests.run(context, PdfFilesRequest.class, request -> pdfs.getFiles(
                request.sourceType(), request.availability(), request.folderId(), request.query(),
                request.cursor(), Request.integer(request.limit(), 50)));
    }

    public void refreshPdfFileAvailability(Context context) {
        requests.run(context, PdfIdsRequest.class,
                request -> pdfs.refreshAvailability(request.ids()));
    }

    public void inspectPdfFileForDeletion(Context context) {
        requests.run(context, PdfIdRequest.class,
                request -> pdfs.inspectFileForDeletion(requireId(request.id())));
    }

    public void verifyPdfFile(Context context) {
        requests.run(context, PdfIdRequest.class,
                request -> pdfs.verifyFile(requireId(request.id())));
    }

    public void removePdfFromLibrary(Context context) {
        requests.run(context, PdfIdRequest.class,
                request -> pdfs.removeFromLibrary(requireId(request.id())));
    }

    public void deletePdfFile(Context context) {
        requests.run(context, PdfIdRequest.class,
                request -> pdfs.deleteFile(requireId(request.id())));
    }

    public void deleteImportedPdf(Context context) {
        removePdfFromLibrary(context);
    }

    public void getPdfManagementState(Context context) {
        requests.run(context, pdfs::managementState);
    }

    public void acknowledgePdfDatabaseReset(Context context) {
        requests.run(context, pdfs::acknowledgeDatabaseReset);
    }

    public void updateLocalEpisodeType(Context context) {
        requests.run(context, UpdateLocalEpisodeTypeRequest.class, request ->
                pdfs.updateLocalEpisodeType(
                        Request.requiredText(request.albumId(), "albumId"),
                        requireBoolean(request.isSingleEpisode(), "isSingleEpisode")));
    }

    public void openPdf(Context context) {
        requests.run(context, PdfFileRefRequest.class, request ->
                pdfs.openPdf(Request.requiredText(request.fileRef(), "fileRef")));
    }

    public void openPdfFolder(Context context) {
        requests.run(context, PdfFileRefRequest.class, request ->
                pdfs.openPdfFolder(Request.requiredText(request.fileRef(), "fileRef")));
    }

    public void getPdfInfo(Context context) {
        requests.run(context, PdfFileRefRequest.class, request ->
                pdfs.getPdfInfo(Request.requiredText(request.fileRef(), "fileRef")));
    }

    public void renderPdfPage(Context context) {
        requests.run(context, PdfRenderPageRequest.class, request -> pdfs.renderPdfPage(
                Request.requiredText(request.fileRef(), "fileRef"),
                Request.integer(request.page(), 1),
                Request.integer(request.targetWidth(), 1080)));
    }

    private static long requireId(Long id) {
        if (id == null || id < 0L) throw ApiException.invalidRequest("id必须是非负整数");
        return id;
    }

    private static boolean requireBoolean(Boolean value, String name) {
        if (value == null) throw ApiException.invalidRequest(name + "不能为空");
        return value;
    }
}
