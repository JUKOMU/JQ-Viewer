package io.github.jukomu.desktop.feature.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportLoggingTest {
    @Test
    void progressLoggingUsesFiveSecondsOrTwentyFivePages() {
        long fiveSeconds = 5_000_000_000L;

        assertFalse(ExportService.shouldLogProgress(1_000_000_000L, 10,
            500_000_000L, 1, 100));
        assertTrue(ExportService.shouldLogProgress(1_000_000_000L, 26,
            500_000_000L, 1, 100));
        assertTrue(ExportService.shouldLogProgress(500_000_000L + fiveSeconds, 10,
            500_000_000L, 1, 100));
        assertTrue(ExportService.shouldLogProgress(1_000_000_000L, 1,
            500_000_000L, 1, 100));
        assertTrue(ExportService.shouldLogProgress(1_000_000_000L, 100,
            500_000_000L, 99, 100));
    }
}
