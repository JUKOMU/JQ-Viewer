package io.github.jukomu.jqviewer.desktop.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.jukomu.jqviewer.desktop.util.JsonFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class DesktopDownloadStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Path downloadDir;
    private JsonArray tasks;

    public DesktopDownloadStore(Path dataDir, Path downloadDir) throws IOException {
        Files.createDirectories(dataDir);
        Files.createDirectories(downloadDir);
        this.file = dataDir.resolve("download-tasks.json");
        this.downloadDir = downloadDir.toAbsolutePath().normalize();
        this.tasks = load();
        normalizeStartupTasks();
    }

    public Path downloadDir() {
        return downloadDir;
    }

    public synchronized List<JsonObject> allTasks() {
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement element : tasks) {
            if (element.isJsonObject()) {
                result.add(element.getAsJsonObject().deepCopy());
            }
        }
        return result;
    }

    public synchronized JsonObject findByTaskId(String taskId) {
        JsonObject task = findMutableByTaskId(taskId);
        return task == null ? null : task.deepCopy();
    }

    public synchronized JsonObject findByAlbumChapter(String albumId, String chapterId) {
        for (JsonElement element : tasks) {
            if (!element.isJsonObject()) continue;
            JsonObject task = element.getAsJsonObject();
            if (albumId.equals(stringValue(task, "albumId", ""))
                && chapterId.equals(stringValue(task, "chapterId", ""))) {
                return task.deepCopy();
            }
        }
        return null;
    }

    public synchronized JsonObject findCompletedByChapterId(String chapterId) {
        for (JsonElement element : tasks) {
            if (!element.isJsonObject()) continue;
            JsonObject task = element.getAsJsonObject();
            if (chapterId.equals(stringValue(task, "chapterId", ""))
                && "completed".equals(stringValue(task, "status", ""))) {
                return task.deepCopy();
            }
        }
        return null;
    }

    public synchronized void upsert(JsonObject task) throws IOException {
        String taskId = stringValue(task, "taskId", "");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }

        for (int i = 0; i < tasks.size(); i++) {
            JsonElement element = tasks.get(i);
            if (element.isJsonObject() && taskId.equals(stringValue(element.getAsJsonObject(), "taskId", ""))) {
                tasks.set(i, task.deepCopy());
                save();
                return;
            }
        }
        tasks.add(task.deepCopy());
        save();
    }

    public synchronized JsonObject patchStatus(String taskId, String status, int downloadedPages,
                                               int totalPages, String error, long downloadedBytes,
                                               long totalSize) throws IOException {
        JsonObject task = findMutableByTaskId(taskId);
        if (task == null) {
            throw new IllegalArgumentException("download task not found");
        }
        task.addProperty("status", status);
        task.addProperty("downloadedPages", Math.max(0, downloadedPages));
        task.addProperty("totalPages", Math.max(0, totalPages));
        task.addProperty("speed", 0);
        if (downloadedBytes >= 0) task.addProperty("downloadedBytes", downloadedBytes);
        if (totalSize >= 0) task.addProperty("totalSize", totalSize);
        if (error == null || error.isBlank()) {
            task.remove("error");
        } else {
            task.addProperty("error", error);
        }
        if ("completed".equals(status)) {
            task.addProperty("completedAt", System.currentTimeMillis());
        } else {
            task.remove("completedAt");
        }
        save();
        return task.deepCopy();
    }

    public synchronized JsonObject deleteByAlbumChapter(String albumId, String chapterId) throws IOException {
        for (int i = 0; i < tasks.size(); i++) {
            JsonElement element = tasks.get(i);
            if (!element.isJsonObject()) continue;
            JsonObject task = element.getAsJsonObject();
            if (albumId.equals(stringValue(task, "albumId", ""))
                && chapterId.equals(stringValue(task, "chapterId", ""))) {
                tasks.remove(i);
                save();
                return task.deepCopy();
            }
        }
        return null;
    }

    public Path resolveTaskDir(String albumId, String chapterId) {
        Path dir = downloadDir.resolve(safeName(albumId)).resolve(safeName(chapterId)).normalize();
        if (!dir.startsWith(downloadDir)) {
            throw new IllegalArgumentException("invalid download path");
        }
        return dir;
    }

    public boolean isPathInsideDownloadDir(Path path) {
        return path.toAbsolutePath().normalize().startsWith(downloadDir);
    }

    private JsonArray load() throws IOException {
        if (!Files.exists(file)) {
            tasks = new JsonArray();
            save();
            return tasks;
        }
        return JsonFiles.readJson(file, GSON, JsonArray.class, new JsonArray());
    }

    private void normalizeStartupTasks() throws IOException {
        boolean changed = false;
        for (JsonElement element : tasks) {
            if (!element.isJsonObject()) continue;
            JsonObject task = element.getAsJsonObject();
            String status = stringValue(task, "status", "");
            if ("queued".equals(status) || "downloading".equals(status)) {
                task.addProperty("status", "paused");
                task.addProperty("speed", 0);
                changed = true;
            }
        }
        if (changed) save();
    }

    private void save() throws IOException {
        JsonFiles.writeJson(file, GSON, tasks);
    }

    private JsonObject findMutableByTaskId(String taskId) {
        for (JsonElement element : tasks) {
            if (!element.isJsonObject()) continue;
            JsonObject task = element.getAsJsonObject();
            if (taskId.equals(stringValue(task, "taskId", ""))) {
                return task;
            }
        }
        return null;
    }

    private static String safeName(String value) {
        String raw = value == null || value.isBlank() ? "unknown" : value;
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String stringValue(JsonObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsString();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
