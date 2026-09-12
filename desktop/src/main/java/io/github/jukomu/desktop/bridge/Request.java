package io.github.jukomu.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import io.javalin.http.Context;

import java.io.IOException;
import java.util.Collection;

/** 解析并校验 bridge 请求体中的 JSON 参数。 */
public final class Request {
    private Request() {
    }

    public static void requireObject(Context context, ObjectMapper mapper) {
        parseObject(context, mapper);
    }

    public static <T> T body(Context context, ObjectMapper mapper, Class<T> type) {
        JsonNode parsed = parseObject(context, mapper);
        try {
            return mapper.treeToValue(parsed, type);
        } catch (MismatchedInputException exception) {
            throw ApiException.invalidRequest(typeError(exception));
        } catch (IOException exception) {
            throw ApiException.invalidRequest("请求参数类型不正确");
        }
    }

    private static JsonNode parseObject(Context context, ObjectMapper mapper) {
        try {
            JsonNode parsed = mapper.readTree(context.body());
            if (parsed == null || !parsed.isObject()) {
                throw ApiException.invalidRequest("请求体必须是 JSON 对象");
            }
            return parsed;
        } catch (IOException exception) {
            throw ApiException.invalidRequest("请求体不是有效的 JSON");
        }
    }

    public static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw ApiException.invalidRequest(name + "不能为空");
        }
        return value;
    }

    public static int integer(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    public static long longValue(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    public static boolean bool(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    private static String typeError(MismatchedInputException exception) {
        String field = null;
        boolean indexedValue = false;
        for (JsonMappingException.Reference reference : exception.getPath()) {
            if (reference.getFieldName() != null) field = reference.getFieldName();
            if (reference.getIndex() >= 0) indexedValue = true;
        }
        if (field == null) return "请求参数类型不正确";

        Class<?> target = exception.getTargetType();
        if (target == String.class) return field + "必须是字符串";
        if (target == Integer.class || target == int.class
                || target == Long.class || target == long.class) {
            return field + "必须是整数";
        }
        if (target == Boolean.class || target == boolean.class) {
            return field + "必须是布尔值";
        }
        if (target != null && Collection.class.isAssignableFrom(target)) {
            return field + "必须是数组";
        }
        return indexedValue ? field + "包含无效元素" : field + "必须是对象";
    }
}
