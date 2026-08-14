package io.github.jukomu.service;

import android.content.Context;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Opens a PDF and verifies that every expected page can be addressed. */
public final class PdfFileValidator {

    private PdfFileValidator() {
    }

    public static Report validate(Context context, String locator, int expectedPages)
            throws ValidationException {
        if (locator == null || locator.trim().isEmpty()) {
            throw new ValidationException("PDF_PATH_INVALID", "PDF 路径为空");
        }
        try (ParcelFileDescriptor descriptor = openDescriptor(context, locator);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            int actualPages = renderer.getPageCount();
            if (actualPages <= 0) {
                throw new ValidationException("PDF_INVALID", "PDF 没有可读取页面");
            }
            if (expectedPages >= 0 && actualPages != expectedPages) {
                throw new ValidationException("PDF_PAGE_MISMATCH",
                    "PDF 页数不符，预期 " + expectedPages + " 页，实际 " + actualPages + " 页");
            }
            for (int pageIndex = 0; pageIndex < actualPages; pageIndex++) {
                try (PdfRenderer.Page ignored = renderer.openPage(pageIndex)) {
                    // Opening every page detects a damaged page tree without rendering full bitmaps.
                }
            }
            long fileSize = descriptor.getStatSize();
            if (fileSize < 0 && !locator.startsWith("content://")) {
                fileSize = new File(locator).length();
            }
            return new Report(Math.max(0L, fileSize), actualPages);
        } catch (ValidationException error) {
            throw error;
        } catch (SecurityException error) {
            throw new ValidationException("PDF_INACCESSIBLE", "没有权限读取 PDF", error);
        } catch (FileNotFoundException error) {
            throw new ValidationException("PDF_MISSING", "PDF 文件不存在", error);
        } catch (OutOfMemoryError error) {
            throw new ValidationException("PDF_VALIDATION_OOM", "PDF 校验资源不足", error);
        } catch (Exception error) {
            throw new ValidationException("PDF_INVALID", "PDF 文件无法读取", error);
        }
    }

    private static ParcelFileDescriptor openDescriptor(Context context, String locator)
            throws IOException {
        if (locator.startsWith("content://")) {
            ParcelFileDescriptor descriptor = context.getContentResolver()
                .openFileDescriptor(Uri.parse(locator), "r");
            if (descriptor == null) throw new IOException("Content URI cannot be opened");
            return descriptor;
        }
        File file = new File(locator);
        if (!file.isFile()) throw new FileNotFoundException("PDF file does not exist");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    public static final class Report {
        public final long fileSize;
        public final int pageCount;

        private Report(long fileSize, int pageCount) {
            this.fileSize = fileSize;
            this.pageCount = pageCount;
        }
    }

    public static final class ValidationException extends Exception {
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
