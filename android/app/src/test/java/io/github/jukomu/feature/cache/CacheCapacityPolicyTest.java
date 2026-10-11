package io.github.jukomu.feature.cache;

import org.junit.Test;

import static org.junit.Assert.*;

public class CacheCapacityPolicyTest {

    private final CacheCapacityPolicy policy = new CacheCapacityPolicy();

    @Test
    public void capsNormalDeviceAtSixtyFivePercentOfMaxHeap() {
        CacheCapacityPolicy.Result result = calculate(1024, 512, false,
            CacheCapacityPolicy.PressureLevel.NORMAL, CacheCapacityPolicy.SpaceSnapshot.unavailable());

        assertEquals(128, result.effectiveMb);
        assertEquals(128, result.memoryCapacityMb);
        assertEquals(512, result.maxHeapMb);
        assertEquals(0.65, result.safeRatio, 0.0001);
        assertEquals("disk-unavailable", result.reason);
        assertFalse(result.temporaryClamp);
    }

    @Test
    public void usesLowerBudgetForLowRamDevice() {
        CacheCapacityPolicy.Result result = calculate(1024, 512, true,
            CacheCapacityPolicy.PressureLevel.NORMAL, CacheCapacityPolicy.SpaceSnapshot.unavailable());

        assertEquals(128, result.effectiveMb);
        assertEquals(0.45, result.safeRatio, 0.0001);
        assertEquals("disk-unavailable", result.reason);
    }

    @Test
    public void appliesTemporaryPressureClampWithoutChangingRequestedValue() {
        CacheCapacityPolicy.Result result = calculate(1024, 512, false,
            CacheCapacityPolicy.PressureLevel.RUNNING_LOW, CacheCapacityPolicy.SpaceSnapshot.unavailable());

        assertEquals(1024, result.requestedMb);
        assertEquals(128, result.effectiveMb);
        assertEquals(0.35, result.safeRatio, 0.0001);
        assertEquals("disk-unavailable", result.reason);
        assertTrue(result.temporaryClamp);
    }

    @Test
    public void pressureLevelsMatchTrimPersistenceSemantics() {
        assertFalse(CacheCapacityPolicy.PressureLevel.NORMAL.clampsCapacity());
        assertFalse(CacheCapacityPolicy.PressureLevel.RUNNING_MODERATE.clampsCapacity());
        assertTrue(CacheCapacityPolicy.PressureLevel.RUNNING_LOW.clampsCapacity());
        assertTrue(CacheCapacityPolicy.PressureLevel.RUNNING_CRITICAL.clampsCapacity());
        assertFalse(CacheCapacityPolicy.PressureLevel.UI_HIDDEN.clampsCapacity());
        assertTrue(CacheCapacityPolicy.PressureLevel.BACKGROUND.clampsCapacity());
        assertTrue(CacheCapacityPolicy.PressureLevel.MODERATE.clampsCapacity());
        assertTrue(CacheCapacityPolicy.PressureLevel.COMPLETE.clampsCapacity());

        assertTrue(calculate(1024, 512, false,
            CacheCapacityPolicy.PressureLevel.BACKGROUND, CacheCapacityPolicy.SpaceSnapshot.unavailable()).temporaryClamp);
        assertTrue(calculate(1024, 512, false,
            CacheCapacityPolicy.PressureLevel.MODERATE, CacheCapacityPolicy.SpaceSnapshot.unavailable()).temporaryClamp);
    }

    @Test
    public void keepsRequestedValueWhenItFitsHeapBudget() {
        CacheCapacityPolicy.Result result = calculate(100, 512, false,
            CacheCapacityPolicy.PressureLevel.NORMAL,
            new CacheCapacityPolicy.SpaceSnapshot(1024L * CacheCapacityPolicy.MIB, 0L, true));

        assertEquals(100, result.effectiveMb);
        assertEquals("requested-limit", result.reason);
    }

    @Test
    public void keepsTinyHeapWithinBudgetAndRejectsInvalidHeap() {
        CacheCapacityPolicy.Result tiny = calculate(1024, 8, false,
            CacheCapacityPolicy.PressureLevel.NORMAL, CacheCapacityPolicy.SpaceSnapshot.unavailable());

        assertEquals(5, tiny.effectiveMb);
        assertEquals("disk-unavailable", tiny.reason);
        assertThrows(IllegalStateException.class, () -> policy.calculate(1024, 0, false,
            CacheCapacityPolicy.PressureLevel.NORMAL, CacheCapacityPolicy.SpaceSnapshot.unavailable()));
    }

    @Test
    public void calculatesAndroidHotAndColdLayersFromSpaceSnapshot() {
        CacheCapacityPolicy.Result result = policy.calculate(512, 512L * CacheCapacityPolicy.MIB,
            false, CacheCapacityPolicy.PressureLevel.NORMAL,
            new CacheCapacityPolicy.SpaceSnapshot(600L * CacheCapacityPolicy.MIB,
                200L * CacheCapacityPolicy.MIB, true));
        assertEquals(128, result.memoryCapacityMb);
        assertEquals(384, result.diskTargetMb);
        assertEquals(300, result.diskCapacityMb);
        assertEquals(428, result.effectiveMb);
        assertEquals("disk-space", result.reason);
    }

    private CacheCapacityPolicy.Result calculate(long requestedMb, long maxHeapMb,
                                                 boolean lowRam,
                                                 CacheCapacityPolicy.PressureLevel level,
                                                 CacheCapacityPolicy.SpaceSnapshot space) {
        return policy.calculate(requestedMb, maxHeapMb * CacheCapacityPolicy.MIB, lowRam, level,
            space);
    }
}
