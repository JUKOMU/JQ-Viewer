package io.github.jukomu.jqviewer.desktop.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonFilesTest {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @TempDir
    Path tempDir;

    @Test
    void restoresBackupWhenPrimaryJsonIsCorrupt() throws IOException {
        Path file = tempDir.resolve("state.json");
        JsonObject first = json("value", "first");
        JsonObject second = json("value", "second");

        JsonFiles.writeJson(file, GSON, first);
        JsonFiles.writeJson(file, GSON, second);
        Files.writeString(file, "{broken", StandardCharsets.UTF_8);

        JsonObject restored = JsonFiles.readJson(file, GSON, JsonObject.class, new JsonObject());

        assertEquals("first", restored.get("value").getAsString());
        assertEquals("first", JsonFiles.readJson(file, GSON, JsonObject.class, new JsonObject()).get("value").getAsString());
        try (var files = Files.list(tempDir)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("state.json.corrupt-")));
        }
    }

    private static JsonObject json(String key, String value) {
        JsonObject object = new JsonObject();
        object.addProperty(key, value);
        return object;
    }
}
