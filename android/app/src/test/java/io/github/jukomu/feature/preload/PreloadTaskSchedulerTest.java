package io.github.jukomu.feature.preload;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PreloadTaskSchedulerTest {

    @Test
    public void samePriorityTasksRunInSubmissionOrder() throws Exception {
        PreloadTaskScheduler scheduler = new PreloadTaskScheduler(1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(3);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());

        scheduler.submit("session", "scope", 100, attempt -> {
            firstStarted.countDown();
            awaitUninterruptibly(releaseFirst);
            order.add(1);
        }, completed::countDown, completed::countDown);
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        scheduler.submit("session", "scope", 100, attempt -> {
            order.add(2);
        }, completed::countDown, completed::countDown);
        scheduler.submit("session", "scope", 100, attempt -> {
            order.add(3);
        }, completed::countDown, completed::countDown);

        releaseFirst.countDown();

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(Arrays.asList(1, 2, 3), order);
    }

    @Test
    public void higherPriorityTaskStartsBeforeInterruptedWorkerExitsAndVictimRequeues()
        throws Exception {
        PreloadTaskScheduler scheduler = new PreloadTaskScheduler(1);
        CountDownLatch firstLowStarted = new CountDownLatch(1);
        CountDownLatch releaseOldWorker = new CountDownLatch(1);
        CountDownLatch highStarted = new CountDownLatch(1);
        CountDownLatch secondLowStarted = new CountDownLatch(1);
        AtomicInteger lowRuns = new AtomicInteger();

        scheduler.submit("low", "low-scope", 100, attempt -> {
            if (lowRuns.incrementAndGet() == 1) {
                firstLowStarted.countDown();
                awaitUninterruptibly(releaseOldWorker);
            } else {
                secondLowStarted.countDown();
            }
        }, () -> { }, () -> { });
        assertTrue(firstLowStarted.await(1, TimeUnit.SECONDS));

        scheduler.submit("high", "high-scope", 300, attempt -> highStarted.countDown(),
            () -> { }, () -> { });

        assertTrue(highStarted.await(1, TimeUnit.SECONDS));
        assertTrue(secondLowStarted.await(1, TimeUnit.SECONDS));
        releaseOldWorker.countDown();
        assertEquals(2, lowRuns.get());
    }

    @Test
    public void cancellingSessionDiscardsQueuedWorkAndDoesNotRequeueRunningWork()
        throws Exception {
        PreloadTaskScheduler scheduler = new PreloadTaskScheduler(1);
        CountDownLatch runningStarted = new CountDownLatch(1);
        CountDownLatch releaseRunning = new CountDownLatch(1);
        CountDownLatch discarded = new CountDownLatch(2);
        AtomicInteger runs = new AtomicInteger();

        scheduler.submit("session", "scope", 100, attempt -> {
            runs.incrementAndGet();
            runningStarted.countDown();
            awaitUninterruptibly(releaseRunning);
        }, () -> { }, discarded::countDown);
        assertTrue(runningStarted.await(1, TimeUnit.SECONDS));
        scheduler.submit("session", "scope", 100, attempt -> runs.incrementAndGet(),
            () -> { }, discarded::countDown);

        scheduler.cancelSession("session");
        releaseRunning.countDown();

        assertTrue(discarded.await(1, TimeUnit.SECONDS));
        assertEquals(1, runs.get());
    }

    @Test
    public void replacingScopeCancelsOldWorkAndFreesLogicalSlot() throws Exception {
        PreloadTaskScheduler scheduler = new PreloadTaskScheduler(1);
        CountDownLatch runningStarted = new CountDownLatch(1);
        CountDownLatch releaseOldWorker = new CountDownLatch(1);
        CountDownLatch discarded = new CountDownLatch(2);
        CountDownLatch replacementStarted = new CountDownLatch(1);
        AtomicInteger oldRuns = new AtomicInteger();

        scheduler.submit("reader", "reader/photo/image", 200, attempt -> {
            oldRuns.incrementAndGet();
            runningStarted.countDown();
            awaitUninterruptibly(releaseOldWorker);
        }, () -> { }, discarded::countDown);
        assertTrue(runningStarted.await(1, TimeUnit.SECONDS));
        scheduler.submit("reader", "reader/photo/image", 200,
            attempt -> oldRuns.incrementAndGet(), () -> { }, discarded::countDown);

        scheduler.cancelScope("reader/photo/image");
        scheduler.submit("reader", "reader/photo/image", 300,
            attempt -> replacementStarted.countDown(), () -> { }, () -> { });

        assertTrue(replacementStarted.await(1, TimeUnit.SECONDS));
        assertTrue(discarded.await(1, TimeUnit.SECONDS));
        releaseOldWorker.countDown();
        assertEquals(1, oldRuns.get());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException error) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
