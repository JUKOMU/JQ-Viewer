package io.github.jukomu.feature.update;

import org.junit.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 只验证总时限触发断开，以及请求结束后取消定时任务。
 */
public class RequestDeadlineTest {
    @Test(timeout = 5_000)
    public void deadlineDisconnectsTheRequest() throws Exception {
        TestConnection connection = new TestConnection();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try (RequestDeadline deadline = new RequestDeadline(scheduler, connection, 50)) {
            assertTrue(connection.disconnected.await(2, TimeUnit.SECONDS));
            assertTrue(deadline.expired());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test(timeout = 5_000)
    public void completedRequestCancelsTheDeadline() throws Exception {
        TestConnection connection = new TestConnection();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try (RequestDeadline deadline = new RequestDeadline(scheduler, connection, 100)) {
            deadline.close();
            assertFalse(connection.disconnected.await(300, TimeUnit.MILLISECONDS));
            assertFalse(deadline.expired());
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static final class TestConnection extends HttpURLConnection {
        private final CountDownLatch disconnected = new CountDownLatch(1);

        private TestConnection() throws Exception {
            super(new URL("http://127.0.0.1/update"));
        }

        @Override
        public void disconnect() {
            disconnected.countDown();
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }
    }
}
