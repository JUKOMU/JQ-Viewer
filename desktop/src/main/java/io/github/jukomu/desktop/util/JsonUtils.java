package io.github.jukomu.desktop.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

public final class JsonUtils {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtils() {
    }

    public static String toJsonString(Object value, String errorMessage) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(errorMessage, exception);
        }
    }

    public static <T> T fromJson(String json, TypeReference<T> type) throws IOException {
        return MAPPER.readValue(json, type);
    }

    public static List<String> parseJsonStringList(String json) throws IOException {
        return fromJson(json, new TypeReference<>() {
        });
    }

    public static List<String> parseJsonStringListOrEmpty(String json) {
        try {
            List<String> values = parseJsonStringList(json);
            return values == null ? List.of() : values;
        } catch (IOException exception) {
            return List.of();
        }
    }
}
