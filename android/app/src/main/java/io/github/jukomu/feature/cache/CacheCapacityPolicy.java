package io.github.jukomu.feature.cache;

/**
 * 根据进程 heap 上限、设备类型和当前压力状态计算图片缓存的实际容量。
 */
public final class CacheCapacityPolicy {

    public static final long MIB = 1024L * 1024L;
    public static final long DEFAULT_REQUESTED_MB = 256L;
    public static final long MIN_EFFECTIVE_MB = 16L;
    public static final long HOT_LAYER_MAX_MB = 128L;
    public static final long DISK_RESERVE_MB = 500L;

    private static final double NORMAL_SAFE_RATIO = 0.65;
    private static final double LOW_RAM_SAFE_RATIO = 0.45;
    private static final double PRESSURE_SAFE_RATIO = 0.35;

    public Result calculate(long requestedMb, long maxHeapBytes, boolean lowRam,
                            PressureLevel pressureLevel, SpaceSnapshot space) {
        PressureLevel level = pressureLevel == null ? PressureLevel.NORMAL : pressureLevel;
        long maxHeapMb = maxHeapBytes > 0 ? maxHeapBytes / MIB : 0L;
        if (maxHeapBytes <= 0L) {
            throw new IllegalStateException("无法确定进程 heap 上限");
        }

        double baseRatio = lowRam ? LOW_RAM_SAFE_RATIO : NORMAL_SAFE_RATIO;
        boolean temporaryClamp = level.clampsCapacity();
        double safeRatio = temporaryClamp ? Math.min(baseRatio, PRESSURE_SAFE_RATIO) : baseRatio;
        long heapBudgetMb = (long) Math.floor((maxHeapBytes * safeRatio) / MIB);
        if (heapBudgetMb <= 0L) {
            throw new IllegalStateException("进程 heap 安全预算不足 1 MiB");
        }
        long hotLayerMb = heapBudgetMb < MIN_EFFECTIVE_MB
            ? heapBudgetMb
            : Math.max(MIN_EFFECTIVE_MB, Math.min(requestedMb, Math.min(HOT_LAYER_MAX_MB, heapBudgetMb)));
        long diskTargetMb = Math.max(0L, requestedMb - hotLayerMb);
        long diskAvailableMb = space.availableBytes > 0 && space.known
            ? safeMb(Math.max(0L, safeAdd(space.availableBytes, space.occupiedBytes) - DISK_RESERVE_MB * MIB))
            : 0L;
        long diskLayerMb = space.known ? Math.min(diskTargetMb, diskAvailableMb) : 0L;
        long effectiveMb = safeAdd(hotLayerMb, diskLayerMb);

        String reason;
        if (!space.known && diskTargetMb > 0) {
            reason = "disk-unavailable";
        } else if (diskLayerMb < diskTargetMb) {
            reason = "disk-space";
        } else if (effectiveMb == MIN_EFFECTIVE_MB && requestedMb < MIN_EFFECTIVE_MB) {
            reason = "minimum-safe-capacity";
        } else if (temporaryClamp && effectiveMb < requestedMb) {
            reason = "memory-pressure";
        } else if (effectiveMb < requestedMb) {
            reason = lowRam ? "low-ram-heap-budget" : "heap-budget";
        } else {
            reason = "requested-limit";
        }

        return new Result(requestedMb, effectiveMb, hotLayerMb, diskTargetMb, diskLayerMb,
            maxHeapMb, safeRatio, level, temporaryClamp, reason,
            space.known ? space.availableBytes : -1L);
    }

    private static long safeMb(long bytes) {
        return bytes / MIB;
    }

    private static long safeAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0 && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }

    public static final class SpaceSnapshot {
        public final long availableBytes;
        public final long occupiedBytes;
        public final boolean known;

        public SpaceSnapshot(long availableBytes, long occupiedBytes, boolean known) {
            this.availableBytes = Math.max(0L, availableBytes);
            this.occupiedBytes = Math.max(0L, occupiedBytes);
            this.known = known;
        }

        public static SpaceSnapshot unavailable() {
            return new SpaceSnapshot(0L, 0L, false);
        }
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
        public final long memoryCapacityMb;
        public final long diskTargetMb;
        public final long diskCapacityMb;
        public final long maxHeapMb;
        public final double safeRatio;
        public final PressureLevel pressureLevel;
        public final boolean temporaryClamp;
        public final String reason;
        public final long availableDiskBytes;

        Result(long requestedMb, long effectiveMb, long memoryCapacityMb, long diskTargetMb,
               long diskCapacityMb, long maxHeapMb, double safeRatio,
               PressureLevel pressureLevel, boolean temporaryClamp, String reason,
               long availableDiskBytes) {
            this.requestedMb = requestedMb;
            this.effectiveMb = effectiveMb;
            this.memoryCapacityMb = memoryCapacityMb;
            this.diskTargetMb = diskTargetMb;
            this.diskCapacityMb = diskCapacityMb;
            this.maxHeapMb = maxHeapMb;
            this.safeRatio = safeRatio;
            this.pressureLevel = pressureLevel;
            this.temporaryClamp = temporaryClamp;
            this.reason = reason;
            this.availableDiskBytes = availableDiskBytes;
        }
    }
}
