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
import io.github.jukomu.feature.download.data.DownloadStore;
import io.github.jukomu.feature.pdf.PdfOperationException;
import io.github.jukomu.feature.pdf.data.PdfRef;
import io.github.jukomu.feature.pdf.data.PdfRefResolver;
import io.github.jukomu.feature.pdf.data.PdfStore;
import io.github.jukomu.feature.pdf.export.PdfExportJobValidator;
import io.github.jukomu.feature.pdf.export.PdfExportService;
import io.github.jukomu.feature.pdf.management.PdfManagementService;
import io.github.jukomu.feature.pdf.render.PdfPageCache;
import io.github.jukomu.feature.pdf.render.PdfPageResourceId;
import io.github.jukomu.feature.pdf.render.PdfPageSizing;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Exposes PDF bridge operations and owns bridge-specific argument and response handling.
 * Long-running file, rendering, and export commands use the executor supplied by the plugin session.
 */
public final class PdfPluginHandler {
    private static final String TAG = "PdfPluginHandler";
    private static final String PDF_FOLDER_NOT_FOUND_MESSAGE = "PDF 文件夹不存在";
    private static final String PDF_FOLDER_PERMISSION_MESSAGE =
        "PDF 文件夹读取权限已失效，请重新选择文件夹";
    private static final String EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY =
        "com.android.externalstorage.documents";

    private final Context context;
    private final DownloadStore downloadDb;
    private final Executor pdfCommandExecutor;

    public PdfPluginHandler(Context context, DownloadStore downloadDb,
                            Executor pdfCommandExecutor) {
        this.context = context.getApplicationContext();
        this.downloadDb = downloadDb;
        this.pdfCommandExecutor = pdfCommandExecutor;
    }

    // ---- 文件扫描与导入 ----

    public void scanPdfFiles(PluginCall call) {
        String folderRef = call.getString("folderRef");
        if (folderRef == null || folderRef.isEmpty()) {
            call.reject("folderRef is required");
            return;
        }
        try {
            PdfRef.Parsed parsed = PdfRef.parse(folderRef);
            if (parsed.kind != PdfRef.Kind.FOLDER) {
                call.reject("folderRef must be a folder reference");
                return;
            }
            if (parsed.provider == PdfRef.Provider.SAF) {
                scanPdfFilesViaSaf(call, Uri.parse(parsed.payload));
            } else {
                scanPdfFilesViaFile(call, parsed.payload);
            }
        } catch (IllegalArgumentException error) {
            call.reject("folderRef is invalid", error);
        }
    }

