package io.github.jukomu.runtime;

import org.junit.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ServiceExecutorsTest {

    @Test
    public void fixedExecutorUsesNamedThreadsAndUnboundedQueue() throws Exception {
        ThreadPoolExecutor executor = ServiceExecutors.fixed("test-service", 2);
        try {
            assertTrue(executor.getQueue() instanceof LinkedBlockingQueue);
            assertEquals(Integer.MAX_VALUE, executor.getQueue().remainingCapacity());
            assertEquals("jq-viewer-test-service-1",
                executor.submit(() -> Thread.currentThread().getName()).get(1, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void scheduledExecutorUsesUnboundedQueueAndRemovesCancelledTasks() {
        ScheduledThreadPoolExecutor executor = ServiceExecutors.scheduled("test-timeout", 1);
        try {
            assertEquals(Integer.MAX_VALUE, executor.getQueue().remainingCapacity());
            assertTrue(executor.getRemoveOnCancelPolicy());
        } finally {
            executor.shutdownNow();
        }
    }
}
