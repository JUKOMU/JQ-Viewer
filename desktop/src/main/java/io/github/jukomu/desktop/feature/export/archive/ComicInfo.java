package io.github.jukomu.desktop.feature.export.archive;

import java.util.List;

/** ComicInfo v2.0 中当前导出和导入需要的标准字段。 */
public record ComicInfo(
        String title,
        String series,
        String number,
        String writer,
        String web,
        int pageCount,
        String manga,
        Integer volume,
        Integer count,
        List<Page> pages
) {
    public ComicInfo {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    public record Page(int image, String bookmark) {
    }
}
