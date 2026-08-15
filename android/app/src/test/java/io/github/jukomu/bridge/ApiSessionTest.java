package io.github.jukomu.bridge;

import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ApiSessionTest {

    @Test
    public void destroyShutsDownApiExecutors() {
        ExecutorService apiExecutor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService timeoutExecutor =
            Executors.newSingleThreadScheduledExecutor();
        ApiSession session = new ApiSession(null, apiExecutor, timeoutExecutor);

        assertNotNull(session.getApiService());
        session.destroy();

        assertTrue(apiExecutor.isShutdown());
        assertTrue(timeoutExecutor.isShutdown());
    }
}
