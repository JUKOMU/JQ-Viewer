package io.github.jukomu.feature.update;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UpdateRaceStateTest {

    @Test
    public void exactThresholdSelectsOneWinner() {
        UpdateRaceState state = new UpdateRaceState();

        assertTrue(state.trySelectWinner(UpdateRaceState.Source.GITHUB,
            UpdateRaceState.RACE_THRESHOLD_BYTES));
        assertFalse(state.trySelectWinner(UpdateRaceState.Source.GITEE,
            UpdateRaceState.RACE_THRESHOLD_BYTES));
        assertEquals(UpdateRaceState.Source.GITHUB, state.getWinner());
    }

    @Test
    public void sourceFailureBeforeThresholdLeavesOtherSourceEligible() {
        UpdateRaceState state = new UpdateRaceState();

        assertFalse(state.trySelectWinner(UpdateRaceState.Source.GITHUB, 1024L));
        assertFalse(state.sourceFinished(false));
        assertTrue(state.trySelectWinner(UpdateRaceState.Source.GITEE,
            UpdateRaceState.RACE_THRESHOLD_BYTES));
        assertEquals(UpdateRaceState.Source.GITEE, state.getWinner());
    }

    @Test
    public void firstSmallCompletedSourceBecomesWinner() {
        UpdateRaceState state = new UpdateRaceState();

        assertFalse(state.sourceFinished(UpdateRaceState.Source.GITHUB, true));
        assertEquals(UpdateRaceState.Source.GITHUB, state.getWinner());
        assertFalse(state.trySelectWinner(UpdateRaceState.Source.GITEE,
            UpdateRaceState.RACE_THRESHOLD_BYTES));
    }

    @Test
    public void concurrentThresholdCrossingHasOneWinner() throws Exception {
        UpdateRaceState state = new UpdateRaceState();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        Thread github = selectInParallel(state, UpdateRaceState.Source.GITHUB, ready, start, winners);
        Thread gitee = selectInParallel(state, UpdateRaceState.Source.GITEE, ready, start, winners);
        github.start();
        gitee.start();
        ready.await();
        start.countDown();
        github.join();
        gitee.join();

        assertEquals(1, winners.get());
        assertTrue(state.getWinner() != null);
    }

    @Test
    public void cancellationPreventsWinnerSelection() {
        UpdateRaceState state = new UpdateRaceState();
        state.cancel();

        assertFalse(state.trySelectWinner(UpdateRaceState.Source.GITHUB,
            UpdateRaceState.RACE_THRESHOLD_BYTES));
        assertTrue(state.isCancelled());
    }

    private static Thread selectInParallel(UpdateRaceState state, UpdateRaceState.Source source,
                                           CountDownLatch ready, CountDownLatch start,
                                           AtomicInteger winners) {
        return new Thread(() -> {
            ready.countDown();
            try {
                start.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
            if (state.trySelectWinner(source, UpdateRaceState.RACE_THRESHOLD_BYTES)) {
                winners.incrementAndGet();
            }
        });
    }
}
