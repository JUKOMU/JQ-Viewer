package io.github.jukomu.runtime;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class ClientSessionTest {

    @Test
    public void unavailableNetworkDoesNotCreateClient() {
        AtomicInteger factoryCalls = new AtomicInteger();
        RecordingObserver observer = new RecordingObserver();
        ClientSession<Object> session = new ClientSession<>(() -> {
            factoryCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new Object());
        }, observer);

        session.updateEnvironment("offline", false);

        assertEquals(0, factoryCalls.get());
        assertNull(session.getClient());
        assertEquals("unavailable", session.getSnapshot().state());
        assertEquals("no_network", session.getSnapshot().reason());
    }

    @Test
    public void sameEnvironmentAttemptsOnlyOnceButNewEnvironmentRetries() {
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicReference<CompletableFuture<Object>> future = new AtomicReference<>();
        RecordingObserver observer = new RecordingObserver();
        ClientSession<Object> session = new ClientSession<>(() -> {
            factoryCalls.incrementAndGet();
            CompletableFuture<Object> next = new CompletableFuture<>();
            future.set(next);
            return next;
        }, observer);

        session.updateEnvironment("wifi", true);
        session.updateEnvironment("wifi", true);
        future.get().completeExceptionally(new IllegalStateException("blocked"));
        session.updateEnvironment("wifi", true);
        session.updateEnvironment("vpn", true);

        assertEquals(2, factoryCalls.get());
        assertEquals("initializing", session.getSnapshot().state());
        assertEquals(1, observer.failureCount);
    }

    @Test
    public void successfulAttemptPublishesOnlyReadyClient() {
        CompletableFuture<Object> future = new CompletableFuture<>();
        RecordingObserver observer = new RecordingObserver();
        ClientSession<Object> session = new ClientSession<>(() -> future, observer);
        Object client = new Object();

        session.updateEnvironment("vpn", true);
        assertNull(session.getClient());
        future.complete(client);

        assertSame(client, session.getClient());
        assertSame(client, observer.readyClient);
        assertEquals(List.of("initializing", "ready"), observer.states);
    }

    @Test
    public void changedEnvironmentDuringAttemptIsRetriedAfterFailure() {
        List<CompletableFuture<Object>> futures = new ArrayList<>();
        AtomicInteger factoryCalls = new AtomicInteger();
        RecordingObserver observer = new RecordingObserver();
        ClientSession<Object> session = new ClientSession<>(() -> {
            factoryCalls.incrementAndGet();
            CompletableFuture<Object> future = new CompletableFuture<>();
            futures.add(future);
            return future;
        }, observer);

        session.updateEnvironment("wifi|epoch:1", true);
        session.updateEnvironment("wifi|epoch:3", true);
        futures.get(0).completeExceptionally(new IllegalStateException("blocked"));

        assertEquals(2, factoryCalls.get());
        assertEquals("initializing", session.getSnapshot().state());
    }

    private static final class RecordingObserver implements ClientSession.Observer<Object> {
        private final List<String> states = new ArrayList<>();
        private Object readyClient;
        private int failureCount;

        @Override
        public void onStateChanged(ClientSession.Snapshot snapshot) {
            states.add(snapshot.state());
        }

        @Override
        public void onReady(Object client) {
            readyClient = client;
        }

        @Override
        public void onFailure(Throwable error) {
            failureCount++;
        }
    }
}
