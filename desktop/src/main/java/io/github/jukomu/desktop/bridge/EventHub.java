package io.github.jukomu.desktop.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.sse.SseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 通过一个具名 SSE 通道向前端发布 JSON 事件。
 */
public final class EventHub implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventHub.class);

    private final ObjectMapper mapper;
    private final Set<SseClient> clients = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Set<Consumer<Object>>> listeners =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> retainedEvents = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private boolean closed;

    public EventHub(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void connect(SseClient client) {
        synchronized (lifecycleLock) {
            if (closed) {
                LOGGER.debug("bridge sse connect rejected status=closed");
                closeClient(client);
                return;
            }
            clients.add(client);
            LOGGER.debug("bridge sse client connected clients={}", clients.size());
            client.onClose(() -> {
                clients.remove(client);
                LOGGER.debug("bridge sse client closed clients={}", clients.size());
            });
            try {
                client.keepAlive();
            } catch (RuntimeException failure) {
                clients.remove(client);
                LOGGER.warn("bridge sse keepalive failed errorType={}",
                    failure.getClass().getSimpleName());
                closeClient(client);
                throw failure;
            }
            try {
                for (var event : retainedEvents.entrySet()) {
                    client.sendEvent(event.getKey(), event.getValue());
                }
            } catch (RuntimeException failure) {
                clients.remove(client);
                LOGGER.warn("bridge sse retained event send failed errorType={}",
                    failure.getClass().getSimpleName());
                closeClient(client);
            }
        }
    }

    public void publish(String event, Object payload) {
        publish(event, payload, false);
    }

    public void publishRetained(String event, Object payload) {
        publish(event, payload, true);
    }

    private void publish(String event, Object payload, boolean retain) {
        for (Consumer<Object> listener : listeners.getOrDefault(event, Set.of())) {
            try {
                listener.accept(payload);
            } catch (RuntimeException exception) {
                LOGGER.warn("bridge event listener failed event={} errorType={}",
                    event, exception.getClass().getSimpleName());
            }
        }

        final String data;
        try {
            data = mapper.writeValueAsString(payload);
        } catch (Exception exception) {
            LOGGER.warn("bridge event serialization failed event={} errorType={}",
                event, exception.getClass().getSimpleName());
            return;
        }
        synchronized (lifecycleLock) {
            if (closed) {
                LOGGER.debug("bridge event publish skipped event={} status=closed", event);
                return;
            }
            if (retain) retainedEvents.put(event, data);

            for (SseClient client : clients) {
                try {
                    client.sendEvent(event, data);
                } catch (RuntimeException exception) {
                    clients.remove(client);
                    LOGGER.warn("bridge event send failed event={} clients={} errorType={}",
                        event, clients.size(), exception.getClass().getSimpleName());
                    closeClient(client);
                }
            }
        }
    }

    public AutoCloseable subscribe(String event, Consumer<Object> listener) {
        if (event == null || event.isBlank()) throw new IllegalArgumentException("event不能为空");
        if (listener == null) throw new IllegalArgumentException("listener不能为空");
        synchronized (lifecycleLock) {
            if (closed) throw new IllegalStateException("事件中心已关闭");
            listeners.compute(event, (ignored, eventListeners) -> {
                Set<Consumer<Object>> current = eventListeners == null
                    ? ConcurrentHashMap.newKeySet()
                    : eventListeners;
                current.add(listener);
                return current;
            });
        }
        return () -> listeners.computeIfPresent(event, (ignored, eventListeners) -> {
            eventListeners.remove(listener);
            return eventListeners.isEmpty() ? null : eventListeners;
        });
    }

    @Override
    public void close() {
        Set<SseClient> closingClients;
        synchronized (lifecycleLock) {
            if (closed) {
                LOGGER.debug("bridge event hub close skipped status=closed");
                return;
            }
            closed = true;
            closingClients = Set.copyOf(clients);
            clients.clear();
            listeners.clear();
            retainedEvents.clear();
        }
        LOGGER.info("bridge event hub closed clients={}", closingClients.size());
        closingClients.forEach(EventHub::closeClient);
    }

    private static void closeClient(SseClient client) {
        try {
            client.close();
        } catch (RuntimeException failure) {
            LOGGER.debug("bridge sse client close failed errorType={}",
                failure.getClass().getSimpleName());
        }
    }
}
