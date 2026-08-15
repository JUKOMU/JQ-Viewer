package io.github.jukomu.feature.download.validation;

import android.util.Log;
import io.github.jukomu.feature.download.api.DownloadTaskReader;
import io.github.jukomu.feature.download.storage.FileStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;

/**
 * Validates a downloaded chapter manifest without mutating its files.
 */
public final class ChapterManifestValidator {

    private static final String TAG = "ChapterManifestValidator";

    private ChapterManifestValidator() {
    }

    public static Report validate(FileStore fileStore, DownloadTaskReader downloadStore,
                                  String albumId, String chapterId) throws ValidationException {
        String taskId = albumId + "_" + chapterId;
        JSONObject meta;
        try {
            meta = fileStore.readMeta(albumId, chapterId);
        } catch (FileNotFoundException error) {
            throw failure("IMAGE_MANIFEST_MISSING", "章节清单 meta.json 缺失");
        } catch (org.json.JSONException error) {
            throw failure("IMAGE_MANIFEST_INVALID", "章节清单 meta.json 内容无效");
        } catch (IOException error) {
            Log.e(TAG, "读取章节清单失败: " + taskId, error);
            throw failure("IMAGE_MANIFEST_READ_FAILED", "章节清单读取失败");
        }

        JSONObject databaseTask = downloadStore.getTask(taskId);
        List<JSONObject> databaseImages = downloadStore.getImages(taskId);
        JSONArray metaImages = meta.optJSONArray("images");
        int totalPages = meta.optInt("totalPages", -1);
        if (databaseTask == null
            || !albumId.equals(requiredString(databaseTask, "albumId"))
            || !chapterId.equals(requiredString(databaseTask, "chapterId"))
            || totalPages <= 0
            || databaseTask.optInt("totalPages", -1) != totalPages
            || metaImages == null
            || metaImages.length() != totalPages
            || databaseImages.size() != totalPages) {
            throw failure("IMAGE_MANIFEST_MISMATCH", "下载记录、meta.json 和图片清单不一致");
        }

        Map<Integer, JSONObject> databaseBySortOrder = new HashMap<>();
        for (JSONObject image : databaseImages) {
            String filename = requiredString(image, "filename");
            String photoId = requiredString(image, "photoId");
            int sortOrder = image.optInt("sortOrder", Integer.MIN_VALUE);
            if (filename == null || photoId == null || sortOrder == Integer.MIN_VALUE
                || databaseBySortOrder.put(sortOrder, image) != null) {
                throw failure("IMAGE_MANIFEST_MISMATCH", "下载记录包含重复或无效图片清单");
            }
        }

        Set<String> expectedNames = new HashSet<>();
        Set<Integer> seenSortOrders = new HashSet<>();
        for (int index = 0; index < metaImages.length(); index++) {
            JSONObject image = metaImages.optJSONObject(index);
            if (image == null) {
                throw failure("IMAGE_MANIFEST_MISMATCH", "meta.json 图片清单包含无效项");
            }
            String filename = requiredString(image, "filename");
            String photoId = requiredString(image, "photoId");
            int sortOrder = image.optInt("sortOrder", Integer.MIN_VALUE);
            if (filename == null || photoId == null || sortOrder == Integer.MIN_VALUE
                || !seenSortOrders.add(sortOrder) || !expectedNames.add(filename)) {
                throw failure("IMAGE_MANIFEST_MISMATCH", "meta.json 图片清单包含重复或无效项");
            }
            JSONObject databaseImage = databaseBySortOrder.get(sortOrder);
            if (databaseImage == null
                || !filename.equals(requiredString(databaseImage, "filename"))
                || !photoId.equals(requiredString(databaseImage, "photoId"))) {
                throw failure("IMAGE_MANIFEST_MISMATCH", "下载记录与 meta.json 图片清单不一致");
            }
        }
        if (seenSortOrders.size() != databaseBySortOrder.size()) {
            throw failure("IMAGE_MANIFEST_MISMATCH", "下载记录与 meta.json 图片清单不一致");
        }

        File[] actualFiles = fileStore.listImageFiles(albumId, chapterId);
        Set<String> actualNames = new HashSet<>();
        if (actualFiles != null) {
            for (File actualFile : actualFiles) {
                if (actualFile != null && actualFile.isFile()) {
                    actualNames.add(actualFile.getName());
                }
            }
        }
        if (!actualNames.equals(expectedNames)) {
            Set<String> missing = new HashSet<>(expectedNames);
            missing.removeAll(actualNames);
            if (!missing.isEmpty()) {
                throw failure("IMAGE_MISSING", "缺少图片: " + missing.iterator().next());
            }
            throw failure("IMAGE_EXTRA", "章节目录包含未登记图片");
        }

        List<JSONObject> orderedImages = new ArrayList<>(databaseImages);
        orderedImages.sort(Comparator.comparingInt(image ->
            image.optInt("sortOrder", Integer.MAX_VALUE)));
        List<File> expectedFiles = new ArrayList<>(orderedImages.size());
        for (JSONObject image : orderedImages) {
            String filename = requiredString(image, "filename");
            File imageFile = fileStore.getExpectedImageFile(albumId, chapterId, filename);
            if (imageFile == null || !imageFile.isFile()) {
                throw failure("IMAGE_MISSING", "缺少图片: " + filename);
            }
            try {
                if (!ImageFileValidator.validateFull(imageFile)) {
                    throw failure("IMAGE_CORRUPT", "图片无法完整解码: " + filename,
                        filename, expectedFiles.size());
                }
            } catch (OutOfMemoryError error) {
                Log.e(TAG, "图片完整校验资源不足: " + filename, error);
                throw failure("IMAGE_VALIDATION_OOM", "图片校验资源不足，未生成 PDF",
                    filename, expectedFiles.size());
            }
            expectedFiles.add(imageFile);
        }

        return new Report(albumId, chapterId, totalPages, expectedFiles);
    }

    private static String requiredString(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) return null;
        String value = object.optString(key, null);
        return value == null || value.trim().isEmpty() ? null : value;
    }

    private static ValidationException failure(String code, String message) {
        return new ValidationException(code, message);
    }

    private static ValidationException failure(String code, String message,
                                               String offendingFilename, int verifiedPages) {
        return new ValidationException(code, message, offendingFilename, verifiedPages);
    }

    public static final class Report {
        public final String albumId;
        public final String chapterId;
        public final int totalPages;
        public final List<File> expectedFiles;

        private Report(String albumId, String chapterId, int totalPages,
                       List<File> expectedFiles) {
            this.albumId = albumId;
            this.chapterId = chapterId;
            this.totalPages = totalPages;
            this.expectedFiles = expectedFiles;
        }
    }

    public static final class ValidationException extends Exception {
        public final String code;
        public final String offendingFilename;
        public final int verifiedPages;

        private ValidationException(String code, String message) {
            this(code, message, null, 0);
        }

        private ValidationException(String code, String message,
                                    String offendingFilename, int verifiedPages) {
            super(message);
            this.code = code;
            this.offendingFilename = offendingFilename;
            this.verifiedPages = verifiedPages;
        }
    }
}
