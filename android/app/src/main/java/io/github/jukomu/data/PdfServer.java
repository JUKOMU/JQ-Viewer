package io.github.jukomu.data;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
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
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Range, Content-Type");
        response.setResponseHeaders(headers);
        return response;
    }

    public static WebResourceResponse handleRequest(String url, Context context) {
        try {
            int idx = url.indexOf(VIRTUAL_HOST);
            String pathPart = url.substring(idx + VIRTUAL_HOST.length());
            String encoded = pathPart.substring(PDF_PATH_PREFIX.length());
            // Remove any query/fragment before decoding
            int qi = encoded.indexOf('?');
            if (qi >= 0) encoded = encoded.substring(0, qi);
            int fi = encoded.indexOf('#');
            if (fi >= 0) encoded = encoded.substring(0, fi);

            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            String filePath = new String(decoded, "UTF-8");

            InputStream stream;
            if (filePath.startsWith("content://")) {
                ContentResolver resolver = context.getContentResolver();
                stream = resolver.openInputStream(Uri.parse(filePath));
            } else {
                File file = new File(filePath);
                if (!file.exists()) return null;
                stream = new FileInputStream(file);
            }
            if (stream == null) return null;
            return withCorsHeaders(new WebResourceResponse("application/pdf", "binary", stream));
        } catch (Exception e) {
            return null;
        }
    }
}
