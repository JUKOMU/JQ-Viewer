package io.github.jukomu.data;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 图片内容校验的统一入口。该类只读取输入并返回结果，不修改文件或缓存。
 */
public final class ImageValidator {

    private static final int VALIDATION_MAX_DIMENSION = 256;

    public enum Status {
        VALID,
        MISSING,
        EMPTY,
        INCOMPLETE,
        UNDECODABLE;

        public boolean isValid() {
            return this == VALID;
        }
    }

    public static final class DownloadValidationResult {
        private final int expectedCount;
        private final int mappedCount;
        private final int validCount;
        private final int invalidContentCount;
        private final int missingCount;
        private final List<File> invalidFiles;

        DownloadValidationResult(int expectedCount, int mappedCount, int validCount,
                                 int invalidContentCount, int missingCount,
                                 List<File> invalidFiles) {
            this.expectedCount = expectedCount;
            this.mappedCount = mappedCount;
            this.validCount = validCount;
            this.invalidContentCount = invalidContentCount;
            this.missingCount = missingCount;
            this.invalidFiles = Collections.unmodifiableList(
                new ArrayList<>(invalidFiles));
        }

        public int getExpectedCount() {
            return expectedCount;
        }

        public int getValidCount() {
            return validCount;
        }

        public int getMappedCount() {
            return mappedCount;
        }

        public int getInvalidContentCount() {
            return invalidContentCount;
        }

        public int getMissingCount() {
            return missingCount;
        }

        public List<File> getInvalidFiles() {
            return invalidFiles;
        }

        public boolean isComplete() {
            return mappedCount == expectedCount
                && validCount == expectedCount
                && invalidContentCount == 0
                && missingCount == 0;
        }

        /** 防止失败任务因进度等于总页数而被 DownloadStore 重新归一化为完成。 */
        public int getFailedProgressCount() {
            return Math.min(validCount, Math.max(0, expectedCount - 1));
        }

        public String getFailureMessage(int cleanupFailureCount) {
            int failedCount = Math.max(0,
                expectedCount - Math.min(validCount, expectedCount));
            StringBuilder detail = new StringBuilder();
            if (invalidContentCount > 0) {
                detail.append("损坏 ").append(invalidContentCount).append(" 张");
            }
            if (missingCount > 0) {
                if (detail.length() > 0) detail.append("，");
                detail.append("缺失 ").append(missingCount).append(" 张");
            }
            if (cleanupFailureCount > 0) {
                if (detail.length() > 0) detail.append("，");
                detail.append("无法清理 ").append(cleanupFailureCount).append(" 张");
            }
            if (mappedCount != expectedCount) {
                if (detail.length() > 0) detail.append("，");
                detail.append("页映射 ").append(mappedCount).append("/")
                    .append(expectedCount);
            }
            if (failedCount == 0) {
                return "下载图片校验失败（" + detail + "）";
            }
            return failedCount + "/" + expectedCount
                + " 张图片下载失败（文件校验未通过：" + detail + "）";
        }
    }

    private ImageValidator() {
    }

