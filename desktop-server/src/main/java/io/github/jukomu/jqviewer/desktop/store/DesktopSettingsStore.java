package io.github.jukomu.jqviewer.desktop.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DesktopSettingsStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private JsonObject settings;

    public DesktopSettingsStore(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        this.file = dataDir.resolve("settings.json");
        this.settings = load();
    }

    public synchronized JsonObject getAll() {
        JsonObject copy = new JsonObject();
        for (String key : settings.keySet()) {
            copy.add(key, settings.get(key));
        }
        return copy;
    }

    public synchronized JsonObject update(JsonObject patch) throws IOException {
        for (String key : patch.keySet()) {
            JsonElement value = patch.get(key);
            if (value != null && !value.isJsonNull()) {
                settings.add(key, value);
            }
        }
        save();
        return getAll();
    }

    private JsonObject load() throws IOException {
        JsonObject base = defaults();
        if (!Files.exists(file)) {
            settings = base;
            save();
            return base;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject loaded = GSON.fromJson(reader, JsonObject.class);
            if (loaded != null) {
                for (String key : loaded.keySet()) {
                    base.add(key, loaded.get(key));
                }
            }
        }
        settings = base;
        save();
        return base;
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(settings, writer);
        }
    }

    private static JsonObject defaults() {
        JsonObject defaults = new JsonObject();
        defaults.addProperty("readerPreloadPages", 15);
        defaults.addProperty("preloadConcurrency", 6);
        defaults.addProperty("downloadConcurrency", 6);
        defaults.addProperty("downloadPublic", false);
        defaults.addProperty("cacheCapacityMb", 256);
        defaults.addProperty("ocrEnabled", true);
        defaults.addProperty("readerDisplayMode", "vertical");
        defaults.addProperty("readerScreenOrientation", "auto");
        defaults.addProperty("readerBrightness", -1);
        defaults.addProperty("readerKeepScreenOn", true);
        defaults.addProperty("readerVolumeNavigation", false);
        return defaults;
    }
}
