package io.github.jukomu.feature.pdf.web;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;
import io.github.jukomu.feature.cache.ImageCache;
import io.github.jukomu.feature.cbz.CbzDocumentService;
import io.github.jukomu.feature.localfile.data.LocalFileRef;
import io.github.jukomu.feature.localfile.data.LocalFileRefResolver;
import io.github.jukomu.feature.pdf.render.PdfPageCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通过 PDF reader 使用的虚拟 WebView host 提供 FileRef 指向的 PDF，并统一附加 CORS 头。
 */
public class PdfServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(PdfServer.class);

    static final String VIRTUAL_HOST = ImageCache.VIRTUAL_HOST;
    static final String PDF_PATH_PREFIX = "/pdf/";
    static final String PDF_PAGE_PATH_PREFIX = "/pdf-page/";
    static final String CBZ_PAGE_PATH_PREFIX = "/cbz-page/";
    private static final Pattern PDF_PATH_PATTERN = Pattern.compile(
        "^" + PDF_PATH_PREFIX + "[A-Za-z0-9_-]+$");
    private static final Pattern PDF_PAGE_PATH_PATTERN = Pattern.compile(
        "^" + PDF_PAGE_PATH_PREFIX + "([0-9a-f]{64})\\.png$");
    private static final Pattern CBZ_PAGE_PATH_PATTERN = Pattern.compile(
        "^" + CBZ_PAGE_PATH_PREFIX + "([A-Za-z0-9_-]+)/([1-9][0-9]*)$");

    public static boolean isPdfUrl(String url) {
        Uri uri = parseUri(url);
        return uri != null
            && "https".equalsIgnoreCase(uri.getScheme())
            && VIRTUAL_HOST.equalsIgnoreCase(uri.getHost())
            && uri.getQuery() == null
            && uri.getFragment() == null
            && PDF_PATH_PATTERN.matcher(uri.getPath() == null ? "" : uri.getPath())
            .matches();
    }

    /**
     * 只匹配固定 host、scheme 和页面 PNG 路径，避免把 URL 当作任意文件路径。
     */
    public static boolean isPdfPageUrl(String url) {
        Uri uri = parseUri(url);
        return uri != null
            && "https".equalsIgnoreCase(uri.getScheme())
            && VIRTUAL_HOST.equals(uri.getHost())
            && uri.getQuery() == null
            && uri.getFragment() == null
            && PDF_PAGE_PATH_PATTERN.matcher(uri.getPath() == null ? "" : uri.getPath())
            .matches();
    }

    public static boolean isCbzPageUrl(String url) {
        Uri uri = parseUri(url);
        return uri != null
            && "https".equalsIgnoreCase(uri.getScheme())
            && VIRTUAL_HOST.equals(uri.getHost())
            && uri.getQuery() == null
            && uri.getFragment() == null
            && CBZ_PAGE_PATH_PATTERN.matcher(uri.getPath() == null ? "" : uri.getPath())
            .matches();
    }

    public static WebResourceResponse handleCbzPageRequest(String url, Context context) {
        if (!isCbzPageUrl(url)) return errorResponse(400, "Bad Request", null);
        Matcher matcher = CBZ_PAGE_PATH_PATTERN.matcher(parseUri(url).getPath());
        if (!matcher.matches()) return errorResponse(400, "Bad Request", null);
        try {
            String fileRef = new String(Base64.getUrlDecoder().decode(matcher.group(1)),
                StandardCharsets.UTF_8);
            int page = Integer.parseInt(matcher.group(2));
            CbzDocumentService.PageResource resource = CbzDocumentService.getInstance(context)
                .openPage(fileRef, page);
            Map<String, String> headers = corsHeaders(null);
            headers.put("Cache-Control", "private, max-age=3600");
            return new WebResourceResponse(
                resource.mimeType, null, 200, "OK", headers, resource.input);
        } catch (CbzDocumentService.CbzException error) {
            return errorResponse(error.status, "CBZ Error", error.code);
        } catch (Exception error) {
            return errorResponse(400, "Bad Request", "invalid-path");
        }
    }

    public static WebResourceResponse withCorsHeaders(WebResourceResponse response) {
        if (response == null) return null;
        response.setResponseHeaders(corsHeaders(null));
        return response;
    }

    public static WebResourceResponse optionsResponse() {
        return new WebResourceResponse(
            "text/plain",
            "UTF-8",
            200,
            "OK",
            corsHeaders(null),
            new ByteArrayInputStream(new byte[0])
        );
    }

    public static WebResourceResponse errorResponse(int statusCode, String reasonPhrase, String errorCode) {
        String bodyText = errorCode == null ? reasonPhrase : errorCode;
        byte[] body = bodyText.getBytes(StandardCharsets.UTF_8);
        return new WebResourceResponse(
            "text/plain",
            "UTF-8",
            statusCode,
            reasonPhrase,
            corsHeaders(errorCode),
            new ByteArrayInputStream(body)
        );
    }

    /**
     * 读取已生成的 PNG；该入口不创建 PdfRenderer，也不访问源 PDF。
     */
    public static WebResourceResponse handlePdfPageRequest(String url, Context context) {
        long startedNanos = System.nanoTime();
        if (!isPdfPageUrl(url)) {
            LOGGER.debug("PDF 页面资源请求拒绝: event=invalid_url, elapsedMs={}", elapsedMs(startedNanos));
            return errorResponse(400, "Bad Request", null);
        }

        Uri uri = parseUri(url);
        Matcher matcher = PDF_PAGE_PATH_PATTERN.matcher(uri.getPath());
        if (!matcher.matches()) {
            LOGGER.debug("PDF 页面资源请求拒绝: event=invalid_path, elapsedMs={}", elapsedMs(startedNanos));
            return errorResponse(400, "Bad Request", null);
        }

        String resourceId = matcher.group(1);
        try {
            FileInputStream stream = PdfPageCache.getInstance(context).openPage(resourceId);
            LOGGER.debug("PDF 页面资源读取完成: event=read, resourceId={}, elapsedMs={}",
                resourceId, elapsedMs(startedNanos));
            return withCorsHeaders(new WebResourceResponse(
                "image/png", null, 200, "OK", corsHeaders(null), stream));
        } catch (FileNotFoundException error) {
            LOGGER.debug("PDF 页面资源读取失败: event=missing, resourceId={}, elapsedMs={}",
                resourceId, elapsedMs(startedNanos));
            return errorResponse(404, "Not Found", null);
        } catch (Exception error) {
            LOGGER.warn("PDF 页面资源读取失败: event=failed, resourceId={}, errorClass={}, elapsedMs={}",
                resourceId, error.getClass().getSimpleName(), elapsedMs(startedNanos));
            return errorResponse(500, "Internal Server Error", null);
        }
    }

    private static Map<String, String> corsHeaders(String errorCode) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Range, Content-Type");
        if (errorCode != null) {
            headers.put("X-JQViewer-Pdf-Error", errorCode);
        }
        return headers;
    }

    private static Uri parseUri(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            return Uri.parse(url);
        } catch (RuntimeException error) {
            return null;
        }
    }

    public static WebResourceResponse handleRequest(String url, Context context) {
        long startedNanos = System.nanoTime();
        try {
            if (!isPdfUrl(url)) return errorResponse(400, "Bad Request", "invalid-url");
            Uri uri = parseUri(url);
            if (uri == null || uri.getPath() == null) {
                return errorResponse(400, "Bad Request", "invalid-url");
            }
            String encoded = uri.getPath().substring(PDF_PATH_PREFIX.length());
            if (encoded.isEmpty()) return errorResponse(400, "Bad Request", "invalid-path");

            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            String fileRef = new String(decoded, StandardCharsets.UTF_8);
            LocalFileRef.Parsed parsed = LocalFileRef.parse(fileRef);
            if (parsed.kind != LocalFileRef.Kind.FILE) {
                throw new IllegalArgumentException("需要文件引用");
            }
            InputStream stream = LocalFileRefResolver.openReadStream(context, fileRef);
            LOGGER.debug("PDF 源文件资源读取完成: event=read, provider={}, elapsedMs={}",
                parsed.provider, elapsedMs(startedNanos));
            return withCorsHeaders(new WebResourceResponse(
                "application/pdf", "binary", 200, "OK", corsHeaders(null), stream));
        } catch (SecurityException e) {
            LOGGER.warn("PDF 源文件资源读取失败: event=permission_denied, errorClass={}, elapsedMs={}",
                e.getClass().getSimpleName(), elapsedMs(startedNanos));
            return errorResponse(403, "Forbidden", "permission-denied");
        } catch (FileNotFoundException e) {
            LOGGER.debug("PDF 源文件资源读取失败: event=missing, elapsedMs={}", elapsedMs(startedNanos));
            return errorResponse(404, "Not Found", "file-missing");
        } catch (IllegalArgumentException e) {
            LOGGER.debug("PDF 源文件资源读取失败: event=invalid_path, elapsedMs={}", elapsedMs(startedNanos));
            return errorResponse(400, "Bad Request", "invalid-path");
        } catch (Exception e) {
            LOGGER.warn("PDF 源文件资源读取失败: event=failed, errorClass={}, elapsedMs={}",
                e.getClass().getSimpleName(), elapsedMs(startedNanos));
            return errorResponse(500, "Internal Server Error", "open-failed");
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
