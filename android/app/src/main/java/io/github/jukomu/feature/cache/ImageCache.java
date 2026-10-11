package io.github.jukomu.feature.cache;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.webkit.WebResourceResponse;
import io.github.jukomu.feature.download.storage.FileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 线程安全 LRU 内存缓存（基于字节容量淘汰）。
 * 线程安全策略参考库内 CachePool 的 ReentrantReadWriteLock 设计。
 * <p>
 * 容量由 {@link CacheCapacityPolicy} 统一计算，并支持读取前的在途字节预留。
 */
public class ImageCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageCache.class);

    public static final String VIRTUAL_HOST = "jqviewer.local";
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

    private long memoryUsedBytes;
    private long reservedMemoryBytes;
    private CacheCapacityPolicy.Result policyResult;
    private volatile DiskImageCache diskCache;
    private CompletableFuture<DiskImageCache> diskInitialization;
    private Throwable diskInitializationFailure;
    private boolean policyApplied;
    private final ExecutorService diskExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "jq-viewer-image-cache");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Map.Entry<String, ImageEntry>> pendingSpills = new ArrayList<>();
    private final Object spillLock = new Object();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();

    private ImageCache() {
        policyResult = new CacheCapacityPolicy().calculate(
            CacheCapacityPolicy.DEFAULT_REQUESTED_MB,
            Runtime.getRuntime().maxMemory(),
            false,
            CacheCapacityPolicy.PressureLevel.NORMAL,
            CacheCapacityPolicy.SpaceSnapshot.unavailable());
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

    public synchronized void initDiskCache(Context context) {
        if (context == null || diskInitialization != null) return;
        Context applicationContext = context.getApplicationContext();
        diskInitialization = CompletableFuture.supplyAsync(() -> {
            DiskImageCache recovered = new DiskImageCache(applicationContext);
            recovered.recover();
            CacheCapacityPolicy.Result result;
            boolean shouldApply;
            readLock.lock();
            try {
                result = policyResult;
                shouldApply = policyApplied;
            } finally {
                readLock.unlock();
            }
            if (shouldApply && result != null) {
                long available = recovered.availableBytes();
                if (available >= 0L) {
                    long usable = Math.max(0L, available + recovered.usedBytes()
                        - CacheCapacityPolicy.DISK_RESERVE_MB * CacheCapacityPolicy.MIB);
                    long diskAvailableMb = usable / CacheCapacityPolicy.MIB;
                    long diskCapacityMb = Math.min(result.diskTargetMb, diskAvailableMb);
                    result = new CacheCapacityPolicy.Result(
                        result.requestedMb, result.memoryCapacityMb + diskCapacityMb,
                        result.memoryCapacityMb, result.diskTargetMb, diskCapacityMb,
                        result.maxHeapMb, result.safeRatio, result.pressureLevel,
                        result.temporaryClamp, result.reason, available);
                    writeLock.lock();
                    try {
                        policyResult = result;
                    } finally {
                        writeLock.unlock();
                    }
                }
                recovered.setCapacity(result.diskCapacityMb * CacheCapacityPolicy.MIB);
            }
            synchronized (ImageCache.this) {
                diskCache = recovered;
            }
            drainPendingSpills();
            return recovered;
        }, diskExecutor).whenComplete((ignored, error) -> {
            if (error != null) {
                synchronized (ImageCache.this) { diskInitializationFailure = error; }
                writeLock.lock();
                try {
                    pendingSpills.clear();
                } finally {
                    writeLock.unlock();
                }
                LOGGER.warn("图片冷层初始化失败，降级为仅内存缓存", error);
            }
        });
    }

    static ImageCache createIsolated() {
        return new ImageCache();
    }

    private DiskImageCache awaitDiskCache() {
        DiskImageCache current = diskCache;
        if (current != null) return current;
        CompletableFuture<DiskImageCache> initialization;
        synchronized (this) {
            if (diskCache != null) return diskCache;
            if (diskInitializationFailure != null || diskInitialization == null) return null;
            initialization = diskInitialization;
        }
        try {
            return initialization.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.ExecutionException error) {
            return null;
        }
    }

    public synchronized String getDiskState() {
        if (diskCache != null) return "ready";
        if (diskInitializationFailure != null) return "failed";
        return diskInitialization == null ? "not-initialized" : "initializing";
    }

    // ---- 容量配置 ----

    /** 获取内存热层容量（字节）。 */
    public long getMemoryCapacity() {
        readLock.lock();
        try {
            return memoryCapacityBytes();
        } finally {
            readLock.unlock();
        }
    }

    private long memoryCapacityBytes() {
        return policyResult.memoryCapacityMb * CacheCapacityPolicy.MIB;
    }

    /**
     * 设置容量（字节），若当前占用超过新容量则触发淘汰。
     */
    public void applyPolicy(CacheCapacityPolicy.Result result) {
        if (result == null) return;
        DiskImageCache disk = diskCache;
        writeLock.lock();
        try {
            policyResult = result;
            policyApplied = true;
            evictIfNeeded();
        } finally {
            writeLock.unlock();
        }
        scheduleDrain();
        disk = diskCache;
        if (disk != null) {
            DiskImageCache targetDisk = disk;
            long diskCapacity = result.diskCapacityMb * CacheCapacityPolicy.MIB;
            diskExecutor.execute(() -> targetDisk.setCapacity(diskCapacity));
        }
    }


    /** 获取内存热层当前图片 payload 使用量（字节）。 */
    public long getCurrentSize() {
        readLock.lock();
        try {
            return memoryUsedBytes;
        } finally {
            readLock.unlock();
        }
    }

    public CacheStats getStats() {
        CacheCapacityPolicy.Result result;
        long memoryBytes;
        long reservedBytes;
        readLock.lock();
        try {
            result = policyResult;
            memoryBytes = memoryUsedBytes;
            reservedBytes = reservedMemoryBytes;
        } finally {
            readLock.unlock();
        }
        DiskImageCache disk = awaitDiskCache();
        long diskBytes = disk == null ? 0L : disk.payloadBytes();
        return new CacheStats(result, memoryBytes, reservedBytes, diskBytes, disk == null ? getDiskState() : "ready");
    }

    /** 获取磁盘冷层容量（字节）。 */
    public long getDiskCapacity() {
        readLock.lock();
        try {
            return policyResult.diskCapacityMb * CacheCapacityPolicy.MIB;
        } finally {
            readLock.unlock();
        }
    }

    public boolean canAccept(long size) {
        readLock.lock();
        try {
            return size > 0L && size <= memoryCapacityBytes();
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 在读取大对象前预留容量，并提前淘汰最久未访问的缓存条目。
     */
    public IncomingReservation prepareForIncomingBytes(long size) {
        if (size <= 0L) return null;
        IncomingReservation reservation;
        writeLock.lock();
        try {
            long capacity = memoryCapacityBytes();
            if (size > capacity || reservedMemoryBytes + size > capacity) {
                LOGGER.warn("图片缓存预留拒绝: event=reserve_reject, reason=capacity, sizeBytes={}, capacityBytes={}",
                    size, capacity);
                return null;
            }
            if (!evictForHeapMargin(reservedMemoryBytes + size)) {
                LOGGER.warn("图片缓存预留拒绝: event=reserve_reject, reason=heap_margin, sizeBytes={}",
                    size);
                return null;
            }
            evictToTarget(capacity - reservedMemoryBytes - size);
            reservedMemoryBytes += size;
            reservation = new IncomingReservation(this, size);
        } finally {
            writeLock.unlock();
        }
        scheduleDrain();
        return reservation;
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

        boolean accepted;
        writeLock.lock();
        try {
            boolean hadReservation = consumeReservation(reservation);
            long capacity = memoryCapacityBytes();
            if (size > capacity || reservedMemoryBytes + size > capacity) {
                LOGGER.warn("图片缓存写入拒绝: event=reject, reason=capacity, sizeBytes={}, capacityBytes={}",
                    size, capacity);
                return false;
            }

            long unaccountedBytes = hadReservation ? reservedMemoryBytes : reservedMemoryBytes + size;
            if (!evictForHeapMargin(unaccountedBytes)) {
                LOGGER.warn("图片缓存写入拒绝: event=reject, reason=heap_margin, sizeBytes={}", size);
                return false;
            }

            // 若 key 已存在，先移除旧的
            ImageEntry old = cache.remove(key);
            if (old != null) {
                memoryUsedBytes -= old.data.length;
            }

            evictToTarget(capacity - reservedMemoryBytes - size);

            ImageEntry entry = new ImageEntry(data, mimeType);
            cache.put(key, entry);
            memoryUsedBytes += size;
            evictIfNeeded();
            accepted = cache.containsKey(key);
        } finally {
            writeLock.unlock();
        }
        scheduleDrain();
        return accepted;
    }

    public ImageEntry get(String key) {
        writeLock.lock();
        try {
            ImageEntry entry = cache.get(key);
            LOGGER.debug("图片缓存{}: key={}", entry == null ? "未命中" : "命中", safeKey(key));
            return entry;
        } finally {
            writeLock.unlock();
        }
    }

    DiskImageCache.Entry getDisk(String key) {
        DiskImageCache disk = awaitDiskCache();
        return disk == null ? null : disk.get(key);
    }

    private boolean promoteFromDisk(String key, DiskImageCache.Entry diskEntry) {
        IncomingReservation reservation = prepareForIncomingBytes(diskEntry.size);
        if (reservation == null) {
            diskEntry.close();
            return false;
        }
        try (diskEntry; reservation) {
            byte[] data = diskEntry.readBytes();
            if (!put(key, data, diskEntry.mimeType, reservation)) return false;
            DiskImageCache disk = diskCache;
            if (disk != null) disk.removeKey(key, diskEntry);
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    public List<DiskImageCache.Info> getDiskEntries() {
        DiskImageCache disk = awaitDiskCache();
        return disk == null ? java.util.Collections.emptyList() : disk.snapshot();
    }

    /** 获取磁盘冷层图片 payload 使用量（字节）。 */
    public long getDiskUsedBytes() {
        DiskImageCache disk = awaitDiskCache();
        return disk == null ? 0L : disk.payloadBytes();
    }

    public CacheCapacityPolicy.SpaceSnapshot getSpaceSnapshot() {
        DiskImageCache disk = awaitDiskCache();
        if (disk == null) return CacheCapacityPolicy.SpaceSnapshot.unavailable();
        long available = disk.availableBytes();
        return available < 0L ? CacheCapacityPolicy.SpaceSnapshot.unavailable()
            : new CacheCapacityPolicy.SpaceSnapshot(available, disk.usedBytes(), true);
    }

    public boolean has(String key) {
        writeLock.lock();
        try {
            if (cache.containsKey(key)) return true;
        } finally {
            writeLock.unlock();
        }
        DiskImageCache disk = awaitDiskCache();
        return disk != null && disk.has(key);
    }

    /**
     * 返回当前缓存条目的只读元数据快照，不触碰 LRU 访问顺序。
     */
    public List<CacheEntryInfo> getEntriesSnapshot() {
        readLock.lock();
        try {
            List<CacheEntryInfo> entries = new ArrayList<>(cache.size());
            for (Map.Entry<String, ImageEntry> entry : cache.entrySet()) {
                ImageEntry value = entry.getValue();
                entries.add(new CacheEntryInfo(entry.getKey(), value.data.length, value.mimeType));
            }
            return entries;
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
            memoryUsedBytes = 0;
            pendingSpills.clear();
            LOGGER.info("图片缓存清理完成: event=clear");
        } finally {
            writeLock.unlock();
        }
        DiskImageCache disk = awaitDiskCache();
        if (disk != null) {
            synchronized (spillLock) {
                disk.clear();
            }
        }
    }

    /** 系统内存压力只释放热层，保留可重启恢复的冷层。 */
    public void clearMemory() {
        writeLock.lock();
        try {
            cache.clear();
            memoryUsedBytes = 0;
            if ("failed".equals(getDiskState())) pendingSpills.clear();
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
            int removed = 0;
            var it = cache.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                if (entry.getKey().startsWith(prefix)) {
                    memoryUsedBytes -= entry.getValue().data.length;
                    it.remove();
                    removed++;
                }
            }
            pendingSpills.removeIf(entry -> entry.getKey().startsWith(prefix));
            LOGGER.info("图片缓存前缀清理完成: event=clear_prefix, prefix={}, removed={}",
                safeKey(prefix), removed);
        } finally {
            writeLock.unlock();
        }
        DiskImageCache disk = awaitDiskCache();
        if (disk != null) {
            synchronized (spillLock) {
                disk.removeByPrefix(prefix);
            }
        }
    }

    // ---- 淘汰 ----

    private void evictIfNeeded() {
        evictToTarget(Math.max(0L, memoryCapacityBytes() - reservedMemoryBytes));
    }

    private void evictToTarget(long target) {
        if (memoryUsedBytes <= target) return;
        long before = memoryUsedBytes;
        int removed = 0;
        var it = cache.entrySet().iterator();
        while (it.hasNext() && memoryUsedBytes > target) {
            var entry = it.next();
            memoryUsedBytes -= entry.getValue().data.length;
            pendingSpills.add(Map.entry(entry.getKey(), entry.getValue()));
            it.remove();
            removed++;
        }
        LOGGER.debug("图片缓存淘汰完成: event=evict, removed={}, freedBytes={}",
            removed, before - memoryUsedBytes);
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
            memoryUsedBytes -= len;
            pendingSpills.add(Map.entry(entry.getKey(), entry.getValue()));
            freed += len;
            it.remove();
        }
        LOGGER.debug("图片缓存堆压力淘汰: event=heap_evict, freedBytes={}, needBytes={}",
            freed, needToFree);
        return true;
    }

    private boolean consumeReservation(IncomingReservation reservation) {
        if (reservation == null || reservation.owner != this || !reservation.open) return false;
        reservedMemoryBytes -= reservation.size;
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
     * 供内存压力回调使用，不改变持久化的内存热层容量。
     */
    public void trimToFraction(double fraction) {
        writeLock.lock();
        try {
            long target = (long) (memoryCapacityBytes() * fraction);
            if (memoryUsedBytes <= target) return;
            var it = cache.entrySet().iterator();
            while (it.hasNext() && memoryUsedBytes > target) {
                var entry = it.next();
                pendingSpills.add(Map.entry(entry.getKey(), entry.getValue()));
                memoryUsedBytes -= entry.getValue().data.length;
                it.remove();
            }
        } finally {
            writeLock.unlock();
        }
        scheduleDrain();
    }

    private void drainPendingSpills() {
        synchronized (spillLock) {
            DiskImageCache disk = diskCache;
            if (disk == null) {
                if ("failed".equals(getDiskState())) {
                    writeLock.lock();
                    try {
                        pendingSpills.clear();
                    } finally {
                        writeLock.unlock();
                    }
                }
                return;
            }
            List<Map.Entry<String, ImageEntry>> batch;
            writeLock.lock();
            try {
                if (pendingSpills.isEmpty()) return;
                batch = new ArrayList<>(pendingSpills);
                pendingSpills.clear();
            } finally {
                writeLock.unlock();
            }
            for (Map.Entry<String, ImageEntry> entry : batch) {
                disk.put(entry.getKey(), entry.getValue().data, entry.getValue().mimeType);
            }
        }
    }

    private void scheduleDrain() {
        DiskImageCache disk = diskCache;
        if (disk == null || !hasPendingSpills()) return;
        if (!drainScheduled.compareAndSet(false, true)) return;
        diskExecutor.execute(() -> {
            try {
                drainPendingSpills();
            } finally {
                drainScheduled.set(false);
                scheduleDrain();
            }
        });
    }

    private boolean hasPendingSpills() {
        writeLock.lock();
        try {
            return !pendingSpills.isEmpty();
        } finally {
            writeLock.unlock();
        }
    }

    /** 删除指定键的磁盘冷层条目，用于覆盖重试结果。 */
    public void removeDisk(String key) {
        DiskImageCache disk = awaitDiskCache();
        if (disk != null) {
            synchronized (spillLock) {
                disk.removeKey(key);
            }
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
        long startedNanos = System.nanoTime();
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
                logResource("memory-hit", type, photoId, sortOrder, startedNanos, null);
                return new WebResourceResponse(
                    entry.mimeType,
                    "UTF-8",
                    new ByteArrayInputStream(entry.data)
                );
            }

            DiskImageCache.Entry diskEntry = getInstance().getDisk(cacheKey);
            if (diskEntry != null) {
                if (getInstance().promoteFromDisk(cacheKey, diskEntry)) {
                    ImageEntry promoted = getInstance().get(cacheKey);
                    logResource("disk-promote", type, photoId, sortOrder, startedNanos, null);
                    return new WebResourceResponse(promoted.mimeType, "UTF-8",
                        new ByteArrayInputStream(promoted.data));
                }
                diskEntry = getInstance().getDisk(cacheKey);
                if (diskEntry == null) return null;
                logResource("disk-hit", type, photoId, sortOrder, startedNanos, null);
                return new WebResourceResponse(diskEntry.mimeType, null, diskEntry.open());
            }

            // 2. 缓存 miss → 依次：原图内存缓存 → FileStore
            if ("thumb".equals(type)) {
                // 2a. 查原图内存缓存（从内存生成缩略图，最快）
                ImageEntry original = getInstance().get(photoId + "/" + sortOrder);
                if (original != null) {
                    byte[] thumbData = createThumbnail(original.data);
                    getInstance().put(cacheKey, thumbData, "image/jpeg");
                    logResource("thumbnail-memory", type, photoId, sortOrder, startedNanos, null);
                    return new WebResourceResponse("image/jpeg", "UTF-8",
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
                        logResource("file-thumbnail", type, photoId, sortOrder, startedNanos, null);
                        return new WebResourceResponse("image/jpeg", "UTF-8",
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
                        logResource("file-hit", type, photoId, sortOrder, startedNanos, null);
                        return new WebResourceResponse(mime, "UTF-8",
                            new ByteArrayInputStream(data));
                    }
                }
            }

            // 3. 仍未找到 → 返回 null（在线场景等待 preloadImages 下载/FileStore 兜底）
            logResource("miss", type, photoId, sortOrder, startedNanos, null);
            return null;
        } catch (Exception e) {
            LOGGER.debug("图片缓存资源读取失败: event=failed, errorClass={}",
                e.getClass().getSimpleName());
            return null;
        }
    }

    private static void logResource(String event, String type, String photoId, int sortOrder,
                                    long startedNanos, Throwable error) {
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        LOGGER.debug("图片缓存资源: event={}, type={}, photoId={}, sortOrder={}, elapsedMs={}, errorClass={}",
            event, type, photoId, sortOrder, elapsedMs,
            error == null ? "" : error.getClass().getSimpleName());
    }

    private static String safeKey(String value) {
        if (value == null) return "";
        return value.length() <= 96 ? value : value.substring(0, 96);
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
            return new WebResourceResponse(
                "image/" + guessFormatName(header),
                null,
                input
            );
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

    public static class CacheEntryInfo {
        public final String key;
        public final long sizeBytes;
        public final String mimeType;

        CacheEntryInfo(String key, long sizeBytes, String mimeType) {
            this.key = key;
            this.sizeBytes = sizeBytes;
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
        /** 冷热两层合计的有效容量（MiB）。 */
        public final long effectiveMb;
        /** 冷热两层合计的图片 payload 使用量（字节）。 */
        public final long currentBytes;
        /** 内存热层当前图片 payload 使用量（字节）。 */
        public final long memoryBytes;
        public final long reservedBytes;
        public final long maxHeapMb;
        public final double safeRatio;
        public final String pressureLevel;
        public final boolean temporaryClamp;
        public final String reason;
        /** 内存热层容量（MiB）。 */
        public final long memoryCapacityMb;
        /** 磁盘冷层容量（MiB）。 */
        public final long diskCapacityMb;
        /** 磁盘冷层图片 payload 使用量（字节）。 */
        public final long diskUsedBytes;
        public final long diskReserveMb;
        public final String diskState;

        CacheStats(CacheCapacityPolicy.Result result, long currentBytes, long reservedBytes,
                   long diskBytes, String diskState) {
            this.requestedMb = result.requestedMb;
            this.effectiveMb = result.effectiveMb;
            this.memoryBytes = currentBytes;
            this.currentBytes = currentBytes + diskBytes;
            this.reservedBytes = reservedBytes;
            this.maxHeapMb = result.maxHeapMb;
            this.safeRatio = result.safeRatio;
            this.pressureLevel = result.pressureLevel.getValue();
            this.temporaryClamp = result.temporaryClamp;
            this.reason = result.reason;
            this.memoryCapacityMb = result.memoryCapacityMb;
            this.diskCapacityMb = result.diskCapacityMb;
            this.diskUsedBytes = diskBytes;
            this.diskReserveMb = CacheCapacityPolicy.DISK_RESERVE_MB;
            this.diskState = diskState;
        }
    }
}
