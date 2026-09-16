package io.github.jukomu.desktop.bridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.sse.SseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** 通过一个具名 SSE 通道向前端发布 JSON 事件。 */
public final class EventHub implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventHub.class);

    private final ObjectMapper mapper;
    private final Set<SseClient> clients = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Set<Consumer<Object>>> listeners =
            new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private boolean closed;

    public EventHub(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void connect(SseClient client) {
        synchronized (lifecycleLock) {
            if (closed) {
                closeClient(client);
                return;
            }
            clients.add(client);
            client.onClose(() -> clients.remove(client));
            client.keepAlive();
        }
    }

    public void publish(String event, Object payload) {
        for (Consumer<Object> listener : listeners.getOrDefault(event, Set.of())) {
            try {
                listener.accept(payload);
            } catch (RuntimeException exception) {
                LOGGER.warn("内部事件处理失败: {}", event, exception);
            }
        }

        final String data;
        try {
            data = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            LOGGER.warn("事件序列化失败: {}", event, exception);
            return;
        }

        for (SseClient client : clients) {
            try {
                client.sendEvent(event, data);
            } catch (RuntimeException exception) {
                clients.remove(client);
                closeClient(client);
            }
        }
    }

    public AutoCloseable subscribe(String event, Consumer<Object> listener) {
        if (event == null || event.isBlank()) throw new IllegalArgumentException("event不能为空");
        if (listener == null) throw new IllegalArgumentException("listener不能为空");
        synchronized (lifecycleLock) {
            if (closed) throw new IllegalStateException("事件中心已关闭");
            listeners.computeIfAbsent(event, ignored -> ConcurrentHashMap.newKeySet())
                    .add(listener);
        }
        return () -> {
            Set<Consumer<Object>> eventListeners = listeners.get(event);
            if (eventListeners == null) return;
            eventListeners.remove(listener);
            if (eventListeners.isEmpty()) listeners.remove(event, eventListeners);
        };
    }

    @Override
    public void close() {
        Set<SseClient> closingClients;
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            closingClients = Set.copyOf(clients);
            clients.clear();
            listeners.clear();
        }
        closingClients.forEach(EventHub::closeClient);
    }

    private static void closeClient(SseClient client) {
        try {
            client.close();
        } catch (RuntimeException ignored) {
            // 连接已失效时无需再次上报关闭异常。
        }
    }
}
