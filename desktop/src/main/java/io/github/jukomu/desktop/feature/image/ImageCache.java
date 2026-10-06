package io.github.jukomu.desktop.feature.image;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 进程内按字节容量淘汰的图片缓存。
 */
public final class ImageCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageCache.class);

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
        if (bytes.length > capacityBytes) {
            LOGGER.warn("image-cache event=skip-entry key={} sizeBytes={} capacityBytes={}",
                clean(key), bytes.length, capacityBytes);
            return;
        }
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
        int entryCount = entries.size();
        long clearedBytes = usedBytes;
        entries.clear();
        usedBytes = 0;
        if (entryCount > 0) {
            LOGGER.info("image-cache event=cleared entryCount={} sizeBytes={}", entryCount, clearedBytes);
        }
    }

    public synchronized void setCapacityBytes(long capacityBytes) {
        if (capacityBytes <= 0) throw new IllegalArgumentException("缓存容量必须为正数");
        this.capacityBytes = capacityBytes;
        evict();
        LOGGER.info("image-cache event=capacity-updated capacityBytes={} usedBytes={}",
            capacityBytes, usedBytes);
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
        int evictedCount = 0;
        long evictedBytes = 0L;
        while (usedBytes > capacityBytes && iterator.hasNext()) {
            Entry removed = iterator.next().getValue();
            long size = removed.bytes().length;
            usedBytes -= size;
            evictedBytes += size;
            evictedCount++;
            iterator.remove();
        }
        if (evictedCount > 0) {
            LOGGER.info("image-cache event=evicted entryCount={} sizeBytes={} usedBytes={} capacityBytes={}",
                evictedCount, evictedBytes, usedBytes, capacityBytes);
        }
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) return "-";
        String cleaned = value.replaceAll("[\\p{Cntrl}\\r\\n]+", " ").trim();
        return cleaned.substring(0, Math.min(128, cleaned.length()));
    }
}
