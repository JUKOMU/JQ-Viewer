package io.github.jukomu.desktop.feature.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.desktop.feature.image.model.ImageEvent;
import io.github.jukomu.jmcomic.api.client.JmClient;
import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.api.model.JmPhoto;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageServiceTest {
    private static final JmImage IMAGE = new JmImage(
        "photo", "1", "001.png", "https://example.invalid/001.png", "", 1);

    @Test
    void resourceReadSharesPreloadFetchAndRetryWaitsForCachedResult() throws Exception {
        byte[] imageBytes = imageBytes();
        CountDownLatch firstFetchStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstFetch = new CountDownLatch(1);
        CountDownLatch retryFetchStarted = new CountDownLatch(1);
        CountDownLatch releaseRetryFetch = new CountDownLatch(1);
        AtomicInteger fetchCount = new AtomicInteger();
        JmClient client = client(imageBytes, fetchCount, firstFetchStarted, releaseFirstFetch,
            retryFetchStarted, releaseRetryFetch);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        EventHub events = new EventHub(new ObjectMapper());
        CountDownLatch thumbReady = new CountDownLatch(1);
        AtomicReference<ImageEvent> thumbReadyEvent = new AtomicReference<>();
        AutoCloseable subscription = events.subscribe("imageReady", payload -> {
            if (payload instanceof ImageEvent event && "thumb".equals(event.type())) {
                thumbReadyEvent.set(event);
                thumbReady.countDown();
            }
        });
        ImageService service = new ImageService(client, executor, events);

        try {
            service.preload("photo", "thumb", List.of(IMAGE), false, "preview", 100);
            assertTrue(firstFetchStarted.await(1, TimeUnit.SECONDS));
            CountDownLatch readStarted = new CountDownLatch(1);
            CompletableFuture<ImageCache.Entry> reader = CompletableFuture.supplyAsync(
                () -> {
                    readStarted.countDown();
                    return service.read("photo", 1, "image");
                });
            assertTrue(readStarted.await(1, TimeUnit.SECONDS));
            Thread.sleep(50L);
            assertFalse(reader.isDone());
            releaseFirstFetch.countDown();

            assertArrayEquals(imageBytes, reader.get(1, TimeUnit.SECONDS).bytes());
            assertArrayEquals(imageBytes, service.cache().get("photo/1/image").bytes());
            assertNotNull(service.cache().get("photo/1/thumb"));
            assertTrue(thumbReady.await(1, TimeUnit.SECONDS));
            assertEquals("preview", thumbReadyEvent.get().sessionId());
            assertEquals(1, fetchCount.get());

            CompletableFuture<Void> retry = CompletableFuture.runAsync(
                () -> service.retry("photo", IMAGE));
            assertTrue(retryFetchStarted.await(1, TimeUnit.SECONDS));
            assertFalse(retry.isDone());
            assertNull(service.cache().get("photo/1/image"));
            releaseRetryFetch.countDown();
            retry.get(1, TimeUnit.SECONDS);
            assertArrayEquals(imageBytes, service.cache().get("photo/1/image").bytes());
            assertEquals(2, fetchCount.get());
        } finally {
            releaseFirstFetch.countDown();
            releaseRetryFetch.countDown();
            service.close();
            subscription.close();
            events.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeRejectsNewReadsAndSuppressesLatePreloadEvents() throws Exception {
        byte[] imageBytes = imageBytes();
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        AtomicInteger eventsPublished = new AtomicInteger();
        AtomicInteger fetchCount = new AtomicInteger();
        JmClient client = client(imageBytes, fetchCount, fetchStarted, releaseFetch,
            new CountDownLatch(0), new CountDownLatch(0));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        EventHub events = new EventHub(new ObjectMapper());
        AutoCloseable subscription = events.subscribe("imageReady", ignored -> eventsPublished.incrementAndGet());
        ImageService service = new ImageService(client, executor, events);

        try {
            service.preload("photo", "image", List.of(IMAGE), false, "reader", 200);
            assertTrue(fetchStarted.await(1, TimeUnit.SECONDS));
            service.close();
            assertThrows(ApiException.class, () -> service.read("photo", 1, "image"));
            releaseFetch.countDown();
            Thread.sleep(50L);
            assertEquals(0, eventsPublished.get());
        } finally {
            releaseFetch.countDown();
            service.close();
            subscription.close();
            events.close();
            executor.shutdownNow();
        }
    }

    private static JmClient client(
        byte[] imageBytes,
        AtomicInteger fetchCount,
        CountDownLatch firstFetchStarted,
        CountDownLatch releaseFirstFetch,
        CountDownLatch secondFetchStarted,
        CountDownLatch releaseSecondFetch
    ) {
        return (JmClient) Proxy.newProxyInstance(JmClient.class.getClassLoader(),
            new Class<?>[]{JmClient.class}, (proxy, method, arguments) -> switch (method.getName()) {
                case "getPhoto" -> new JmPhoto("photo", "Photo", "album", "1", 1,
                    "Author", List.of(), List.of(IMAGE), false);
                case "fetchImageBytes" -> {
                    int count = fetchCount.incrementAndGet();
                    if (count == 1) {
                        firstFetchStarted.countDown();
                        releaseFirstFetch.await();
                    } else {
                        secondFetchStarted.countDown();
                        releaseSecondFetch.await();
                    }
                    yield imageBytes;
                }
                case "toString" -> "ImageServiceTestClient";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> throw new AssertionError("Unexpected JM client call: " + method.getName());
            });
    }

    private static byte[] imageBytes() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
