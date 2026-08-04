package io.github.jukomu.data;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.*;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 线程安全 LRU 内存缓存（基于字节容量淘汰）。
 * 线程安全策略参考库内 CachePool 的 ReentrantReadWriteLock 设计。
 * <p>
 * 容量由 {@link CacheCapacityPolicy} 统一计算，并支持读取前的在途字节预留。
 */
public class ImageCache {

    static final String VIRTUAL_HOST = "jqviewer.local";
    private static final int VALIDATION_MAX_DIMENSION = 256;
    private static final int THUMBNAIL_MAX_WIDTH = 300;
    private static final int THUMBNAIL_JPEG_QUALITY = 70;
    private static final Map<String, String> NO_CACHE_RESPONSE_HEADERS;

    static {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        headers.put("Pragma", "no-cache");
        headers.put("Expires", "0");
        NO_CACHE_RESPONSE_HEADERS = Collections.unmodifiableMap(headers);
    }

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

    private long capacity;
    private long currentSize = 0;
    private long reservedSize = 0;
    private CacheCapacityPolicy.Result policyResult;

    private ImageCache() {
        policyResult = new CacheCapacityPolicy().calculate(
            CacheCapacityPolicy.DEFAULT_REQUESTED_MB,
            Runtime.getRuntime().maxMemory(),
            false,
            CacheCapacityPolicy.PressureLevel.NORMAL);
        capacity = policyResult.effectiveMb * CacheCapacityPolicy.MIB;
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

    static ImageCache createIsolated() {
        return new ImageCache();
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
    public void applyPolicy(CacheCapacityPolicy.Result result) {
        if (result == null) return;
        writeLock.lock();
        try {
            policyResult = result;
            capacity = result.effectiveMb * CacheCapacityPolicy.MIB;
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

    public CacheStats getStats() {
        readLock.lock();
        try {
            return new CacheStats(policyResult, currentSize, reservedSize);
        } finally {
            readLock.unlock();
        }
    }

    public boolean canAccept(long size) {
        readLock.lock();
        try {
            return size > 0L && size <= capacity;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 在读取大对象前预留容量，并提前淘汰最久未访问的缓存条目。
     */
    public IncomingReservation prepareForIncomingBytes(long size) {
        if (size <= 0L) return null;
        writeLock.lock();
        try {
            if (size > capacity || reservedSize + size > capacity) return null;
            if (!evictForHeapMargin(reservedSize + size)) return null;
            evictToTarget(capacity - reservedSize - size);
            reservedSize += size;
            return new IncomingReservation(this, size);
        } finally {
            writeLock.unlock();
        }
    }

    // ---- 缓存操作 ----

    public boolean put(String key, byte[] data, String mimeType) {
        return put(key, data, mimeType, null);
    }

    public boolean put(String key, byte[] data, String mimeType,
                       IncomingReservation reservation) {
        if (data == null || data.length == 0) {
            if (reservation != null) reservation.close();
            return false;
        }
        int size = data.length;

        writeLock.lock();
        try {
            boolean hadReservation = consumeReservation(reservation);
            if (size > capacity || reservedSize + size > capacity) return false;

            long unaccountedBytes = hadReservation ? reservedSize : reservedSize + size;
            if (!evictForHeapMargin(unaccountedBytes)) return false;

            // 若 key 已存在，先移除旧的
            ImageEntry old = cache.remove(key);
            if (old != null) {
                currentSize -= old.data.length;
            }

            evictToTarget(capacity - reservedSize - size);

            ImageEntry entry = new ImageEntry(data, mimeType);
            cache.put(key, entry);
            currentSize += size;
            evictIfNeeded();
            return cache.containsKey(key);
        } finally {
            writeLock.unlock();
        }
    }

    public ImageEntry get(String key) {
        writeLock.lock();
        try {
            return cache.get(key);
        } finally {
            writeLock.unlock();
        }
    }

    public void remove(String key) {
        if (key == null) return;
        writeLock.lock();
        try {
            ImageEntry removed = cache.remove(key);
            if (removed != null) {
                currentSize -= removed.data.length;
            }
        } finally {
            writeLock.unlock();
        }
    }

    public boolean has(String key) {
        writeLock.lock();
        try {
            return cache.containsKey(key);
        } finally {
            writeLock.unlock();
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
        evictToTarget(Math.max(0L, capacity - reservedSize));
    }

    private void evictToTarget(long target) {
        if (currentSize <= target) return;
        var it = cache.entrySet().iterator();
        while (it.hasNext() && currentSize > target) {
            var entry = it.next();
            currentSize -= entry.getValue().data.length;
            it.remove();
        }
    }

    /**
     * 淘汰最久未访问的条目直到堆余量充足（不超过最大堆的 85%）。
     * 在插入新数据前调用，优先保留用户当前正在浏览的图片。
     */
    private boolean evictForHeapMargin(long projectedBytes) {
        Runtime rt = Runtime.getRuntime();
        long maxHeap = rt.maxMemory();
        long usedHeap = rt.totalMemory() - rt.freeMemory();
        return evictForHeapMargin(projectedBytes, maxHeap, usedHeap);
    }

    boolean evictForHeapMargin(long projectedBytes, long maxHeap, long usedHeap) {
        long safeThreshold = (long) (maxHeap * 0.85);
        long needToFree = (usedHeap + projectedBytes) - safeThreshold;
        if (needToFree <= 0) return true;

        long evictableBytes = 0;
        for (ImageEntry entry : cache.values()) {
            evictableBytes += entry.data.length;
            if (evictableBytes >= needToFree) break;
        }
        if (evictableBytes < needToFree) return false;

        long freed = 0;
        var it = cache.entrySet().iterator();
        while (it.hasNext() && freed < needToFree) {
            var entry = it.next();
            int len = entry.getValue().data.length;
            currentSize -= len;
            freed += len;
            it.remove();
        }
        return true;
    }

    private boolean consumeReservation(IncomingReservation reservation) {
        if (reservation == null || reservation.owner != this || !reservation.open) return false;
        reservedSize -= reservation.size;
        reservation.open = false;
        return true;
    }

    private void releaseReservation(IncomingReservation reservation) {
        writeLock.lock();
        try {
            consumeReservation(reservation);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 将缓存缩减至容量的 fraction 比例（0.0 = 清空, 0.5 = 减半, 1.0 = 无操作）。
     * 供内存压力回调使用，不改变持久化的 capacity 值。
     */
    public void trimToFraction(double fraction) {
        writeLock.lock();
        try {
            long target = (long) (capacity * fraction);
            if (currentSize <= target) return;
            var it = cache.entrySet().iterator();
            while (it.hasNext() && currentSize > target) {
                var entry = it.next();
                currentSize -= entry.getValue().data.length;
                it.remove();
            }
        } finally {
            writeLock.unlock();
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
                return createImageResponse(entry.mimeType,
                    new ByteArrayInputStream(entry.data));
            }

            // 2. 缓存 miss → 依次：原图内存缓存 → FileStore
            if ("thumb".equals(type)) {
                // 2a. 查原图内存缓存（从内存生成缩略图，最快）
                ImageEntry original = getInstance().get(photoId + "/" + sortOrder);
                if (original != null) {
                    byte[] thumbData = createThumbnail(original.data);
                    getInstance().put(cacheKey, thumbData, "image/jpeg");
                    return createImageResponse("image/jpeg",
                        new ByteArrayInputStream(thumbData));
                }

                // 2b. 查 FileStore（从本地原图生成缩略图）
                File imageFile = FileStore.getInstance().getImageFileByPhotoId(photoId, sortOrder);
                if (imageFile != null) {
                    try (IncomingReservation reservation = getInstance()
                        .prepareForIncomingBytes(imageFile.length())) {
                        if (reservation == null) return null;
                        byte[] originalData = FileStore.getInstance().readImageBytes(imageFile);
                        byte[] thumbData = createThumbnail(originalData);
                        reservation.close();
                        getInstance().put(cacheKey, thumbData, "image/jpeg");
                        return createImageResponse("image/jpeg",
                            new ByteArrayInputStream(thumbData));
                    }
                }
            } else {
                File imageFile = FileStore.getInstance().getImageFileByPhotoId(photoId, sortOrder);
                if (imageFile != null) {
                    try (IncomingReservation reservation = getInstance()
                        .prepareForIncomingBytes(imageFile.length())) {
                        if (reservation == null) return createFileResponse(imageFile);
                        byte[] data = FileStore.getInstance().readImageBytes(imageFile);
                        String mime = "image/" + guessFormatName(data);
                        getInstance().put(cacheKey, data, mime, reservation);
                        return createImageResponse(mime, new ByteArrayInputStream(data));
                    }
                }
            }

            // 3. 仍未找到 → 返回 null（在线场景等待 preloadImages 下载/FileStore 兜底）
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    static WebResourceResponse createFileResponse(File imageFile) throws IOException {
        if (!imageFile.isFile() || imageFile.length() <= 0L) {
            throw new IOException("图片文件不可用");
        }
        FileInputStream input = new FileInputStream(imageFile);
        try {
            byte[] header = new byte[12];
            int offset = 0;
            while (offset < header.length) {
                int count = input.read(header, offset, header.length - offset);
                if (count < 0) break;
                offset += count;
            }
            input.getChannel().position(0L);
            return createImageResponse("image/" + guessFormatName(header), input);
        } catch (IOException | RuntimeException e) {
            try {
                input.close();
            } catch (IOException ignored) {
                // 保留创建响应时的原始异常。
            }
            throw e;
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

    /** 使用低分辨率实际解码，避免仅有合法头部的截断图片通过校验。 */
    public static boolean isDecodableImage(byte[] data) {
        if (data == null || data.length == 0) return false;
        try {
            if (!hasCompleteImageContainer(data)) return false;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;

            int sampleSize = 1;
            int maxDimension = Math.max(bounds.outWidth, bounds.outHeight);
            while (maxDimension / sampleSize > VALIDATION_MAX_DIMENSION
                && sampleSize <= Integer.MAX_VALUE / 2) {
                sampleSize *= 2;
            }

            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = sampleSize;
            decode.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, decode);
            if (bitmap == null) return false;
            bitmap.recycle();
            return true;
        } catch (RuntimeException | OutOfMemoryError e) {
            return false;
        }
    }

    /** 文件版校验避免为了下载完成检查而把整张图片再次读入 Java 堆。 */
    public static boolean isDecodableImage(File imageFile) {
        if (imageFile == null || !imageFile.isFile() || imageFile.length() <= 0L) return false;
        try {
            if (!hasCompleteImageContainer(imageFile)) return false;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imageFile.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;

            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = calculateValidationSampleSize(bounds.outWidth, bounds.outHeight);
            decode.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath(), decode);
            if (bitmap == null) return false;
            bitmap.recycle();
            return true;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return false;
        }
    }

    private static int calculateValidationSampleSize(int width, int height) {
        int sampleSize = 1;
        int maxDimension = Math.max(width, height);
        while (maxDimension / sampleSize > VALIDATION_MAX_DIMENSION
            && sampleSize <= Integer.MAX_VALUE / 2) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    private static boolean hasCompleteImageContainer(byte[] data) {
        if (isJpeg(data)) {
            return data.length >= 4
                && data[data.length - 2] == (byte) 0xff
                && data[data.length - 1] == (byte) 0xd9;
        }
        if (isPng(data)) {
            return hasPngEndMarker(data, data.length - 12);
        }
        if (isGif(data)) {
            return data.length >= 7 && data[data.length - 1] == 0x3b;
        }
        if (!isWebp(data)) return true;

        long riffSize = (data[4] & 0xffL)
            | ((data[5] & 0xffL) << 8)
            | ((data[6] & 0xffL) << 16)
            | ((data[7] & 0xffL) << 24);
        return riffSize + 8L == data.length;
    }

    private static boolean hasCompleteImageContainer(File imageFile) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(imageFile, "r")) {
            long length = input.length();
            if (length <= 0L) return false;

            byte[] header = new byte[(int) Math.min(12L, length)];
            input.readFully(header);
            if (isJpeg(header)) {
                if (length < 4L) return false;
                input.seek(length - 2L);
                return input.readUnsignedByte() == 0xff && input.readUnsignedByte() == 0xd9;
            }
            if (isPng(header)) {
                if (length < 12L) return false;
                byte[] footer = new byte[12];
                input.seek(length - footer.length);
                input.readFully(footer);
                return hasPngEndMarker(footer, 0);
            }
            if (isGif(header)) {
                if (length < 7L) return false;
                input.seek(length - 1L);
                return input.readUnsignedByte() == 0x3b;
            }
            if (!isWebp(header)) return true;

            long riffSize = (header[4] & 0xffL)
                | ((header[5] & 0xffL) << 8)
                | ((header[6] & 0xffL) << 16)
                | ((header[7] & 0xffL) << 24);
            return riffSize + 8L == length;
        }
    }

    private static boolean isJpeg(byte[] data) {
        return data.length >= 2
            && data[0] == (byte) 0xff && data[1] == (byte) 0xd8;
    }

    private static boolean isPng(byte[] data) {
        return data.length >= 8
            && data[0] == (byte) 0x89 && data[1] == 0x50
            && data[2] == 0x4e && data[3] == 0x47
            && data[4] == 0x0d && data[5] == 0x0a
            && data[6] == 0x1a && data[7] == 0x0a;
    }

    private static boolean isGif(byte[] data) {
        return data.length >= 6
            && data[0] == 'G' && data[1] == 'I' && data[2] == 'F'
            && data[3] == '8' && (data[4] == '7' || data[4] == '9') && data[5] == 'a';
    }

    private static boolean isWebp(byte[] data) {
        return data.length >= 12
            && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
            && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }

    private static boolean hasPngEndMarker(byte[] data, int offset) {
        return offset >= 0 && data.length - offset >= 12
            && data[offset] == 0 && data[offset + 1] == 0
            && data[offset + 2] == 0 && data[offset + 3] == 0
            && data[offset + 4] == 'I' && data[offset + 5] == 'E'
            && data[offset + 6] == 'N' && data[offset + 7] == 'D'
            && data[offset + 8] == (byte) 0xae && data[offset + 9] == 0x42
            && data[offset + 10] == 0x60 && data[offset + 11] == (byte) 0x82;
    }

    private static WebResourceResponse createImageResponse(String mimeType, InputStream data) {
        WebResourceResponse response = new WebResourceResponse(mimeType, null, data);
        response.setResponseHeaders(NO_CACHE_RESPONSE_HEADERS);
        return response;
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

    public static final class IncomingReservation implements AutoCloseable {
        private final ImageCache owner;
        private final long size;
        private boolean open = true;

        IncomingReservation(ImageCache owner, long size) {
            this.owner = owner;
            this.size = size;
        }

        @Override
        public void close() {
            owner.releaseReservation(this);
        }
    }

    public static final class CacheStats {
        public final long requestedMb;
        public final long effectiveMb;
        public final long currentBytes;
        public final long reservedBytes;
        public final long maxHeapMb;
        public final double safeRatio;
        public final String pressureLevel;
        public final boolean temporaryClamp;
        public final String reason;

        CacheStats(CacheCapacityPolicy.Result result, long currentBytes, long reservedBytes) {
            this.requestedMb = result.requestedMb;
            this.effectiveMb = result.effectiveMb;
            this.currentBytes = currentBytes;
            this.reservedBytes = reservedBytes;
            this.maxHeapMb = result.maxHeapMb;
            this.safeRatio = result.safeRatio;
            this.pressureLevel = result.pressureLevel.getValue();
            this.temporaryClamp = result.temporaryClamp;
            this.reason = result.reason;
        }
    }
}
