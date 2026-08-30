package io.github.jukomu.picacomic;

/** Opaque image reference safe for the WebView. */
public final class PicacomicImageRef {
    public final String imageKey;
    public final int pageIndex;
    public final String cacheUrl;

    public PicacomicImageRef(String imageKey, int pageIndex, String cacheUrl) {
        if (imageKey == null || imageKey.isEmpty()) {
            throw new IllegalArgumentException("imageKey is required");
        }
        if (pageIndex <= 0) {
            throw new IllegalArgumentException("pageIndex must be positive");
        }
        if (cacheUrl == null || cacheUrl.isEmpty()) {
            throw new IllegalArgumentException("cacheUrl is required");
        }
        this.imageKey = imageKey;
        this.pageIndex = pageIndex;
        this.cacheUrl = cacheUrl;
    }

    public String cacheKey() {
        return PicacomicCacheNamespace.cacheKey(this);
    }
}
