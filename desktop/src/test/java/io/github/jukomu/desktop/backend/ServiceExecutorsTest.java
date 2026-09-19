package io.github.jukomu.desktop.backend;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceExecutorsTest {
    @Test
    void imagePreloadConfigurationDoesNotResizeOrBlockApiExecutor() throws Exception {
        ThreadPoolExecutor api = new ThreadPoolExecutor(
                1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4));
        CountDownLatch preloadStarted = new CountDownLatch(4);
        CountDownLatch releasePreload = new CountDownLatch(1);

        try (ServiceExecutors executors = new ServiceExecutors(api)) {
            executors.configureImagePreload(4);
            for (int index = 0; index < 4; index++) {
                executors.imagePreload().execute(() -> {
                    preloadStarted.countDown();
                    await(releasePreload);
                });
            }

            assertTrue(preloadStarted.await(2, TimeUnit.SECONDS));
            assertEquals(1, api.getCorePoolSize());
            assertEquals(4,
                    ((ThreadPoolExecutor) executors.imagePreload()).getCorePoolSize());
            assertEquals("api-ready", executors.api().submit(() -> "api-ready")
                    .get(1, TimeUnit.SECONDS));
            assertEquals("image-ready", executors.imageOnDemand().submit(() -> "image-ready")
                    .get(1, TimeUnit.SECONDS));

            releasePreload.countDown();
        } finally {
            releasePreload.countDown();
        }

        assertTrue(api.isShutdown());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
