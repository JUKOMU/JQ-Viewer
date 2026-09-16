package io.github.jukomu.desktop.feature.image;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.feature.image.model.CacheCapacityInfo;
import io.github.jukomu.desktop.feature.image.model.CacheCapacityUpdateResponse;
import io.github.jukomu.desktop.feature.image.model.ImageCacheContentsResponse;
import io.github.jukomu.desktop.feature.image.model.ImageCacheEntryResponse;
import io.github.jukomu.desktop.feature.pdf.render.PdfPageCache;
import io.github.jukomu.desktop.feature.settings.SettingsService;

import java.util.List;

/** 统一处理图片缓存容量、内容查询和缓存域清理。 */
public final class CacheService {
    private final SettingsService settings;
    private final ImageCache imageCache;
    private final PdfPageCache pdfPageCache;
    private final CacheCapacityPolicy capacityPolicy;
    private final long maxHeapBytes;
    private CacheCapacityPolicy.Result capacity;

    public CacheService(
            SettingsService settings,
            ImageCache imageCache,
            PdfPageCache pdfPageCache
    ) {
        this(settings, imageCache, pdfPageCache, new CacheCapacityPolicy(),
                Runtime.getRuntime().maxMemory());
    }

    CacheService(
            SettingsService settings,
            ImageCache imageCache,
            PdfPageCache pdfPageCache,
            CacheCapacityPolicy capacityPolicy,
            long maxHeapBytes
    ) {
        this.settings = settings;
        this.imageCache = imageCache;
        this.pdfPageCache = pdfPageCache;
        this.capacityPolicy = capacityPolicy;
        this.maxHeapBytes = maxHeapBytes;
        apply(settings.cacheCapacityMb());
    }

    public synchronized CacheCapacityUpdateResponse setCapacity(Integer requestedMb) {
        if (requestedMb == null || requestedMb < 64 || requestedMb > 1024) {
            throw ApiException.invalidRequest("mb must be between 64 and 1024");
        }
        settings.setCacheCapacityMb(requestedMb);
        apply(requestedMb);
        CacheCapacityInfo info = capacityInfo();
        return new CacheCapacityUpdateResponse(
                true,
                info.capacityMb(),
                info.usedMb(),
                info.requestedMb(),
                info.effectiveMb(),
                info.maxHeapMb(),
                info.safeRatio(),
                info.pressureLevel(),
                info.temporaryClamp(),
                info.limitReason()
        );
    }

    public synchronized CacheCapacityInfo capacityInfo() {
        return new CacheCapacityInfo(
                capacity.effectiveMb(),
                Math.round(imageCache.usedBytes() / (double) CacheCapacityPolicy.MIB),
                capacity.requestedMb(),
                capacity.effectiveMb(),
                capacity.maxHeapMb(),
                capacity.safeRatio(),
                "normal",
                capacity.temporaryClamp(),
                capacity.reason()
        );
    }

    public ImageCacheContentsResponse contents() {
        List<ImageCacheEntryResponse> entries = imageCache.snapshot().stream()
                .map(entry -> new ImageCacheEntryResponse(
                        entry.photoId(),
                        entry.sortOrder(),
                        entry.type(),
                        entry.sizeBytes(),
                        entry.mimeType()))
                .toList();
        return new ImageCacheContentsResponse(entries);
    }

    public void clear() {
        imageCache.clear();
        pdfPageCache.clear();
    }

    public List<ResourceSnapshot> diagnosticResources() {
        PdfPageCache.Stats pdfStats = pdfPageCache.stats();
        return List.of(
                new ResourceSnapshot(
                        "image-cache", "图片缓存", imageCache.snapshot().size(),
                        imageCache.usedBytes()),
                new ResourceSnapshot(
                        "pdf-page-cache", "PDF 页面缓存", pdfStats.entryCount(),
                        pdfStats.sizeBytes())
        );
    }

    private void apply(long requestedMb) {
        capacity = capacityPolicy.calculate(requestedMb, maxHeapBytes);
        imageCache.setCapacityBytes(capacity.effectiveMb() * CacheCapacityPolicy.MIB);
    }

    public record ResourceSnapshot(
            String kind,
            String label,
            int entryCount,
            long sizeBytes
    ) {
    }
}
