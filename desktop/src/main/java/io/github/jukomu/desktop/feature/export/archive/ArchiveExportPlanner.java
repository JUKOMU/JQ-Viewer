package io.github.jukomu.desktop.feature.export.archive;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 将规范化章节和页范围转换为确定的 CBZ/ZIP 条目布局。
 */
public final class ArchiveExportPlanner {
    private static final String MANGA = "YesAndRightToLeft";

    private ArchiveExportPlanner() {
    }

    public static Plan plan(
        String format,
        String mode,
        String albumId,
        String albumTitle,
        String authors,
        List<Chapter> chapters,
        List<Path> images,
        int volumeStart,
        int volumeEnd,
        int volumeIndex,
        int volumeCount
    ) {
        if (!"cbz".equals(format) && !"zip".equals(format)) {
            throw new IllegalArgumentException("归档格式必须是 cbz 或 zip");
        }
        if (volumeStart < 0 || volumeEnd > images.size() || volumeStart >= volumeEnd) {
            throw new IllegalArgumentException("归档分卷页范围无效");
        }
        List<Entry> entries = new ArrayList<>(volumeEnd - volumeStart);
        List<ComicInfo.Page> pages = new ArrayList<>(volumeEnd - volumeStart);
        List<ChapterSpan> spans = chapterSpans(chapters);
        int plannedPages = spans.isEmpty() ? 0 : spans.getLast().end();
        if (plannedPages != images.size()) {
            throw new IllegalArgumentException("章节页数与图片数量不一致");
        }
        if (volumeIndex < 1 || volumeCount < 1 || volumeIndex > volumeCount) {
            throw new IllegalArgumentException("归档分卷编号无效");
        }
        for (int globalPage = volumeStart; globalPage < volumeEnd; globalPage++) {
            ChapterSpan span = containing(spans, globalPage);
            int volumePage = globalPage - volumeStart;
            Path image = images.get(globalPage);
            String extension = extension(image);
            String name;
            if ("zip".equals(format) && "merged".equals(mode)) {
                int chapterStartInVolume = Math.max(span.start(), volumeStart);
                name = String.format(Locale.ROOT, "%03d_%s/%s.%s",
                    span.index() + 1, archiveSegment(span.chapter().title()),
                    pageNumber(globalPage - chapterStartInVolume + 1,
                        Math.min(span.end(), volumeEnd) - chapterStartInVolume),
                    extension);
            } else {
                name = pageNumber(volumePage + 1, volumeEnd - volumeStart) + "." + extension;
            }
            entries.add(new Entry(name, image));
            String bookmark = span.start() == globalPage ? span.chapter().title() : null;
            pages.add(new ComicInfo.Page(volumePage, bookmark));
        }

        byte[] comicInfo = null;
        if ("cbz".equals(format)) {
            List<ChapterSpan> covered = spans.stream()
                .filter(span -> span.end() > volumeStart && span.start() < volumeEnd)
                .toList();
            Chapter first = covered.getFirst().chapter();
            Chapter last = covered.getLast().chapter();
            boolean singleChapter = covered.size() == 1;
            String title = singleChapter ? first.title() : range(first.title(), last.title());
            String number = singleChapter ? chapterNumber(first) : range(chapterNumber(first), chapterNumber(last));
            ComicInfo info = new ComicInfo(
                title, albumTitle, number, authors,
                albumId == null || albumId.isBlank() ? null
                    : "https://18comic.vip/album/" + albumId,
                pages.size(), MANGA,
                volumeCount > 1 ? volumeIndex : null,
                volumeCount > 1 ? volumeCount : null,
                pages);
            comicInfo = ComicInfoCodec.serialize(info);
        }
        return new Plan(List.copyOf(entries), comicInfo, volumeEnd - volumeStart);
    }

    private static List<ChapterSpan> chapterSpans(List<Chapter> chapters) {
        List<ChapterSpan> spans = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < chapters.size(); index++) {
            Chapter chapter = chapters.get(index);
            if (chapter.pageCount() <= 0) throw new IllegalArgumentException("章节页数必须大于 0");
            spans.add(new ChapterSpan(index, start, start + chapter.pageCount(), chapter));
            start += chapter.pageCount();
        }
        return spans;
    }

    private static ChapterSpan containing(List<ChapterSpan> spans, int page) {
        return spans.stream().filter(span -> page >= span.start() && page < span.end())
            .findFirst().orElseThrow(() -> new IllegalArgumentException("页码没有对应章节"));
    }

    private static String extension(Path image) {
        String name = image.getFileName().toString();
        int separator = name.lastIndexOf('.');
        String extension = separator < 0 ? "" : name.substring(separator + 1).toLowerCase(Locale.ROOT);
        if ("jpeg".equals(extension)) return "jpeg";
        if (!List.of("jpg", "png", "webp", "gif").contains(extension)) {
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
        return normalized.isBlank() ? "章节" : normalized;
    }

    private static String chapterNumber(Chapter chapter) {
        return chapter.sortOrder() > 0 ? String.valueOf(chapter.sortOrder()) : chapter.id();
    }

    private static String range(String first, String last) {
        if (first == null || first.isBlank()) return last;
        if (last == null || last.isBlank() || first.equals(last)) return first;
        return first + " - " + last;
    }

    public record Chapter(String id, String title, int sortOrder, int pageCount) {
    }

    public record Entry(String name, Path source) {
    }

    public record Plan(List<Entry> entries, byte[] comicInfo, int pageCount) {
        public Plan {
            entries = List.copyOf(entries);
            comicInfo = comicInfo == null ? null : comicInfo.clone();
        }
    }

    private record ChapterSpan(int index, int start, int end, Chapter chapter) {
    }
}
