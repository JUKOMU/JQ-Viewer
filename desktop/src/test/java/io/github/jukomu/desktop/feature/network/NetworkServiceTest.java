package io.github.jukomu.desktop.feature.network;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.feature.network.model.NetworkProbeEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkServiceTest {
    @Test
    void returnsCompleteSnapshotsAndTimeoutResults() {
        AtomicReference<Map<String, Integer>> states = new AtomicReference<>(linkedMap(
                "https://fast.invalid", 42,
                "https://dead.invalid", Integer.MAX_VALUE));
        NetworkService service = service(states::get, () -> linkedMap(
                "https://fast.invalid", 37,
                "https://dead.invalid", -1), () -> {
        }, Runnable::run, ignored -> {
        });

        var snapshot = service.getDomainStates();
        var latency = service.measureLatency();

        assertEquals(1, snapshot.alive());
        assertEquals(2, snapshot.total());
        assertFalse(snapshot.allDeadFallback());
        assertTrue(snapshot.domains().get(0).reachable());
        assertFalse(snapshot.domains().get(1).reachable());
        assertEquals(37, latency.results().get(0).latencyMs());
        assertFalse(latency.results().get(0).timedOut());
        assertEquals(0, latency.results().get(1).latencyMs());
        assertTrue(latency.results().get(1).timedOut());

        states.set(linkedMap(
                "https://fast.invalid", -1,
                "https://dead.invalid", -1));
        snapshot = service.getDomainStates();
        assertEquals(0, snapshot.alive());
        assertTrue(snapshot.allDeadFallback());
        assertTrue(snapshot.domains().stream().noneMatch(domain -> domain.reachable()));
    }

    @Test
    void mergesRepeatedReprobesAndPublishesOneResultSequence() {
        Queue<Runnable> tasks = new ArrayDeque<>();
        List<NetworkProbeEvent> events = new ArrayList<>();
        AtomicInteger reprobes = new AtomicInteger();
        NetworkService service = service(
                () -> Map.of("https://fast.invalid", 20),
                Map::of,
                reprobes::incrementAndGet,
                tasks::add,
                events::add);

        service.reprobeDomains();
        service.reprobeDomains();

        assertEquals(1, tasks.size());
        tasks.remove().run();
        assertEquals(1, reprobes.get());
        assertEquals(List.of("probing", "result"),
                events.stream().map(NetworkProbeEvent::phase).toList());
        assertEquals(1, events.get(1).alive());
    }

    @Test
    void publishesFailuresAndSuppressesEventsAfterClose() {
        List<NetworkProbeEvent> events = new ArrayList<>();
        NetworkService failed = service(
                Map::of,
                Map::of,
                () -> {
                    throw new IllegalStateException("offline");
                },
                Runnable::run,
                events::add);

        failed.reprobeDomains();
        assertEquals(List.of("probing", "error"),
                events.stream().map(NetworkProbeEvent::phase).toList());
        assertTrue(events.get(1).message().contains("offline"));

        Queue<Runnable> tasks = new ArrayDeque<>();
        events.clear();
        NetworkService closed = service(Map::of, Map::of, () -> {
        }, tasks::add, events::add);
        closed.reprobeDomains();
        closed.close();
        tasks.remove().run();

        assertTrue(events.isEmpty());
        assertEquals("unavailable",
                assertThrows(ApiException.class, closed::getDomainStates).code());
    }

    private static NetworkService service(
            java.util.function.Supplier<Map<String, Integer>> states,
            java.util.function.Supplier<Map<String, Integer>> latency,
            Runnable reprobe,
            java.util.concurrent.Executor executor,
            java.util.function.Consumer<NetworkProbeEvent> events
    ) {
        return new NetworkService(
                new NetworkService.Operations(states, latency, reprobe), executor, events);
    }

    private static Map<String, Integer> linkedMap(
            String firstKey,
            int firstValue,
            String secondKey,
            int secondValue
    ) {
        Map<String, Integer> values = new LinkedHashMap<>();
        values.put(firstKey, firstValue);
        values.put(secondKey, secondValue);
        return values;
    }
}
