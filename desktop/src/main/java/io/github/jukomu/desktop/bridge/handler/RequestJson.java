package io.github.jukomu.desktop.bridge.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.http.Context;

/** 读取并校验 Desktop bridge 的 JSON 请求参数。 */
public final class RequestJson {
    private RequestJson() {
    }

    public static ObjectNode body(Context context) {
        JsonNode body = context.bodyAsClass(JsonNode.class);
        if (!(body instanceof ObjectNode object)) {
            throw new IllegalArgumentException("request body must be a JSON object");
        }
        return object;
    }

    public static ObjectNode object(ObjectNode body, String name) {
        JsonNode value = body.get(name);
        if (!(value instanceof ObjectNode object)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return object;
    }

    public static String requiredText(ObjectNode body, String name) {
        String value = text(body, name, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    public static String text(ObjectNode body, String name, String fallback) {
        JsonNode value = body.get(name);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }

    public static int integer(ObjectNode body, String name, int fallback) {
        JsonNode value = body.get(name);
        if (value == null || value.isNull()) return fallback;
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return value.intValue();
    }

    public static long longValue(ObjectNode body, String name, long fallback) {
        JsonNode value = body.get(name);
        if (value == null || value.isNull()) return fallback;
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return value.longValue();
    }

    public static Long nullableLong(ObjectNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException(name + " must be an integer or null");
        }
        return value.longValue();
    }

    public static boolean requiredBoolean(ObjectNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.booleanValue();
    }
}
