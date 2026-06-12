package io.github.jukomu.jqviewer.desktop.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.jukomu.jqviewer.desktop.util.JsonFiles;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DesktopOfflineFavoriteStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type STATE_TYPE = State.class;

    private final Path file;
    private State state;

    public DesktopOfflineFavoriteStore(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        this.file = dataDir.resolve("offline-favorites.json");
        this.state = load();
    }

    public synchronized JsonObject getFolders() {
        JsonArray folders = new JsonArray();
        for (Folder folder : state.folders) {
            JsonObject obj = new JsonObject();
            obj.addProperty("folderId", folder.folderId);
            obj.addProperty("name", folder.name);
            obj.addProperty("count", folder.items.size());
            folders.add(obj);
        }
        JsonObject result = new JsonObject();
        result.add("folders", folders);
        return result;
    }

    public synchronized JsonObject createFolder(JsonObject body) throws IOException {
        Folder folder = new Folder();
        folder.folderId = generateUniqueFolderId();
        folder.name = stringValue(body, "name", "");
        state.folders.add(folder);
        save();

        JsonObject result = new JsonObject();
        result.addProperty("folderId", folder.folderId);
        return result;
    }

    public synchronized JsonObject renameFolder(JsonObject body) throws IOException {
        Folder folder = findFolder(stringValue(body, "folderId", ""));
        String name = stringValue(body, "name", "").trim();
        boolean success = folder != null && !name.isEmpty();
        if (success) {
            folder.name = name;
            save();
        }
        return success(success);
    }

    public synchronized JsonObject deleteFolder(JsonObject body) throws IOException {
        String folderId = stringValue(body, "folderId", "");
        boolean removed = state.folders.removeIf(folder -> folder.folderId.equals(folderId));
        if (removed) save();
        return success(removed);
    }

    public synchronized JsonObject addFavorite(JsonObject body) throws IOException {
        Folder folder = requireFolder(stringValue(body, "folderId", ""));
        JsonObject item = normalizeItem(objectValue(body, "item"));
        String albumId = stringValue(item, "id", "");
        boolean added = false;
        if (!albumId.isBlank() && findItem(folder, albumId) == null) {
            folder.items.add(item);
            save();
            added = true;
        }
        return success(added);
    }

    public synchronized JsonObject removeFavorite(JsonObject body) throws IOException {
        Folder folder = findFolder(stringValue(body, "folderId", ""));
        String albumId = stringValue(body, "albumId", "");
        boolean removed = folder != null && folder.items.removeIf(item -> albumId.equals(stringValue(item, "id", "")));
        if (removed) save();
        return success(removed);
    }

    public synchronized JsonObject getFavorites(JsonObject body) {
        Folder folder = findFolder(stringValue(body, "folderId", ""));
        List<JsonObject> items = folder == null ? List.of() : filtered(folder.items, stringValue(body, "keyword", ""));
        return paged(items, intValue(body, "page", 1), intValue(body, "pageSize", 20));
    }

    public synchronized JsonObject getAllFavorites(JsonObject body) {
        Folder folder = findFolder(stringValue(body, "folderId", ""));
        JsonObject result = new JsonObject();
        result.add("items", itemsToJson(folder == null ? List.of() : folder.items));
        return result;
    }

    public synchronized JsonObject getTotalCount() {
        JsonObject result = new JsonObject();
        result.addProperty("count", mergedItems().size());
        return result;
    }

    public synchronized JsonObject getAllFavoritesMerged() {
        JsonObject result = new JsonObject();
        result.add("items", itemsToJson(mergedItems()));
        return result;
    }

    public synchronized JsonObject moveAll(JsonObject body) throws IOException {
        Folder source = findFolder(stringValue(body, "sourceId", ""));
        Folder target = findFolder(stringValue(body, "targetId", ""));
        if (source == null || target == null || source == target) return success(false);

        for (JsonObject item : source.items) {
            String albumId = stringValue(item, "id", "");
            if (!albumId.isBlank() && findItem(target, albumId) == null) {
                target.items.add(item.deepCopy());
            }
        }
        source.items.clear();
        save();
        return success(true);
    }

    public synchronized JsonObject copyFolder(JsonObject body) throws IOException {
        Folder source = findFolder(stringValue(body, "sourceId", ""));
        if (source == null) {
            JsonObject result = new JsonObject();
            result.addProperty("folderId", "");
            return result;
        }
        Folder target = new Folder();
        target.folderId = generateUniqueFolderId();
        target.name = stringValue(body, "name", "");
        for (JsonObject item : source.items) {
            target.items.add(item.deepCopy());
        }
        state.folders.add(target);
        save();

        JsonObject result = new JsonObject();
        result.addProperty("folderId", target.folderId);
        return result;
    }

    public synchronized JsonObject addFavoritesBatch(JsonObject body) throws IOException {
        Folder folder = requireFolder(stringValue(body, "folderId", ""));
        JsonArray items = arrayValue(body, "items");
        int count = 0;
        for (JsonElement element : items) {
            if (!element.isJsonObject()) continue;
            JsonObject item = normalizeItem(element.getAsJsonObject());
            String albumId = stringValue(item, "id", "");
            if (!albumId.isBlank() && findItem(folder, albumId) == null) {
                folder.items.add(item);
                count++;
            }
        }
        if (count > 0) save();

        JsonObject result = new JsonObject();
        result.addProperty("count", count);
        return result;
    }

    public synchronized JsonObject mergeAllToFolder(JsonObject body) throws IOException {
        Folder target = findFolder(stringValue(body, "targetId", ""));
        if (target == null) return success(false);

        List<JsonObject> merged = mergedItems();
        target.items.clear();
        for (JsonObject item : merged) {
            target.items.add(item.deepCopy());
        }
        for (Folder folder : state.folders) {
            if (folder != target) {
                folder.items.clear();
            }
        }
        save();
        return success(true);
    }

    public synchronized JsonObject saveBackup(JsonObject body) throws IOException {
        String key = stringValue(body, "key", "");
        if (key.isBlank()) return success(false);
        Backup backup = new Backup();
        backup.key = key;
        backup.createdAt = System.currentTimeMillis();
        for (JsonElement element : arrayValue(body, "items")) {
            if (element.isJsonObject()) {
                backup.items.add(normalizeItem(element.getAsJsonObject()));
            }
        }
        state.backups.removeIf(item -> item.key.equals(key));
        state.backups.add(backup);
        save();
        return success(true);
    }

    public synchronized JsonObject loadBackup(JsonObject body) {
        String key = stringValue(body, "key", "");
        JsonObject result = new JsonObject();
        for (Backup backup : state.backups) {
            if (backup.key.equals(key)) {
                result.add("items", itemsToJson(backup.items));
                return result;
            }
        }
        result.add("items", null);
        return result;
    }

    public synchronized JsonObject deleteBackup(JsonObject body) throws IOException {
        String key = stringValue(body, "key", "");
        boolean removed = state.backups.removeIf(backup -> backup.key.equals(key));
        if (removed) save();
        return success(removed);
    }

    public synchronized JsonObject listBackupKeys() {
        List<Backup> backups = new ArrayList<>(state.backups);
        backups.sort(Comparator.comparingLong((Backup backup) -> backup.createdAt).reversed());
        JsonArray keys = new JsonArray();
        for (Backup backup : backups) {
            keys.add(backup.key);
        }
        JsonObject result = new JsonObject();
        result.add("keys", keys);
        return result;
    }

    private State load() throws IOException {
        if (!Files.exists(file)) {
            State empty = new State();
            JsonFiles.writeJson(file, GSON, empty);
            return empty;
        }
        State loaded = JsonFiles.readJson(file, GSON, STATE_TYPE, new State());
        if (loaded.folders == null) loaded.folders = new ArrayList<>();
        if (loaded.backups == null) loaded.backups = new ArrayList<>();
        for (Folder folder : loaded.folders) {
            if (folder.items == null) folder.items = new ArrayList<>();
            if (folder.folderId == null) folder.folderId = "";
            if (folder.name == null) folder.name = "";
        }
        for (Backup backup : loaded.backups) {
            if (backup.items == null) backup.items = new ArrayList<>();
            if (backup.key == null) backup.key = "";
        }
        return loaded;
    }

    private void save() throws IOException {
        JsonFiles.writeJson(file, GSON, state);
    }

    private String generateUniqueFolderId() {
        String folderId;
        do {
            folderId = generateFolderId();
        } while (findFolder(folderId) != null);
        return folderId;
    }

    private Folder requireFolder(String folderId) {
        Folder folder = findFolder(folderId);
        if (folder == null) {
            throw new IllegalArgumentException("offline folder not found");
        }
        return folder;
    }

    private Folder findFolder(String folderId) {
        for (Folder folder : state.folders) {
            if (folder.folderId.equals(folderId)) return folder;
        }
        return null;
    }

    private static JsonObject findItem(Folder folder, String albumId) {
        for (JsonObject item : folder.items) {
            if (albumId.equals(stringValue(item, "id", ""))) return item;
        }
        return null;
    }

    private static List<JsonObject> filtered(List<JsonObject> items, String keyword) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        if (kw.isEmpty()) return new ArrayList<>(items);
        List<JsonObject> result = new ArrayList<>();
        for (JsonObject item : items) {
            if (stringValue(item, "title", "").toLowerCase().contains(kw)) {
                result.add(item);
            }
        }
        return result;
    }

    private List<JsonObject> mergedItems() {
        Map<String, JsonObject> byAlbumId = new LinkedHashMap<>();
        for (Folder folder : state.folders) {
            for (JsonObject item : folder.items) {
                String albumId = stringValue(item, "id", "");
                if (!albumId.isBlank()) {
                    byAlbumId.putIfAbsent(albumId, item);
                }
            }
        }
        return new ArrayList<>(byAlbumId.values());
    }

    private static JsonObject paged(List<JsonObject> items, int page, int pageSize) {
        int normalizedPageSize = pageSize > 0 ? pageSize : 20;
        int totalItems = items.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / normalizedPageSize));
        int currentPage = Math.min(Math.max(1, page), totalPages);
        int from = (currentPage - 1) * normalizedPageSize;
        int to = Math.min(totalItems, from + normalizedPageSize);

        JsonObject result = new JsonObject();
        result.addProperty("totalItems", totalItems);
        result.addProperty("totalPages", totalPages);
        result.addProperty("currentPage", currentPage);
        result.add("content", itemsToJson(from < totalItems ? items.subList(from, to) : List.of()));
        return result;
    }

    private static JsonArray itemsToJson(List<JsonObject> items) {
        JsonArray arr = new JsonArray();
        for (JsonObject item : items) {
            arr.add(item.deepCopy());
        }
        return arr;
    }

    private static JsonObject normalizeItem(JsonObject item) {
        JsonObject normalized = new JsonObject();
        normalized.addProperty("id", stringValue(item, "id", ""));
        normalized.addProperty("title", stringValue(item, "title", ""));
        normalized.addProperty("coverUrl", stringValue(item, "coverUrl", ""));
        normalized.add("authors", arrayValue(item, "authors"));
        normalized.add("tags", arrayValue(item, "tags"));
        return normalized;
    }

    private static JsonObject success(boolean success) {
        JsonObject result = new JsonObject();
        result.addProperty("success", success);
        return result;
    }

    private static String generateFolderId() {
        return "offline_" + System.currentTimeMillis() + "_" + Math.round(Math.random() * 1_000_000);
    }

    private static JsonObject objectValue(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonObject()) return new JsonObject();
        return obj.getAsJsonObject(key);
    }

    private static JsonArray arrayValue(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) return new JsonArray();
        return obj.getAsJsonArray(key).deepCopy();
    }

    private static int intValue(JsonObject obj, String key, int fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String stringValue(JsonObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsString();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static final class State {
        List<Folder> folders = new ArrayList<>();
        List<Backup> backups = new ArrayList<>();
    }

    private static final class Folder {
        String folderId = "";
        String name = "";
        List<JsonObject> items = new ArrayList<>();
    }

    private static final class Backup {
        String key = "";
        long createdAt;
        List<JsonObject> items = new ArrayList<>();
    }
}
