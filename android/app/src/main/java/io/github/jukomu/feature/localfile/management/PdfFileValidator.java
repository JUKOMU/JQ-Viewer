package io.github.jukomu.feature.localfile.management;

import android.content.Context;
import android.graphics.pdf.PdfRenderer;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;

import io.github.jukomu.feature.localfile.data.LocalFileRef;
import io.github.jukomu.feature.localfile.data.LocalFileRefResolver;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * 打开PDF并验证所有预期页面均可访问。
 */
public final class PdfFileValidator {

    private PdfFileValidator() {
    }

    public static Report validate(Context context, String fileRef, int expectedPages)
        throws ValidationException {
        if (fileRef == null || fileRef.isEmpty()) {
            throw new ValidationException("PDF_PATH_INVALID", "PDF 文件引用为空");
        }
        try {
            LocalFileRef.Parsed parsed = LocalFileRef.parse(fileRef);
            if (parsed.kind != LocalFileRef.Kind.FILE) {
                throw new ValidationException("PDF_PATH_INVALID", "PDF 引用不是文件");
            }
        } catch (IllegalArgumentException error) {
            throw new ValidationException("PDF_PATH_INVALID", "PDF 文件引用无效", error);
        }
        Exception rendererFailure = null;
        // Android 7 的 PdfRenderer 构造失败后可能重复释放原生库，导致后续正常 PDF 校验崩溃。
        // 这些版本直接使用已有 PDFBox 校验器，避免损坏文件进入系统原生解析器。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try (ParcelFileDescriptor descriptor = openDescriptor(context, fileRef);
                 PdfRenderer renderer = new PdfRenderer(descriptor)) {
                int actualPages = validatePageCount(renderer.getPageCount(), expectedPages);
                for (int pageIndex = 0; pageIndex < actualPages; pageIndex++) {
                    try (PdfRenderer.Page ignored = renderer.openPage(pageIndex)) {
                        // 逐页打开可检查页面树，无需渲染完整位图。
                    }
                }
                long fileSize = descriptor.getStatSize();
                if (fileSize < 0) {
                    File file = LocalFileRefResolver.pathFile(fileRef);
                    if (file != null) fileSize = file.length();
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
                rendererFailure = error;
            }
        }

        try {
            return validateWithPdfBox(context, fileRef, expectedPages);
        } catch (ValidationException error) {
            if (rendererFailure != null) error.addSuppressed(rendererFailure);
            throw error;
        } catch (SecurityException error) {
            throw new ValidationException("PDF_INACCESSIBLE", "没有权限读取 PDF", error);
        } catch (FileNotFoundException error) {
            throw new ValidationException("PDF_MISSING", "PDF 文件不存在", error);
        } catch (OutOfMemoryError error) {
            throw new ValidationException("PDF_VALIDATION_OOM", "PDF 校验资源不足", error);
        } catch (Exception error) {
            if (rendererFailure != null) error.addSuppressed(rendererFailure);
            throw new ValidationException("PDF_INVALID", "PDF 文件无法读取", error);
        }
    }

    private static Report validateWithPdfBox(Context context, String fileRef, int expectedPages)
        throws Exception {
        PDFBoxResourceLoader.init(context.getApplicationContext());
        ParcelFileDescriptor descriptor = openDescriptor(context, fileRef);
        long fileSize = descriptor.getStatSize();
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             PDDocument document = PDDocument.load(input,
                 MemoryUsageSetting.setupTempFileOnly().setTempDir(context.getCacheDir()))) {
            int actualPages = validatePageCount(document.getNumberOfPages(), expectedPages);
            for (int pageIndex = 0; pageIndex < actualPages; pageIndex++) {
                if (document.getPage(pageIndex) == null) {
                    throw new ValidationException("PDF_INVALID", "PDF 包含无法读取的页面");
                }
            }
            if (fileSize < 0) {
                File file = LocalFileRefResolver.pathFile(fileRef);
                if (file != null) fileSize = file.length();
            }
            return new Report(Math.max(0L, fileSize), actualPages);
        }
    }

    private static int validatePageCount(int actualPages, int expectedPages)
        throws ValidationException {
        if (actualPages <= 0) {
            throw new ValidationException("PDF_INVALID", "PDF 没有可读取页面");
        }
        if (expectedPages >= 0 && actualPages != expectedPages) {
            throw new ValidationException("PDF_PAGE_MISMATCH",
                "PDF 页数不符，预期 " + expectedPages + " 页，实际 " + actualPages + " 页");
        }
        return actualPages;
    }

    private static ParcelFileDescriptor openDescriptor(Context context, String fileRef)
        throws IOException {
        return LocalFileRefResolver.openReadDescriptor(context, fileRef);
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
