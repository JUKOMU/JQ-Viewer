package io.github.jukomu.feature.update;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UpdateForegroundServiceTest {

    @Test
    public void readyAndFailedAreTerminalNotificationPhases() {
        assertTrue(UpdateForegroundService.isTerminalPhase("ready_to_install"));
        assertTrue(UpdateForegroundService.isTerminalPhase("failed"));
        assertFalse(UpdateForegroundService.isTerminalPhase("verifying"));
    }

    @Test
    public void downloadContentShowsProgressAndSpeedWithoutSource() {
        long mib = 1024L * 1024L;

        String content = UpdateForegroundService.buildContent("selected", "GitHub",
            12L * mib, 8L * mib, 57L * mib, mib + mib / 2L, "");

        assertEquals("下载中 21%  12.0 / 57.0 MiB  1.5 MiB/s", content);
        assertFalse(content.contains("GitHub"));
        assertFalse(content.contains("Gitee"));
    }

    @Test
    public void downloadContentCapsDisplayedProgressAtTotal() {
        long mib = 1024L * 1024L;

        String content = UpdateForegroundService.buildContent("selected", "Gitee",
            24L * mib, 80L * mib, 57L * mib, 2L * mib, "");

        assertEquals("下载中 100%  57.0 / 57.0 MiB  2.0 MiB/s", content);
    }
}