    private void scanPdfFilesViaSaf(PluginCall call, Uri treeUri) {
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
                if (child.isFile() && child.getName() != null
                    && child.getName().toLowerCase().endsWith(".pdf")) {
                    JSObject obj = new JSObject();
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

    private void scanPdfFilesViaFile(PluginCall call, String path) {
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
        File[] pdfFiles = dir.listFiles((d, name) ->
            name.toLowerCase().endsWith(".pdf"));
        if (pdfFiles == null) {
            rejectWithCode(call, PDF_FOLDER_PERMISSION_MESSAGE,
                PdfOperationException.PERMISSION_DENIED, null);
            return;
        }
        JSArray arr = new JSArray();
        for (File f : pdfFiles) {
            if (!f.isFile()) continue;
            JSObject obj = new JSObject();
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

    public void importPdfs(PluginCall call) {
        JSArray items = call.getArray("items");
        if (items == null || items.length() == 0) {
            call.reject("items is required and must not be empty");
            return;
        }
        dispatchPdfCommand(() -> {
            int imported = 0;
            int skipped = 0;
            int duplicateCount = 0;
            int errorCount = 0;
            PdfManagementService service = PdfManagementService.getInstance(context);
            for (int i = 0; i < items.length(); i++) {
                try {
                    JSONObject result = service.importPdf(items.getJSONObject(i));
                    if ("imported".equals(result.optString("result"))) imported++;
                    else {
                        skipped++;
                        duplicateCount++;
                    }
                } catch (Exception error) {
                    skipped++;
                    errorCount++;
                    Log.w(TAG, "跳过无效的 PDF 导入项", error);
                }
            }
            JSObject ret = new JSObject();
            ret.put("imported", imported);
            ret.put("skipped", skipped);
            ret.put("duplicateCount", duplicateCount);
            ret.put("errorCount", errorCount);
            call.resolve(ret);
        });
    }

    // ---- PDF 文件库 ----

    public void getImportedPdfs(PluginCall call) {
        JSONArray pdfs = PdfStore.getInstance(context).getAllFiles();
        JSObject ret = new JSObject();
        ret.put("pdfs", pdfs);
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
        int updatedPdfs = PdfStore.getInstance(context)
            .updateAlbumEpisodeType(albumId, isSingleEpisode);
        JSObject ret = new JSObject();
        ret.put("success", true);
        ret.put("updatedDownloads", updatedDownloads);
        ret.put("updatedPdfs", updatedPdfs);
        call.resolve(ret);
    }

    public void deleteImportedPdf(PluginCall call) {
        int id = call.getInt("id", -1);
        if (id < 0) {
            call.reject("id is required");
            return;
        }
        boolean ok = PdfStore.getInstance(context).removeFileFromLibrary(id);
        JSObject ret = new JSObject();
        ret.put("success", ok);
        call.resolve(ret);
    }


    public void getPdfFiles(PluginCall call) {
        try {
            JSONObject result = PdfManagementService.getInstance(context).getFiles(
                call.getString("sourceType"), call.getString("availability"),
                call.getString("folderId"), call.getString("query"), call.getString("cursor"),
                call.getInt("limit", 50));
            call.resolve(JSObject.fromJSONObject(result));
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    public void refreshPdfFileAvailability(PluginCall call) {
        JSArray ids = call.getArray("ids");
        if (ids == null) {
            call.reject("ids is required");
            return;
        }
        dispatchPdfCommand(() -> {
            JSObject result = new JSObject();
            result.put("files", PdfManagementService.getInstance(context)
                .refreshFileAvailability(ids));
            call.resolve(result);
        });
    }

    public void inspectPdfFileForDeletion(PluginCall call) {
        int id = call.getInt("id", -1);
        if (id < 0) {
            call.reject("id is required");
            return;
        }
        dispatchPdfCommand(() -> {
            try {
                call.resolve(JSObject.fromJSONObject(PdfManagementService.getInstance(context)
                    .inspectFileForDeletion(id)));
            } catch (PdfOperationException error) {
                rejectPdfOperation(call, error);
            } catch (Exception error) {
                call.reject(error.getMessage(), error);
            }
        });
    }

    public void verifyPdfFile(PluginCall call) {
        int id = call.getInt("id", -1);
        if (id < 0) {
            call.reject("id is required");
            return;
        }
        dispatchPdfCommand(() -> {
            try {
                call.resolve(JSObject.fromJSONObject(
                    PdfManagementService.getInstance(context).verifyFile(id)));
            } catch (Exception error) {
                call.reject(error.getMessage(), error);
            }
        });
    }

    public void removePdfFromLibrary(PluginCall call) {
        int id = call.getInt("id", -1);
        if (id < 0) {
            call.reject("id is required");
            return;
        }
        JSObject result = new JSObject();
        result.put("success", PdfStore.getInstance(context).removeFileFromLibrary(id));
        call.resolve(result);
    }

    public void deletePdfFile(PluginCall call) {
        int id = call.getInt("id", -1);
        if (id < 0) {
            call.reject("id is required");
            return;
        }
        dispatchPdfCommand(() -> {
            try {
                call.resolve(JSObject.fromJSONObject(
                    PdfManagementService.getInstance(context).deleteFile(id)));
            } catch (PdfOperationException error) {
                rejectPdfOperation(call, error);
            } catch (Exception error) {
                call.reject(error.getMessage(), error);
            }
        });
    }

    public void getPdfManagementState(PluginCall call) {
        try {
            call.resolve(JSObject.fromJSONObject(
                PdfExportService.getInstance(context).getManagementState()));
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    public void acknowledgePdfDatabaseReset(PluginCall call) {
        JSObject result = new JSObject();
        result.put("acknowledged", PdfStore.getInstance(context).acknowledgeDatabaseReset());
        call.resolve(result);
    }

    // ---- 打开与渲染 ----

    public void openPdf(PluginCall call) {
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

    public void openPdfFolder(PluginCall call) {
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

        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        try {
            pfd = openPdfDescriptor(fileRef);
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

        dispatchPdfCommand(() -> renderPdfPageOnExecutor(call, fileRef, pageNumber, targetWidth));
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
            pfd = openPdfDescriptor(fileRef);
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

    private ParcelFileDescriptor openPdfDescriptor(String fileRef) throws Exception {
        return PdfRefResolver.openReadDescriptor(context, fileRef);
    }

    // ---- 导出任务 ----

    public void exportPdfBatch(PluginCall call) {
        try {
            JSArray tasksJson = call.getArray("tasks");
            if (tasksJson == null || tasksJson.length() == 0) {
                call.reject("tasks is required and must not be empty");
                return;
            }

            List<PdfExportService.ExportJob> jobs = new ArrayList<>();
            for (int i = 0; i < tasksJson.length(); i++) {
                try {
                    JSONObject t = tasksJson.getJSONObject(i);
                    PdfExportService.ExportJob job = new PdfExportService.ExportJob();
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
                                PdfExportService.ExportChapter chapter =
                                    new PdfExportService.ExportChapter();
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

            dispatchPdfCommand(() -> {
                try {
                    PdfExportService pdfService = PdfExportService.getInstance(context);
                    call.resolve(JSObject.fromJSONObject(pdfService.submitExport(jobs)));
                } catch (Exception error) {
                    call.reject(error.getMessage(), error);
                }
            });
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    public void getPdfExportTasks(PluginCall call) {
        try {
            call.resolve(JSObject.fromJSONObject(PdfExportService.getInstance(context)
                .getExportTasksPage(call.getString("status"), call.getString("cursor"),
                    call.getInt("limit", 50))));
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    public void getPdfExportTask(PluginCall call) {
        String exportId = call.getString("exportId");
        JSONObject task = exportId == null ? null
            : PdfExportService.getInstance(context).getExportTask(exportId);
        if (task == null) {
            rejectWithCode(call, "PDF 导出任务不存在", PdfOperationException.NOT_FOUND, null);
            return;
        }
        try {
            call.resolve(JSObject.fromJSONObject(task));
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    public void cancelPdfExport(PluginCall call) {
        String exportId = call.getString("exportId");
        JSONObject task = exportId == null ? null
            : PdfExportService.getInstance(context).cancelExport(exportId);
        if (task == null) {
            rejectWithCode(call, "PDF 导出任务不存在", PdfOperationException.NOT_FOUND, null);
            return;
        }
        try {
            call.resolve(JSObject.fromJSONObject(task));
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    public void retryPdfExport(PluginCall call) {
        String exportId = call.getString("exportId");
        if (exportId == null || exportId.isEmpty()) {
            call.reject("exportId is required");
            return;
        }
        dispatchPdfCommand(() -> {
            try {
                call.resolve(JSObject.fromJSONObject(PdfExportService.getInstance(context)
                    .retryExport(exportId, call.getBoolean("allowOverwrite", false))));
            } catch (PdfOperationException error) {
                rejectPdfOperation(call, error);
            } catch (Exception error) {
                call.reject(error.getMessage(), error);
            }
        });
    }

    public void deletePdfExportTask(PluginCall call) {
        String exportId = call.getString("exportId");
        if (exportId == null || exportId.isEmpty()) {
            call.reject("exportId is required");
            return;
        }
        dispatchPdfCommand(() -> {
            try {
                JSObject result = new JSObject();
                result.put("success", PdfExportService.getInstance(context)
                    .deleteExportTask(exportId));
                call.resolve(result);
            } catch (Exception error) {
                call.reject(error.getMessage(), error);
            }
        });
    }


    private void dispatchPdfCommand(Runnable command) {
        pdfCommandExecutor.execute(command);
    }

    private static void rejectWithCode(PluginCall call, String message, String code,
                                       Exception cause) {
        call.reject(message, code, cause);
    }

    private static void rejectPdfOperation(PluginCall call, PdfOperationException error) {
        rejectWithCode(call, error.getMessage(), error.code, error);
    }
}
