package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.feature.files.FileService;
import io.github.jukomu.desktop.feature.files.model.FileRefRequest;
import io.github.jukomu.desktop.feature.files.model.FileRefsRequest;
import io.github.jukomu.desktop.feature.files.model.FolderPurposeRequest;
import io.github.jukomu.desktop.feature.files.model.FolderRefRequest;
import io.javalin.http.Context;

import static io.github.jukomu.desktop.util.RequestValidation.requiredText;

/**
 * 处理 Desktop 文件与目录 bridge 请求。
 */
public final class FilePluginHandler {
    private final RequestExecutor fileRequests;
    private final RequestExecutor dialogRequests;
    private final FileService files;

    public FilePluginHandler(
        RequestExecutor fileRequests,
        RequestExecutor dialogRequests,
        FileService files
    ) {
        this.fileRequests = fileRequests;
        this.dialogRequests = dialogRequests;
        this.files = files;
    }

    public void pickFolder(Context context) {
        dialogRequests.runLongOperation(context, FolderPurposeRequest.class,
            request -> files.pickFolder(requiredText(request.purpose(), "purpose")));
    }

    public void getDefaultFolder(Context context) {
        fileRequests.run(context, FolderPurposeRequest.class,
            request -> files.getDefaultFolder(requiredText(request.purpose(), "purpose")));
    }

    public void checkFilesExist(Context context) {
        fileRequests.run(context, FileRefsRequest.class,
            request -> files.checkFilesExist(request.files()));
    }

    public void openFile(Context context) {
        fileRequests.run(context, FileRefRequest.class,
            request -> files.openFile(requiredText(request.file(), "file")));
    }

    public void openContainingFolder(Context context) {
        fileRequests.run(context, FileRefRequest.class,
            request -> files.openContainingFolder(requiredText(request.file(), "file")));
    }

    public void scanImportableFiles(Context context) {
        fileRequests.runLongOperation(context, FolderRefRequest.class,
            request -> files.scanImportableFiles(
                requiredText(request.folder(), "folder"), request.formats()));
    }
}
