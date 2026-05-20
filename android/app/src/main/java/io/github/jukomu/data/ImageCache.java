package io.github.jukomu.data;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
public class ImageCache {

    static final String VIRTUAL_HOST = "jqviewer.local";
    static final long DEFAULT_CAPACITY = 640L * 1024 * 1024; // 640MB
    private static final int THUMBNAIL_MAX_WIDTH = 300;
    private static final int THUMBNAIL_JPEG_QUALITY = 70;

    private static volatile ImageCache instance;

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

    private ImageCache() {
    }

    public static ImageCache getInstance() {
        if (instance == null) {
            synchronized (ImageCache.class) {
                if (instance == null) {
                    instance = new ImageCache();
                }
            }
        }
        return instance;
    }

    // ---- 容量配置 ----

    /**
     * 获取当前容量（字节），供外部查询。
     */
    public long getCapacity() {
        readLock.lock();
        try {
            return capacity;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 设置容量（字节），若当前占用超过新容量则触发淘汰。
     */
    public void setCapacity(long newCapacity) {
        writeLock.lock();
        try {
            this.capacity = newCapacity;
            evictIfNeeded();
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 获取当前已用字节。
     */
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

    /**
     * 清空全部缓存。
     */
    public void clear() {
        writeLock.lock();
        try {
            cache.clear();
            currentSize = 0;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 按前缀清理（如清理某个 photoId 下的所有图片：photoId + "/"）。
     */
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
     * URL: https://jqviewer.local/{type}/{photoId}/{sortOrder}
     * <p>
     * 查找顺序：内存缓存 → FileStore（离线下载的图片）→ null（在线等待 preloadImages）
     */
    public static WebResourceResponse handleRequest(String url) {
        try {
            Uri uri = Uri.parse(url);
            List<String> segments = uri.getPathSegments();
            if (segments.size() < 3) return null;

            int size = segments.size();
            String type = segments.get(size - 3);
            String photoId = segments.get(size - 2);
            int sortOrder;
            try {
                sortOrder = Integer.parseInt(segments.get(size - 1));
            } catch (NumberFormatException e) {
                return null;
            }

            String cacheKey = photoId + "/" + sortOrder + ("thumb".equals(type) ? "/thumb" : "");

            // 1. 查内存缓存
            ImageEntry entry = getInstance().get(cacheKey);
            if (entry != null) {
                return new WebResourceResponse(
                        entry.mimeType,
                        "UTF-8",
                        new ByteArrayInputStream(entry.data)
                );
            }

            // 2. 缓存 miss → 依次：原图内存缓存 → FileStore
            if ("thumb".equals(type)) {
                // 2a. 查原图内存缓存（从内存生成缩略图，最快）
                ImageEntry original = getInstance().get(photoId + "/" + sortOrder);
                if (original != null) {
                    byte[] thumbData = createThumbnail(original.data);
                    getInstance().put(cacheKey, thumbData, "image/jpeg");
                    return new WebResourceResponse("image/jpeg", "UTF-8",
                            new ByteArrayInputStream(thumbData));
                }

                // 2b. 查 FileStore（从本地原图生成缩略图）
                byte[] originalData = FileStore.getInstance()
                        .getImageBytesByPhotoId(photoId, sortOrder);
                if (originalData != null) {
                    byte[] thumbData = createThumbnail(originalData);
                    getInstance().put(cacheKey, thumbData, "image/jpeg");
                    return new WebResourceResponse("image/jpeg", "UTF-8",
                            new ByteArrayInputStream(thumbData));
                }
            } else {
                byte[] data = FileStore.getInstance()
                        .getImageBytesByPhotoId(photoId, sortOrder);
                if (data != null) {
                    String mime = "image/" + guessFormatName(data);
                    getInstance().put(cacheKey, data, mime);
                    return new WebResourceResponse(mime, "UTF-8",
                            new ByteArrayInputStream(data));
                }
            }

            // 3. 仍未找到 → 返回 null（在线场景等待 preloadImages 下载/FileStore 兜底）
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 通过文件头魔数判断图片格式
     */
    public static String guessFormatName(byte[] data) {
        if (data == null || data.length < 3) return "jpeg";
        int b0 = data[0] & 0xFF;
        int b1 = data[1] & 0xFF;
        int b2 = data[2] & 0xFF;
        if (b0 == 0xFF && b1 == 0xD8) return "jpeg";
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E) return "png";
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return "gif";
        if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46) return "webp"; // RIFF....WEBP
        return "jpeg";
    }

    /**
     * 从原图字节生成缩略图（JPEG，宽≤300px，质量70）
     */
    public static byte[] createThumbnail(byte[] imageBytes) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        if (bitmap == null) return imageBytes;

        int width = bitmap.getWidth();
        if (width <= THUMBNAIL_MAX_WIDTH) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, baos);
            bitmap.recycle();
            return baos.toByteArray();
        }

        float ratio = (float) THUMBNAIL_MAX_WIDTH / width;
        int targetHeight = Math.round(bitmap.getHeight() * ratio);
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, THUMBNAIL_MAX_WIDTH, targetHeight, true);
        bitmap.recycle();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, baos);
        scaled.recycle();
        return baos.toByteArray();
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
