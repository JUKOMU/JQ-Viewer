package io.github.jukomu.feature.pdf.management;

import android.content.Context;
import android.database.Cursor;
import io.github.jukomu.feature.pdf.PdfOperationException;
import io.github.jukomu.feature.pdf.data.PdfRef;
import io.github.jukomu.feature.pdf.data.PdfRefResolver;
import io.github.jukomu.feature.pdf.data.PdfStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

/**
 * File-library operations that do not own the export task lifecycle.
 */
public final class PdfManagementService {

    private static PdfManagementService instance;

    private final Context context;
    private final PdfStore store;

    private PdfManagementService(Context context) {
        this.context = context.getApplicationContext();
        this.store = PdfStore.getInstance(this.context);
    }

    public static synchronized PdfManagementService getInstance(Context context) {
        if (instance == null) instance = new PdfManagementService(context);
        return instance;
    }

    public static synchronized void clearInstanceForTest() {
        instance = null;
    }

    public JSONObject importPdf(JSONObject item) throws Exception {
        String fileRef = item.getString("fileRef");
        PdfRef.parse(fileRef);
        String displayPath = item.optString("displayPath", "");
        JSONObject existing = store.getFileByRef(fileRef);
        if (existing != null) return outcome("already_managed", existing, null);

        PdfFileValidator.Report report = PdfFileValidator.validate(context, fileRef, -1);
        long id = store.insertImportedPdf(
            fileRef,
            displayPath,
            item.optString("fileName", PdfRef.fileName(fileRef)),
            item.getString("albumId"),
            item.optString("albumTitle", ""),
            item.optString("coverUrl", ""),
            item.optString("authors", ""),
            item.optString("chapterId", ""),
            item.optString("chapterTitle", ""),
            item.optInt("chapterSortOrder", 0),
            item.has("isSingleEpisode")
                ? (item.optBoolean("isSingleEpisode") ? 1 : 0) : -1,
            System.currentTimeMillis(),
            item.optString("folderId", null),
            report.fileSize,
            report.pageCount
        );
        JSONObject result = outcome("imported", store.getFile(id), null);
        result.put("fileSize", report.fileSize);
        result.put("pageCount", report.pageCount);
        return result;
    }

    public JSONObject getFiles(String sourceType, String availability, String folderId,
                               String query, String cursor, int limit) {
        return store.getFilesPage(sourceType, availability, folderId, query, cursor, limit);
    }

    public JSONObject verifyFile(long id) throws Exception {
        JSONObject record = requireFile(id);
        try {
            PdfFileValidator.Report report = PdfFileValidator.validate(
                context, record.getString("fileRef"), record.optInt("pageCount", -1));
            return store.updateFileVerification(id, "available", "valid", null,
                report.fileSize, report.pageCount);
        } catch (PdfFileValidator.ValidationException error) {
            String availability = "invalid";
            String verificationStatus = "corrupt";
            if ("PDF_MISSING".equals(error.code)) {
                availability = "missing";
                verificationStatus = "unverified";
            } else if ("PDF_INACCESSIBLE".equals(error.code)) {
                availability = "inaccessible";
                verificationStatus = "unverified";
            } else if ("PDF_PAGE_MISMATCH".equals(error.code)) {
                verificationStatus = "page_mismatch";
            }
            return store.updateFileVerification(id, availability, verificationStatus,
                error.code + ": " + error.getMessage(), -1L, -1);
        }
    }

    /**
     * Refreshes the exact information shown by the destructive confirmation dialog.
     */
    public JSONObject inspectFileForDeletion(long id) throws Exception {
        JSONObject refreshed = verifyFile(id);
        if (refreshed == null) {
            throw PdfOperationException.notFound("PDF 文件记录不存在");
        }
        return refreshed;
    }

    public JSONArray refreshFileAvailability(JSONArray ids) {
        JSONArray files = new JSONArray();
        for (int index = 0; index < ids.length(); index++) {
            long id = ids.optLong(index, -1L);
            if (id < 0L) continue;
            try {
                files.put(verifyFile(id));
            } catch (Exception ignored) {
                JSONObject current = store.getFile(id);
                if (current != null) files.put(current);
            }
        }
        return files;
    }

    public JSONObject updateMetadata(long id, JSONObject metadata) {
        return store.updateFileMetadata(id, metadata);
    }

    public JSONObject deleteFile(long id) throws Exception {
        JSONObject record = requireFile(id);
        String fileRef = record.getString("fileRef");
        DeleteOutcome result = deleteFileRef(fileRef);
        if (!store.removeFileFromLibrary(id)) {
            throw new IOException("PDF 文件已处理，但文件库记录移除失败");
        }
        return outcome(result == DeleteOutcome.DELETED ? "deleted" : "already_missing",
            record, null);
    }

    private JSONObject requireFile(long id) throws PdfOperationException {
        JSONObject record = store.getFile(id);
        if (record == null) {
            throw PdfOperationException.notFound("PDF 文件记录不存在");
        }
        return record;
    }

    private DeleteOutcome deleteFileRef(String fileRef) throws IOException {
        PdfRef.Parsed parsed = PdfRef.parse(fileRef);
        if (parsed.provider == PdfRef.Provider.SAF) {
            try {
                if (context.getContentResolver().delete(PdfRefResolver.uri(fileRef), null, null) > 0) {
                    return DeleteOutcome.DELETED;
                }
                if (!PdfRefResolver.exists(context, fileRef)) return DeleteOutcome.ALREADY_MISSING;
                throw new IOException("PDF_DELETE_FAILED: 文件提供方拒绝删除 PDF");
            } catch (SecurityException error) {
                throw PdfOperationException.permissionDenied(
                    "PDF_INACCESSIBLE: 没有权限删除 PDF", error);
            } catch (IOException error) {
                throw error;
            } catch (Exception error) {
                throw new IOException("PDF_DELETE_FAILED: 无法删除 PDF", error);
            }
        }
        java.io.File file = PdfRefResolver.pathFile(fileRef);
        if (!file.exists()) return DeleteOutcome.ALREADY_MISSING;
        if (!file.canWrite()) {
            throw PdfOperationException.permissionDenied(
                "PDF_INACCESSIBLE: 没有权限删除 PDF", null);
        }
        if (!file.isFile() || !file.delete()) {
            throw new IOException("PDF_DELETE_FAILED: PDF 文件删除失败");
        }
        return DeleteOutcome.DELETED;
    }

    private static JSONObject outcome(String kind, JSONObject record, String message)
        throws Exception {
        JSONObject result = new JSONObject();
        result.put("result", kind);
        if (record != null) {
            result.put("id", record.optLong("id"));
            result.put("sourceType", record.optString("sourceType"));
            result.put("ownership", record.optString("ownership"));
            result.put("fileRef", record.optString("fileRef"));
            result.put("displayPath", record.optString("displayPath"));
            result.put("fileName", record.optString("fileName"));
        }
        if (message != null) result.put("errorMessage", message);
        return result;
    }

    private enum DeleteOutcome {
        DELETED,
        ALREADY_MISSING
    }
}
