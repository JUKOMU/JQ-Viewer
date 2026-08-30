package io.github.jukomu.picacomic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 1-based catalog page DTO. */
public final class PicacomicCatalogPage {
    public final int currentPage;
    public final int totalPages;
    public final int totalItems;
    public final List<PicacomicCatalogItem> items;

    public PicacomicCatalogPage(int currentPage, int totalPages, int totalItems,
                                List<PicacomicCatalogItem> items) {
        if (currentPage <= 0 || totalPages < 0 || totalItems < 0
            || (totalPages == 0 && totalItems > 0)
            || (totalPages > 0 && currentPage > totalPages)) {
            throw new IllegalArgumentException("invalid catalog page");
        }
        this.currentPage = currentPage;
        this.totalPages = totalPages;
        this.totalItems = totalItems;
        if (items == null || items.isEmpty()) {
            this.items = Collections.emptyList();
        } else {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
        }
    }
}
