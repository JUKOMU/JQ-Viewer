package io.github.jukomu.data;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class PdfServer {

    static final String VIRTUAL_HOST = ImageCache.VIRTUAL_HOST;
    static final String PDF_PATH_PREFIX = "/pdf/";

    public static boolean isPdfUrl(String url) {
        if (url == null) return false;
        int idx = url.indexOf(VIRTUAL_HOST);
        if (idx < 0) return false;
        String pathPart = url.substring(idx + VIRTUAL_HOST.length());
        return pathPart.startsWith(PDF_PATH_PREFIX);
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
        byte[] body = errorCode.getBytes(StandardCharsets.UTF_8);
        return new WebResourceResponse(
            "text/plain",
            "UTF-8",
            statusCode,
            reasonPhrase,
            corsHeaders(errorCode),
            new ByteArrayInputStream(body)
        );
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
