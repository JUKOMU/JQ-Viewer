package io.github.jukomu.desktop.feature.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.desktop.feature.image.model.ImageEvent;
import io.github.jukomu.jmcomic.api.client.JmClient;
import io.github.jukomu.jmcomic.api.model.JmImage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageServiceSchedulerTest {
    @Test
    void higherPriorityPreemptsAndRequeuesValidTaskWithoutPublishingDetachedResult() throws Exception {
        CountDownLatch lowStarted = new CountDownLatch(1);
        CountDownLatch releaseLow = new CountDownLatch(1);
        AtomicInteger lowCalls = new AtomicInteger();
        byte[] imageBytes = png();
        JmClient client = client(image -> {
            if (image.getFilename().equals("low.png") && lowCalls.incrementAndGet() == 1) {
                lowStarted.countDown();
                awaitIgnoringInterrupt(releaseLow);
            }
            return imageBytes;
        });
        ExecutorService concurrency = Executors.newFixedThreadPool(1);
        EventHub events = new EventHub(new ObjectMapper());
        ImageService service = new ImageService(client, concurrency, events);
        List<ImageEvent> ready = new CopyOnWriteArrayList<>();
        CountDownLatch highReady = new CountDownLatch(1);
        events.subscribe("imageReady", payload -> {
            ImageEvent event = (ImageEvent) payload;
            ready.add(event);
            if ("high-session".equals(event.sessionId())) highReady.countDown();
        });

        try {
            service.preload("photo", "image", List.of(image(1, "low.png")), false,
                "low-session", 100);
            assertTrue(lowStarted.await(2, TimeUnit.SECONDS));
            service.preload("photo", "image", List.of(image(2, "high.png")), false,
                "high-session", 300);
            assertTrue(highReady.await(2, TimeUnit.SECONDS));
            assertEquals(1, ready.size());

            releaseLow.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (lowCalls.get() < 2 && System.nanoTime() < deadline) Thread.sleep(10);

            assertEquals(2, lowCalls.get());
            assertTrue(service.cache().contains("photo/2/image"));
            assertTrue(service.cache().contains("photo/1/image"));
            assertEquals(List.of("high-session", "low-session"),
                ready.stream().map(ImageEvent::sessionId).toList());
        } finally {
            releaseLow.countDown();
            concurrency.shutdownNow();
            events.close();
        }
    }

    @Test
    void cancellingSessionDropsLateResultAndEvent() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        byte[] imageBytes = png();
        JmClient client = client(image -> {
            started.countDown();
            awaitIgnoringInterrupt(release);
            return imageBytes;
        });
        ExecutorService concurrency = Executors.newFixedThreadPool(1);
        EventHub events = new EventHub(new ObjectMapper());
        ImageService service = new ImageService(client, concurrency, events);
        List<ImageEvent> ready = new CopyOnWriteArrayList<>();
        List<ImageEvent> failed = new CopyOnWriteArrayList<>();
        events.subscribe("imageReady", payload -> ready.add((ImageEvent) payload));
        events.subscribe("imageFailed", payload -> failed.add((ImageEvent) payload));

        try {
            service.preload("photo", "image", List.of(image(1, "one.png")), false,
                "reader-session", 300);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            service.cancelPreload("reader-session");
            release.countDown();
            Thread.sleep(100);

            assertFalse(service.cache().contains("photo/1/image"));
            assertTrue(ready.isEmpty());
            assertTrue(failed.isEmpty());
        } finally {
            release.countDown();
            concurrency.shutdownNow();
            events.close();
        }
    }

    private static JmClient client(ImageFetcher fetcher) {
        return (JmClient) Proxy.newProxyInstance(
            JmClient.class.getClassLoader(), new Class<?>[]{JmClient.class},
            (proxy, method, args) -> method.getName().equals("fetchImageBytes")
                ? fetcher.fetch((JmImage) args[0])
                : null);
    }

    private static JmImage image(int sortOrder, String filename) {
        return new JmImage("photo", "1", filename, "https://example.invalid/" + filename,
            "", sortOrder);
    }

    private static byte[] png() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean released = false;
        while (!released) {
            try {
                released = latch.await(20, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                // 模拟底层网络调用在中断后仍未立即返回。
            }
        }
    }

    @FunctionalInterface
    private interface ImageFetcher {
        byte[] fetch(JmImage image);
    }
}
