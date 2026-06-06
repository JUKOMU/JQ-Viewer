package io.github.jukomu.jqviewer.desktop.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DesktopPdfStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private JsonArray pdfs;

    public DesktopPdfStore(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        this.file = dataDir.resolve("imported-pdfs.json");
        this.pdfs = load();
    }

    public synchronized List<JsonObject> allPdfs() {
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement element : pdfs) {
            if (element.isJsonObject()) {
                result.add(element.getAsJsonObject().deepCopy());
            }
        }
        result.sort(Comparator
            .comparing((JsonObject obj) -> stringValue(obj, "albumId", ""))
            .thenComparingInt(obj -> intValue(obj, "chapterSortOrder", 0))
            .thenComparingLong(obj -> longValue(obj, "createdAt", 0)));
        return result;
    }

    public synchronized JsonObject findByPath(Path path) {
        String normalized = normalize(path);
        for (JsonElement element : pdfs) {
            if (!element.isJsonObject()) continue;
            JsonObject pdf = element.getAsJsonObject();
            if (normalized.equals(stringValue(pdf, "filePath", ""))) {
                return pdf.deepCopy();
            }
        }
        return null;
    }

    public synchronized JsonObject insert(JsonObject pdf) throws IOException {
        String filePath = stringValue(pdf, "filePath", "");
        if (filePath.isBlank()) {
            throw new IllegalArgumentException("filePath is required");
        }
        if (findByPath(Path.of(filePath)) != null) {
            return null;
        }

        JsonObject next = pdf.deepCopy();
        next.addProperty("id", nextId());
        pdfs.add(next);
        save();
        return next.deepCopy();
    }

    public synchronized boolean delete(long id) throws IOException {
        for (int i = 0; i < pdfs.size(); i++) {
            JsonElement element = pdfs.get(i);
            if (!element.isJsonObject()) continue;
            JsonObject pdf = element.getAsJsonObject();
            if (longValue(pdf, "id", -1) == id) {
                pdfs.remove(i);
                save();
                return true;
            }
        }
        return false;
    }

    public synchronized int updateAlbumEpisodeType(String albumId, boolean isSingleEpisode) throws IOException {
        int updated = 0;
        for (JsonElement element : pdfs) {
            if (!element.isJsonObject()) continue;
            JsonObject pdf = element.getAsJsonObject();
            if (albumId.equals(stringValue(pdf, "albumId", ""))) {
                pdf.addProperty("isSingleEpisode", isSingleEpisode);
                updated++;
            }
        }
        if (updated > 0) save();
        return updated;
    }

    public synchronized boolean containsPath(Path path) {
        return findByPath(path) != null;
    }

    private JsonArray load() throws IOException {
        if (!Files.exists(file)) {
            pdfs = new JsonArray();
            save();
            return pdfs;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonArray loaded = GSON.fromJson(reader, JsonArray.class);
            return loaded == null ? new JsonArray() : loaded;
        }
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(pdfs, writer);
        }
    }

    private long nextId() {
        long max = 0;
        for (JsonElement element : pdfs) {
            if (element.isJsonObject()) {
                max = Math.max(max, longValue(element.getAsJsonObject(), "id", 0));
            }
        }
        return max + 1;
    }

    private static String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static String stringValue(JsonObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsString();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int intValue(JsonObject obj, String key, int fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static long longValue(JsonObject obj, String key, long fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
