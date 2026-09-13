package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.bridge.Request;
import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.feature.files.FileService;
import io.github.jukomu.desktop.feature.files.model.FileRefRequest;
import io.github.jukomu.desktop.feature.files.model.FileRefsRequest;
import io.github.jukomu.desktop.feature.files.model.FolderPurposeRequest;
import io.github.jukomu.desktop.feature.files.model.FolderRefRequest;
import io.javalin.http.Context;

/** 处理 Desktop 文件与目录 bridge 请求。 */
public final class FilePluginHandler {
    private final RequestExecutor requests;
    private final FileService files;

    public FilePluginHandler(RequestExecutor requests, FileService files) {
        this.requests = requests;
        this.files = files;
    }

    public void pickFolder(Context context) {
        requests.run(context, FolderPurposeRequest.class,
                request -> files.pickFolder(Request.requiredText(request.purpose(), "purpose")));
    }

    public void getDefaultFolder(Context context) {
        requests.run(context, FolderPurposeRequest.class,
                request -> files.getDefaultFolder(Request.requiredText(request.purpose(), "purpose")));
    }

    public void checkFilesExist(Context context) {
        requests.run(context, FileRefsRequest.class,
                request -> files.checkFilesExist(request.files()));
    }

    public void openFile(Context context) {
        requests.run(context, FileRefRequest.class,
                request -> files.openFile(Request.requiredText(request.file(), "file")));
    }

    public void openContainingFolder(Context context) {
        requests.run(context, FileRefRequest.class,
                request -> files.openContainingFolder(Request.requiredText(request.file(), "file")));
    }

    public void scanPdfFiles(Context context) {
        requests.run(context, FolderRefRequest.class,
                request -> files.scanPdfFiles(Request.requiredText(request.folder(), "folder")));
    }
}
