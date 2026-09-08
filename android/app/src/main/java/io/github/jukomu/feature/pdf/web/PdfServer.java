package io.github.jukomu.feature.pdf.web;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;
import io.github.jukomu.feature.cache.ImageCache;
import io.github.jukomu.feature.pdf.render.PdfPageCache;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves PDF files through the virtual WebView host used by the PDF reader.
 * Paths may reference app files or persisted SAF content URIs and always receive CORS headers.
 */
public class PdfServer {

    static final String VIRTUAL_HOST = ImageCache.VIRTUAL_HOST;
    static final String PDF_PATH_PREFIX = "/pdf/";
    static final String PDF_PAGE_PATH_PREFIX = "/pdf-page/";
    private static final Pattern PDF_PAGE_PATH_PATTERN = Pattern.compile(
        "^" + PDF_PAGE_PATH_PREFIX + "([0-9a-f]{64})\\.png$");

    public static boolean isPdfUrl(String url) {
        if (url == null) return false;
        int idx = url.indexOf(VIRTUAL_HOST);
        if (idx < 0) return false;
        String pathPart = url.substring(idx + VIRTUAL_HOST.length());
        return pathPart.startsWith(PDF_PATH_PREFIX);
    }

    /** 只匹配固定 host、scheme 和页面 PNG 路径，避免把 URL 当作任意文件路径。 */
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

    /** 读取已生成的 PNG；该入口不创建 PdfRenderer，也不访问源 PDF。 */
    public static WebResourceResponse handlePdfPageRequest(String url, Context context) {
        if (!isPdfPageUrl(url)) {
            return errorResponse(400, "Bad Request", null);
        }

        Uri uri = parseUri(url);
        Matcher matcher = PDF_PAGE_PATH_PATTERN.matcher(uri.getPath());
        if (!matcher.matches()) {
            return errorResponse(400, "Bad Request", null);
        }

        String resourceId = matcher.group(1);
        try {
            FileInputStream stream = PdfPageCache.getInstance(context).openPage(resourceId);
            return withCorsHeaders(new WebResourceResponse("image/png", null, stream));
        } catch (FileNotFoundException error) {
            return errorResponse(404, "Not Found", null);
        } catch (Exception error) {
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
        try {
            int idx = url.indexOf(VIRTUAL_HOST);
            if (idx < 0) return errorResponse(400, "Bad Request", "invalid-url");
            String pathPart = url.substring(idx + VIRTUAL_HOST.length());
            String encoded = pathPart.substring(PDF_PATH_PREFIX.length());
            // Remove any query/fragment before decoding
            int qi = encoded.indexOf('?');
            if (qi >= 0) encoded = encoded.substring(0, qi);
            int fi = encoded.indexOf('#');
            if (fi >= 0) encoded = encoded.substring(0, fi);

            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            String filePath = new String(decoded, StandardCharsets.UTF_8);

            InputStream stream;
            if (filePath.startsWith("content://")) {
                ContentResolver resolver = context.getContentResolver();
                stream = resolver.openInputStream(Uri.parse(filePath));
            } else {
                File file = new File(filePath);
                if (!file.exists() || !file.isFile()) {
                    return errorResponse(404, "Not Found", "file-missing");
                }
                stream = new FileInputStream(file);
            }
            if (stream == null) return errorResponse(404, "Not Found", "file-missing");
            return withCorsHeaders(new WebResourceResponse("application/pdf", "binary", stream));
        } catch (SecurityException e) {
            return errorResponse(403, "Forbidden", "permission-denied");
        } catch (FileNotFoundException e) {
            return errorResponse(404, "Not Found", "file-missing");
        } catch (IllegalArgumentException e) {
            return errorResponse(400, "Bad Request", "invalid-path");
        } catch (Exception e) {
            return errorResponse(500, "Internal Server Error", "open-failed");
        }
    }
}
