package io.github.jukomu.desktop.feature.localfile.management;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.Request;
import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.feature.cbz.CbzDocumentService;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.files.FileReferences;
import io.github.jukomu.desktop.feature.files.FileService;
import io.github.jukomu.desktop.feature.localfile.data.LocalFileStore;
import io.github.jukomu.desktop.feature.localfile.data.StoredLocalFile;
import io.github.jukomu.desktop.feature.localfile.model.*;
import io.github.jukomu.desktop.feature.pdf.model.PdfInfoResponse;
import io.github.jukomu.desktop.feature.pdf.model.PdfRenderPageResponse;
import io.github.jukomu.desktop.feature.pdf.render.PdfDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Desktop 本地文件库的导入、校验、管理与阅读入口。
 */
public final class LocalFileManagementService {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalFileManagementService.class);
    private static final Set<String> FORMATS = Set.of("pdf", "cbz", "zip");
    private static final Set<String> SOURCE_TYPES = Set.of("imported", "exported");
    private static final Set<String> AVAILABILITY = Set.of(
        "unknown", "available", "missing", "inaccessible", "invalid", "problem");

    private final LocalFileStore store;
    private final DownloadStore downloads;
    private final FileService files;
    private final PdfDocumentService documents;
    private final CbzDocumentService cbzDocuments;

    public LocalFileManagementService(
        LocalFileStore store,
        DownloadStore downloads,
        FileService files,
        PdfDocumentService documents,
        CbzDocumentService cbzDocuments
    ) {
        this.store = store;
        this.downloads = downloads;
        this.files = files;
        this.documents = documents;
        this.cbzDocuments = cbzDocuments;
    }

    public ImportLocalFilesResponse importLocalFiles(List<ImportLocalFileItemRequest> items) {
        if (items == null || items.isEmpty()) {
            throw ApiException.invalidRequest("items不能为空");
        }
        String operationId = operationId();
        long startedNanos = System.nanoTime();
        LOGGER.info("local_file.import event=started operationId={} batchSize={} elapsedMs=0",
            clean(operationId), items.size());
        int imported = 0;
        int skipped = 0;
        int duplicateCount = 0;
        int errorCount = 0;
        List<ImportLocalFileResultResponse> results = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            ImportLocalFileItemRequest item = items.get(index);
            String format = normalizeFormat(item == null ? null : item.format());
            String fileName = fileName(item == null ? null : item.fileName(),
                item == null ? null : item.fileRef());
            try {
                format = requireImportFormat(item == null ? null : item.format());
                String fileRef = Request.requiredText(item.fileRef(), "fileRef");
                Path file = FileReferences.parseFile(fileRef);
                fileName = fileName(item.fileName(), fileRef);
                StoredLocalFile existing = store.findByRef(fileRef);
                if (existing != null) {
                    skipped++;
                    duplicateCount++;
                    results.add(result("already_managed", existing));
                    logItem("skipped", operationId, index, format, fileName, existing.fileSize(),
                        existing.pageCount(), "DUPLICATE", "already_managed", null, null);
                    continue;
                }
                ValidationReport report = validate(format, fileRef, -1);
                LocalFileStore.InsertResult inserted = store.insertImported(
                    format,
                    fileRef,
                    item.displayPath() == null || item.displayPath().isBlank()
                        ? file.toString() : item.displayPath(),
                    item.fileName() == null || item.fileName().isBlank()
                        ? file.getFileName().toString() : item.fileName(),
                    Request.requiredText(item.albumId(), "albumId"),
                    item.albumTitle(), item.coverUrl(), item.authors(), item.chapterId(),
                    item.chapterTitle(), Request.integer(item.chapterSortOrder(), 0),
                    item.isSingleEpisode(), item.folderId(), report.fileSize(),
                    report.pageCount(), System.currentTimeMillis()
                );
                if (inserted.inserted()) {
                    imported++;
                    results.add(result("imported", inserted.file()));
                    logItem("completed", operationId, index, format, fileName, report.fileSize(),
                        report.pageCount(), null, "imported", null, null);
                } else {
                    skipped++;
                    duplicateCount++;
                    results.add(result("already_managed", inserted.file()));
                    logItem("skipped", operationId, index, format, fileName,
                        inserted.file().fileSize(), inserted.file().pageCount(), "DUPLICATE",
                        "already_managed", null, null);
                }
            } catch (ApiException | PdfFileValidator.ValidationException
                     | CbzDocumentService.CbzException
                     | ZipFileValidator.ValidationException exception) {
                skipped++;
                errorCount++;
                logItem("skipped", operationId, index, format, fileName, -1, -1,
                    errorCode(exception), "validation_failed", null, exception.getMessage());
            } catch (RuntimeException exception) {
                logItem("failed", operationId, index, format, fileName, -1, -1,
                    errorCode(exception), "unexpected_failure", exception, null);
                logBatch("failed", operationId, items.size(), imported, skipped, duplicateCount,
                    errorCount, errorCode(exception), elapsed(startedNanos), exception);
                throw exception;
            }
        }
        ImportLocalFilesResponse response = new ImportLocalFilesResponse(
            imported, skipped, duplicateCount, errorCount, List.copyOf(results));
        logBatch("completed", operationId, items.size(), imported, skipped, duplicateCount,
            errorCount, null, elapsed(startedNanos), null);
        return response;
    }

    public LocalFilesResponse getFiles(
        List<String> formats,
        String sourceType,
        String availability,
        Long fileId,
        String albumId,
        String chapterId,
        String folderId,
        String query,
        String cursor,
        int limit
    ) {
        if (formats != null && (formats.isEmpty() || formats.stream().anyMatch(
            format -> format == null || !FORMATS.contains(format)))) {
            throw ApiException.invalidRequest("formats无效");
        }
        if (sourceType != null && !sourceType.isBlank() && !SOURCE_TYPES.contains(sourceType)) {
            throw ApiException.invalidRequest("sourceType无效");
        }
        if (availability != null && !availability.isBlank()
            && !AVAILABILITY.contains(availability)) {
            throw ApiException.invalidRequest("availability无效");
        }
        LocalFileStore.Page page = store.list(
            formats, sourceType, availability, fileId, albumId, chapterId,
            folderId, query, cursor, limit);
        return new LocalFilesResponse(
            page.files().stream().map(LocalFileResponse::from).toList(), page.nextCursor());
    }

    public LocalFilesResponse getImportedLocalFiles() {
        return new LocalFilesResponse(
            store.listAll(LocalFileStore.SOURCE_IMPORTED).stream()
                .map(LocalFileResponse::from).toList(), null);
    }

    public LocalFileResponse verifyFile(long id) {
        return verifyFile(id, operationId());
    }

    private LocalFileResponse verifyFile(long id, String operationId) {
        long startedNanos = System.nanoTime();
        StoredLocalFile current = requireFile(id);
        LOGGER.info("local_file.verify event=started operationId={} fileId={} format={} fileName={} expectedPages={} elapsedMs=0",
            clean(operationId), id, clean(current.format()), clean(current.fileName()), current.pageCount());
        long now = System.currentTimeMillis();
        StoredLocalFile updated;
        String validationErrorCode = null;
        try {
            ValidationReport report = validate(
                current.format(), current.fileRef(), current.pageCount());
            updated = store.updateVerification(
                id, "available", "valid", null,
                report.fileSize(), report.pageCount(), now);
        } catch (PdfFileValidator.ValidationException exception) {
            validationErrorCode = exception.code();
            String availability = "invalid";
            String verificationStatus = "corrupt";
            if ("PDF_MISSING".equals(exception.code())) {
                availability = "missing";
                verificationStatus = "unverified";
            } else if ("PDF_INACCESSIBLE".equals(exception.code())) {
                availability = "inaccessible";
                verificationStatus = "unverified";
            } else if ("PDF_PAGE_MISMATCH".equals(exception.code())) {
                verificationStatus = "page_mismatch";
            }
            updated = store.updateVerification(
                id, availability, verificationStatus,
                exception.code() + ": " + exception.getMessage(), null, null, now);
        } catch (CbzDocumentService.CbzException exception) {
            validationErrorCode = exception.code();
            String availability = "invalid";
            String verificationStatus = "corrupt";
            if ("CBZ_MISSING".equals(exception.code())) {
                availability = "missing";
                verificationStatus = "unverified";
            } else if ("CBZ_INACCESSIBLE".equals(exception.code())) {
                availability = "inaccessible";
                verificationStatus = "unverified";
            } else if ("CBZ_PAGE_MISMATCH".equals(exception.code())) {
                verificationStatus = "page_mismatch";
            }
            updated = store.updateVerification(
                id, availability, verificationStatus,
                exception.code() + ": " + exception.getMessage(), null, null, now);
        } catch (ZipFileValidator.ValidationException exception) {
            validationErrorCode = exception.code();
            String availability = "invalid";
            String verificationStatus = "corrupt";
            if ("ZIP_MISSING".equals(exception.code())
                || "ZIP_INACCESSIBLE".equals(exception.code())) {
                availability = "ZIP_MISSING".equals(exception.code()) ? "missing" : "inaccessible";
                verificationStatus = "unverified";
            } else if ("ZIP_PAGE_MISMATCH".equals(exception.code())) {
                verificationStatus = "page_mismatch";
            }
            updated = store.updateVerification(
                id, availability, verificationStatus,
                exception.code() + ": " + exception.getMessage(), null, null, now);
        } catch (RuntimeException exception) {
            LOGGER.error("local_file.verify event=failed operationId={} fileId={} format={} fileName={} errorCode={} elapsedMs={} errorClass={}",
                clean(operationId), id, clean(current.format()), clean(current.fileName()),
                clean(errorCode(exception)), elapsed(startedNanos), exception.getClass().getSimpleName());
            throw exception;
        }
        if (updated == null) throw ApiException.notFound("本地文件记录不存在");
        logVerify("completed", operationId, id, current, updated, validationErrorCode,
            elapsed(startedNanos), null);
        return LocalFileResponse.from(updated);
    }

    public LocalFileResponse inspectFileForDeletion(long id) {
        return verifyFile(id);
    }

    public LocalFilesRefreshResponse refreshAvailability(List<Long> ids) {
        if (ids == null) throw ApiException.invalidRequest("ids必须是数组");
        long startedNanos = System.nanoTime();
        String operationId = operationId();
        LOGGER.info("local_file.verify event=started operationId={} batchSize={} elapsedMs=0",
            clean(operationId), ids.size());
        List<LocalFileResponse> refreshed = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || id < 0L) continue;
            StoredLocalFile existing = store.find(id);
            if (existing == null) continue;
            refreshed.add(verifyFile(id, operationId));
        }
        LocalFilesRefreshResponse response = new LocalFilesRefreshResponse(List.copyOf(refreshed));
        LOGGER.info("local_file.verify event=completed operationId={} batchSize={} refreshedCount={} elapsedMs={}",
            clean(operationId), ids.size(), refreshed.size(), elapsed(startedNanos));
        return response;
    }

    public SuccessResponse removeFromLibrary(long id) {
        long startedNanos = System.nanoTime();
        boolean removed = store.remove(id);
        LOGGER.info("local_file.delete event=completed operationId={} fileId={} result={} ownership=library-only elapsedMs={}",
            clean(operationId()), id, removed ? "removed_from_library" : "missing_record",
            elapsed(startedNanos));
        return new SuccessResponse(removed);
    }

    public LocalFileStorageDeleteResponse deleteFile(long id) {
        long startedNanos = System.nanoTime();
        String operationId = operationId();
        StoredLocalFile record = requireFile(id);
        Path file = FileReferences.parseFile(record.fileRef());
        LOGGER.info("local_file.delete event=started operationId={} fileId={} format={} fileName={} elapsedMs=0",
            clean(operationId), id, clean(record.format()), clean(record.fileName()));
        String result;
        try {
            if (!Files.exists(file)) {
                result = "already_missing";
            } else {
                if (!Files.isRegularFile(file)) {
                    throw new IOException("定位符未指向普通文件");
                }
                Files.delete(file);
                result = "deleted";
            }
        } catch (NoSuchFileException exception) {
            result = "already_missing";
        } catch (AccessDeniedException | SecurityException exception) {
            LOGGER.warn("local_file.delete event=failed operationId={} fileId={} format={} fileName={} errorCode=permission-denied elapsedMs={}",
                clean(operationId), id, clean(record.format()), clean(record.fileName()), elapsed(startedNanos), exception);
            throw ApiException.permissionDenied("没有权限删除本地文件");
        } catch (IOException exception) {
            LOGGER.error("local_file.delete event=failed operationId={} fileId={} format={} fileName={} errorCode=DELETE_IO_FAILED elapsedMs={}",
                clean(operationId), id, clean(record.format()), clean(record.fileName()), elapsed(startedNanos), exception);
            throw new ApiException("internal", 500,
                "本地文件删除失败，文件库记录已保留: " + exception.getMessage());
        }
        if (!store.remove(id)) {
            LOGGER.error("local_file.delete event=failed operationId={} fileId={} format={} fileName={} errorCode=STORE_REMOVE_FAILED elapsedMs={}",
                clean(operationId), id, clean(record.format()), clean(record.fileName()), elapsed(startedNanos));
            throw new ApiException("internal", 500, "本地文件已处理，但文件库记录移除失败");
        }
        LOGGER.info("local_file.delete event=completed operationId={} fileId={} format={} fileName={} result={} elapsedMs={}",
            clean(operationId), id, clean(record.format()), clean(record.fileName()), clean(result),
            elapsed(startedNanos));
        return new LocalFileStorageDeleteResponse(
            result, record.id(), record.sourceType(), record.ownership(),
            record.fileRef(), record.displayPath(), record.fileName());
    }

    public SuccessResponse openLocalFile(String fileRef) {
        return files.openFile(fileRef);
    }

    public SuccessResponse openLocalFileFolder(String fileRef) {
        return files.openContainingFolder(fileRef);
    }

    public PdfInfoResponse getPdfInfo(String fileRef) {
        return documents.getInfo(fileRef);
    }

    public PdfRenderPageResponse renderPdfPage(String fileRef, int page, int targetWidth) {
        return documents.renderPage(fileRef, page, targetWidth);
    }

    public CbzDocumentService.Info getCbzInfo(String fileRef) {
        try {
            return cbzDocuments.getInfo(fileRef);
        } catch (CbzDocumentService.CbzException exception) {
            throw new ApiException(exception.code(), exception.status(), exception.getMessage());
        }
    }

    public LocalFileManagementStateResponse managementState() {
        return new LocalFileManagementStateResponse("ready");
    }

    public LocalFileDatabaseResetResponse acknowledgeDatabaseReset() {
        return new LocalFileDatabaseResetResponse(false);
    }

    public UpdateLocalEpisodeTypeResponse updateLocalEpisodeType(
        String albumId,
        boolean singleEpisode
    ) {
        return new UpdateLocalEpisodeTypeResponse(
            true,
            downloads.updateAlbumEpisodeType(albumId, singleEpisode),
            store.updateAlbumEpisodeType(albumId, singleEpisode)
        );
    }

    private StoredLocalFile requireFile(long id) {
        StoredLocalFile file = store.find(id);
        if (file == null) throw ApiException.notFound("本地文件记录不存在");
        return file;
    }

    private static ImportLocalFileResultResponse result(String result, StoredLocalFile file) {
        return new ImportLocalFileResultResponse(
            result, file.fileRef(), file.displayPath(), file.fileName(), file.id());
    }

    private static String requireImportFormat(String format) {
        String normalized = normalizeFormat(format);
        if (!"pdf".equals(normalized) && !"cbz".equals(normalized)) {
            throw ApiException.invalidRequest("导入仅支持 PDF 和 CBZ");
        }
        return normalized;
    }

    private static void logItem(String status, String operationId, int index, String format,
                                String fileName, long fileSize, int pageCount, String errorCode,
                                String result, Throwable failure, String message) {
        StringBuilder line = new StringBuilder("local_file event=item.").append(clean(status))
            .append(" operationId=").append(clean(operationId))
            .append(" index=").append(index)
            .append(" format=").append(clean(format))
            .append(" fileName=").append(clean(fileName))
            .append(" fileSize=").append(fileSize)
            .append(" pageCount=").append(pageCount)
            .append(" result=").append(clean(result));
        append(line, "errorCode", errorCode);
        if (failure == null) LOGGER.info(line.toString());
        else LOGGER.warn(line.append(" errorClass=").append(failure.getClass().getSimpleName()).toString());
    }

    private static void logBatch(String status, String operationId, int batchSize, int imported,
                                 int skipped, int duplicateCount, int errorCount, String errorCode,
                                 long elapsedMs, Throwable failure) {
        StringBuilder line = new StringBuilder("local_file event=")
            .append(clean("import." + status))
            .append(" operationId=").append(clean(operationId))
            .append(" batchSize=").append(batchSize)
            .append(" imported=").append(imported)
            .append(" skipped=").append(skipped)
            .append(" duplicateCount=").append(duplicateCount)
            .append(" errorCount=").append(errorCount)
            .append(" elapsedMs=").append(elapsedMs);
        append(line, "errorCode", errorCode);
        if (failure == null) LOGGER.info(line.toString());
        else LOGGER.error(line.append(" errorClass=").append(failure.getClass().getSimpleName()).toString());
    }

    private static void logVerify(String status, String operationId, long id, StoredLocalFile current,
                                  StoredLocalFile updated, String errorCode, long elapsedMs,
                                  Throwable failure) {
        String line = "local_file event=verify." + clean(status)
            + " operationId=" + clean(operationId)
            + " fileId=" + id
            + " format=" + clean(current.format())
            + " fileName=" + clean(current.fileName())
            + " fileSize=" + updated.fileSize()
            + " pageCount=" + updated.pageCount()
            + " availability=" + clean(updated.availability())
            + " verificationStatus=" + clean(updated.verificationStatus())
            + " elapsedMs=" + elapsedMs;
        if (errorCode != null) line += " errorCode=" + clean(errorCode);
        if (failure == null) LOGGER.info(line);
        else LOGGER.warn(line + " errorClass=" + failure.getClass().getSimpleName());
    }

    private static void append(StringBuilder line, String key, String value) {
        if (value != null && !value.isBlank()) line.append(' ').append(key).append('=').append(clean(value));
    }

    private static String normalizeFormat(String format) {
        return format == null || format.isBlank() ? "pdf" : format.trim().toLowerCase();
    }

    private static String fileName(String preferred, String reference) {
        if (preferred != null && !preferred.isBlank()) return preferred;
        if (reference == null || reference.isBlank()) return "-";
        int slash = Math.max(reference.lastIndexOf('/'), reference.lastIndexOf('\\'));
        return slash >= 0 && slash + 1 < reference.length()
            ? reference.substring(slash + 1) : reference;
    }

    private static String errorCode(Throwable exception) {
        if (exception instanceof ApiException apiException) return apiException.code();
        if (exception instanceof PdfFileValidator.ValidationException validation) return validation.code();
        if (exception instanceof CbzDocumentService.CbzException cbz) return cbz.code();
        if (exception instanceof ZipFileValidator.ValidationException zip) return zip.code();
        return exception.getClass().getSimpleName();
    }

    private static String operationId() {
        return UUID.randomUUID().toString();
    }

    private static long elapsed(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) return "-";
        String cleaned = value.replaceAll("[\\p{Cntrl}\\r\\n]+", " ").trim();
        return cleaned.substring(0, Math.min(256, cleaned.length()));
    }

    private ValidationReport validate(String format, String fileRef, int expectedPages)
        throws PdfFileValidator.ValidationException, CbzDocumentService.CbzException,
        ZipFileValidator.ValidationException {
        if ("cbz".equals(format)) {
            CbzDocumentService.ValidationReport report = cbzDocuments.validate(
                fileRef, expectedPages);
            return new ValidationReport(report.fileSize(), report.pageCount());
        }
        if ("zip".equals(format)) {
            ZipFileValidator.Report report = ZipFileValidator.validate(fileRef, expectedPages);
            return new ValidationReport(report.fileSize(), report.pageCount());
        }
        if (!"pdf".equals(format)) {
            throw ApiException.invalidRequest("内容校验仅支持 PDF 和 CBZ");
        }
        PdfFileValidator.Report report = PdfFileValidator.validate(fileRef, expectedPages);
        return new ValidationReport(report.fileSize(), report.pageCount());
    }

    private record ValidationReport(long fileSize, int pageCount) {
    }
}
