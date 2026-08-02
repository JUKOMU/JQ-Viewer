package io.github.jukomu.data;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CacheCapacityPolicyTest {

    private final CacheCapacityPolicy policy = new CacheCapacityPolicy();

    @Test
    public void capsNormalDeviceAtSixtyFivePercentOfMaxHeap() {
        CacheCapacityPolicy.Result result = calculate(1024, 512, false,
            CacheCapacityPolicy.PressureLevel.NORMAL);

        assertEquals(332, result.effectiveMb);
        assertEquals(512, result.maxHeapMb);
        assertEquals(0.65, result.safeRatio, 0.0001);
        assertEquals("heap-budget", result.reason);
        assertFalse(result.temporaryClamp);
    }

    @Test
    public void usesLowerBudgetForLowRamDevice() {
        CacheCapacityPolicy.Result result = calculate(1024, 512, true,
            CacheCapacityPolicy.PressureLevel.NORMAL);

        assertEquals(230, result.effectiveMb);
        assertEquals(0.45, result.safeRatio, 0.0001);
        assertEquals("low-ram-heap-budget", result.reason);
    }

    @Test
    public void appliesTemporaryPressureClampWithoutChangingRequestedValue() {
        CacheCapacityPolicy.Result result = calculate(1024, 512, false,
            CacheCapacityPolicy.PressureLevel.RUNNING_LOW);

        assertEquals(1024, result.requestedMb);
        assertEquals(179, result.effectiveMb);
        assertEquals(0.35, result.safeRatio, 0.0001);
        assertEquals("memory-pressure", result.reason);
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
            CacheCapacityPolicy.PressureLevel.BACKGROUND).temporaryClamp);
        assertTrue(calculate(1024, 512, false,
            CacheCapacityPolicy.PressureLevel.MODERATE).temporaryClamp);
    }

    @Test
    public void keepsRequestedValueWhenItFitsHeapBudget() {
        CacheCapacityPolicy.Result result = calculate(100, 512, false,
            CacheCapacityPolicy.PressureLevel.NORMAL);

        assertEquals(100, result.effectiveMb);
        assertEquals("requested-limit", result.reason);
    }

    @Test
    public void preservesMinimumCapacityForTinyOrInvalidHeap() {
        CacheCapacityPolicy.Result tiny = calculate(1024, 8, false,
            CacheCapacityPolicy.PressureLevel.NORMAL);
        CacheCapacityPolicy.Result invalid = policy.calculate(1024, 0, false,
            CacheCapacityPolicy.PressureLevel.NORMAL);

        assertEquals(16, tiny.effectiveMb);
        assertEquals("minimum-safe-capacity", tiny.reason);
        assertEquals(16, invalid.effectiveMb);
        assertEquals("invalid-heap-fallback", invalid.reason);
        assertTrue(invalid.temporaryClamp);
    }

    private CacheCapacityPolicy.Result calculate(long requestedMb, long maxHeapMb,
                                                  boolean lowRam,
                                                  CacheCapacityPolicy.PressureLevel level) {
        return policy.calculate(requestedMb, maxHeapMb * CacheCapacityPolicy.MIB, lowRam, level);
    }
}
