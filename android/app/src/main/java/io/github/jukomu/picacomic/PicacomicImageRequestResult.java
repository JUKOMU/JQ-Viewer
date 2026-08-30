package io.github.jukomu.picacomic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Snapshot returned when image work is accepted by the runtime. */
public final class PicacomicImageRequestResult {
    public final List<String> cached;
    public final List<String> pending;

    public PicacomicImageRequestResult(List<String> cached, List<String> pending) {
        this.cached = immutable(cached);
        this.pending = immutable(pending);
    }

    private static List<String> immutable(List<String> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
