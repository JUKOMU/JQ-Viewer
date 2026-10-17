package io.github.jukomu.desktop;

import io.github.jukomu.desktop.host.SingleInstanceGuard;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleInstanceGuardTest {
    @Test
    void secondGuardSignalsPrimaryAndLockIsReleased() throws Exception {
        Path lockPath = Files.createTempDirectory("jq-viewer-instance-").resolve("instance.lock");
        CountDownLatch openHome = new CountDownLatch(1);
        try (SingleInstanceGuard primary = new SingleInstanceGuard(lockPath);
             SingleInstanceGuard secondary = new SingleInstanceGuard(lockPath)) {
            assertTrue(primary.tryAcquire(openHome::countDown));
            assertTrue(primary.isOwner());
            assertTrue(primary.ipcPort() > 0);
            assertFalse(secondary.tryAcquire(() -> {
            }));
            assertTrue(secondary.notifyExistingInstance());
            assertTrue(openHome.await(2, TimeUnit.SECONDS));
        }

        assertTrue(Files.isRegularFile(lockPath));

        SingleInstanceGuard replacement = new SingleInstanceGuard(lockPath);
        try {
            assertTrue(replacement.tryAcquire(() -> {
            }));
        } finally {
            replacement.close();
        }
    }

    @Test
    void timesOutIncompleteSignalAndContinuesServingIpc() throws Exception {
        Path lockPath = Files.createTempDirectory("jq-viewer-instance-timeout-").resolve("instance.lock");
        CountDownLatch openHome = new CountDownLatch(1);

        try (SingleInstanceGuard primary = new SingleInstanceGuard(lockPath)) {
            assertTrue(primary.tryAcquire(openHome::countDown));
            try (Socket hanging = connect(primary)) {
                assertFalse(openHome.await(1_500, TimeUnit.MILLISECONDS));
                assertTrue(primary.notifyExistingInstance());
                assertTrue(openHome.await(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void rejectsOversizedSignalAndContinuesServingIpc() throws Exception {
        Path lockPath = Files.createTempDirectory("jq-viewer-instance-length-").resolve("instance.lock");
        AtomicInteger openHomeCalls = new AtomicInteger();

        try (SingleInstanceGuard primary = new SingleInstanceGuard(lockPath)) {
            assertTrue(primary.tryAcquire(openHomeCalls::incrementAndGet));
            try (Socket socket = connect(primary)) {
                socket.getOutputStream().write(
                        "OPEN_HOME_TOO_LONG\n".getBytes(StandardCharsets.UTF_8)
                );
                socket.getOutputStream().flush();
            }
            Thread.sleep(250);
            assertEquals(0, openHomeCalls.get());
            assertTrue(primary.notifyExistingInstance());
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (openHomeCalls.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(1, openHomeCalls.get());
        }
    }

    private static Socket connect(SingleInstanceGuard guard) throws IOException {
        Socket socket = new Socket();
        socket.connect(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), guard.ipcPort()),
                1_000
        );
        return socket;
    }
}
