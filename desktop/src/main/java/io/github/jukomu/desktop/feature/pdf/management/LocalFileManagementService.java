package io.github.jukomu.desktop.feature.pdf.management;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.Request;
import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.feature.cbz.CbzDocumentService;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.files.FileReferences;
import io.github.jukomu.desktop.feature.files.FileService;
import io.github.jukomu.desktop.feature.pdf.data.LocalFileStore;
import io.github.jukomu.desktop.feature.pdf.data.StoredLocalFile;
import io.github.jukomu.desktop.feature.pdf.model.ImportLocalFileItemRequest;
import io.github.jukomu.desktop.feature.pdf.model.ImportLocalFileResultResponse;
import io.github.jukomu.desktop.feature.pdf.model.ImportLocalFilesResponse;
import io.github.jukomu.desktop.feature.pdf.model.LocalFileDatabaseResetResponse;
import io.github.jukomu.desktop.feature.pdf.model.LocalFileResponse;
import io.github.jukomu.desktop.feature.pdf.model.LocalFilesRefreshResponse;
import io.github.jukomu.desktop.feature.pdf.model.LocalFilesResponse;
import io.github.jukomu.desktop.feature.pdf.model.PdfInfoResponse;
import io.github.jukomu.desktop.feature.pdf.model.LocalFileManagementStateResponse;
import io.github.jukomu.desktop.feature.pdf.model.PdfRenderPageResponse;
import io.github.jukomu.desktop.feature.pdf.model.LocalFileStorageDeleteResponse;
import io.github.jukomu.desktop.feature.pdf.model.UpdateLocalEpisodeTypeResponse;
import io.github.jukomu.desktop.feature.pdf.render.PdfDocumentService;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Desktop 本地文件库的导入、校验、管理与阅读入口。 */
public final class LocalFileManagementService {
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
        int imported = 0;
        int skipped = 0;
        int duplicateCount = 0;
        int errorCount = 0;
        List<ImportLocalFileResultResponse> results = new ArrayList<>();
        for (ImportLocalFileItemRequest item : items) {
            try {
                String format = requireImportFormat(item.format());
                String fileRef = Request.requiredText(item.fileRef(), "fileRef");
                Path file = FileReferences.parseFile(fileRef);
                StoredLocalFile existing = store.findByRef(fileRef);
                if (existing != null) {
                    skipped++;
                    duplicateCount++;
                    results.add(result("already_managed", existing));
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
                } else {
                    skipped++;
                    duplicateCount++;
                    results.add(result("already_managed", inserted.file()));
                }
            } catch (ApiException | PdfFileValidator.ValidationException
                     | CbzDocumentService.CbzException exception) {
                skipped++;
                errorCount++;
            }
        }
        return new ImportLocalFilesResponse(
                imported, skipped, duplicateCount, errorCount, List.copyOf(results));
    }

    public LocalFilesResponse getFiles(
            List<String> formats,
            String sourceType,
            String availability,
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
                formats, sourceType, availability, folderId, query, cursor, limit);
        return new LocalFilesResponse(
                page.files().stream().map(LocalFileResponse::from).toList(), page.nextCursor());
    }

    public LocalFilesResponse getImportedLocalFiles() {
        return new LocalFilesResponse(
                store.listAll(LocalFileStore.SOURCE_IMPORTED).stream()
                        .map(LocalFileResponse::from).toList(), null);
    }

    public LocalFileResponse verifyFile(long id) {
        StoredLocalFile current = requireFile(id);
        long now = System.currentTimeMillis();
        StoredLocalFile updated;
        try {
            ValidationReport report = validate(
                    current.format(), current.fileRef(), current.pageCount());
            updated = store.updateVerification(
                    id, "available", "valid", null,
                    report.fileSize(), report.pageCount(), now);
        } catch (PdfFileValidator.ValidationException exception) {
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
        }
        if (updated == null) throw ApiException.notFound("本地文件记录不存在");
        return LocalFileResponse.from(updated);
    }

    public LocalFileResponse inspectFileForDeletion(long id) {
        return verifyFile(id);
    }

    public LocalFilesRefreshResponse refreshAvailability(List<Long> ids) {
        if (ids == null) throw ApiException.invalidRequest("ids必须是数组");
        List<LocalFileResponse> refreshed = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || id < 0L) continue;
            StoredLocalFile existing = store.find(id);
            if (existing == null) continue;
            refreshed.add(verifyFile(id));
        }
        return new LocalFilesRefreshResponse(List.copyOf(refreshed));
    }

    public SuccessResponse removeFromLibrary(long id) {
        return new SuccessResponse(store.remove(id));
    }

    public LocalFileStorageDeleteResponse deleteFile(long id) {
        StoredLocalFile record = requireFile(id);
        Path file = FileReferences.parseFile(record.fileRef());
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
            throw ApiException.permissionDenied("没有权限删除本地文件");
        } catch (IOException exception) {
            throw new ApiException("internal", 500,
                    "本地文件删除失败，文件库记录已保留: " + exception.getMessage());
        }
        if (!store.remove(id)) {
            throw new ApiException("internal", 500, "本地文件已处理，但文件库记录移除失败");
        }
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
        String normalized = format == null || format.isBlank()
                ? "pdf" : format.trim().toLowerCase();
        if (!"pdf".equals(normalized) && !"cbz".equals(normalized)) {
            throw ApiException.invalidRequest("导入仅支持 PDF 和 CBZ");
        }
        return normalized;
    }

    private ValidationReport validate(String format, String fileRef, int expectedPages)
            throws PdfFileValidator.ValidationException, CbzDocumentService.CbzException {
        if ("cbz".equals(format)) {
            CbzDocumentService.ValidationReport report = cbzDocuments.validate(
                    fileRef, expectedPages);
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
