package io.github.jukomu.feature.localfile.management;

import android.content.Context;
import io.github.jukomu.feature.cbz.CbzDocumentService;
import io.github.jukomu.feature.localfile.LocalFileOperationException;
import io.github.jukomu.feature.localfile.data.LocalFileRef;
import io.github.jukomu.feature.localfile.data.LocalFileRefResolver;
import io.github.jukomu.feature.localfile.data.LocalFileStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

/**
 * 不拥有导出任务生命周期的文件库操作。
 */
public final class LocalFileManagementService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalFileManagementService.class);

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
        return importLocalFile(item, newOperationId());
    }

    public JSONObject importLocalFile(JSONObject item, String operationId) throws Exception {
        long startedAt = System.nanoTime();
        String fileRef = null;
        String format = "pdf";
        try {
            format = item.optString("format", "pdf").trim().toLowerCase();
            fileRef = item.getString("fileRef");
            if (!"pdf".equals(format) && !"cbz".equals(format)) {
                throw new IllegalArgumentException("导入仅支持 PDF 和 CBZ");
            }
            LocalFileRef.parse(fileRef);
            String displayPath = item.optString("displayPath", "");
            JSONObject existing = store.getFileByRef(fileRef);
            if (existing != null) {
                logImport(operationId, fileRef, format, "already_managed", null,
                    -1L, existing.optInt("pageCount", -1), startedAt, null);
                return outcome("already_managed", existing, null);
            }

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
            logImport(operationId, fileRef, format, "imported", id, report.fileSize,
                report.pageCount, startedAt, null);
            return result;
        } catch (Exception error) {
            logImport(operationId, fileRef, format, "failed", null, -1L, -1,
                startedAt, error);
            throw error;
        }
    }

    public JSONObject getFiles(List<String> formats, String sourceType, String availability,
                               Long fileId, String albumId, String chapterId, String folderId,
                               String query, String cursor, int limit) {
        String operationId = newOperationId();
        long startedAt = System.nanoTime();
        try {
            JSONObject result = store.getFilesPage(formats, sourceType, availability, fileId,
                albumId, chapterId, folderId, query, cursor, limit);
            LOGGER.info("event=local_file_query phase=complete operationId={} result=success "
                    + "count={} durationMs={}", operationId,
                result.optJSONArray("files") == null ? 0 : result.optJSONArray("files").length(),
                elapsedMs(startedAt));
            return result;
        } catch (RuntimeException error) {
            LOGGER.error("event=local_file_query phase=complete operationId={} result=failed "
                    + "errorCode={} durationMs={} errorClass={}", operationId, errorCode(error),
                elapsedMs(startedAt), error.getClass().getSimpleName());
            throw error;
        }
    }

    public JSONObject verifyFile(long id) throws Exception {
        return verifyFile(id, newOperationId());
    }

    public JSONObject verifyFile(long id, String operationId) throws Exception {
        long startedAt = System.nanoTime();
        JSONObject record = requireFile(id);
        String format = record.optString("format", "pdf");
        String fileRef = record.optString("fileRef", null);
        String oldAvailability = record.optString("availability", "");
        String oldVerificationStatus = record.optString("verificationStatus", "");
        try {
            ValidationReport report = validate(
                format, record.getString("fileRef"), record.optInt("pageCount", -1));
            JSONObject result = store.updateFileVerification(id, "available", "valid", null,
                report.fileSize, report.pageCount);
            logVerify(operationId, id, format, fileRef, oldAvailability,
                oldVerificationStatus, result, report.fileSize, report.pageCount, startedAt, null);
            return result;
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
            JSONObject result = store.updateFileVerification(id, availability, verificationStatus,
                error.code + ": " + error.getMessage(), -1L, -1);
            logVerify(operationId, id, format, fileRef, oldAvailability, oldVerificationStatus,
                result, -1L, -1, startedAt, error);
            return result;
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
            JSONObject result = store.updateFileVerification(id, availability, verificationStatus,
                error.code + ": " + error.getMessage(), -1L, -1);
            logVerify(operationId, id, format, fileRef, oldAvailability, oldVerificationStatus,
                result, -1L, -1, startedAt, error);
            return result;
        } catch (ZipFileValidator.ValidationException error) {
            String availability = "invalid";
            String verificationStatus = "corrupt";
            if ("ZIP_MISSING".equals(error.code) || "ZIP_INACCESSIBLE".equals(error.code)) {
                availability = "ZIP_MISSING".equals(error.code) ? "missing" : "inaccessible";
                verificationStatus = "unverified";
            } else if ("ZIP_PAGE_MISMATCH".equals(error.code)) {
                verificationStatus = "page_mismatch";
            }
            JSONObject result = store.updateFileVerification(id, availability, verificationStatus,
                error.code + ": " + error.getMessage(), -1L, -1);
            logVerify(operationId, id, format, fileRef, oldAvailability, oldVerificationStatus,
                result, -1L, -1, startedAt, error);
            return result;
        } catch (Exception error) {
            LOGGER.error("event=local_file_verify phase=complete operationId={} fileId={} "
                    + "format={} provider={} fileRefHash={} result=failed errorCode={} durationMs={} errorClass={}",
                operationId, id, format, provider(fileRef), refHash(fileRef), errorCode(error),
                elapsedMs(startedAt), error.getClass().getSimpleName());
            throw error;
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
        return refreshFileAvailability(ids, newOperationId());
    }

    public JSONArray refreshFileAvailability(JSONArray ids, String operationId) throws Exception {
        long startedAt = System.nanoTime();
        JSONArray files = new JSONArray();
        int skippedNotFound = 0;
        for (int index = 0; index < ids.length(); index++) {
            long id = ids.optLong(index, -1L);
            if (id < 0L) continue;
            try {
                JSONObject refreshed = verifyFile(id, operationId);
                if (refreshed != null) files.put(refreshed);
            } catch (LocalFileOperationException error) {
                if (LocalFileOperationException.NOT_FOUND.equals(error.code)) {
                    skippedNotFound++;
                    continue;
                }
                LOGGER.error("event=local_file_refresh_batch phase=complete operationId={} "
                        + "requestedCount={} verifiedCount={} skippedNotFound={} result=failed "
                        + "errorCode={} durationMs={} errorClass={}", operationId, ids.length(), files.length(),
                    skippedNotFound, errorCode(error), elapsedMs(startedAt),
                    error.getClass().getSimpleName());
                throw error;
            }
        }
        LOGGER.info("event=local_file_refresh_batch phase=complete operationId={} "
                + "requestedCount={} verifiedCount={} skippedNotFound={} result=success durationMs={}",
            operationId, ids.length(), files.length(), skippedNotFound, elapsedMs(startedAt));
        return files;
    }

    public JSONObject updateMetadata(long id, JSONObject metadata) {
        return store.updateFileMetadata(id, metadata);
    }

    public JSONObject deleteFile(long id) throws Exception {
        return deleteFile(id, newOperationId());
    }

    public JSONObject deleteFile(long id, String operationId) throws Exception {
        long startedAt = System.nanoTime();
        JSONObject record = requireFile(id);
        String fileRef = record.getString("fileRef");
        try {
            DeleteOutcome result = deleteFileRef(fileRef);
            if (!store.removeFileFromLibrary(id)) {
                throw new IOException("文件已处理，但文件库记录移除失败");
            }
            JSONObject output = outcome(result == DeleteOutcome.DELETED ? "deleted" : "already_missing",
                record, null);
            LOGGER.info("event=local_file_delete phase=complete operationId={} fileId={} "
                    + "provider={} fileRefHash={} result={} physicalDelete=true recordDelete=true "
                    + "durationMs={}", operationId, id, provider(fileRef), refHash(fileRef),
                output.optString("result"), elapsedMs(startedAt));
            return output;
        } catch (Exception error) {
            LOGGER.error("event=local_file_delete phase=complete operationId={} fileId={} "
                    + "provider={} fileRefHash={} result=failed physicalDelete=unknown "
                    + "recordDelete=unknown errorCode={} durationMs={} errorClass={}", operationId, id,
                provider(fileRef), refHash(fileRef), errorCode(error), elapsedMs(startedAt),
                error.getClass().getSimpleName());
            throw error;
        }
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

    private static void logImport(String operationId, String fileRef, String format, String result,
                                  Long fileId, long fileSize, int pageCount, long startedAt,
                                  Throwable error) {
        String message = "event=local_file_import_item phase=complete operationId=" + operationId
            + " format=" + format + " provider=" + provider(fileRef)
            + " fileRefHash=" + refHash(fileRef) + " result=" + result
            + (fileId == null ? "" : " fileId=" + fileId)
            + " fileSize=" + fileSize + " pageCount=" + pageCount
            + " durationMs=" + elapsedMs(startedAt)
            + (error == null ? "" : " errorCode=" + errorCode(error));
        if (error == null) LOGGER.info(message);
        else LOGGER.warn(message + " errorClass=" + error.getClass().getSimpleName());
    }

    private static void logVerify(String operationId, long id, String format, String fileRef,
                                  String oldAvailability, String oldVerificationStatus,
                                  JSONObject result, long fileSize, int pageCount, long startedAt,
                                  Throwable error) {
        String message = "event=local_file_verify phase=complete operationId=" + operationId
            + " fileId=" + id + " format=" + format + " provider=" + provider(fileRef)
            + " fileRefHash=" + refHash(fileRef) + " oldAvailability=" + oldAvailability
            + " newAvailability=" + result.optString("availability")
            + " oldVerificationStatus=" + oldVerificationStatus
            + " newVerificationStatus=" + result.optString("verificationStatus")
            + " fileSize=" + fileSize + " pageCount=" + pageCount
            + " result=" + result.optString("availability")
            + " durationMs=" + elapsedMs(startedAt)
            + (error == null ? "" : " errorCode=" + errorCode(error));
        if (error == null) LOGGER.info(message);
        else LOGGER.warn(message + " errorClass=" + error.getClass().getSimpleName());
    }

    private static String newOperationId() {
        return UUID.randomUUID().toString();
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static String provider(String fileRef) {
        if (fileRef == null) return "unknown";
        try {
            return LocalFileRef.parse(fileRef).provider.name().toLowerCase();
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }

    private static String refHash(String fileRef) {
        if (fileRef == null) return "unknown";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(fileRef.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int index = 0; index < 8; index++) {
                result.append(String.format("%02x", digest[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(fileRef.hashCode());
        }
    }

    private static String errorCode(Throwable error) {
        if (error instanceof PdfFileValidator.ValidationException) {
            return ((PdfFileValidator.ValidationException) error).code;
        }
        if (error instanceof ZipFileValidator.ValidationException) {
            return ((ZipFileValidator.ValidationException) error).code;
        }
        if (error instanceof CbzDocumentService.CbzException) {
            return ((CbzDocumentService.CbzException) error).code;
        }
        if (error instanceof LocalFileOperationException) {
            return ((LocalFileOperationException) error).code;
        }
        if (error instanceof IllegalArgumentException) return "INVALID_ARGUMENT";
        if (error instanceof IOException) return "IO_ERROR";
        return error == null ? "UNKNOWN" : error.getClass().getSimpleName();
    }
}
