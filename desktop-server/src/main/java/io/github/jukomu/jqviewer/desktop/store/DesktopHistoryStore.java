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
    private static final Type BROWSE_ENTRY_LIST_TYPE = new TypeToken<List<BrowseEntry>>() {}.getType();
    private static final Type PARSE_ENTRY_LIST_TYPE = new TypeToken<List<ParseEntry>>() {}.getType();

    private final Path browseFile;
    private final Path parseFile;
    private List<BrowseEntry> browseItems;
    private List<ParseEntry> parseItems;

    public DesktopHistoryStore(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        this.browseFile = dataDir.resolve("browse-history.json");
        this.parseFile = dataDir.resolve("parse-history.json");
        this.browseItems = loadBrowse();
        this.parseItems = loadParse();
    }

    public synchronized JsonObject getBrowseHistory(int limit, int offset) {
        List<BrowseEntry> sorted = new ArrayList<>(browseItems);
        sorted.sort(Comparator.comparingLong((Entry entry) -> entry.timestamp).reversed());

        int from = Math.max(0, offset);
        int to = limit > 0 ? Math.min(sorted.size(), from + limit) : sorted.size();
        JsonArray arr = new JsonArray();
        if (from < sorted.size()) {
            for (BrowseEntry entry : sorted.subList(from, to)) {
                arr.add(toBrowseJson(entry));
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

        BrowseEntry target = null;
        for (BrowseEntry item : browseItems) {
            if (item.albumId.equals(albumId) && item.chapterId.equals(chapterId)) {
                target = item;
                break;
            }
        }

        if (target == null) {
            target = new BrowseEntry();
            target.id = nextBrowseId();
            browseItems.add(target);
        }

        target.albumId = albumId;
        target.albumTitle = stringValue(body, "albumTitle");
        target.coverUrl = stringValue(body, "coverUrl");
        target.authors = stringValue(body, "authors");
        target.chapterId = chapterId;
        target.chapterTitle = stringValue(body, "chapterTitle");
        target.timestamp = now;
        saveBrowse();

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        return result;
    }

    public synchronized JsonObject clearBrowseHistory() throws IOException {
        browseItems.clear();
        saveBrowse();
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        return result;
    }

    public synchronized JsonObject deleteBrowseItem(int id) throws IOException {
        browseItems.removeIf(item -> item.id == id);
        saveBrowse();
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        return result;
    }

    public synchronized JsonObject getParseHistory(int limit, int offset) {
        List<ParseEntry> sorted = new ArrayList<>(parseItems);
        sorted.sort(Comparator.comparingLong((Entry entry) -> entry.timestamp).reversed());

        int from = Math.max(0, offset);
        int to = limit > 0 ? Math.min(sorted.size(), from + limit) : sorted.size();
        JsonArray arr = new JsonArray();
        if (from < sorted.size()) {
            for (ParseEntry entry : sorted.subList(from, to)) {
                arr.add(toParseJson(entry));
            }
        }

        JsonObject result = new JsonObject();
        result.add("items", arr);
        return result;
    }

    public synchronized JsonObject recordParse(JsonObject body) throws IOException {
        String text = stringValue(body, "text").trim();
        if (text.isEmpty()) {
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            return result;
        }

        long now = System.currentTimeMillis();
        ParseEntry target = null;
        for (ParseEntry item : parseItems) {
            if (item.text.equals(text)) {
                target = item;
                break;
            }
        }

        if (target == null) {
            target = new ParseEntry();
            target.id = nextParseId();
            parseItems.add(target);
        }

        target.text = text;
        target.mode = parseMode(stringValue(body, "mode"));
        target.timestamp = now;
        saveParse();

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        return result;
    }

    public synchronized JsonObject clearParseHistory() throws IOException {
        parseItems.clear();
        saveParse();
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        return result;
    }

    public synchronized JsonObject deleteParseItem(int id) throws IOException {
        parseItems.removeIf(item -> item.id == id);
        saveParse();
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        return result;
    }

    private List<BrowseEntry> loadBrowse() throws IOException {
        if (!Files.exists(browseFile)) return new ArrayList<>();
        return JsonFiles.readJson(browseFile, GSON, BROWSE_ENTRY_LIST_TYPE, new ArrayList<>());
    }

    private List<ParseEntry> loadParse() throws IOException {
        if (!Files.exists(parseFile)) return new ArrayList<>();
        return JsonFiles.readJson(parseFile, GSON, PARSE_ENTRY_LIST_TYPE, new ArrayList<>());
    }

    private void saveBrowse() throws IOException {
        JsonFiles.writeJson(browseFile, GSON, browseItems);
    }

    private void saveParse() throws IOException {
        JsonFiles.writeJson(parseFile, GSON, parseItems);
    }

    private long nextBrowseId() {
        long max = 0;
        for (BrowseEntry item : browseItems) {
            max = Math.max(max, item.id);
        }
        return max + 1;
    }

    private long nextParseId() {
        long max = 0;
        for (ParseEntry item : parseItems) {
            max = Math.max(max, item.id);
        }
        return max + 1;
    }

    private static JsonObject toBrowseJson(BrowseEntry entry) {
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

    private static JsonObject toParseJson(ParseEntry entry) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", entry.id);
        obj.addProperty("text", entry.text);
        obj.addProperty("timestamp", entry.timestamp);
        obj.addProperty("mode", parseMode(entry.mode));
        return obj;
    }

    private static String parseMode(String mode) {
        return "batch-mode".equals(mode) ? "batch-mode" : "single-mode";
    }

    private static String stringValue(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return "";
        return obj.get(key).getAsString();
    }

    private static class Entry {
        long id;
        long timestamp;
    }

    private static final class BrowseEntry extends Entry {
        String albumId = "";
        String albumTitle = "";
        String coverUrl = "";
        String authors = "";
        String chapterId = "";
        String chapterTitle = "";
    }

    private static final class ParseEntry extends Entry {
        String text = "";
        String mode = "single-mode";
    }
}
