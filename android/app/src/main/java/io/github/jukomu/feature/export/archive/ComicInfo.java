package io.github.jukomu.feature.export.archive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** ComicInfo v2.0 中当前导出和导入需要的标准字段。 */
public final class ComicInfo {
    public final String title;
    public final String series;
    public final String number;
    public final String writer;
    public final String web;
    public final int pageCount;
    public final String manga;
    public final Integer volume;
    public final Integer count;
    public final List<Page> pages;

    public ComicInfo(String title, String series, String number, String writer, String web,
                     int pageCount, String manga, Integer volume, Integer count, List<Page> pages) {
        this.title = title;
        this.series = series;
        this.number = number;
        this.writer = writer;
        this.web = web;
        this.pageCount = pageCount;
        this.manga = manga;
        this.volume = volume;
        this.count = count;
        this.pages = pages == null ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(pages));
    }

    public static final class Page {
        public final int image;
        public final String bookmark;
        public final String type;

        public Page(int image, String bookmark) {
            this(image, bookmark, null);
        }

        public Page(int image, String bookmark, String type) {
            this.image = image;
            this.bookmark = bookmark;
            this.type = type;
        }
    }
}
