package io.github.jukomu.picacomic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Native-owned catalog option lists. */
public final class PicacomicCatalogOptions {
    public final List<PicacomicCatalogOption> categories;
    public final List<PicacomicCatalogOption> orderBy;

    public PicacomicCatalogOptions(List<PicacomicCatalogOption> categories,
                                   List<PicacomicCatalogOption> orderBy) {
        this.categories = immutable(categories);
        this.orderBy = immutable(orderBy);
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
