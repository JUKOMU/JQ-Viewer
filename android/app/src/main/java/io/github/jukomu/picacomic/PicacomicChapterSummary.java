package io.github.jukomu.picacomic;

/** Chapter list item with stable identity and current order locator. */
public class PicacomicChapterSummary {
    public final ChapterRef ref;
    public final String title;
    public final String updatedAt;

    public PicacomicChapterSummary(ChapterRef ref, String title, String updatedAt) {
        if (ref == null) throw new IllegalArgumentException("ref is required");
        this.ref = ref;
        this.title = title == null ? "" : title;
        this.updatedAt = updatedAt == null ? "" : updatedAt;
    }
}
