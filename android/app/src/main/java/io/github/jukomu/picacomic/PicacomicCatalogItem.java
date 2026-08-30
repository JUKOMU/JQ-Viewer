package io.github.jukomu.picacomic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Search/category result DTO. */
public final class PicacomicCatalogItem {
    public final AlbumRef ref;
    public final String title;
    public final List<String> authors;
    public final String translator;
    public final PicacomicImageRef cover;
    public final int pagesCount;
    public final boolean finished;

    public PicacomicCatalogItem(AlbumRef ref, String title, List<String> authors,
                                String translator, PicacomicImageRef cover,
                                int pagesCount, boolean finished) {
        if (ref == null) throw new IllegalArgumentException("ref is required");
        this.ref = ref;
        this.title = title == null ? "" : title;
        this.authors = immutable(authors);
        this.translator = translator == null ? "" : translator;
        this.cover = cover;
        this.pagesCount = pagesCount;
        this.finished = finished;
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
