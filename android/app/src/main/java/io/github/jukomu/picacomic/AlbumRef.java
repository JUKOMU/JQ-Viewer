package io.github.jukomu.picacomic;

import java.util.Objects;

/** Stable identity of a Picacomic album. */
public class AlbumRef {

    public static final String PROVIDER = "picacomic";

    public final String provider;
    public final String albumId;

    public AlbumRef(String provider, String albumId) {
        requireProvider(provider);
        requireText(albumId, "albumId");
        this.provider = provider;
        this.albumId = albumId;
    }

    public static AlbumRef of(String albumId) {
        return new AlbumRef(PROVIDER, albumId);
    }

    public boolean sameIdentity(AlbumRef other) {
        return other != null
            && provider.equals(other.provider)
            && albumId.equals(other.albumId);
    }

    public String identityKey() {
        return provider + "\u0000" + albumId;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof AlbumRef)) return false;
        AlbumRef other = (AlbumRef) value;
        return provider.equals(other.provider) && albumId.equals(other.albumId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, albumId);
    }

    @Override
    public String toString() {
        return "AlbumRef{" + provider + ":" + albumId + "}";
    }

    static void requireProvider(String provider) {
        if (!PROVIDER.equals(provider)) {
            throw new IllegalArgumentException("provider must be picacomic");
        }
    }

    static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty() || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
