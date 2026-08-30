package io.github.jukomu.picacomic;

import java.util.Objects;

/** Native-only image locator retained by the process registry. */
public final class PicacomicImageSource {

    public enum Kind {
        COVER,
        PAGE
    }

    public final Kind kind;
    public final String albumId;
    public final String chapterId;
    public final String contentRevision;
    public final int pageIndex;
    public final String originalName;
    public final String fileServer;
    public final String path;
    public final String imageUrl;

    private PicacomicImageSource(Kind kind, String albumId, String chapterId,
                                 String contentRevision, int pageIndex,
                                 String originalName, String fileServer, String path,
                                 String imageUrl) {
        if (kind == null) throw new IllegalArgumentException("kind is required");
        AlbumRef.requireText(albumId, "albumId");
        AlbumRef.requireText(contentRevision, "contentRevision");
        if (kind == Kind.PAGE) AlbumRef.requireText(chapterId, "chapterId");
        if (pageIndex <= 0) throw new IllegalArgumentException("pageIndex must be positive");
        this.kind = kind;
        this.albumId = albumId;
        this.chapterId = chapterId == null ? "" : chapterId;
        this.contentRevision = contentRevision;
        this.pageIndex = pageIndex;
        this.originalName = originalName;
        this.fileServer = fileServer;
        this.path = path;
        this.imageUrl = imageUrl;
    }

    public static PicacomicImageSource cover(String albumId, String contentRevision,
                                             String originalName, String fileServer,
                                             String path, String imageUrl) {
        return new PicacomicImageSource(Kind.COVER, albumId, "", contentRevision, 1,
            originalName, fileServer, path, imageUrl);
    }

    public static PicacomicImageSource page(String albumId, String chapterId,
                                            String contentRevision, int pageIndex,
                                            String originalName, String fileServer,
                                            String path, String imageUrl) {
        return new PicacomicImageSource(Kind.PAGE, albumId, chapterId, contentRevision,
            pageIndex, originalName, fileServer, path, imageUrl);
    }

    public String provider() {
        return AlbumRef.PROVIDER;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof PicacomicImageSource)) return false;
        PicacomicImageSource other = (PicacomicImageSource) value;
        return kind == other.kind
            && pageIndex == other.pageIndex
            && Objects.equals(albumId, other.albumId)
            && Objects.equals(chapterId, other.chapterId)
            && Objects.equals(contentRevision, other.contentRevision)
            && Objects.equals(originalName, other.originalName)
            && Objects.equals(fileServer, other.fileServer)
            && Objects.equals(path, other.path)
            && Objects.equals(imageUrl, other.imageUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, albumId, chapterId, contentRevision, pageIndex,
            originalName, fileServer, path, imageUrl);
    }
}
