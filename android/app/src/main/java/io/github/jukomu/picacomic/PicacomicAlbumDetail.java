package io.github.jukomu.picacomic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Album detail DTO; chapter order is a locator, not identity. */
public final class PicacomicAlbumDetail {
    public final AlbumRef ref;
    public final String title;
    public final List<String> authors;
    public final String translator;
    public final List<String> categories;
    public final List<String> tags;
    public final PicacomicImageRef cover;
    public final String description;
    public final int pagesCount;
    public final int epsCount;
    public final boolean finished;
    public final String createdAt;
    public final String updatedAt;
    public final List<PicacomicChapterSummary> chapters;

    public PicacomicAlbumDetail(AlbumRef ref, String title, List<String> authors,
                                String translator, List<String> categories, List<String> tags,
                                PicacomicImageRef cover, String description, int pagesCount,
                                int epsCount, boolean finished, String createdAt,
                                String updatedAt, List<PicacomicChapterSummary> chapters) {
        if (ref == null) throw new IllegalArgumentException("ref is required");
        this.ref = ref;
        this.title = title == null ? "" : title;
        this.authors = immutable(authors);
        this.translator = translator == null ? "" : translator;
        this.categories = immutable(categories);
        this.tags = immutable(tags);
        this.cover = cover;
        this.description = description == null ? "" : description;
        this.pagesCount = pagesCount;
        this.epsCount = epsCount;
        this.finished = finished;
        this.createdAt = createdAt == null ? "" : createdAt;
        this.updatedAt = updatedAt == null ? "" : updatedAt;
        this.chapters = immutable(chapters);
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
