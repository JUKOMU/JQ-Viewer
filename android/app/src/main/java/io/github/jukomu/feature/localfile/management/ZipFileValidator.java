package io.github.jukomu.feature.localfile.management;

import android.content.Context;
import androidx.documentfile.provider.DocumentFile;
import io.github.jukomu.feature.localfile.data.LocalFileRefResolver;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Stream through the archive so verification checks image bytes, not just ZIP headers. */
public final class ZipFileValidator {
    private ZipFileValidator() {
    }

    public static Report validate(Context context, String fileRef, int expectedPages)
        throws ValidationException {
        int pages = 0;
        byte[] buffer = new byte[8192];
        try (InputStream source = LocalFileRefResolver.openReadStream(context, fileRef);
             ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (!entry.isDirectory() && (name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".png") || name.endsWith(".webp") || name.endsWith(".gif"))) {
                    pages++;
                }
                while (zip.read(buffer) != -1) {
                    // Consume the entry to verify the ZIP stream and its checksum.
                }
                zip.closeEntry();
            }
            if (pages == 0) throw new ValidationException("ZIP_INVALID", "ZIP 中没有图片");
            if (expectedPages > 0 && pages != expectedPages) {
                throw new ValidationException("ZIP_PAGE_MISMATCH", "ZIP 页数与文件库记录不一致");
            }
            File path = LocalFileRefResolver.pathFile(fileRef);
            DocumentFile document = path == null ? LocalFileRefResolver.documentFile(context, fileRef) : null;
            long size = path != null ? path.length() : document == null ? 0 : document.length();
            return new Report(size, pages);
        } catch (ValidationException error) {
            throw error;
        } catch (FileNotFoundException error) {
            throw new ValidationException("ZIP_MISSING", "ZIP 文件不存在", error);
        } catch (SecurityException error) {
            throw new ValidationException("ZIP_INACCESSIBLE", "没有权限读取 ZIP", error);
        } catch (IOException error) {
            throw new ValidationException("ZIP_INVALID", "ZIP 文件无法读取", error);
        }
    }

    public static final class Report {
        public final long fileSize;
        public final int pageCount;

        Report(long fileSize, int pageCount) {
            this.fileSize = fileSize;
            this.pageCount = pageCount;
        }
    }

    public static final class ValidationException extends IOException {
        public final String code;

        ValidationException(String code, String message) {
            super(message);
            this.code = code;
        }

        ValidationException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }
    }
}
