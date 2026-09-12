package io.github.jukomu.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.http.Context;

import java.io.IOException;

/** 解析并校验 bridge 请求体中的 JSON 参数。 */
public final class Request {
    private Request() {
    }

    public static ObjectNode object(Context context, ObjectMapper mapper) {
        try {
            JsonNode parsed = mapper.readTree(context.body());
            if (parsed == null || !parsed.isObject()) {
                throw new ApiException("bad-request", 400, "请求体必须是 JSON 对象");
            }
            return (ObjectNode) parsed;
        } catch (IOException exception) {
            throw new ApiException("bad-request", 400, "请求体不是有效的 JSON");
        }
    }

    public static String requiredText(ObjectNode request, String name) {
        JsonNode value = request.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new ApiException("bad-request", 400, name + "不能为空");
        }
        return value.textValue();
    }

    public static String text(ObjectNode request, String name, String fallback) {
        JsonNode value = request.get(name);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isTextual()) {
            throw new ApiException("bad-request", 400, name + "必须是字符串");
        }
        return value.textValue();
    }

    public static int integer(ObjectNode request, String name, int fallback) {
        JsonNode value = request.get(name);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.canConvertToInt() || !value.isIntegralNumber()) {
            throw new ApiException("bad-request", 400, name + "必须是整数");
        }
        return value.intValue();
    }

    public static long longValue(ObjectNode request, String name, long fallback) {
        JsonNode value = request.get(name);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.canConvertToLong() || !value.isIntegralNumber()) {
            throw new ApiException("bad-request", 400, name + "必须是整数");
        }
        return value.longValue();
    }

    public static boolean bool(ObjectNode request, String name, boolean fallback) {
        JsonNode value = request.get(name);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isBoolean()) {
            throw new ApiException("bad-request", 400, name + "必须是布尔值");
        }
        return value.booleanValue();
    }

    public static ArrayNode array(ObjectNode request, String name) {
        JsonNode value = request.get(name);
        if (value == null || !value.isArray()) {
            throw new ApiException("bad-request", 400, name + "必须是数组");
        }
        return (ArrayNode) value;
    }
}
