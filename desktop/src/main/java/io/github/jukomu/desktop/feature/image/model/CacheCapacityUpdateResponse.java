package io.github.jukomu.desktop.feature.image.model;

/** 设置图片缓存容量后的完整状态。 */
public record CacheCapacityUpdateResponse(
        boolean success,
        long capacityMb,
        long usedMb,
        long requestedMb,
        long effectiveMb,
        long maxHeapMb,
        double safeRatio,
        String pressureLevel,
        boolean temporaryClamp,
        String limitReason
) {
}
