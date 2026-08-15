package io.github.jukomu.feature.update;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UpdateForegroundServiceTest {

    @Test
    public void readyAndFailedAreTerminalNotificationPhases() {
        assertTrue(UpdateForegroundService.isTerminalPhase("ready_to_install"));
        assertTrue(UpdateForegroundService.isTerminalPhase("failed"));
        assertFalse(UpdateForegroundService.isTerminalPhase("verifying"));
    }
}
