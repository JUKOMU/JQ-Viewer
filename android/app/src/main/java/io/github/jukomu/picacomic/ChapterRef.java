package io.github.jukomu.picacomic;

import java.util.Objects;

/**
 * Chapter identity plus its current network locator.
 *
 * <p>{@link #chapterId} is the stable identity.  {@link #order} is only the
 * locator accepted by the provider's getPhoto(albumId, order) endpoint and
 * may change after the album is refreshed.</p>
 */
public final class ChapterRef extends AlbumRef {

    public final String chapterId;
    public final int order;

    public ChapterRef(String provider, String albumId, String chapterId, int order) {
        super(provider, albumId);
        requireText(chapterId, "chapterId");
        if (order <= 0) {
            throw new IllegalArgumentException("order must be positive");
        }
        this.chapterId = chapterId;
        this.order = order;
    }

    public static ChapterRef of(String albumId, String chapterId, int order) {
        return new ChapterRef(PROVIDER, albumId, chapterId, order);
    }

    /** Returns true when identity matches, intentionally ignoring order. */
    public boolean sameChapterIdentity(ChapterRef other) {
        return other != null
            && super.sameIdentity(other)
            && chapterId.equals(other.chapterId);
    }

    /** Strict response check used after getPhoto(albumId, order). */
    public boolean matchesResponse(String returnedAlbumId, String returnedChapterId,
                                   int returnedOrder) {
        return albumId.equals(returnedAlbumId)
            && chapterId.equals(returnedChapterId)
            && order == returnedOrder;
    }

    public ChapterRef withOrder(int newOrder) {
        return new ChapterRef(provider, albumId, chapterId, newOrder);
    }

    public String chapterIdentityKey() {
        return provider + "\u0000" + albumId + "\u0000" + chapterId;
    }

    public String locatorKey() {
        return albumId + "\u0000" + order;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof ChapterRef)) return false;
        ChapterRef other = (ChapterRef) value;
        return order == other.order
            && super.equals(other)
            && chapterId.equals(other.chapterId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), chapterId, order);
    }

    @Override
    public String toString() {
        return "ChapterRef{" + provider + ":" + albumId + ":" + chapterId
            + "@" + order + "}";
    }
}
