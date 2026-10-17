package io.github.jukomu.desktop.feature.localfile.management;

import io.github.jukomu.desktop.feature.files.FileReferences;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 使用当前 FileRef 校验 PDF 的可读性、页树和文件元数据。
 */
public final class PdfFileValidator {
    private PdfFileValidator() {
    }

    public static Report validate(String fileRef, int expectedPages) throws ValidationException {
        final Path file;
        try {
            file = FileReferences.parseFile(fileRef);
        } catch (RuntimeException exception) {
            throw new ValidationException("PDF_PATH_INVALID", "PDF 文件引用无效", exception);
        }
        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new ValidationException("PDF_PATH_INVALID", "文件扩展名不是 PDF");
        }
        if (!Files.exists(file)) {
            throw new ValidationException("PDF_MISSING", "PDF 文件不存在");
        }
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new ValidationException("PDF_INACCESSIBLE", "没有权限读取 PDF");
        }

        try (PDDocument document = Loader.loadPDF(
            file.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            int pageCount = document.getNumberOfPages();
            if (pageCount <= 0) {
                throw new ValidationException("PDF_INVALID", "PDF 没有可读取页面");
            }
            if (expectedPages >= 0 && expectedPages != pageCount) {
                throw new ValidationException(
                    "PDF_PAGE_MISMATCH",
                    "PDF 页数不符，预期 " + expectedPages + " 页，实际 " + pageCount + " 页"
                );
            }
            for (int index = 0; index < pageCount; index++) {
                if (document.getPage(index) == null) {
                    throw new ValidationException("PDF_INVALID", "PDF 包含无法读取的页面");
                }
            }
            return new Report(Files.size(file), pageCount);
        } catch (ValidationException exception) {
            throw exception;
        } catch (SecurityException exception) {
            throw new ValidationException("PDF_INACCESSIBLE", "没有权限读取 PDF", exception);
        } catch (IOException exception) {
            if (!Files.exists(file)) {
                throw new ValidationException("PDF_MISSING", "PDF 文件不存在", exception);
            }
            throw new ValidationException("PDF_INVALID", "PDF 文件无法读取", exception);
        }
    }

    public record Report(long fileSize, int pageCount) {
    }

    public static final class ValidationException extends Exception {
        private final String code;

        private ValidationException(String code, String message) {
            super(message);
            this.code = code;
        }

        private ValidationException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
