package io.github.jukomu.desktop.feature.localfile.management;

import io.github.jukomu.desktop.feature.files.FileReferences;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Stream through the archive so verification checks image bytes, not just ZIP headers. */
public final class ZipFileValidator {
    private ZipFileValidator() {
    }

    public static Report validate(String fileRef, int expectedPages) throws ValidationException {
        Path file = FileReferences.parseFile(fileRef);
        int pages = 0;
        byte[] buffer = new byte[8192];
        try (InputStream source = Files.newInputStream(file);
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
            return new Report(Files.size(file), pages);
        } catch (ValidationException exception) {
            throw exception;
        } catch (NoSuchFileException exception) {
            throw new ValidationException("ZIP_MISSING", "ZIP 文件不存在", exception);
        } catch (AccessDeniedException exception) {
            throw new ValidationException("ZIP_INACCESSIBLE", "没有权限读取 ZIP", exception);
        } catch (IOException exception) {
            throw new ValidationException("ZIP_INVALID", "ZIP 文件无法读取", exception);
        }
    }

    public record Report(long fileSize, int pageCount) {
    }

    public static final class ValidationException extends Exception {
        private final String code;

        ValidationException(String code, String message) {
            super(message);
            this.code = code;
        }

        ValidationException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
