package io.github.jukomu.bridge.handler;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.util.Log;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import io.github.jukomu.bridge.PluginCallSession;
import io.github.jukomu.feature.cbz.CbzDocumentService;
import io.github.jukomu.feature.download.data.DownloadStore;
import io.github.jukomu.feature.pdf.PdfOperationException;
import io.github.jukomu.feature.pdf.data.PdfRef;
import io.github.jukomu.feature.pdf.data.PdfRefResolver;
import io.github.jukomu.feature.pdf.data.LocalFileStore;
import io.github.jukomu.feature.pdf.export.PdfExportJobValidator;
import io.github.jukomu.feature.pdf.export.ExportService;
import io.github.jukomu.feature.pdf.management.PdfFileValidator;
import io.github.jukomu.feature.pdf.management.LocalFileManagementService;
import io.github.jukomu.feature.pdf.render.PdfPageCache;
import io.github.jukomu.feature.pdf.render.PdfPageResourceId;
import io.github.jukomu.feature.pdf.render.PdfPageSizing;
import io.github.jukomu.runtime.ServiceExecutors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Exposes PDF bridge operations and owns bridge-specific argument and response handling.
 * Long-running file, rendering, and export commands use the executor supplied by the plugin session.
 */
public final class LocalFilePluginHandler {
    private static final String TAG = "LocalFilePluginHandler";
    private static final String PDF_FOLDER_NOT_FOUND_MESSAGE = "PDF 文件夹不存在";
    private static final String PDF_FOLDER_PERMISSION_MESSAGE =
        "PDF 文件夹读取权限已失效，请重新选择文件夹";
    private static final String EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY =
        "com.android.externalstorage.documents";

    private final Context context;
    private final DownloadStore downloadDb;
    private final Executor pdfCommandExecutor;
    private final PluginCallSession callSession = new PluginCallSession();

    public LocalFilePluginHandler(Context context, DownloadStore downloadDb) {
        this(context, downloadDb, ServiceExecutors.fixed("pdf-command", 1));
    }

    public LocalFilePluginHandler(Context context, DownloadStore downloadDb,
                            Executor pdfCommandExecutor) {
        this.context = context.getApplicationContext();
        this.downloadDb = downloadDb;
        this.pdfCommandExecutor = pdfCommandExecutor;
    }

    /** Ends the plugin session and completes every queued or running PDF bridge call. */
    public void destroy() {
        callSession.close();
        if (pdfCommandExecutor instanceof ExecutorService) {
            ((ExecutorService) pdfCommandExecutor).shutdownNow();
        }
    }

    // ---- 文件扫描与导入 ----

    public void scanImportableFiles(PluginCall call) {
        String folderRef = call.getString("folderRef");
        if (folderRef == null || folderRef.isEmpty()) {
            call.reject("folderRef is required");
            return;
        }
        final Set<String> requestedFormats = new HashSet<>();
        JSArray formats = call.getArray("formats");
        if (formats == null || formats.length() == 0) requestedFormats.add("pdf");
        else for (int index = 0; index < formats.length(); index++) {
            String format = formats.optString(index, "").toLowerCase();
            if (!"pdf".equals(format) && !"cbz".equals(format)) {
                call.reject("导入扫描仅支持 PDF 和 CBZ");
                return;
            }
            requestedFormats.add(format);
        }
        final PdfRef.Parsed parsed;
        try {
            parsed = PdfRef.parse(folderRef);
            if (parsed.kind != PdfRef.Kind.FOLDER) {
                call.reject("folderRef must be a folder reference");
                return;
            }
        } catch (IllegalArgumentException error) {
            call.reject("folderRef is invalid", error);
            return;
        }
        dispatchPdfCommand(call, trackedCall -> {
            if (parsed.provider == PdfRef.Provider.SAF) {
                scanImportableFilesViaSaf(trackedCall, Uri.parse(parsed.payload), requestedFormats);
            } else {
                scanImportableFilesViaFile(trackedCall, parsed.payload, requestedFormats);
            }
        });
    }