    public static Status validate(byte[] data) {
        if (data == null) return Status.MISSING;
        if (data.length == 0) return Status.EMPTY;
        try {
            if (!hasCompleteImageContainer(data)) return Status.INCOMPLETE;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return Status.UNDECODABLE;
            }

            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = calculateValidationSampleSize(
                bounds.outWidth, bounds.outHeight);
            decode.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, decode);
            if (bitmap == null) return Status.UNDECODABLE;
            bitmap.recycle();
            return Status.VALID;
        } catch (RuntimeException | OutOfMemoryError e) {
            return Status.UNDECODABLE;
        }
    }

    public static Status validate(File imageFile) {
        if (imageFile == null || !imageFile.isFile()) return Status.MISSING;
        if (imageFile.length() <= 0L) return Status.EMPTY;
        try {
            if (!hasCompleteImageContainer(imageFile)) return Status.INCOMPLETE;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imageFile.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return Status.UNDECODABLE;
            }

            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = calculateValidationSampleSize(
                bounds.outWidth, bounds.outHeight);
            decode.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath(), decode);
            if (bitmap == null) return Status.UNDECODABLE;
            bitmap.recycle();
            return Status.VALID;
        } catch (IOException e) {
            return Status.INCOMPLETE;
        } catch (RuntimeException | OutOfMemoryError e) {
            return Status.UNDECODABLE;
        }
    }

    public static DownloadValidationResult validateDownloadedImages(
        File chapterDir, int expectedCount, List<String> filenames) {
        List<String> safeFilenames = filenames == null
            ? Collections.emptyList() : filenames;
        int normalizedExpectedCount = Math.max(0, expectedCount);
        int validCount = 0;
        int invalidContentCount = 0;
        int missingCount = Math.max(0,
            normalizedExpectedCount - safeFilenames.size());
        List<File> invalidFiles = new ArrayList<>();

        for (String filename : safeFilenames) {
            File imageFile;
            try {
                imageFile = resolveMappedImage(chapterDir, filename);
            } catch (IOException | RuntimeException e) {
                imageFile = null;
            }

            Status status = validate(imageFile);
            if (status == Status.VALID) {
                validCount++;
            } else if (status == Status.MISSING) {
                missingCount++;
            } else {
                invalidContentCount++;
                invalidFiles.add(imageFile);
            }
        }

        return new DownloadValidationResult(normalizedExpectedCount,
            safeFilenames.size(), validCount, invalidContentCount, missingCount,
            invalidFiles);
    }

    static File resolveMappedImage(File chapterDir, String filename) throws IOException {
        if (chapterDir == null || filename == null || filename.isEmpty()
            || new File(filename).isAbsolute()) {
            return null;
        }
        File canonicalChapterDir = chapterDir.getCanonicalFile();
        File canonicalImageFile = new File(canonicalChapterDir, filename).getCanonicalFile();
        String chapterPrefix = canonicalChapterDir.getPath() + File.separator;
        if (!canonicalImageFile.getPath().startsWith(chapterPrefix)) return null;
        return canonicalImageFile;
    }

    private static int calculateValidationSampleSize(int width, int height) {
        int sampleSize = 1;
        int maxDimension = Math.max(width, height);
        while (maxDimension / sampleSize > VALIDATION_MAX_DIMENSION
            && sampleSize <= Integer.MAX_VALUE / 2) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    private static boolean hasCompleteImageContainer(byte[] data) {
        if (isJpeg(data)) {
            return data.length >= 4
                && data[data.length - 2] == (byte) 0xff
                && data[data.length - 1] == (byte) 0xd9;
        }
        if (isPng(data)) {
            return hasPngEndMarker(data, data.length - 12);
        }
        if (isGif(data)) {
            return data.length >= 7 && data[data.length - 1] == 0x3b;
        }
        if (!isWebp(data)) return true;

        long riffSize = (data[4] & 0xffL)
            | ((data[5] & 0xffL) << 8)
            | ((data[6] & 0xffL) << 16)
            | ((data[7] & 0xffL) << 24);
        return riffSize + 8L == data.length;
    }

    private static boolean hasCompleteImageContainer(File imageFile) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(imageFile, "r")) {
            long length = input.length();
            if (length <= 0L) return false;

            byte[] header = new byte[(int) Math.min(12L, length)];
            input.readFully(header);
            if (isJpeg(header)) {
                if (length < 4L) return false;
                input.seek(length - 2L);
                return input.readUnsignedByte() == 0xff
                    && input.readUnsignedByte() == 0xd9;
            }
            if (isPng(header)) {
                if (length < 12L) return false;
                byte[] footer = new byte[12];
                input.seek(length - footer.length);
                input.readFully(footer);
                return hasPngEndMarker(footer, 0);
            }
            if (isGif(header)) {
                if (length < 7L) return false;
                input.seek(length - 1L);
                return input.readUnsignedByte() == 0x3b;
            }
            if (!isWebp(header)) return true;

            long riffSize = (header[4] & 0xffL)
                | ((header[5] & 0xffL) << 8)
                | ((header[6] & 0xffL) << 16)
                | ((header[7] & 0xffL) << 24);
            return riffSize + 8L == length;
        }
    }

    private static boolean isJpeg(byte[] data) {
        return data.length >= 2
            && data[0] == (byte) 0xff && data[1] == (byte) 0xd8;
    }

    private static boolean isPng(byte[] data) {
        return data.length >= 8
            && data[0] == (byte) 0x89 && data[1] == 0x50
            && data[2] == 0x4e && data[3] == 0x47
            && data[4] == 0x0d && data[5] == 0x0a
            && data[6] == 0x1a && data[7] == 0x0a;
    }

    private static boolean isGif(byte[] data) {
        return data.length >= 6
            && data[0] == 'G' && data[1] == 'I' && data[2] == 'F'
            && data[3] == '8' && (data[4] == '7' || data[4] == '9')
            && data[5] == 'a';
    }

    private static boolean isWebp(byte[] data) {
        return data.length >= 12
            && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
            && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }

    private static boolean hasPngEndMarker(byte[] data, int offset) {
        return offset >= 0 && data.length - offset >= 12
            && data[offset] == 0 && data[offset + 1] == 0
            && data[offset + 2] == 0 && data[offset + 3] == 0
            && data[offset + 4] == 'I' && data[offset + 5] == 'E'
            && data[offset + 6] == 'N' && data[offset + 7] == 'D'
            && data[offset + 8] == (byte) 0xae && data[offset + 9] == 0x42
            && data[offset + 10] == 0x60 && data[offset + 11] == (byte) 0x82;
    }
}
