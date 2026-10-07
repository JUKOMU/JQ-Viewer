package io.github.jukomu.desktop.feature.pdf.render;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.feature.files.FileReferences;
import io.github.jukomu.desktop.feature.pdf.model.PdfInfoResponse;
import io.github.jukomu.desktop.feature.pdf.model.PdfRenderPageResponse;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 读取 PDF 页数，并为 pdf.js 失败场景生成受控 PNG 页面。
 */
public final class PdfDocumentService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PdfDocumentService.class);
    static final int MIN_TARGET_WIDTH = 360;
    static final int MAX_TARGET_WIDTH = 2400;
    static final long MAX_RENDER_PIXELS = 8_000_000L;
    private static final long SLOW_OPERATION_MS = 500L;

    private final PdfPageCache cache;

    public PdfDocumentService(PdfPageCache cache) {
        this.cache = cache;
    }

    public PdfInfoResponse getInfo(String fileRef) {
        Path file = requireReadablePdf(fileRef);
        String pdfKey = fileKey(file);
        long startedNanos = System.nanoTime();
        LOGGER.debug("pdf.open event=started pdfKey={}", pdfKey);
        try (PDDocument document = Loader.loadPDF(
            file.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            int pages = document.getNumberOfPages();
            if (pages <= 0) throw ApiException.invalidRequest("PDF 没有可读取页面");
            LOGGER.info("pdf.open event=completed pdfKey={} pages={} elapsedMs={}",
                pdfKey, pages, elapsed(startedNanos));
            return new PdfInfoResponse(pages);
        } catch (IOException exception) {
            LOGGER.error("pdf.open event=failed pdfKey={} elapsedMs={} errorClass={}",
                pdfKey, elapsed(startedNanos), exception.getClass().getSimpleName());
            throw loadFailure(file, "PDF 信息读取失败", exception);
        } catch (ApiException exception) {
            LOGGER.warn("pdf.open event=failed pdfKey={} elapsedMs={} errorCode={}",
                pdfKey, elapsed(startedNanos), exception.code());
            throw exception;
        }
    }

    public synchronized PdfRenderPageResponse renderPage(
        String fileRef,
        int pageNumber,
        int requestedWidth
    ) {
        if (pageNumber < 1) throw ApiException.invalidRequest("page必须从1开始");
        Path file = requireReadablePdf(fileRef);
        String pdfKey = fileKey(file);
        long startedNanos = System.nanoTime();
        int targetWidth = Math.max(MIN_TARGET_WIDTH, Math.min(MAX_TARGET_WIDTH, requestedWidth));
        try {
            long size = Files.size(file);
            long modified = Files.getLastModifiedTime(file).toMillis();
            String resourceId = resourceId(fileRef, pageNumber, targetWidth, size, modified);
            boolean cacheHit = cache.contains(resourceId);
            if (cacheHit) {
                LOGGER.debug("pdf.render event=cache-hit pdfKey={} page={} targetWidth={} elapsedMs={}",
                    pdfKey, pageNumber, targetWidth, elapsed(startedNanos));
            } else {
                render(file, pageNumber, targetWidth, resourceId);
            }
            long elapsedMs = elapsed(startedNanos);
            LOGGER.debug("pdf.render event=completed pdfKey={} page={} targetWidth={} cacheHit={} elapsedMs={}",
                pdfKey, pageNumber, targetWidth, cacheHit, elapsedMs);
            if (elapsedMs >= SLOW_OPERATION_MS) {
                LOGGER.warn("pdf.render event=slow pdfKey={} page={} targetWidth={} cacheHit={} elapsedMs={}",
                    pdfKey, pageNumber, targetWidth, cacheHit, elapsedMs);
            }
            return new PdfRenderPageResponse("/pdf-page/" + resourceId + ".png");
        } catch (IOException exception) {
            LOGGER.error("pdf.render event=failed pdfKey={} page={} targetWidth={} elapsedMs={} errorClass={}",
                pdfKey, pageNumber, targetWidth, elapsed(startedNanos), exception.getClass().getSimpleName());
            throw loadFailure(file, "PDF 页面渲染失败", exception);
        } catch (ApiException exception) {
            LOGGER.warn("pdf.render event=failed pdfKey={} page={} targetWidth={} elapsedMs={} errorCode={}",
                pdfKey, pageNumber, targetWidth, elapsed(startedNanos), exception.code());
            throw exception;
        }
    }

    private void render(Path file, int pageNumber, int targetWidth, String resourceId)
        throws IOException {
        try (PDDocument document = Loader.loadPDF(
            file.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            if (pageNumber > document.getNumberOfPages()) {
                throw ApiException.invalidRequest("page超出PDF页数");
            }
            PDPage page = document.getPage(pageNumber - 1);
            float sourceWidth = page.getCropBox().getWidth();
            float sourceHeight = page.getCropBox().getHeight();
            if (!(sourceWidth > 0F) || !(sourceHeight > 0F)) {
                throw ApiException.invalidRequest("PDF 页面尺寸无效");
            }
            double scale = targetWidth / (double) sourceWidth;
            long targetHeight = Math.max(1L, Math.round(sourceHeight * scale));
            long pixels = saturatedMultiply(targetWidth, targetHeight);
            if (pixels > MAX_RENDER_PIXELS) {
                scale *= Math.sqrt((double) MAX_RENDER_PIXELS / (double) pixels);
            }
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImage(pageNumber - 1, (float) scale, ImageType.RGB);
            try {
                if (saturatedMultiply(image.getWidth(), image.getHeight()) > MAX_RENDER_PIXELS) {
                    throw ApiException.invalidRequest("PDF 页面渲染尺寸过大");
                }
                long startedNanos = System.nanoTime();
                cache.write(resourceId, image);
                long elapsedMs = elapsed(startedNanos);
                if (elapsedMs >= SLOW_OPERATION_MS) {
                    LOGGER.warn("pdf.render-cache event=slow resourceId={} elapsedMs={}", resourceId, elapsedMs);
                }
            } finally {
                image.flush();
            }
        }
    }

    private static Path requireReadablePdf(String fileRef) {
        Path file = FileReferences.parseFile(fileRef);
        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw ApiException.invalidRequest("文件扩展名不是 PDF");
        }
        if (!Files.exists(file)) throw ApiException.notFound("PDF 文件不存在");
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw ApiException.permissionDenied("没有权限读取 PDF");
        }
        return file;
    }

    private static ApiException loadFailure(Path file, String message, IOException exception) {
        if (exception instanceof NoSuchFileException || !Files.exists(file)) {
            return ApiException.notFound("PDF 文件不存在");
        }
        if (exception instanceof AccessDeniedException || !Files.isReadable(file)) {
            return ApiException.permissionDenied("没有权限读取 PDF");
        }
        return new ApiException("internal", 500, message + ": " + exception.getMessage());
    }

    private static String resourceId(
        String fileRef,
        int page,
        int targetWidth,
        long sourceLength,
        long sourceModified
    ) {
        String material = fileRef + "\n" + page + "\n" + targetWidth + "\n"
            + sourceLength + "\n" + sourceModified;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static long saturatedMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) return 0L;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }

    private static long elapsed(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime() - startedNanos);
    }

    private static String fileKey(Path file) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(file.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
