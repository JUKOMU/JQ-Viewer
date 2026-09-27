package io.github.jukomu.feature.export.archive;

import java.io.File;
import java.util.*;

/**
 * 将规范化章节和页范围转换为确定的 CBZ/ZIP 条目布局。
 */
public final class ArchiveExportPlanner {
    private ArchiveExportPlanner() {
    }

    public static Plan plan(String format, String mode, String albumId, String albumTitle,
                            String authors, List<Chapter> chapters, List<File> images,
                            int volumeStart, int volumeEnd, int volumeIndex, int volumeCount) {
        if (!"cbz".equals(format) && !"zip".equals(format)) {
            throw new IllegalArgumentException("归档格式必须是 cbz 或 zip");
        }
        if (volumeStart < 0 || volumeEnd > images.size() || volumeStart >= volumeEnd) {
            throw new IllegalArgumentException("归档分卷页范围无效");
        }
        List<ChapterSpan> spans = chapterSpans(chapters);
        int plannedPages = spans.isEmpty() ? 0 : spans.get(spans.size() - 1).end;
        if (plannedPages != images.size()) {
            throw new IllegalArgumentException("章节页数与图片数量不一致");
        }
        if (volumeIndex < 1 || volumeCount < 1 || volumeIndex > volumeCount) {
            throw new IllegalArgumentException("归档分卷编号无效");
        }
        List<Entry> entries = new ArrayList<>();
        List<ComicInfo.Page> pages = new ArrayList<>();
        for (int globalPage = volumeStart; globalPage < volumeEnd; globalPage++) {
            ChapterSpan span = containing(spans, globalPage);
            int volumePage = globalPage - volumeStart;
            File image = images.get(globalPage);
            String extension = extension(image);
            String name;
            if ("zip".equals(format) && "merged".equals(mode)) {
                int chapterStartInVolume = Math.max(span.start, volumeStart);
                name = String.format(Locale.ROOT, "%03d_%s/%s.%s", span.index + 1,
                    archiveSegment(span.chapter.title),
                    pageNumber(globalPage - chapterStartInVolume + 1,
                        Math.min(span.end, volumeEnd) - chapterStartInVolume), extension);
            } else {
                name = pageNumber(volumePage + 1, volumeEnd - volumeStart) + "." + extension;
            }
            entries.add(new Entry(name, image));
            pages.add(new ComicInfo.Page(volumePage,
                span.start == globalPage ? span.chapter.title : null));
        }

        byte[] comicInfo = null;
        if ("cbz".equals(format)) {
            List<ChapterSpan> covered = new ArrayList<>();
            for (ChapterSpan span : spans) {
                if (span.end > volumeStart && span.start < volumeEnd) covered.add(span);
            }
            Chapter first = covered.get(0).chapter;
            Chapter last = covered.get(covered.size() - 1).chapter;
            boolean single = covered.size() == 1;
            ComicInfo info = new ComicInfo(
                single ? first.title : range(first.title, last.title), albumTitle,
                single ? chapterNumber(first) : range(chapterNumber(first), chapterNumber(last)),
                authors, albumId == null || albumId.trim().isEmpty() ? null
                : "https://18comic.vip/album/" + albumId,
                pages.size(), "YesAndRightToLeft", volumeCount > 1 ? volumeIndex : null,
                volumeCount > 1 ? volumeCount : null, pages);
            comicInfo = ComicInfoCodec.serialize(info);
        }
        return new Plan(entries, comicInfo, volumeEnd - volumeStart);
    }

    private static List<ChapterSpan> chapterSpans(List<Chapter> chapters) {
        List<ChapterSpan> spans = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < chapters.size(); index++) {
            Chapter chapter = chapters.get(index);
            if (chapter.pageCount <= 0) throw new IllegalArgumentException("章节页数必须大于 0");
            spans.add(new ChapterSpan(index, start, start + chapter.pageCount, chapter));
            start += chapter.pageCount;
        }
        return spans;
    }

    private static ChapterSpan containing(List<ChapterSpan> spans, int page) {
        for (ChapterSpan span : spans) if (page >= span.start && page < span.end) return span;
        throw new IllegalArgumentException("页码没有对应章节");
    }

    private static String extension(File image) {
        String name = image.getName();
        int separator = name.lastIndexOf('.');
        String extension = separator < 0 ? "" : name.substring(separator + 1).toLowerCase(Locale.ROOT);
        if (!Arrays.asList("jpg", "jpeg", "png", "webp", "gif").contains(extension)) {
            throw new IllegalArgumentException("不支持的归档图片格式: " + name);
        }
        return extension;
    }

    private static String pageNumber(int number, int pageCount) {
        int width = Math.max(4, String.valueOf(Math.max(number, pageCount)).length());
        return String.format(Locale.ROOT, "%0" + width + "d", number);
    }

    private static String archiveSegment(String value) {
        String normalized = value == null ? "" : value.trim()
                                                 .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return normalized.trim().isEmpty() ? "章节" : normalized;
    }

    private static String chapterNumber(Chapter chapter) {
        return chapter.sortOrder > 0 ? String.valueOf(chapter.sortOrder) : chapter.id;
    }

    private static String range(String first, String last) {
        if (first == null || first.trim().isEmpty()) return last;
        if (last == null || last.trim().isEmpty() || first.equals(last)) return first;
        return first + " - " + last;
    }

    public static final class Chapter {
        public final String id;
        public final String title;
        public final int sortOrder;
        public final int pageCount;

        public Chapter(String id, String title, int sortOrder, int pageCount) {
            this.id = id;
            this.title = title;
            this.sortOrder = sortOrder;
            this.pageCount = pageCount;
        }
    }

    public static final class Entry {
        public final String name;
        public final File source;

        Entry(String name, File source) {
            this.name = name;
            this.source = source;
        }
    }

    public static final class Plan {
        public final List<Entry> entries;
        public final byte[] comicInfo;
        public final int pageCount;

        Plan(List<Entry> entries, byte[] comicInfo, int pageCount) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.comicInfo = comicInfo == null ? null : comicInfo.clone();
            this.pageCount = pageCount;
        }
    }

    private static final class ChapterSpan {
        final int index;
        final int start;
        final int end;
        final Chapter chapter;

        ChapterSpan(int index, int start, int end, Chapter chapter) {
            this.index = index;
            this.start = start;
            this.end = end;
            this.chapter = chapter;
        }
    }
}
