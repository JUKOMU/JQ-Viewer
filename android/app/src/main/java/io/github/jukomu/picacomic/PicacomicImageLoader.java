package io.github.jukomu.picacomic;

import io.github.jukomu.feature.cache.ImageCache;

/**
 * Loads registry-backed bytes into the shared in-memory cache.
 *
 * <p>The loader never gives a remote locator to the WebView and never uses
 * the JMComic FileStore.  Callers provide the bounded executor/cancellation
 * scope.</p>
 */
public final class PicacomicImageLoader {

    public static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;

    private final PicacomicRemoteClient client;
    private final PicacomicImageRegistry registry;
    private final ImageCache cache;

    public PicacomicImageLoader(PicacomicRemoteClient client,
                                PicacomicImageRegistry registry, ImageCache cache) {
        if (client == null || registry == null || cache == null) {
            throw new IllegalArgumentException("client, registry and cache are required");
        }
        this.client = client;
        this.registry = registry;
        this.cache = cache;
    }

    /**
     * Fetches one image.  Returns false for a cache hit and true when bytes
     * were fetched and inserted.
     */
    public boolean load(PicacomicImageRef image, PicacomicCancellationToken cancellation)
        throws PicacomicException {
        if (image == null) throw invalidArgument();
        if (cancellation == null) throw invalidArgument();

        String cacheKey;
        try {
            cacheKey = PicacomicCacheNamespace.cacheKey(image);
        } catch (IllegalArgumentException error) {
            throw PicacomicErrorMapper.map("image", error);
        }
        PicacomicImageSource source = registry.resolve(image.imageKey);
        if (source == null) {
            throw new PicacomicException(PicacomicErrorCode.INVALID_ARGUMENT, "image");
        }
        String expectedUrl = PicacomicCacheNamespace.cacheUrl(image);
        if (!expectedUrl.equals(image.cacheUrl)) {
            throw new PicacomicException(PicacomicErrorCode.INVALID_ARGUMENT, "image");
        }

        cancellation.throwIfCancelled();
        if (cache.has(cacheKey)) return false;

        byte[] data;
        try {
            data = client.fetchImageBytes(source, cancellation);
        } catch (Throwable error) {
            throw PicacomicErrorMapper.map("image", error);
        }
        cancellation.throwIfCancelled();
        String mimeType = detectMimeType(data);
        if (mimeType == null || data.length > MAX_IMAGE_BYTES) {
            throw new PicacomicException(PicacomicErrorCode.INVALID_RESPONSE, "image");
        }

        ImageCache.IncomingReservation reservation =
            cache.prepareForIncomingBytes(data.length);
        if (reservation == null) {
            throw new PicacomicException(PicacomicErrorCode.INVALID_RESPONSE, "image");
        }
        try {
            if (!cache.put(cacheKey, data, mimeType, reservation)) {
                throw new PicacomicException(PicacomicErrorCode.INVALID_RESPONSE, "image");
            }
        } finally {
            reservation.close();
        }
        cancellation.throwIfCancelled();
        return true;
    }

    public static String detectMimeType(byte[] data) {
        if (data == null || data.length < 3) return null;
        int b0 = data[0] & 0xff;
        int b1 = data[1] & 0xff;
        int b2 = data[2] & 0xff;
        if (b0 == 0xff && b1 == 0xd8) return "image/jpeg";
        if (data.length >= 8 && b0 == 0x89 && b1 == 0x50 && b2 == 0x4e
            && (data[3] & 0xff) == 0x47 && (data[4] & 0xff) == 0x0d
            && (data[5] & 0xff) == 0x0a && (data[6] & 0xff) == 0x1a
            && (data[7] & 0xff) == 0x0a) return "image/png";
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return "image/gif";
        if (data.length >= 12 && b0 == 0x52 && b1 == 0x49 && b2 == 0x46
            && (data[8] & 0xff) == 0x57 && (data[9] & 0xff) == 0x45
            && (data[10] & 0xff) == 0x42 && (data[11] & 0xff) == 0x50) {
            return "image/webp";
        }
        return null;
    }

    private static PicacomicException invalidArgument() {
        return new PicacomicException(PicacomicErrorCode.INVALID_ARGUMENT, "image");
    }
}
