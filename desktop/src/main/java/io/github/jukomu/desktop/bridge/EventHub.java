package io.github.jukomu.desktop.bridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.sse.SseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 通过一个具名 SSE 通道向前端发布 JSON 事件。 */
public final class EventHub implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventHub.class);

    private final ObjectMapper mapper;
    private final Set<SseClient> clients = ConcurrentHashMap.newKeySet();

    public EventHub(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void connect(SseClient client) {
        clients.add(client);
        client.onClose(() -> clients.remove(client));
        client.keepAlive();
    }

    public void publish(String event, Object payload) {
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
                try {
                    client.close();
                } catch (RuntimeException ignored) {
                    // 连接已经失效时无需再次上报关闭异常。
                }
            }
        }
    }

    @Override
    public void close() {
        for (SseClient client : clients) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // 连接已断开时忽略关闭异常。
            }
        }
        clients.clear();
    }
}
