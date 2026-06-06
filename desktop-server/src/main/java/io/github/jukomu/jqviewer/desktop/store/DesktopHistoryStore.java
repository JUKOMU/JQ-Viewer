package io.github.jukomu.jqviewer.desktop.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.github.jukomu.jqviewer.desktop.util.JsonFiles;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DesktopHistoryStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type ENTRY_LIST_TYPE = new TypeToken<List<Entry>>() {}.getType();

    private final Path file;
    private List<Entry> items;

    public DesktopHistoryStore(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        this.file = dataDir.resolve("browse-history.json");
        this.items = load();
    }

    public synchronized JsonObject getBrowseHistory(int limit, int offset) {
        List<Entry> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingLong((Entry entry) -> entry.timestamp).reversed());

        int from = Math.max(0, offset);
        int to = limit > 0 ? Math.min(sorted.size(), from + limit) : sorted.size();
        JsonArray arr = new JsonArray();
        if (from < sorted.size()) {
            for (Entry entry : sorted.subList(from, to)) {
                arr.add(toJson(entry));
            }
        }

        JsonObject result = new JsonObject();
        result.add("items", arr);
        return result;
    }

    public synchronized JsonObject recordBrowse(JsonObject body) throws IOException {
        String albumId = stringValue(body, "albumId");
        String chapterId = stringValue(body, "chapterId");
        long now = System.currentTimeMillis();

        Entry target = null;
        for (Entry item : items) {
            if (item.albumId.equals(albumId) && item.chapterId.equals(chapterId)) {
                target = item;
                break;
            }
        }

        if (target == null) {
            target = new Entry();
            target.id = nextId();
            items.add(target);
        }

        target.albumId = albumId;
        target.albumTitle = stringValue(body, "albumTitle");
        target.coverUrl = stringValue(body, "coverUrl");
        target.authors = stringValue(body, "authors");
        target.chapterId = chapterId;
        target.chapterTitle = stringValue(body, "chapterTitle");
        target.timestamp = now;
        save();

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        return result;
    }

    public synchronized JsonObject clearBrowseHistory() throws IOException {
        items.clear();
        save();
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        return result;
    }

    public synchronized JsonObject deleteBrowseItem(int id) throws IOException {
        items.removeIf(item -> item.id == id);
        save();
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        return result;
    }

    private List<Entry> load() throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        return JsonFiles.readJson(file, GSON, ENTRY_LIST_TYPE, new ArrayList<>());
    }

    private void save() throws IOException {
        JsonFiles.writeJson(file, GSON, items);
    }

    private long nextId() {
        long max = 0;
        for (Entry item : items) {
            max = Math.max(max, item.id);
        }
        return max + 1;
    }

    private static JsonObject toJson(Entry entry) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", entry.id);
        obj.addProperty("albumId", entry.albumId);
        obj.addProperty("albumTitle", entry.albumTitle);
        obj.addProperty("coverUrl", entry.coverUrl);
        obj.addProperty("authors", entry.authors);
        obj.addProperty("chapterId", entry.chapterId);
        obj.addProperty("chapterTitle", entry.chapterTitle);
        obj.addProperty("timestamp", entry.timestamp);
        return obj;
    }

    private static String stringValue(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return "";
        return obj.get(key).getAsString();
    }

    private static final class Entry {
        long id;
        String albumId = "";
        String albumTitle = "";
        String coverUrl = "";
        String authors = "";
        String chapterId = "";
        String chapterTitle = "";
        long timestamp;
    }
}
