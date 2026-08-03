package io.github.jukomu.service;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DownloadForegroundStateTest {

    @Test
    public void publishesCountAndRevisionFromTheSameTransition() {
        DownloadForegroundState state = new DownloadForegroundState();
        List<DownloadForegroundState.Snapshot> snapshots = new ArrayList<>();

        state.start("a", snapshots::add);
        state.start("b", snapshots::add);
        state.stop("a", snapshots::add);
        state.stop("b", snapshots::add);

        assertSnapshot(snapshots.get(0), 1, 1);
        assertSnapshot(snapshots.get(1), 2, 2);
        assertSnapshot(snapshots.get(2), 3, 1);
        assertSnapshot(snapshots.get(3), 4, 0);
    }

    @Test
    public void oldStopCannotRemoveANewerActiveTask() {
        DownloadForegroundState state = new DownloadForegroundState();
        List<DownloadForegroundState.Snapshot> snapshots = new ArrayList<>();

        state.start("old", snapshots::add);
        state.stop("old", snapshots::add);
        state.start("new", snapshots::add);
        state.stop("old", snapshots::add);

        assertEquals(3, snapshots.size());
        assertSnapshot(snapshots.get(2), 3, 1);
    }

    @Test
    public void serializesConcurrentStartAndStopPublication() throws Exception {
        DownloadForegroundState state = new DownloadForegroundState();
        List<DownloadForegroundState.Snapshot> snapshots =
            Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Thread first = transitionThread(state, "a", snapshots, ready, start);
        Thread second = transitionThread(state, "b", snapshots, ready, start);
        first.start();
        second.start();
        ready.await();
        start.countDown();
        first.join();
        second.join();

        assertEquals(4, snapshots.size());
        for (int i = 0; i < snapshots.size(); i++) {
            assertEquals(i + 1, snapshots.get(i).revision);
            assertTrue(snapshots.get(i).activeCount >= 0);
            assertTrue(snapshots.get(i).activeCount <= 2);
        }
        assertEquals(0, snapshots.get(snapshots.size() - 1).activeCount);
    }

    private static Thread transitionThread(DownloadForegroundState state, String taskId,
            List<DownloadForegroundState.Snapshot> snapshots, CountDownLatch ready,
            CountDownLatch start) {
        return new Thread(() -> {
            ready.countDown();
            await(start);
            state.start(taskId, snapshots::add);
            state.stop(taskId, snapshots::add);
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void assertSnapshot(DownloadForegroundState.Snapshot snapshot, int revision,
            int activeCount) {
        assertEquals(revision, snapshot.revision);
        assertEquals(activeCount, snapshot.activeCount);
    }
}
