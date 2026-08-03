package io.github.jukomu.data;

/**
 * 根据进程 heap 上限、设备类型和当前压力状态计算图片缓存的实际容量。
 */
public final class CacheCapacityPolicy {

    public static final long MIB = 1024L * 1024L;
    public static final long DEFAULT_REQUESTED_MB = 256L;
    public static final long MIN_EFFECTIVE_MB = 16L;

    private static final double NORMAL_SAFE_RATIO = 0.65;
    private static final double LOW_RAM_SAFE_RATIO = 0.45;
    private static final double PRESSURE_SAFE_RATIO = 0.35;

    public Result calculate(long requestedMb, long maxHeapBytes, boolean lowRam,
                            PressureLevel pressureLevel) {
        PressureLevel level = pressureLevel == null ? PressureLevel.NORMAL : pressureLevel;
        long maxHeapMb = maxHeapBytes > 0 ? maxHeapBytes / MIB : 0L;
        if (maxHeapBytes <= 0L) {
            return new Result(requestedMb, MIN_EFFECTIVE_MB, maxHeapMb, 0.0,
                level, true, "invalid-heap-fallback");
        }

        double baseRatio = lowRam ? LOW_RAM_SAFE_RATIO : NORMAL_SAFE_RATIO;
        boolean temporaryClamp = level.clampsCapacity();
        double safeRatio = temporaryClamp ? Math.min(baseRatio, PRESSURE_SAFE_RATIO) : baseRatio;
        long heapBudgetMb = (long) Math.floor((maxHeapBytes * safeRatio) / MIB);
        long effectiveMb = Math.max(MIN_EFFECTIVE_MB, Math.min(requestedMb, heapBudgetMb));

        String reason;
        if (effectiveMb == MIN_EFFECTIVE_MB && (requestedMb < MIN_EFFECTIVE_MB
            || heapBudgetMb < MIN_EFFECTIVE_MB)) {
            reason = "minimum-safe-capacity";
        } else if (temporaryClamp && effectiveMb < requestedMb) {
            reason = "memory-pressure";
        } else if (effectiveMb < requestedMb) {
            reason = lowRam ? "low-ram-heap-budget" : "heap-budget";
        } else {
            reason = "requested-limit";
        }

        return new Result(requestedMb, effectiveMb, maxHeapMb, safeRatio,
            level, temporaryClamp, reason);
    }

    public enum PressureLevel {
        NORMAL("normal", false),
        RUNNING_MODERATE("running-moderate", false),
        RUNNING_LOW("running-low", true),
        RUNNING_CRITICAL("running-critical", true),
        UI_HIDDEN("ui-hidden", false),
        BACKGROUND("background", true),
        MODERATE("moderate", true),
        COMPLETE("complete", true);

        private final String value;
        private final boolean clampsCapacity;

        PressureLevel(String value, boolean clampsCapacity) {
            this.value = value;
            this.clampsCapacity = clampsCapacity;
        }

        public String getValue() {
            return value;
        }

        public boolean clampsCapacity() {
            return clampsCapacity;
        }
    }

    public static final class Result {
        public final long requestedMb;
        public final long effectiveMb;
        public final long maxHeapMb;
        public final double safeRatio;
        public final PressureLevel pressureLevel;
        public final boolean temporaryClamp;
        public final String reason;

        Result(long requestedMb, long effectiveMb, long maxHeapMb, double safeRatio,
               PressureLevel pressureLevel, boolean temporaryClamp, String reason) {
            this.requestedMb = requestedMb;
            this.effectiveMb = effectiveMb;
            this.maxHeapMb = maxHeapMb;
            this.safeRatio = safeRatio;
            this.pressureLevel = pressureLevel;
            this.temporaryClamp = temporaryClamp;
            this.reason = reason;
        }
    }
}
