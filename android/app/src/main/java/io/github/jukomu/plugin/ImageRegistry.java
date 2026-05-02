package io.github.jukomu.plugin;

import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 线程安全 LRU 内存缓存（基于字节容量淘汰）。
 * 线程安全策略参考库内 CachePool 的 ReentrantReadWriteLock 设计。
 * <p>
 * 默认容量 640MB，可通过 {@link #setCapacity(long)} 调整。
 */
public class ImageRegistry {

    static final String VIRTUAL_HOST = "jqviewer.local";
    static final long DEFAULT_CAPACITY = 640L * 1024 * 1024; // 640MB

    private static volatile ImageRegistry instance;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();

    /**
     * accessOrder=true，head 为最久未访问，tail 为最近访问。
     * 淘汰时从 head 开始移除。
     */
    private final LinkedHashMap<String, ImageEntry> cache = new LinkedHashMap<String, ImageEntry>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ImageEntry> eldest) {
            // 不使用条目数淘汰，手动按字节淘汰
            return false;
        }
    };

    private long capacity = DEFAULT_CAPACITY;
    private long currentSize = 0;

    private ImageRegistry() {}

    public static ImageRegistry getInstance() {
        if (instance == null) {
            synchronized (ImageRegistry.class) {
                if (instance == null) {
                    instance = new ImageRegistry();
                }
            }
        }
        return instance;
    }

    // ---- 容量配置 ----

    /** 获取当前容量（字节），供外部查询。 */
    public long getCapacity() {
        readLock.lock();
        try {
            return capacity;
        } finally {
            readLock.unlock();
        }
    }

    /** 设置容量（字节），若当前占用超过新容量则触发淘汰。 */
    public void setCapacity(long newCapacity) {
        writeLock.lock();
        try {
            this.capacity = newCapacity;
            evictIfNeeded();
        } finally {
            writeLock.unlock();
        }
    }

    /** 获取当前已用字节。 */
    public long getCurrentSize() {
        readLock.lock();
        try {
            return currentSize;
        } finally {
            readLock.unlock();
        }
    }

    // ---- 缓存操作 ----

    public void put(String key, byte[] data, String mimeType) {
        if (data == null || data.length == 0) return;
        int size = data.length;
        if (size > capacity) return; // 单个对象超过容量，不缓存

        writeLock.lock();
        try {
            // 若 key 已存在，先移除旧的
            ImageEntry old = cache.remove(key);
            if (old != null) {
                currentSize -= old.data.length;
            }
            ImageEntry entry = new ImageEntry(data, mimeType);
            cache.put(key, entry);
            currentSize += size;
            evictIfNeeded();
        } finally {
            writeLock.unlock();
        }
    }

    public ImageEntry get(String key) {
        readLock.lock();
        try {
            return cache.get(key);
        } finally {
            readLock.unlock();
        }
    }

    public boolean has(String key) {
        readLock.lock();
        try {
            return cache.containsKey(key);
        } finally {
            readLock.unlock();
        }
    }

    /** 清空全部缓存。 */
    public void clear() {
        writeLock.lock();
        try {
            cache.clear();
            currentSize = 0;
        } finally {
            writeLock.unlock();
        }
    }

    /** 按前缀清理（如清理某个 photoId 下的所有图片：photoId + "/"）。 */
    public void clearByPrefix(String prefix) {
        writeLock.lock();
        try {
            var it = cache.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                if (entry.getKey().startsWith(prefix)) {
                    currentSize -= entry.getValue().data.length;
                    it.remove();
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    // ---- 淘汰 ----

    private void evictIfNeeded() {
        if (currentSize <= capacity) return;
        var it = cache.entrySet().iterator();
        while (it.hasNext() && currentSize > capacity) {
            var entry = it.next();
            currentSize -= entry.getValue().data.length;
            it.remove();
        }
    }

    // ---- 静态工具 ----

    public static boolean isVirtualImageUrl(String url) {
        return url != null && url.contains(VIRTUAL_HOST);
    }

    /**
     * 解析虚拟 URL 并返回 WebResourceResponse。
     * URL: http://jqviewer.local/{type}/{photoId}/{sortOrder}
     */
    public static WebResourceResponse handleRequest(String url) {
        try {
            Uri uri = Uri.parse(url);
            List<String> segments = uri.getPathSegments();
            if (segments.size() < 3) return null;

            int size = segments.size();
            String type = segments.get(size - 3);
            String photoId = segments.get(size - 2);
            String sortOrderStr = segments.get(size - 1);

            String cacheKey = photoId + "/" + sortOrderStr + ("thumb".equals(type) ? "/thumb" : "");
            ImageEntry entry = getInstance().get(cacheKey);
            if (entry == null) return null;

            return new WebResourceResponse(
                entry.mimeType,
                "UTF-8",
                new ByteArrayInputStream(entry.data)
            );
        } catch (Exception e) {
            return null;
        }
    }

    public static class ImageEntry {
        public final byte[] data;
        public final String mimeType;

        ImageEntry(byte[] data, String mimeType) {
            this.data = data;
            this.mimeType = mimeType;
        }
    }
}
