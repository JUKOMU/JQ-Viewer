package io.github.jukomu.feature.localfile.management;

import android.content.Context;
import io.github.jukomu.feature.cbz.CbzDocumentService;
import io.github.jukomu.feature.localfile.LocalFileOperationException;
import io.github.jukomu.feature.localfile.data.LocalFileRef;
import io.github.jukomu.feature.localfile.data.LocalFileRefResolver;
import io.github.jukomu.feature.localfile.data.LocalFileStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

/**
 * 不拥有导出任务生命周期的文件库操作。
 */
public final class LocalFileManagementService {

    private static LocalFileManagementService instance;

    private final Context context;
    private final LocalFileStore store;

    private LocalFileManagementService(Context context) {
        this.context = context.getApplicationContext();
        this.store = LocalFileStore.getInstance(this.context);
    }

    public static synchronized LocalFileManagementService getInstance(Context context) {
        if (instance == null) instance = new LocalFileManagementService(context);
        return instance;
    }

    public static synchronized void clearInstanceForTest() {
        instance = null;
    }

    public JSONObject importLocalFile(JSONObject item) throws Exception {
        String format = item.optString("format", "pdf").trim().toLowerCase();
        if (!"pdf".equals(format) && !"cbz".equals(format)) {
            throw new IllegalArgumentException("导入仅支持 PDF 和 CBZ");
        }
        String fileRef = item.getString("fileRef");
        LocalFileRef.parse(fileRef);
        String displayPath = item.optString("displayPath", "");
        JSONObject existing = store.getFileByRef(fileRef);
        if (existing != null) return outcome("already_managed", existing, null);

        ValidationReport report = validate(format, fileRef, -1);
        long id = store.insertImportedFile(
            format,
            fileRef,
            displayPath,
            item.optString("fileName", LocalFileRef.fileName(fileRef)),
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

    public JSONObject getFiles(List<String> formats, String sourceType, String availability,
                               Long fileId, String albumId, String chapterId, String folderId,
                               String query, String cursor, int limit) {
        return store.getFilesPage(formats, sourceType, availability, fileId, albumId, chapterId,
            folderId, query, cursor, limit);
    }

    public JSONObject verifyFile(long id) throws Exception {
        JSONObject record = requireFile(id);
        String format = record.optString("format", "pdf");
        try {
            ValidationReport report = validate(
                format, record.getString("fileRef"), record.optInt("pageCount", -1));
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
        } catch (CbzDocumentService.CbzException error) {
            String availability = "invalid";
            String verificationStatus = "corrupt";
            if ("CBZ_MISSING".equals(error.code)) {
                availability = "missing";
                verificationStatus = "unverified";
            } else if ("CBZ_INACCESSIBLE".equals(error.code)) {
                availability = "inaccessible";
                verificationStatus = "unverified";
            } else if ("CBZ_PAGE_MISMATCH".equals(error.code)) {
                verificationStatus = "page_mismatch";
            }
            return store.updateFileVerification(id, availability, verificationStatus,
                error.code + ": " + error.getMessage(), -1L, -1);
        } catch (ZipFileValidator.ValidationException error) {
            String availability = "invalid";
            String verificationStatus = "corrupt";
            if ("ZIP_MISSING".equals(error.code) || "ZIP_INACCESSIBLE".equals(error.code)) {
                availability = "ZIP_MISSING".equals(error.code) ? "missing" : "inaccessible";
                verificationStatus = "unverified";
            } else if ("ZIP_PAGE_MISMATCH".equals(error.code)) {
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
            throw LocalFileOperationException.notFound("本地文件记录不存在");
        }
        return refreshed;
    }

    public JSONArray refreshFileAvailability(JSONArray ids) throws Exception {
        JSONArray files = new JSONArray();
        for (int index = 0; index < ids.length(); index++) {
            long id = ids.optLong(index, -1L);
            if (id < 0L) continue;
            try {
                JSONObject refreshed = verifyFile(id);
                if (refreshed != null) files.put(refreshed);
            } catch (LocalFileOperationException error) {
                if (LocalFileOperationException.NOT_FOUND.equals(error.code)) continue;
                throw error;
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
            throw new IOException("文件已处理，但文件库记录移除失败");
        }
        return outcome(result == DeleteOutcome.DELETED ? "deleted" : "already_missing",
            record, null);
    }

    private JSONObject requireFile(long id) throws LocalFileOperationException {
        JSONObject record = store.getFile(id);
        if (record == null) {
            throw LocalFileOperationException.notFound("本地文件记录不存在");
        }
        return record;
    }

    private DeleteOutcome deleteFileRef(String fileRef) throws IOException {
        LocalFileRef.Parsed parsed = LocalFileRef.parse(fileRef);
        if (parsed.provider == LocalFileRef.Provider.SAF) {
            try {
                if (context.getContentResolver().delete(LocalFileRefResolver.uri(fileRef), null, null) > 0) {
                    return DeleteOutcome.DELETED;
                }
                if (!LocalFileRefResolver.exists(context, fileRef)) return DeleteOutcome.ALREADY_MISSING;
                throw new IOException("FILE_DELETE_FAILED: 文件提供方拒绝删除文件");
            } catch (SecurityException error) {
                throw LocalFileOperationException.permissionDenied(
                    "FILE_INACCESSIBLE: 没有权限删除文件", error);
            } catch (IOException error) {
                throw error;
            } catch (Exception error) {
                throw new IOException("FILE_DELETE_FAILED: 无法删除文件", error);
            }
        }
        java.io.File file = LocalFileRefResolver.pathFile(fileRef);
        if (!file.exists()) return DeleteOutcome.ALREADY_MISSING;
        if (!file.canWrite()) {
            throw LocalFileOperationException.permissionDenied(
                "FILE_INACCESSIBLE: 没有权限删除文件", null);
        }
        if (!file.isFile() || !file.delete()) {
            throw new IOException("FILE_DELETE_FAILED: 文件删除失败");
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

    private ValidationReport validate(String format, String fileRef, int expectedPages)
        throws PdfFileValidator.ValidationException, CbzDocumentService.CbzException,
        ZipFileValidator.ValidationException {
        if ("cbz".equals(format)) {
            CbzDocumentService.ValidationReport report = CbzDocumentService.getInstance(context)
                .validate(fileRef, expectedPages);
            return new ValidationReport(report.fileSize, report.pageCount);
        }
        if ("zip".equals(format)) {
            ZipFileValidator.Report report = ZipFileValidator.validate(context, fileRef, expectedPages);
            return new ValidationReport(report.fileSize, report.pageCount);
        }
        if (!"pdf".equals(format)) {
            throw new IllegalArgumentException("内容校验仅支持 PDF 和 CBZ");
        }
        PdfFileValidator.Report report = PdfFileValidator.validate(context, fileRef, expectedPages);
        return new ValidationReport(report.fileSize, report.pageCount);
    }

    private static final class ValidationReport {
        final long fileSize;
        final int pageCount;

        ValidationReport(long fileSize, int pageCount) {
            this.fileSize = fileSize;
            this.pageCount = pageCount;
        }
    }
}
