package io.github.jukomu.picacomic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Chapter detail returned by the isolated reader contract. */
public final class PicacomicChapterDetail extends PicacomicChapterSummary {
    public final List<PicacomicImageRef> images;
    public final String contentRevision;
    public final boolean isSingleChapterAlbum;

    public PicacomicChapterDetail(ChapterRef ref, String title, String updatedAt,
                                  List<PicacomicImageRef> images, String contentRevision,
                                  boolean isSingleChapterAlbum) {
        super(ref, title, updatedAt);
        if (contentRevision == null || contentRevision.isEmpty()) {
            throw new IllegalArgumentException("contentRevision is required");
        }
        this.images = immutable(images);
        this.contentRevision = contentRevision;
        this.isSingleChapterAlbum = isSingleChapterAlbum;
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
