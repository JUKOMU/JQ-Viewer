package io.github.jukomu.picacomic;

import io.github.jukomu.feature.cache.ImageCache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Opaque, provider-owned keys for the shared byte cache.
 *
 * <p>Every key starts with {@value #KEY_PREFIX}.  The prefix is both a
 * collision boundary with JMComic's legacy {@code photoId/order} keys and a
 * precise cleanup boundary for logout.</p>
 */
public final class PicacomicCacheNamespace {

    public static final String KEY_PREFIX = "pica-";
    public static final String VIRTUAL_TYPE = "picacomic";
    public static final String URL_PREFIX = "https://" + ImageCache.VIRTUAL_HOST
        + "/" + VIRTUAL_TYPE + "/";

    private PicacomicCacheNamespace() {
    }

    public static String contentRevision(String updatedAt,
                                         List<PicacomicRemoteModels.Image> images) {
        List<String> parts = new ArrayList<>();
        parts.add(value(updatedAt));
        if (images != null) {
            for (PicacomicRemoteModels.Image image : images) {
                if (image == null) {
                    throw new IllegalArgumentException("image must not be null");
                }
                parts.add(value(image.originalName));
                parts.add(value(image.fileServer));
                parts.add(value(image.path));
                parts.add(value(image.imageUrl));
            }
        }
        return digest("revision", parts.toArray(new String[0]));
    }

    public static String coverKey(String albumId, String coverRevision) {
        return digest("cover", albumId, coverRevision);
    }

    public static String imageKey(String albumId, String chapterId,
                                  String contentRevision, int pageIndex) {
        if (pageIndex <= 0) throw new IllegalArgumentException("pageIndex must be positive");
        return digest("chapter", albumId, chapterId, contentRevision,
            Integer.toString(pageIndex));
    }

    public static String imageKey(PicacomicImageSource source) {
        if (source == null) throw new IllegalArgumentException("source is required");
        if (source.kind == PicacomicImageSource.Kind.COVER) {
            return coverKey(source.albumId, source.contentRevision);
        }
        return imageKey(source.albumId, source.chapterId, source.contentRevision,
            source.pageIndex);
    }

    public static String cacheKey(PicacomicImageRef image) {
        if (image == null) throw new IllegalArgumentException("image is required");
        if (!ownsKey(image.imageKey)) {
            throw new IllegalArgumentException("imageKey is outside Picacomic namespace");
        }
        return image.imageKey + "/" + image.pageIndex;
    }

    public static String cacheUrl(PicacomicImageRef image) {
        if (image == null) throw new IllegalArgumentException("image is required");
        if (!ownsKey(image.imageKey)) {
            throw new IllegalArgumentException("imageKey is outside Picacomic namespace");
        }
        return URL_PREFIX + image.imageKey + "/" + image.pageIndex;
    }

    public static PicacomicImageRef imageRef(PicacomicImageSource source) {
        String imageKey = imageKey(source);
        PicacomicImageRef ref = new PicacomicImageRef(imageKey, source.pageIndex,
            URL_PREFIX + imageKey + "/" + source.pageIndex);
        return ref;
    }

    public static boolean ownsKey(String key) {
        return key != null && key.startsWith(KEY_PREFIX) && key.length() > KEY_PREFIX.length();
    }

    public static boolean isVirtualPath(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    /** Remove only Pica entries; legacy JM cache entries remain untouched. */
    public static void clear(ImageCache cache) {
        if (cache != null) cache.clearByPrefix(KEY_PREFIX);
    }

    private static String digest(String kind, String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updatePart(digest, "picacomic");
            updatePart(digest, kind);
            for (String value : values) updatePart(digest, value(value));
            return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void updatePart(MessageDigest digest, String value) {
        String part = value(value);
        digest.update(part.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String value(String value) {
        if (value == null) return "";
        if (value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("cache identity contains invalid separator");
        }
        return value;
    }
}
