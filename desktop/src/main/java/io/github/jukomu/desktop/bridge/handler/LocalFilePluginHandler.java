package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.Request;
import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.feature.export.ExportService;
import io.github.jukomu.desktop.feature.localfile.management.LocalFileManagementService;
import io.github.jukomu.desktop.feature.localfile.model.*;
import io.github.jukomu.desktop.feature.pdf.model.PdfRenderPageRequest;
import io.javalin.http.Context;

/**
 * 处理 Desktop 本地文件库、导出与 PDF 阅读 bridge 请求。
 */
public final class LocalFilePluginHandler {
    private final RequestExecutor requests;
    private final LocalFileManagementService localFiles;
    private final ExportService exports;

    public LocalFilePluginHandler(
        RequestExecutor requests,
        LocalFileManagementService localFiles,
        ExportService exports
    ) {
        this.requests = requests;
        this.localFiles = localFiles;
        this.exports = exports;
    }

    public void exportBatch(Context context) {
        requests.runLongOperation(context, ExportBatchRequest.class,
            request -> exports.submit(request.tasks()));
    }

    public void getExportTasks(Context context) {
        requests.run(context, ExportTasksRequest.class, request -> exports.getTasks(
            request.format(), request.status(), request.cursor(),
            Request.integer(request.limit(), 50)));
    }

    public void getExportTask(Context context) {
        requests.run(context, ExportIdRequest.class,
            request -> exports.getTask(Request.requiredText(request.exportId(), "exportId")));
    }

    public void cancelExport(Context context) {
        requests.run(context, ExportIdRequest.class,
            request -> exports.cancel(Request.requiredText(request.exportId(), "exportId")));
    }

    public void retryExport(Context context) {
        requests.runLongOperation(context, ExportRetryRequest.class, request -> exports.retry(
            Request.requiredText(request.exportId(), "exportId"),
            Request.bool(request.allowOverwrite(), false)));
    }

    public void deleteExportTask(Context context) {
        requests.run(context, ExportIdRequest.class, request -> new ExportDeleteResponse(
            exports.deleteTask(Request.requiredText(request.exportId(), "exportId"))));
    }

    public void importLocalFiles(Context context) {
        requests.run(context, ImportLocalFilesRequest.class,
            request -> localFiles.importLocalFiles(request.items()));
    }

    public void getImportedLocalFiles(Context context) {
        requests.run(context, () -> new ImportedLocalFilesResponse(
            localFiles.getImportedLocalFiles().files()));
    }

    public void getLocalFiles(Context context) {
        requests.run(context, LocalFilesRequest.class, request -> localFiles.getFiles(
            request.formats(), request.sourceType(), request.availability(),
            request.fileId(), request.albumId(), request.chapterId(),
            request.folderId(), request.query(),
            request.cursor(), Request.integer(request.limit(), 50)));
    }

    public void refreshLocalFileAvailability(Context context) {
        requests.run(context, LocalFileIdsRequest.class,
            request -> localFiles.refreshAvailability(request.ids()));
    }

    public void inspectLocalFileForDeletion(Context context) {
        requests.run(context, LocalFileIdRequest.class,
            request -> localFiles.inspectFileForDeletion(requireId(request.id())));
    }

    public void verifyLocalFile(Context context) {
        requests.run(context, LocalFileIdRequest.class,
            request -> localFiles.verifyFile(requireId(request.id())));
    }

    public void removeLocalFileFromLibrary(Context context) {
        requests.run(context, LocalFileIdRequest.class,
            request -> localFiles.removeFromLibrary(requireId(request.id())));
    }

    public void deleteLocalFile(Context context) {
        requests.run(context, LocalFileIdRequest.class,
            request -> localFiles.deleteFile(requireId(request.id())));
    }

    public void deleteImportedLocalFile(Context context) {
        removeLocalFileFromLibrary(context);
    }

    public void getLocalFileManagementState(Context context) {
        requests.run(context, localFiles::managementState);
    }

    public void acknowledgeLocalFileDatabaseReset(Context context) {
        requests.run(context, localFiles::acknowledgeDatabaseReset);
    }

    public void updateLocalEpisodeType(Context context) {
        requests.run(context, UpdateLocalEpisodeTypeRequest.class, request ->
            localFiles.updateLocalEpisodeType(
                Request.requiredText(request.albumId(), "albumId"),
                requireBoolean(request.isSingleEpisode(), "isSingleEpisode")));
    }

    public void openLocalFile(Context context) {
        requests.run(context, LocalFileRefRequest.class, request ->
            localFiles.openLocalFile(Request.requiredText(request.fileRef(), "fileRef")));
    }

    public void openLocalFileFolder(Context context) {
        requests.run(context, LocalFileRefRequest.class, request ->
            localFiles.openLocalFileFolder(Request.requiredText(request.fileRef(), "fileRef")));
    }

    public void getPdfInfo(Context context) {
        requests.run(context, LocalFileRefRequest.class, request ->
            localFiles.getPdfInfo(Request.requiredText(request.fileRef(), "fileRef")));
    }

    public void renderPdfPage(Context context) {
        requests.run(context, PdfRenderPageRequest.class, request -> localFiles.renderPdfPage(
            Request.requiredText(request.fileRef(), "fileRef"),
            Request.integer(request.page(), 1),
            Request.integer(request.targetWidth(), 1080)));
    }

    public void getCbzInfo(Context context) {
        requests.run(context, LocalFileRefRequest.class, request -> localFiles.getCbzInfo(
            Request.requiredText(request.fileRef(), "fileRef")));
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
