package io.github.jukomu.desktop.feature.pdf.management;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.Request;
import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.files.FileReferences;
import io.github.jukomu.desktop.feature.files.FileService;
import io.github.jukomu.desktop.feature.pdf.data.PdfStore;
import io.github.jukomu.desktop.feature.pdf.data.StoredPdfFile;
import io.github.jukomu.desktop.feature.pdf.model.ImportPdfItemRequest;
import io.github.jukomu.desktop.feature.pdf.model.ImportPdfResultResponse;
import io.github.jukomu.desktop.feature.pdf.model.ImportPdfsResponse;
import io.github.jukomu.desktop.feature.pdf.model.PdfDatabaseResetResponse;
import io.github.jukomu.desktop.feature.pdf.model.PdfFileResponse;
import io.github.jukomu.desktop.feature.pdf.model.PdfFilesRefreshResponse;
import io.github.jukomu.desktop.feature.pdf.model.PdfFilesResponse;
import io.github.jukomu.desktop.feature.pdf.model.PdfInfoResponse;
import io.github.jukomu.desktop.feature.pdf.model.PdfManagementStateResponse;
import io.github.jukomu.desktop.feature.pdf.model.PdfRenderPageResponse;
import io.github.jukomu.desktop.feature.pdf.model.PdfStorageDeleteResponse;
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

/** Desktop PDF 文件库的导入、校验、管理与阅读入口。 */
public final class PdfManagementService {
    private static final Set<String> SOURCE_TYPES = Set.of("imported", "exported");
    private static final Set<String> AVAILABILITY = Set.of(
            "unknown", "available", "missing", "inaccessible", "invalid", "problem");

    private final PdfStore store;
    private final DownloadStore downloads;
    private final FileService files;
    private final PdfDocumentService documents;

    public PdfManagementService(
            PdfStore store,
            DownloadStore downloads,
            FileService files,
            PdfDocumentService documents
    ) {
        this.store = store;
        this.downloads = downloads;
        this.files = files;
        this.documents = documents;
    }

    public ImportPdfsResponse importPdfs(List<ImportPdfItemRequest> items) {
        if (items == null || items.isEmpty()) {
            throw ApiException.invalidRequest("items不能为空");
        }
        int imported = 0;
        int skipped = 0;
        int duplicateCount = 0;
        int errorCount = 0;
        List<ImportPdfResultResponse> results = new ArrayList<>();
        for (ImportPdfItemRequest item : items) {
            try {
                String fileRef = Request.requiredText(item.fileRef(), "fileRef");
                Path file = FileReferences.parseFile(fileRef);
                StoredPdfFile existing = store.findByRef(fileRef);
                if (existing != null) {
                    skipped++;
                    duplicateCount++;
                    results.add(result("already_managed", existing));
                    continue;
                }
                PdfFileValidator.Report report = PdfFileValidator.validate(fileRef, -1);
                PdfStore.InsertResult inserted = store.insertImported(
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
            } catch (ApiException | PdfFileValidator.ValidationException exception) {
                skipped++;
                errorCount++;
            }
        }
        return new ImportPdfsResponse(
                imported, skipped, duplicateCount, errorCount, List.copyOf(results));
    }

    public PdfFilesResponse getFiles(
            String sourceType,
            String availability,
            String folderId,
            String query,
            String cursor,
            int limit
    ) {
        if (sourceType != null && !sourceType.isBlank() && !SOURCE_TYPES.contains(sourceType)) {
            throw ApiException.invalidRequest("sourceType无效");
        }
        if (availability != null && !availability.isBlank()
                && !AVAILABILITY.contains(availability)) {
            throw ApiException.invalidRequest("availability无效");
        }
        PdfStore.Page page = store.list(sourceType, availability, folderId, query, cursor, limit);
        return new PdfFilesResponse(
                page.files().stream().map(PdfFileResponse::from).toList(), page.nextCursor());
    }

    public PdfFilesResponse getImportedPdfs() {
        return new PdfFilesResponse(
                store.listAll().stream().map(PdfFileResponse::from).toList(), null);
    }

    public PdfFileResponse verifyFile(long id) {
        StoredPdfFile current = requireFile(id);
        long now = System.currentTimeMillis();
        StoredPdfFile updated;
        try {
            PdfFileValidator.Report report = PdfFileValidator.validate(
                    current.fileRef(), current.pageCount());
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
        }
        if (updated == null) throw ApiException.notFound("PDF 文件记录不存在");
        return PdfFileResponse.from(updated);
    }

    public PdfFileResponse inspectFileForDeletion(long id) {
        return verifyFile(id);
    }

    public PdfFilesRefreshResponse refreshAvailability(List<Long> ids) {
        if (ids == null) throw ApiException.invalidRequest("ids必须是数组");
        List<PdfFileResponse> refreshed = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || id < 0L) continue;
            StoredPdfFile existing = store.find(id);
            if (existing == null) continue;
            refreshed.add(verifyFile(id));
        }
        return new PdfFilesRefreshResponse(List.copyOf(refreshed));
    }

    public SuccessResponse removeFromLibrary(long id) {
        return new SuccessResponse(store.remove(id));
    }

    public PdfStorageDeleteResponse deleteFile(long id) {
        StoredPdfFile record = requireFile(id);
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
            throw ApiException.permissionDenied("没有权限删除 PDF 文件");
        } catch (IOException exception) {
            throw new ApiException("internal", 500,
                    "PDF 文件删除失败，文件库记录已保留: " + exception.getMessage());
        }
        if (!store.remove(id)) {
            throw new ApiException("internal", 500, "PDF 文件已处理，但文件库记录移除失败");
        }
        return new PdfStorageDeleteResponse(
                result, record.id(), record.sourceType(), record.ownership(),
                record.fileRef(), record.displayPath(), record.fileName());
    }

    public SuccessResponse openPdf(String fileRef) {
        return files.openFile(fileRef);
    }

    public SuccessResponse openPdfFolder(String fileRef) {
        return files.openContainingFolder(fileRef);
    }

    public PdfInfoResponse getPdfInfo(String fileRef) {
        return documents.getInfo(fileRef);
    }

    public PdfRenderPageResponse renderPdfPage(String fileRef, int page, int targetWidth) {
        return documents.renderPage(fileRef, page, targetWidth);
    }

    public PdfManagementStateResponse managementState() {
        return new PdfManagementStateResponse("ready");
    }

    public PdfDatabaseResetResponse acknowledgeDatabaseReset() {
        return new PdfDatabaseResetResponse(false);
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

    private StoredPdfFile requireFile(long id) {
        StoredPdfFile file = store.find(id);
        if (file == null) throw ApiException.notFound("PDF 文件记录不存在");
        return file;
    }

    private static ImportPdfResultResponse result(String result, StoredPdfFile file) {
        return new ImportPdfResultResponse(
                result, file.fileRef(), file.displayPath(), file.fileName(), file.id());
    }
}
