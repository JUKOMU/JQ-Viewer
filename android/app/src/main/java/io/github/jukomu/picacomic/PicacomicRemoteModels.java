package io.github.jukomu.picacomic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Small provider-shaped records used by the injectable remote seam.
 *
 * <p>These are deliberately local records, not PicaComic library classes.
 * A future artifact adapter can translate its models here without leaking
 * that dependency into the bridge or the rest of the application.</p>
 */
public final class PicacomicRemoteModels {

    private PicacomicRemoteModels() {
    }

    public static final class User {
        public final String id;
        public final String username;
        public final String email;
        public final String avatar;

        public User(String id, String username, String email, String avatar) {
            this.id = id;
            this.username = username;
            this.email = email;
            this.avatar = avatar;
        }
    }

    /** Locator held only by native code; it is never serialized into a bridge DTO. */
    public static final class Image {
        public final String originalName;
        public final String fileServer;
        public final String path;
        public final String imageUrl;

        public Image(String originalName, String fileServer, String path, String imageUrl) {
            this.originalName = originalName;
            this.fileServer = fileServer;
            this.path = path;
            this.imageUrl = imageUrl;
        }

        public String locatorKey() {
            return value(originalName) + "\u0000" + value(fileServer) + "\u0000"
                + value(path) + "\u0000" + value(imageUrl);
        }
    }

    public static final class Photo {
        public final String albumId;
        public final String id;
        public final String title;
        public final String updatedAt;
        public final int order;
        public final boolean isSingleAlbum;
        public final List<Image> images;

        public Photo(String albumId, String id, String title, String updatedAt, int order,
                     boolean isSingleAlbum, List<Image> images) {
            this.albumId = albumId;
            this.id = id;
            this.title = title;
            this.updatedAt = updatedAt;
            this.order = order;
            this.isSingleAlbum = isSingleAlbum;
            this.images = immutable(images);
        }
    }

    public static final class Album {
        public final String id;
        public final String title;
        public final String author;
        public final String chineseTeam;
        public final List<String> categories;
        public final List<String> tags;
        public final Image thumb;
        public final String description;
        public final int pagesCount;
        public final int epsCount;
        public final boolean finished;
        public final String createdAt;
        public final String updatedAt;
        public final List<Photo> photos;

        public Album(String id, String title, String author, String chineseTeam,
                     List<String> categories, List<String> tags, Image thumb,
                     String description, int pagesCount, int epsCount, boolean finished,
                     String createdAt, String updatedAt, List<Photo> photos) {
            this.id = id;
            this.title = title;
            this.author = author;
            this.chineseTeam = chineseTeam;
            this.categories = immutable(categories);
            this.tags = immutable(tags);
            this.thumb = thumb;
            this.description = description;
            this.pagesCount = pagesCount;
            this.epsCount = epsCount;
            this.finished = finished;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.photos = immutable(photos);
        }

        public Album(String id, String title, Image thumb, List<Photo> photos) {
            this(id, title, null, null, null, null, thumb, null, 0, 0, false,
                null, null, photos);
        }
    }

    public static final class Page {
        public final int page;
        public final int pages;
        public final int total;
        public final List<Album> albums;

        public Page(int page, int pages, int total, List<Album> albums) {
            this.page = page;
            this.pages = pages;
            this.total = total;
            this.albums = immutable(albums);
        }
    }

    private static String value(String input) {
        return input == null ? "" : input;
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
