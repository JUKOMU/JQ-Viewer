package io.github.jukomu.feature.download.notification;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DownloadForegroundServiceTest {

    @Test
    public void rejectsOnlyOlderRevisions() {
        assertTrue(DownloadForegroundService.isStaleRevision(4, 5));
        assertFalse(DownloadForegroundService.isStaleRevision(5, 5));
        assertFalse(DownloadForegroundService.isStaleRevision(6, 5));
    }
}
