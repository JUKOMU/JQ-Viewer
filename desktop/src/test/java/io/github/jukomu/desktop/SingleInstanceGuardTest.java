package io.github.jukomu.desktop;

import io.github.jukomu.desktop.host.SingleInstanceGuard;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleInstanceGuardTest {
    @Test
    void secondGuardSignalsPrimaryAndLockIsReleased() throws Exception {
        Path lockPath = Files.createTempDirectory("jq-viewer-instance-").resolve("instance.lock");
        CountDownLatch openHome = new CountDownLatch(1);
        SingleInstanceGuard primary = new SingleInstanceGuard(lockPath);
        SingleInstanceGuard secondary = new SingleInstanceGuard(lockPath);

        assertTrue(primary.tryAcquire(openHome::countDown));
        assertTrue(primary.isOwner());
        assertTrue(primary.ipcPort() > 0);
        assertFalse(secondary.tryAcquire(() -> {
        }));
        assertTrue(secondary.notifyExistingInstance());
        assertTrue(openHome.await(2, TimeUnit.SECONDS));

        secondary.close();
        primary.close();
        primary.close();

        SingleInstanceGuard replacement = new SingleInstanceGuard(lockPath);
        try {
            assertTrue(replacement.tryAcquire(() -> {
            }));
        } finally {
            replacement.close();
        }
    }
}
