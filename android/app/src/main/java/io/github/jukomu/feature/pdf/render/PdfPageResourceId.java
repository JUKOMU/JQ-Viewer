package io.github.jukomu.feature.pdf.render;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * 生成 PDF 页面资源的稳定文件名。
 *
 * <p>输入字段按固定顺序以换行连接后计算 SHA-256。该值只用于缓存路径寻址，
 * 不承担鉴权、内容校验或协议版本标识职责。</p>
 */
public final class PdfPageResourceId {
    public static final int MIN_TARGET_WIDTH = 360;
    public static final int MAX_TARGET_WIDTH = 2400;
    public static final String RESOURCE_URL_PREFIX = "https://jqviewer.local/pdf-page/";

    private static final Pattern RESOURCE_ID_PATTERN =
        Pattern.compile("[0-9a-f]{64}");

    private PdfPageResourceId() {
    }

    public static int normalizeTargetWidth(int targetWidth) {
        return Math.max(MIN_TARGET_WIDTH, Math.min(MAX_TARGET_WIDTH, targetWidth));
    }

    public static String create(String fileRef, int page, int targetWidth,
                                long sourceLength, long sourceLastModified) {
        if (fileRef == null || fileRef.isEmpty()) {
            throw new IllegalArgumentException("fileRef is required");
        }
        if (page < 1) {
            throw new IllegalArgumentException("page must be 1-based");
        }

        String material = fileRef + "\n"
            + page + "\n"
            + normalizeTargetWidth(targetWidth) + "\n"
            + sourceLength + "\n"
            + sourceLastModified;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return toLowerHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available", error);
        }
    }

    public static boolean isValid(String resourceId) {
        return resourceId != null && RESOURCE_ID_PATTERN.matcher(resourceId).matches();
    }

    public static String resourceUrl(String resourceId) {
        if (!isValid(resourceId)) {
            throw new IllegalArgumentException("PDF 页面资源 id 无效");
        }
        return RESOURCE_URL_PREFIX + resourceId + ".png";
    }

    private static String toLowerHex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = digits[value >>> 4];
            result[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(result);
    }
}