    private void scanImportableFilesViaSaf(PluginCall call, Uri treeUri,
                                            Set<String> requestedFormats) {
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
            if (root == null || !root.exists() || !root.isDirectory()) {
                rejectWithCode(call, PDF_FOLDER_NOT_FOUND_MESSAGE,
                    PdfOperationException.NOT_FOUND, null);
                return;
            }
            if (!root.canRead()) {
                rejectWithCode(call, PDF_FOLDER_PERMISSION_MESSAGE,
                    PdfOperationException.PERMISSION_DENIED, null);
                return;
            }
            DocumentFile[] children = root.listFiles();
            if (children == null) {
                rejectWithCode(call, PDF_FOLDER_PERMISSION_MESSAGE,
                    PdfOperationException.PERMISSION_DENIED, null);
                return;
            }
            JSArray arr = new JSArray();
            for (DocumentFile child : children) {
                String format = child.getName() == null ? "" : importFormat(child.getName());
                if (child.isFile() && requestedFormats.contains(format)) {
                    JSObject obj = new JSObject();
                    obj.put("format", format);
                    obj.put("fileName", child.getName());
                    obj.put("fileRef", PdfRef.createSafFileRef(child.getUri().toString()));
                    obj.put("displayPath", child.getName());
                    arr.put(obj);
                }
            }
            JSObject ret = new JSObject();
            ret.put("files", arr);
            call.resolve(ret);
        } catch (SecurityException error) {
            rejectWithCode(call, PDF_FOLDER_PERMISSION_MESSAGE,
                PdfOperationException.PERMISSION_DENIED, error);
        }
    }

    private void scanImportableFilesViaFile(PluginCall call, String path,
                                             Set<String> requestedFormats) {
        File dir = new File(path);
        if (!dir.isDirectory()) {
            rejectWithCode(call, PDF_FOLDER_NOT_FOUND_MESSAGE,
                PdfOperationException.NOT_FOUND, null);
            return;
        }
        if (!dir.canRead()) {
            rejectWithCode(call, PDF_FOLDER_PERMISSION_MESSAGE,
                PdfOperationException.PERMISSION_DENIED, null);
            return;
        }
        File[] importableFiles = dir.listFiles((d, name) ->
            requestedFormats.contains(importFormat(name)));
        if (importableFiles == null) {
            rejectWithCode(call, PDF_FOLDER_PERMISSION_MESSAGE,
                PdfOperationException.PERMISSION_DENIED, null);
            return;
        }
        JSArray arr = new JSArray();
        java.util.Arrays.sort(importableFiles,
            java.util.Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File f : importableFiles) {
            if (!f.isFile()) continue;
            JSObject obj = new JSObject();
            obj.put("format", importFormat(f.getName()));
            obj.put("fileName", f.getName());
            try {
                obj.put("fileRef", PdfRef.createPathFileRef(f.getAbsolutePath()));
            } catch (Exception error) {
                continue;
            }
            obj.put("displayPath", f.getAbsolutePath());
            arr.put(obj);
        }
        JSObject ret = new JSObject();
        ret.put("files", arr);
        call.resolve(ret);
    }

    private static String importFormat(String name) {
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".cbz")) return "cbz";
        return "";
    }

    public void importLocalFiles(PluginCall call) {
        JSArray items = call.getArray("items");
        if (items == null || items.length() == 0) {
            call.reject("items is required and must not be empty");
            return;
        }
        dispatchPdfCommand(call, trackedCall -> {
            int imported = 0;
            int skipped = 0;
            int duplicateCount = 0;
            int errorCount = 0;
            LocalFileManagementService service = LocalFileManagementService.getInstance(context);
            for (int i = 0; i < items.length(); i++) {
                try {
                    JSONObject result = service.importLocalFile(items.getJSONObject(i));
                    if ("imported".equals(result.optString("result"))) imported++;
                    else {
                        skipped++;
                        duplicateCount++;
                    }
                } catch (Exception error) {
                    if (!isExpectedImportFailure(error)) {
                        Log.e(TAG, "本地文件导入失败", error);
                        trackedCall.reject(error.getMessage() == null
                            ? "本地文件导入失败" : error.getMessage(), error);
                        return;
                    }
                    skipped++;
                    errorCount++;
                    Log.w(TAG, "跳过无效的本地文件导入项", error);
                }
            }
            JSObject ret = new JSObject();
            ret.put("imported", imported);
            ret.put("skipped", skipped);
            ret.put("duplicateCount", duplicateCount);
            ret.put("errorCount", errorCount);
            trackedCall.resolve(ret);
        });
    }

    // ---- PDF 文件库 ----

    public void getImportedLocalFiles(PluginCall call) {
        JSONArray files = LocalFileStore.getInstance(context)
            .getAllFiles(null, LocalFileStore.SOURCE_IMPORTED);
        JSObject ret = new JSObject();
        ret.put("files", files);
        call.resolve(ret);
    }

    public void updateLocalEpisodeType(PluginCall call) {
        String albumId = call.getString("albumId");
        Boolean isSingleEpisode = call.getBoolean("isSingleEpisode");
        if (albumId == null || albumId.isEmpty() || isSingleEpisode == null) {
            call.reject("albumId and isSingleEpisode are required");
            return;
        }
        int updatedDownloads = downloadDb.updateAlbumEpisodeType(albumId, isSingleEpisode);
        int updatedLocalFiles = LocalFileStore.getInstance(context)
            .updateAlbumEpisodeType(albumId, isSingleEpisode);
        JSObject ret = new JSObject();
        ret.put("success", true);
        ret.put("updatedDownloads", updatedDownloads);
        ret.put("updatedLocalFiles", updatedLocalFiles);
        call.resolve(ret);
    }

    public void deleteImportedLocalFile(PluginCall call) {
        int id = call.getInt("id", -1);
        if (id < 0) {
            call.reject("id is required");
            return;
        }
        boolean ok = LocalFileStore.getInstance(context).removeFileFromLibrary(id);
        JSObject ret = new JSObject();
        ret.put("success", ok);
        call.resolve(ret);
    }


    public void getLocalFiles(PluginCall call) {
        try {
            JSArray requestedFormats = call.getArray("formats");
            List<String> formats = null;
            if (requestedFormats != null) {
                formats = new ArrayList<>();
                for (int index = 0; index < requestedFormats.length(); index++) {
                    String format = requestedFormats.optString(index, "");
                    if (!"pdf".equals(format) && !"cbz".equals(format) && !"zip".equals(format)) {
                        throw new IllegalArgumentException("formats必须只包含pdf、cbz或zip");
                    }
                    formats.add(format);
                }
                if (formats.isEmpty()) throw new IllegalArgumentException("formats不能为空");
            }
            JSONObject result = LocalFileManagementService.getInstance(context).getFiles(
                formats, call.getString("sourceType"), call.getString("availability"),
                call.getString("folderId"), call.getString("query"), call.getString("cursor"),
                call.getInt("limit", 50));
            call.resolve(JSObject.fromJSONObject(result));
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    public void refreshLocalFileAvailability(PluginCall call) {
        JSArray ids = call.getArray("ids");
        if (ids == null) {
            call.reject("ids is required");
            return;
        }
        dispatchPdfCommand(call, trackedCall -> {
            try {
                JSObject result = new JSObject();
                result.put("files", LocalFileManagementService.getInstance(context)
                    .refreshFileAvailability(ids));
                trackedCall.resolve(result);
            } catch (Exception error) {
                trackedCall.reject(error.getMessage(), error);
            }
        });
    }

    public void inspectLocalFileForDeletion(PluginCall call) {
        int id = call.getInt("id", -1);
        if (id < 0) {
            call.reject("id is required");
            return;
        }
        dispatchPdfCommand(call, trackedCall -> {
            try {
                trackedCall.resolve(JSObject.fromJSONObject(LocalFileManagementService.getInstance(context)
                    .inspectFileForDeletion(id)));
            } catch (PdfOperationException error) {
                rejectPdfOperation(trackedCall, error);
            } catch (Exception error) {
                trackedCall.reject(error.getMessage(), error);
            }
        });
    }

    public void verifyLocalFile(PluginCall call) {
        int id = call.getInt("id", -1);
        if (id < 0) {
            call.reject("id is required");
            return;
        }
        dispatchPdfCommand(call, trackedCall -> {
            try {
                trackedCall.resolve(JSObject.fromJSONObject(
                    LocalFileManagementService.getInstance(context).verifyFile(id)));
            } catch (Exception error) {
                trackedCall.reject(error.getMessage(), error);
            }
        });
    }

    public void removeLocalFileFromLibrary(PluginCall call) {
        int id = call.getInt("id", -1);
        if (id < 0) {
            call.reject("id is required");
            return;
        }
        JSObject result = new JSObject();
        result.put("success", LocalFileStore.getInstance(context).removeFileFromLibrary(id));
        call.resolve(result);
    }

    public void deleteLocalFile(PluginCall call) {
        int id = call.getInt("id", -1);
        if (id < 0) {
            call.reject("id is required");
            return;
        }
        dispatchPdfCommand(call, trackedCall -> {
            try {
                trackedCall.resolve(JSObject.fromJSONObject(
                    LocalFileManagementService.getInstance(context).deleteFile(id)));
            } catch (PdfOperationException error) {
                rejectPdfOperation(trackedCall, error);
            } catch (Exception error) {
                trackedCall.reject(error.getMessage(), error);
            }
        });
    }

    public void getLocalFileManagementState(PluginCall call) {
        try {
            call.resolve(JSObject.fromJSONObject(
                ExportService.getInstance(context).getManagementState()));
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    public void acknowledgeLocalFileDatabaseReset(PluginCall call) {
        JSObject result = new JSObject();
        result.put("acknowledged", LocalFileStore.getInstance(context).acknowledgeDatabaseReset());
        call.resolve(result);
    }

    // ---- 打开与渲染 ----

    public void openLocalFile(PluginCall call) {
        String fileRef = call.getString("fileRef");
        if (fileRef == null || fileRef.isEmpty()) {
            call.reject("fileRef is required");
            return;
        }
        try {
            Uri uri;
            PdfRef.Parsed parsed = PdfRef.parse(fileRef);
            if (parsed.kind != PdfRef.Kind.FILE) throw new IllegalArgumentException("需要文件引用");
            if (parsed.provider == PdfRef.Provider.SAF) {
                uri = PdfRefResolver.uri(fileRef);
            } else {
                File file = PdfRefResolver.pathFile(fileRef);
                if (!file.exists()) {
                    call.reject("File not found: " + parsed.payload);
                    return;
                }
                uri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    file);
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("无法打开 PDF: " + e.getMessage());
        }
    }

    public void openLocalFileFolder(PluginCall call) {
        String fileRef = call.getString("fileRef");
        if (fileRef == null || fileRef.isEmpty()) {
            call.reject("fileRef is required");
            return;
        }
        try {
            Uri folderUri = resolvePdfFolderUri(fileRef);
            boolean canGrantUri = PdfRef.parse(fileRef).provider == PdfRef.Provider.SAF
                || (context.getPackageName() + ".fileprovider")
                .equals(folderUri.getAuthority());
            context.startActivity(createPdfFolderIntent(folderUri, canGrantUri));

            JSObject result = new JSObject();
            result.put("success", true);
            call.resolve(result);
        } catch (Exception error) {
            Log.e(TAG, "打开 PDF 所在文件夹失败", error);
            call.reject("系统文件管理器无法打开该目录");
        }
    }

    private Intent createPdfFolderIntent(Uri folderUri, boolean canGrantUri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR);
        intent.addFlags(pdfFolderGrantFlags(canGrantUri));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    public static int pdfFolderGrantFlags(boolean canGrantUri) {
        return canGrantUri
            ? Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            : 0;
    }

    private Uri resolvePdfFolderUri(String fileRef) throws Exception {
        PdfRef.Parsed parsed = PdfRef.parse(fileRef);
        if (parsed.kind != PdfRef.Kind.FILE) throw new IllegalArgumentException("需要文件引用");
        if (parsed.provider == PdfRef.Provider.SAF) {
            Uri fileUri = PdfRefResolver.uri(fileRef);
            String documentId = DocumentsContract.getDocumentId(fileUri);
            int separator = documentId.lastIndexOf('/');
            String parentDocumentId;
            if (separator >= 0) {
                parentDocumentId = documentId.substring(0, separator);
            } else {
                int volumeSeparator = documentId.indexOf(':');
                if (volumeSeparator < 0) {
                    throw new IllegalArgumentException("无法确定文档所在文件夹");
                }
                parentDocumentId = documentId.substring(0, volumeSeparator + 1);
            }
            if (fileUri.getPath() != null && fileUri.getPath().contains("/tree/")) {
                return DocumentsContract.buildDocumentUriUsingTree(fileUri, parentDocumentId);
            }
            return DocumentsContract.buildDocumentUri(fileUri.getAuthority(), parentDocumentId);
        }

        File file = PdfRefResolver.pathFile(fileRef);
        File parent = file.getCanonicalFile().getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new java.io.FileNotFoundException("Parent folder not found: " + fileRef);
        }

        String parentPath = parent.getCanonicalPath();
        String primaryPath = android.os.Environment.getExternalStorageDirectory().getCanonicalPath();
        if (parentPath.equals(primaryPath) || parentPath.startsWith(primaryPath + File.separator)) {
            String relativePath = parentPath.substring(primaryPath.length()).replace(File.separatorChar, '/');
            if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
            String documentId = relativePath.isEmpty() ? "primary:" : "primary:" + relativePath;
            return DocumentsContract.buildDocumentUri(
                EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY, documentId);
        }

        return FileProvider.getUriForFile(
            context, context.getPackageName() + ".fileprovider", parent);
    }

    public void getPdfInfo(PluginCall call) {
        String fileRef = call.getString("fileRef");
        if (fileRef == null || fileRef.isEmpty()) {
            call.reject("fileRef is required");
            return;
        }

        dispatchPdfCommand(call,
            trackedCall -> getPdfInfoOnExecutor(trackedCall, fileRef));
    }

    private void getPdfInfoOnExecutor(PluginCall call, String fileRef) {
        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        try {
            pfd = openLocalFileDescriptor(fileRef);
            renderer = new PdfRenderer(pfd);
            JSObject ret = new JSObject();
            ret.put("pageCount", renderer.getPageCount());
            call.resolve(ret);
        } catch (FileNotFoundException error) {
            call.reject("PDF 信息读取失败: " + error.getMessage(),
                PdfOperationException.NOT_FOUND, error);
        } catch (SecurityException error) {
            call.reject("PDF 信息读取失败: " + error.getMessage(),
                PdfOperationException.PERMISSION_DENIED, error);
        } catch (Exception e) {
            call.reject("PDF 信息读取失败: " + e.getMessage(), e);
        } finally {
            if (renderer != null) {
                try {
                    renderer.close();
                } catch (Exception ignored) {
                }
            }
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Exception ignored) {

                }
            }
        }
    }

    public void renderPdfPage(PluginCall call) {
        String fileRef = call.getString("fileRef");
        int pageNumber = call.getInt("page", 1);
        int targetWidth = call.getInt("targetWidth", 1080);
        if (fileRef == null || fileRef.isEmpty()) {
            call.reject("fileRef is required");
            return;
        }
        if (pageNumber < 1) {
            call.reject("page out of range");
            return;
        }

        dispatchPdfCommand(call, trackedCall ->
            renderPdfPageOnExecutor(trackedCall, fileRef, pageNumber, targetWidth));
    }

    public void getCbzInfo(PluginCall call) {
        String fileRef = call.getString("fileRef");
        if (fileRef == null || fileRef.isEmpty()) {
            call.reject("fileRef is required");
            return;
        }
        dispatchPdfCommand(call, trackedCall -> {
            try {
                CbzDocumentService.Info info = CbzDocumentService.getInstance(context)
                    .getInfo(fileRef);
                JSObject result = new JSObject();
                result.put("pageCount", info.pageCount);
                result.put("title", info.title);
                result.put("series", info.series);
                result.put("number", info.number);
                result.put("authors", info.authors);
                result.put("web", info.web);
                result.put("coverPage", info.coverPage);
                result.put("metadataWarning", info.metadataWarning);
                trackedCall.resolve(result);
            } catch (CbzDocumentService.CbzException error) {
                trackedCall.reject(error.getMessage(), error.code, error);
            } catch (Exception error) {
                trackedCall.reject(error.getMessage(), error);
            }
        });
    }

    /** PDF 页面渲染和磁盘资源写入统一在单线程 executor 中执行。 */
    private void renderPdfPageOnExecutor(PluginCall call, String fileRef,
                                         int pageNumber, int targetWidth) {
        final PdfPageCache pageCache;
        try {
            pageCache = PdfPageCache.getInstance(context);
        } catch (Exception error) {
            call.reject("PDF 页面渲染失败: " + error.getMessage(), error);
            return;
        }
        PdfPageCache.SourceStamp sourceStamp;
        try {
            sourceStamp = pageCache.getSourceStamp(fileRef);
        } catch (FileNotFoundException error) {
            rejectWithCode(call, "PDF 页面渲染失败: " + error.getMessage(),
                PdfOperationException.NOT_FOUND, error);
            return;
        } catch (SecurityException error) {
            rejectWithCode(call, "PDF 页面渲染失败: " + error.getMessage(),
                PdfOperationException.PERMISSION_DENIED, error);
            return;
        } catch (Exception error) {
            call.reject("PDF 页面渲染失败: " + error.getMessage(), error);
            return;
        }

        final String resourceId;
        try {
            resourceId = PdfPageResourceId.create(
                fileRef, pageNumber, targetWidth, sourceStamp.length, sourceStamp.lastModified);
        } catch (Exception error) {
            call.reject("PDF 页面渲染失败: " + error.getMessage(), error);
            return;
        }

        if (pageCache.hasPage(resourceId)) {
            JSObject result = new JSObject();
            result.put("resourceUrl", pageCache.resourceUrl(resourceId));
            call.resolve(result);
            return;
        }

        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        PdfRenderer.Page rendererPage = null;
        Bitmap bitmap = null;
        try {
            pfd = openLocalFileDescriptor(fileRef);
            renderer = new PdfRenderer(pfd);
            int pageCount = renderer.getPageCount();
            if (pageNumber < 1 || pageNumber > pageCount) {
                call.reject("page out of range");
                return;
            }

            rendererPage = renderer.openPage(pageNumber - 1);
            PdfPageSizing.Size size = PdfPageSizing.calculate(
                targetWidth, rendererPage.getWidth(), rendererPage.getHeight());
            bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            rendererPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            pageCache.writePngAtomically(resourceId, bitmap);

            JSObject result = new JSObject();
            result.put("resourceUrl", pageCache.resourceUrl(resourceId));
            call.resolve(result);
        } catch (FileNotFoundException error) {
            rejectWithCode(call, "PDF 页面渲染失败: " + error.getMessage(),
                PdfOperationException.NOT_FOUND, error);
        } catch (SecurityException error) {
            rejectWithCode(call, "PDF 页面渲染失败: " + error.getMessage(),
                PdfOperationException.PERMISSION_DENIED, error);
        } catch (OutOfMemoryError error) {
            call.reject("PDF 页面渲染失败: " + error.getMessage(),
                new RuntimeException("PDF 页面资源不足", error));
        } catch (Exception e) {
            call.reject("PDF 页面渲染失败: " + e.getMessage(), e);
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            if (rendererPage != null) {
                try {
                    rendererPage.close();
                } catch (Exception ignored) {
                }
            }
            if (renderer != null) {
                try {
                    renderer.close();
                } catch (Exception ignored) {
                }
            }
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private ParcelFileDescriptor openLocalFileDescriptor(String fileRef) throws Exception {
        return PdfRefResolver.openReadDescriptor(context, fileRef);
    }

    // ---- 导出任务 ----

    public void exportBatch(PluginCall call) {
        try {
            JSArray tasksJson = call.getArray("tasks");
            if (tasksJson == null || tasksJson.length() == 0) {
                call.reject("tasks is required and must not be empty");
                return;
            }

            List<ExportService.ExportJob> jobs = new ArrayList<>();
            for (int i = 0; i < tasksJson.length(); i++) {
                try {
                    JSONObject t = tasksJson.getJSONObject(i);
                    String format = t.optString("format", "pdf").trim().toLowerCase();
                    if (!"pdf".equals(format) && !"cbz".equals(format) && !"zip".equals(format)) {
                        throw new IllegalArgumentException("format必须是pdf、cbz或zip");
                    }
                    ExportService.ExportJob job = new ExportService.ExportJob();
                    job.format = format;
                    job.mode = t.optString("mode", "chapter").trim();
                    job.albumId = t.optString("albumId", "");
                    job.albumTitle = t.optString("albumTitle", "");
                    job.coverUrl = t.optString("coverUrl", "");
                    job.authors = t.optString("authors", "");
                    job.singleEpisode = t.has("isSingleEpisode")
                        ? (t.optBoolean("isSingleEpisode") ? 1 : 0) : -1;
                    job.chapterId = t.optString("chapterId", "");
                    job.chapterTitle = t.optString("chapterTitle",
                        "merged".equals(job.mode) ? "合并导出" : job.chapterId);
                    job.targetFolderRef = t.optString("targetFolderRef", "");
                    job.targetName = t.optString("targetName", "");
                    job.displayPath = t.optString("displayPath", "");
                    job.useOriginal = t.optBoolean("useOriginal", true);
                    double cr = t.optDouble("compressionRatio", 1.0);
                    job.compressionRatio = (float) Math.max(0.1, Math.min(1.0, cr));
                    job.splitPages = Math.max(0, t.optInt("splitPages", 0));
                    job.allowOverwrite = t.optBoolean("allowOverwrite", false);

                    if ("merged".equals(job.mode)) {
                        JSONArray chaptersJson = t.optJSONArray("chapters");
                        if (chaptersJson != null) {
                            job.chapters = new ArrayList<>();
                            for (int j = 0; j < chaptersJson.length(); j++) {
                                JSONObject c = chaptersJson.getJSONObject(j);
                                ExportService.ExportChapter chapter =
                                    new ExportService.ExportChapter();
                                chapter.albumId = c.optString("albumId", "");
                                chapter.chapterId = c.optString("chapterId", "");
                                chapter.chapterTitle = c.optString("chapterTitle", chapter.chapterId);
                                chapter.sortOrder = c.optInt("sortOrder", 0);
                                job.chapters.add(chapter);
                            }
                        }
                    }

                    PdfExportJobValidator.validate(job);
                    jobs.add(job);
                } catch (Exception e) {
                    throw new IllegalArgumentException("tasks[" + i + "] 无效: " + e.getMessage(), e);
                }
            }

            dispatchPdfCommand(call, trackedCall -> {
                try {
                    ExportService pdfService = ExportService.getInstance(context);
                    trackedCall.resolve(JSObject.fromJSONObject(pdfService.submitExport(jobs)));
                } catch (Exception error) {
                    trackedCall.reject(error.getMessage(), error);
                }
            });
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    public void getExportTasks(PluginCall call) {
        try {
            call.resolve(JSObject.fromJSONObject(ExportService.getInstance(context)
                .getExportTasksPage(call.getString("format"), call.getString("status"), call.getString("cursor"),
                    call.getInt("limit", 50))));
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    public void getExportTask(PluginCall call) {
        String exportId = call.getString("exportId");
        JSONObject task = exportId == null ? null
            : ExportService.getInstance(context).getExportTask(exportId);
        if (task == null) {
            rejectWithCode(call, "导出任务不存在", PdfOperationException.NOT_FOUND, null);
            return;
        }
        try {
            call.resolve(JSObject.fromJSONObject(task));
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    public void cancelExport(PluginCall call) {
        String exportId = call.getString("exportId");
        JSONObject task = exportId == null ? null
            : ExportService.getInstance(context).cancelExport(exportId);
        if (task == null) {
            rejectWithCode(call, "导出任务不存在", PdfOperationException.NOT_FOUND, null);
            return;
        }
        try {
            call.resolve(JSObject.fromJSONObject(task));
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    public void retryExport(PluginCall call) {
        String exportId = call.getString("exportId");
        if (exportId == null || exportId.isEmpty()) {
            call.reject("exportId is required");
            return;
        }
        boolean allowOverwrite = call.getBoolean("allowOverwrite", false);
        dispatchPdfCommand(call, trackedCall -> {
            try {
                trackedCall.resolve(JSObject.fromJSONObject(ExportService.getInstance(context)
                    .retryExport(exportId, allowOverwrite)));
            } catch (PdfOperationException error) {
                rejectPdfOperation(trackedCall, error);
            } catch (Exception error) {
                trackedCall.reject(error.getMessage(), error);
            }
        });
    }

    public void deleteExportTask(PluginCall call) {
        String exportId = call.getString("exportId");
        if (exportId == null || exportId.isEmpty()) {
            call.reject("exportId is required");
            return;
        }
        dispatchPdfCommand(call, trackedCall -> {
            try {
                JSObject result = new JSObject();
                result.put("success", ExportService.getInstance(context)
                    .deleteExportTask(exportId));
                trackedCall.resolve(result);
            } catch (Exception error) {
                trackedCall.reject(error.getMessage(), error);
            }
        });
    }


    private void dispatchPdfCommand(PluginCall call, Consumer<PluginCall> command) {
        callSession.submit(pdfCommandExecutor, call, command);
    }

    private static boolean isExpectedImportFailure(Exception error) {
        return error instanceof JSONException
            || error instanceof IllegalArgumentException
            || error instanceof PdfFileValidator.ValidationException
            || error instanceof CbzDocumentService.CbzException;
    }

    private static void rejectWithCode(PluginCall call, String message, String code,
                                       Exception cause) {
        call.reject(message, code, cause);
    }

    private static void rejectPdfOperation(PluginCall call, PdfOperationException error) {
        rejectWithCode(call, error.getMessage(), error.code, error);
    }
}
