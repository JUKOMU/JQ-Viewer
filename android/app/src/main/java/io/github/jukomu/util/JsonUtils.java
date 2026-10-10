package io.github.jukomu.util;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

public final class JsonUtils {
    private JsonUtils() {
    }

    public static JSONArray toJsonArray(Collection<String> values) {
        return new JSONArray(values == null ? List.of() : values);
    }

    public static JSONArray parseJsonArray(String json) throws JSONException {
        return new JSONArray(json);
    }

    public static JSONObject parseJsonObject(byte[] json) throws JSONException {
        return new JSONObject(new String(json, StandardCharsets.UTF_8));
    }

    public static JSONArray parseJsonArray(byte[] json) throws JSONException {
        return new JSONArray(new String(json, StandardCharsets.UTF_8));
    }
}
