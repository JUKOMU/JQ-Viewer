package io.github.jukomu.desktop.feature.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.jmcomic.api.client.JmClient;
import io.github.jukomu.jmcomic.api.client.JmDownloadClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JmcomicSessionManagerTest {
    @Test
    void keepsClientNullableAfterFailureAndAllowsManualRetry() throws Exception {
        EventHub events = new EventHub(new ObjectMapper());
        List<JmcomicSessionManager.ClientStateSnapshot> published = new ArrayList<>();
        AutoCloseable subscription = events.subscribe("clientStateChanged",
                value -> published.add((JmcomicSessionManager.ClientStateSnapshot) value));
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        JmClient readyClient = client(closes);

        try (JmcomicSessionManager session = JmcomicSessionManager.managed(
                () -> attempts.incrementAndGet() == 1
                        ? CompletableFuture.failedFuture(new IllegalStateException("offline"))
                        : CompletableFuture.completedFuture(readyClient),
                events)) {
            session.startOrRetry();

            assertEquals(1, attempts.get());
            assertEquals("unavailable", session.getSnapshot().state());
            assertEquals("initialization_failed", session.getSnapshot().reason());
            assertThrows(ApiException.class, session::requireClient);

            session.startOrRetry();

            assertEquals(2, attempts.get());
            assertEquals("ready", session.getSnapshot().state());
            assertSame(readyClient, session.requireClient());
            assertNotNull(session.requireDownloadClient());
            assertEquals(List.of(
                    "initializing", "unavailable", "initializing", "ready"),
                    published.stream().map(
                            JmcomicSessionManager.ClientStateSnapshot::state).toList());
        } finally {
            subscription.close();
            events.close();
        }

        assertEquals(1, closes.get());
    }

    @Test
    void doesNotPublishFailedAttemptAfterANewerRetryStarts() throws Exception {
        EventHub events = new EventHub(new ObjectMapper());
        List<String> published = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch unavailableEntered = new CountDownLatch(1);
        CountDownLatch releaseUnavailable = new CountDownLatch(1);
        AutoCloseable subscription = events.subscribe("clientStateChanged", value -> {
            String state = ((JmcomicSessionManager.ClientStateSnapshot) value).state();
            if ("unavailable".equals(state)) {
                unavailableEntered.countDown();
                try {
                    assertTrue(releaseUnavailable.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
            published.add(state);
        });
        CompletableFuture<JmClient> firstAttempt = new CompletableFuture<>();
        CompletableFuture<JmClient> secondAttempt = new CompletableFuture<>();
        AtomicInteger attempts = new AtomicInteger();

        try (JmcomicSessionManager session = JmcomicSessionManager.managed(
                () -> attempts.incrementAndGet() == 1 ? firstAttempt : secondAttempt,
                events)) {
            session.startOrRetry();
            CompletableFuture<Void> failure = CompletableFuture.runAsync(() ->
                    firstAttempt.completeExceptionally(new IllegalStateException("offline")));
            assertTrue(unavailableEntered.await(5, TimeUnit.SECONDS));

            CompletableFuture<Void> retry = CompletableFuture.runAsync(session::startOrRetry);
            Thread.sleep(50);
            assertFalse(retry.isDone());
            releaseUnavailable.countDown();
            failure.get(5, TimeUnit.SECONDS);
            retry.get(5, TimeUnit.SECONDS);

            assertEquals(2, attempts.get());
            assertEquals("initializing", session.getSnapshot().state());
            assertEquals("initializing", published.get(published.size() - 1));
        } finally {
            releaseUnavailable.countDown();
            subscription.close();
            events.close();
        }
    }

    private static JmClient client(AtomicInteger closes) {
        return (JmClient) Proxy.newProxyInstance(
                JmClient.class.getClassLoader(),
                new Class<?>[]{JmClient.class, JmDownloadClient.class, AutoCloseable.class},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "SessionTestJmClient";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> throw new AssertionError(method.getName());
                        };
                    }
                    if ("close".equals(method.getName())) {
                        closes.incrementAndGet();
                        return null;
                    }
                    throw new AssertionError("未预期的客户端调用: " + method.getName());
                });
    }
}
