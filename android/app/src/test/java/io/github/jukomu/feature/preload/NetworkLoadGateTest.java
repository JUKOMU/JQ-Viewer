package io.github.jukomu.feature.preload;

import io.github.jukomu.feature.cache.CacheCapacityPolicy;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class NetworkLoadGateTest {

    @Test
    public void normalPressureUsesConfiguredConcurrency() throws Exception {
        NetworkLoadGate gate = new NetworkLoadGate(3);
        NetworkLoadGate.Permit first = gate.acquire(() -> false);
        NetworkLoadGate.Permit second = gate.acquire(() -> false);
        NetworkLoadGate.Permit third = gate.acquire(() -> false);

        AtomicReference<NetworkLoadGate.Permit> blocked = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try {
                blocked.set(gate.acquire(() -> false));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        });
        waiter.start();

        assertFalse(finished.await(100, TimeUnit.MILLISECONDS));
        third.close();
        assertTrue(finished.await(1, TimeUnit.SECONDS));
        assertNotNull(blocked.get());

        blocked.get().close();
        second.close();
        first.close();
    }

    @Test
    public void criticalPressureAllowsOnlyOneLoad() throws Exception {
        NetworkLoadGate gate = new NetworkLoadGate(2);
        gate.setPressureLevel(CacheCapacityPolicy.PressureLevel.RUNNING_CRITICAL);
        NetworkLoadGate.Permit first = gate.acquire(() -> false);
        assertNotNull(first);

        AtomicReference<NetworkLoadGate.Permit> second = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try {
                second.set(gate.acquire(() -> false));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        });
        waiter.start();

        assertFalse(finished.await(100, TimeUnit.MILLISECONDS));
        first.close();
        assertTrue(finished.await(1, TimeUnit.SECONDS));
        assertNotNull(second.get());
        second.get().close();
    }

    @Test
    public void completePressureRejectsNewLoadImmediately() throws Exception {
        NetworkLoadGate gate = new NetworkLoadGate(2);
        gate.setPressureLevel(CacheCapacityPolicy.PressureLevel.COMPLETE);

        assertNull(gate.acquire(() -> false));
    }

    @Test
    public void cancelledQueuedLoadReturnsNullWithoutLeakingPermit() throws Exception {
        NetworkLoadGate gate = new NetworkLoadGate(1);
        NetworkLoadGate.Permit active = gate.acquire(() -> false);
        assertNotNull(active);

        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<NetworkLoadGate.Permit> blocked = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try {
                blocked.set(gate.acquire(cancelled::get));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        });
        waiter.start();

        try {
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS));
            cancelled.set(true);
            assertTrue(finished.await(1, TimeUnit.SECONDS));
            assertNull(blocked.get());
            assertEquals(1, gate.getActiveLoads());

            active.close();
            assertEquals(0, gate.getActiveLoads());
        } finally {
            cancelled.set(true);
            gate.close();
            active.close();
            waiter.join(1_000L);
        }
    }

    @Test
    public void lowPressureUsesHalfOfConfiguredConcurrency() throws Exception {
        NetworkLoadGate gate = new NetworkLoadGate(5);
        gate.setPressureLevel(CacheCapacityPolicy.PressureLevel.RUNNING_LOW);
        NetworkLoadGate.Permit first = gate.acquire(() -> false);
        NetworkLoadGate.Permit second = gate.acquire(() -> false);
        NetworkLoadGate.Permit thirdPermit = gate.acquire(() -> false);
        assertNotNull(thirdPermit);

        AtomicReference<NetworkLoadGate.Permit> fourth = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        new Thread(() -> {
            try {
                fourth.set(gate.acquire(() -> false));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        }).start();

        assertFalse(finished.await(100, TimeUnit.MILLISECONDS));
        second.close();
        assertTrue(finished.await(1, TimeUnit.SECONDS));
        assertNotNull(fourth.get());
        fourth.get().close();
        thirdPermit.close();
        first.close();
    }
}
