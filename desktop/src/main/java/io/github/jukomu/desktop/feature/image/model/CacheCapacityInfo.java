package io.github.jukomu.desktop.feature.image.model;

/** 图片缓存容量、占用与限制原因。 */
public record CacheCapacityInfo(
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
