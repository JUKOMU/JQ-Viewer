package io.github.jukomu.desktop.feature.download.validation;

import io.github.jukomu.desktop.feature.download.DownloadFiles;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.download.data.StoredDownloadPage;
import io.github.jukomu.desktop.feature.download.data.StoredDownloadTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 验证已下载章节的任务清单、图片清单和实际文件。
 */
public final class ChapterManifestValidator {

    private ChapterManifestValidator() {
    }

    public static Report validate(
        DownloadStore downloadStore,
        DownloadFiles files,
        String albumId,
        String chapterId
    ) throws ValidationException {
        Objects.requireNonNull(downloadStore, "downloadStore");
        Objects.requireNonNull(files, "files");
        synchronized (files) {
            return validateLocked(downloadStore, files, albumId, chapterId);
        }
    }

    private static Report validateLocked(
        DownloadStore downloadStore,
        DownloadFiles files,
        String albumId,
        String chapterId
    ) throws ValidationException {
        StoredDownloadTask task = downloadStore.findTask(albumId, chapterId);
        String taskId = albumId + "_" + chapterId;
        if (task == null
            || !taskId.equals(task.taskId())
            || !Objects.equals(albumId, task.albumId())
            || !Objects.equals(chapterId, task.chapterId())
            || task.relativeDirectory() == null
            || task.relativeDirectory().isBlank()
            || task.totalPages() <= 0) {
            throw failure("IMAGE_MANIFEST_MISMATCH", "下载任务和图片清单不一致");
        }
        try {
            if (!files.relativeDirectory(albumId, chapterId).equals(task.relativeDirectory())) {
                throw failure("IMAGE_MANIFEST_MISMATCH", "章节目录与下载任务不一致");
            }
        } catch (IllegalArgumentException exception) {
            throw failure("IMAGE_MANIFEST_MISMATCH", "章节目录与下载任务不一致");
        }

        List<StoredDownloadPage> pages = downloadStore.pages(task.taskId());
        if (pages.size() != task.totalPages()) {
            throw failure("IMAGE_MANIFEST_MISMATCH", "下载任务和图片清单不一致");
        }

        Map<Integer, StoredDownloadPage> pagesBySortOrder = new HashMap<>();
        Set<String> expectedNames = new HashSet<>();
        for (StoredDownloadPage page : pages) {
            if (page == null
                || page.sortOrder() <= 0
                || page.filename() == null
                || page.filename().isBlank()
                || page.photoId() == null
                || page.photoId().isBlank()
                || pagesBySortOrder.put(page.sortOrder(), page) != null
                || !expectedNames.add(page.filename())) {
                throw failure("IMAGE_MANIFEST_MISMATCH", "下载图片清单包含重复或无效项");
            }
            try {
                String expectedPath = files.relativeImagePath(
                    task.relativeDirectory(), page.filename());
                if (!samePath(expectedPath, page.relativePath())) {
                    throw failure("IMAGE_MANIFEST_MISMATCH", "下载图片路径与清单不一致");
                }
            } catch (IllegalArgumentException exception) {
                throw failure("IMAGE_MANIFEST_MISMATCH", "下载图片清单包含无效文件名");
            }
        }

        Set<String> actualNames = actualNames(files, task.relativeDirectory());
        if (!actualNames.equals(expectedNames)) {
            Set<String> missing = new LinkedHashSet<>(expectedNames);
            missing.removeAll(actualNames);
            if (!missing.isEmpty()) {
                throw failure("IMAGE_MISSING", "缺少图片: " + missing.iterator().next());
            }
            Set<String> extra = new LinkedHashSet<>(actualNames);
            extra.removeAll(expectedNames);
            throw failure("IMAGE_EXTRA", "章节目录包含未登记图片: " + extra.iterator().next());
        }

        List<StoredDownloadPage> orderedPages = new ArrayList<>(pages);
        orderedPages.sort(Comparator.comparingInt(StoredDownloadPage::sortOrder));
        List<Path> expectedFiles = new ArrayList<>(orderedPages.size());
        long totalSize = 0L;
        int firstSortOrder = orderedPages.get(0).sortOrder();
        int verifiedPages = 0;
        for (StoredDownloadPage page : orderedPages) {
            Path imageFile;
            try {
                imageFile = files.resolvePage(page);
            } catch (RuntimeException exception) {
                throw failure("IMAGE_MANIFEST_MISMATCH", "下载图片路径无效: " + page.filename());
            }
            long size;
            try {
                if (!Files.isRegularFile(imageFile)) {
                    throw failure("IMAGE_MISSING", "缺少图片: " + page.filename(),
                        page.filename(), verifiedPages);
                }
                size = Files.size(imageFile);
            } catch (ValidationException exception) {
                throw exception;
            } catch (IOException | RuntimeException exception) {
                throw failure("IMAGE_READ_FAILED", "图片读取失败: " + page.filename(),
                    page.filename(), verifiedPages);
            }
            if (size <= 0L) {
                throw failure("IMAGE_MISSING", "图片文件为空: " + page.filename(),
                    page.filename(), verifiedPages);
            }
            try {
                if (!ImageFileValidator.validateFull(imageFile)) {
                    throw failure("IMAGE_CORRUPT", "图片无法完整解码: " + page.filename(),
                        page.filename(), verifiedPages);
                }
            } catch (OutOfMemoryError error) {
                throw failure("IMAGE_VALIDATION_OOM", "图片校验资源不足，未完成章节校验",
                    page.filename(), verifiedPages);
            }
            expectedFiles.add(imageFile);
            totalSize += size;
            verifiedPages++;
        }

        return new Report(albumId, chapterId, task.totalPages(), expectedFiles,
            totalSize, firstSortOrder);
    }

    private static Set<String> actualNames(DownloadFiles files, String relativeDirectory)
        throws ValidationException {
        Path directory;
        try {
            directory = files.chapterDirectory(relativeDirectory);
        } catch (RuntimeException exception) {
            throw failure("IMAGE_MANIFEST_READ_FAILED", "章节目录无效");
        }
        if (!Files.isDirectory(directory)) return Set.of();

        Set<String> names = new HashSet<>();
        try (Stream<Path> children = Files.list(directory)) {
            children.filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> !name.endsWith(".tmp"))
                .forEach(names::add);
            return names;
        } catch (IOException | RuntimeException exception) {
            throw failure("IMAGE_MANIFEST_READ_FAILED", "章节目录读取失败");
        }
    }

    private static boolean samePath(String expected, String actual) {
        return actual != null && expected.equals(actual.replace('\\', '/'));
    }

    private static ValidationException failure(String code, String message) {
        return new ValidationException(code, message, null, 0);
    }

    private static ValidationException failure(
        String code,
        String message,
        String offendingFilename,
        int verifiedPages
    ) {
        return new ValidationException(code, message, offendingFilename, verifiedPages);
    }

    public record Report(
        String albumId,
        String chapterId,
        int totalPages,
        List<Path> expectedFiles,
        long totalSize,
        int firstSortOrder
    ) {
        public Report {
            expectedFiles = List.copyOf(expectedFiles);
        }
    }

    public static final class ValidationException extends Exception {
        private final String code;
        private final String offendingFilename;
        private final int verifiedPages;

        private ValidationException(
            String code,
            String message,
            String offendingFilename,
            int verifiedPages
        ) {
            super(message);
            this.code = code;
            this.offendingFilename = offendingFilename;
            this.verifiedPages = verifiedPages;
        }

        public String code() {
            return code;
        }

        public String offendingFilename() {
            return offendingFilename;
        }

        public int verifiedPages() {
            return verifiedPages;
        }
    }
}
