package io.github.jukomu.desktop.feature.image;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 进程内按字节容量淘汰的图片缓存。 */
public final class ImageCache {
    public record Entry(byte[] bytes, String mimeType) {
    }

    public record Info(String photoId, int sortOrder, String type, long sizeBytes, String mimeType) {
    }

    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long capacityBytes;
    private long usedBytes;

    public ImageCache(long capacityBytes) {
        this.capacityBytes = capacityBytes;
    }

    public synchronized Entry get(String key) {
        return entries.get(key);
    }

    public synchronized boolean contains(String key) {
        return entries.containsKey(key);
    }

    public synchronized void put(String key, byte[] bytes, String mimeType) {
        if (bytes.length > capacityBytes) return;
        Entry previous = entries.remove(key);
        if (previous != null) usedBytes -= previous.bytes().length;
        entries.put(key, new Entry(bytes, mimeType));
        usedBytes += bytes.length;
        evict();
    }

    public synchronized void remove(String key) {
        Entry removed = entries.remove(key);
        if (removed != null) usedBytes -= removed.bytes().length;
    }

    public synchronized void clear() {
        entries.clear();
        usedBytes = 0;
    }

    public synchronized long usedBytes() {
        return usedBytes;
    }

    public synchronized long capacityBytes() {
        return capacityBytes;
    }

    public synchronized List<Info> snapshot() {
        List<Info> result = new ArrayList<>();
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            String[] parts = entry.getKey().split("/", -1);
            if (parts.length != 3) continue;
            try {
                result.add(new Info(parts[0], Integer.parseInt(parts[1]), parts[2],
                        entry.getValue().bytes().length, entry.getValue().mimeType()));
            } catch (NumberFormatException ignored) {
                // 只记录由服务写入的合法缓存键。
            }
        }
        return result;
    }

    private void evict() {
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (usedBytes > capacityBytes && iterator.hasNext()) {
            Entry removed = iterator.next().getValue();
            usedBytes -= removed.bytes().length;
            iterator.remove();
        }
    }
}
