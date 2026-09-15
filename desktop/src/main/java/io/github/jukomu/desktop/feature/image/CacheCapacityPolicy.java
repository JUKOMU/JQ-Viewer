package io.github.jukomu.desktop.feature.image;

/** 根据 JVM heap 上限计算 Desktop 图片缓存的安全容量。 */
public final class CacheCapacityPolicy {
    public static final long MIB = 1024L * 1024L;
    public static final long DEFAULT_REQUESTED_MB = 256L;
    public static final long MIN_EFFECTIVE_MB = 16L;

    private static final double SAFE_RATIO = 0.65;

    public Result calculate(long requestedMb, long maxHeapBytes) {
        long maxHeapMb = maxHeapBytes > 0 ? maxHeapBytes / MIB : 0L;
        if (maxHeapBytes <= 0L) {
            return new Result(requestedMb, MIN_EFFECTIVE_MB, maxHeapMb, 0.0,
                    true, "invalid-heap-fallback");
        }

        long heapBudgetMb = (long) Math.floor((maxHeapBytes * SAFE_RATIO) / MIB);
        long effectiveMb = Math.max(MIN_EFFECTIVE_MB, Math.min(requestedMb, heapBudgetMb));
        String reason;
        if (effectiveMb == MIN_EFFECTIVE_MB
                && (requestedMb < MIN_EFFECTIVE_MB || heapBudgetMb < MIN_EFFECTIVE_MB)) {
            reason = "minimum-safe-capacity";
        } else if (effectiveMb < requestedMb) {
            reason = "heap-budget";
        } else {
            reason = "requested-limit";
        }
        return new Result(requestedMb, effectiveMb, maxHeapMb, SAFE_RATIO, false, reason);
    }

    public record Result(
            long requestedMb,
            long effectiveMb,
            long maxHeapMb,
            double safeRatio,
            boolean temporaryClamp,
            String reason
    ) {
    }
}
