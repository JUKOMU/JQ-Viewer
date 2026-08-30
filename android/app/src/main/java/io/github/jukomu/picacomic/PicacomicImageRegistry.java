package io.github.jukomu.picacomic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local registry that keeps remote image locators out of JavaScript. */
public final class PicacomicImageRegistry {

    private final Map<String, PicacomicImageSource> sources = new ConcurrentHashMap<>();

    public PicacomicImageRef register(PicacomicImageSource source) {
        if (source == null) throw new IllegalArgumentException("source is required");
        String key = PicacomicCacheNamespace.imageKey(source);
        PicacomicImageSource previous = sources.putIfAbsent(key, source);
        if (previous != null && !previous.equals(source)) {
            throw new IllegalStateException("Picacomic image key collision");
        }
        return PicacomicCacheNamespace.imageRef(source);
    }

    public PicacomicImageSource resolve(String imageKey) {
        if (!PicacomicCacheNamespace.ownsKey(imageKey)) return null;
        return sources.get(imageKey);
    }

    public boolean contains(String imageKey) {
        return resolve(imageKey) != null;
    }

    public List<String> keysForChapter(String albumId, String chapterId) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, PicacomicImageSource> entry : sources.entrySet()) {
            PicacomicImageSource source = entry.getValue();
            if (source.kind == PicacomicImageSource.Kind.PAGE
                && source.albumId.equals(albumId) && source.chapterId.equals(chapterId)) {
                result.add(entry.getKey());
            }
        }
        Collections.sort(result);
        return result;
    }

    public PicacomicImageSource remove(String imageKey) {
        return sources.remove(imageKey);
    }

    public int size() {
        return sources.size();
    }

    public void clear() {
        sources.clear();
    }
}
