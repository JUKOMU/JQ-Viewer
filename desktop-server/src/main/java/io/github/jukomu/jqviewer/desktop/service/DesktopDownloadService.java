package io.github.jukomu.jqviewer.desktop.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.jukomu.jqviewer.desktop.store.DesktopDownloadStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DesktopDownloadService {
    private final DesktopJmcomicService jmcomicService;
    private final DesktopDownloadStore store;
    private final DesktopEventBroker events;
    private final ExecutorService executor;
    private final Map<String, Control> controls = new ConcurrentHashMap<>();

    public DesktopDownloadService(DesktopJmcomicService jmcomicService, DesktopDownloadStore store,
                                  DesktopEventBroker events, int concurrency) {
        this.jmcomicService = jmcomicService;
        this.store = store;
        this.events = events;
        this.executor = Executors.newFixedThreadPool(Math.max(1, concurrency));
    }

    public JsonObject startDownload(JsonObject body) throws IOException {
        String albumId = stringValue(body, "albumId", "");
        String chapterId = stringValue(body, "chapterId", "");
        if (albumId.isBlank() || chapterId.isBlank()) {
            throw new IllegalArgumentException("albumId and chapterId are required");
        }

        JsonObject photo = jmcomicService.getPhoto(chapterId);
        JsonArray images = photo.has("images") && photo.get("images").isJsonArray()
            ? photo.getAsJsonArray("images")
            : new JsonArray();
        String taskId = albumId + "_" + chapterId;
        JsonObject existing = store.findByTaskId(taskId);
        JsonObject task = existing == null ? new JsonObject() : existing;
        task.addProperty("taskId", taskId);
        task.addProperty("albumId", albumId);
        task.addProperty("chapterId", chapterId);
        task.addProperty("albumTitle", stringValue(body, "albumTitle", albumId));
        task.addProperty("chapterTitle", stringValue(body, "chapterTitle", stringValue(photo, "title", chapterId)));
        task.addProperty("coverUrl", stringValue(body, "coverUrl", ""));
        task.addProperty("chapterSortOrder", intValue(photo, "sortOrder", 0));
        if (photo.has("isSingleEpisode") && !photo.get("isSingleEpisode").isJsonNull()) {
            task.addProperty("isSingleEpisode", photo.get("isSingleEpisode").getAsBoolean());
        }
        task.addProperty("totalPages", images.size());
        task.addProperty("downloadedPages", countDownloaded(task));
        task.addProperty("status", "queued");
        task.addProperty("createdAt", existing == null ? System.currentTimeMillis() : longValue(task, "createdAt", System.currentTimeMillis()));
        task.addProperty("speed", 0);
        task.remove("error");
        task.remove("completedAt");
        task.add("photo", photo.deepCopy());
        task.add("images", ensureImageFileEntries(albumId, chapterId, images));
        if (images.size() > 0 && images.get(0).isJsonObject()) {
            task.addProperty("firstImageSortOrder", intValue(images.get(0).getAsJsonObject(), "sortOrder", 1));
        }
        store.upsert(task);
        submit(taskId);

        JsonObject result = new JsonObject();
        result.addProperty("taskId", taskId);
        return result;
    }

    public JsonObject getTasks() {
        JsonArray tasks = new JsonArray();
        long usedBytes = 0;
        for (JsonObject task : store.allTasks()) {
            tasks.add(publicTask(task));
            usedBytes += Math.max(0, longValue(task, "totalSize", 0));
        }
        JsonObject result = new JsonObject();
        result.add("tasks", tasks);
        result.addProperty("usedBytes", usedBytes);
        result.addProperty("availableBytes", store.downloadDir().toFile().getUsableSpace());
        result.addProperty("downloadDir", store.downloadDir().toString());
        return result;
    }

    public JsonObject roots() {
        JsonObject result = new JsonObject();
        result.addProperty("downloadDir", store.downloadDir().toString());
        return result;
    }

    public JsonObject updateRoots(JsonObject body) throws IOException {
        if (body != null && body.has("downloadDir") && !body.get("downloadDir").isJsonNull()) {
            String raw = body.get("downloadDir").getAsString();
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("downloadDir is required");
            }
            if (!controls.isEmpty()) {
                throw new IllegalStateException("Cannot change download directory while downloads are running");
            }
            store.updateDownloadDir(Path.of(raw));
        }
        return roots();
    }

    public JsonObject pause(String taskId) throws IOException {
        Control control = controls.get(taskId);
        if (control != null) {
            control.paused = true;
        }
        JsonObject task = store.findByTaskId(taskId);
        if (task == null) throw new IllegalArgumentException("download task not found");
        JsonObject patched = store.patchStatus(
            taskId,
            "paused",
            intValue(task, "downloadedPages", 0),
            intValue(task, "totalPages", 0),
            null,
            longValue(task, "downloadedBytes", 0),
            longValue(task, "totalSize", 0)
        );
        publish(patched, "paused", null, 0);
        return success();
    }

    public JsonObject resume(String taskId) throws IOException {
        JsonObject task = store.findByTaskId(taskId);
        if (task == null) throw new IllegalArgumentException("download task not found");
        if (!"completed".equals(stringValue(task, "status", ""))) {
            JsonObject patched = store.patchStatus(
                taskId,
                "queued",
                intValue(task, "downloadedPages", 0),
                intValue(task, "totalPages", 0),
                null,
                longValue(task, "downloadedBytes", 0),
                longValue(task, "totalSize", 0)
            );
            publish(patched, "queued", null, 0);
            submit(taskId);
        }
        return success();
    }

    public JsonObject cancel(String taskId) throws IOException {
        Control control = controls.get(taskId);
        if (control != null) {
            control.cancelled = true;
        }
        JsonObject task = store.findByTaskId(taskId);
        if (task != null) {
            publish(task, "cancelled", null, 0);
            deleteTaskFiles(task);
            store.deleteByAlbumChapter(stringValue(task, "albumId", ""), stringValue(task, "chapterId", ""));
        }
        return success();
    }

    public JsonObject deleteDownloaded(String albumId, String chapterId) throws IOException {
        JsonObject task = store.deleteByAlbumChapter(albumId, chapterId);
        if (task != null) {
            Control control = controls.get(stringValue(task, "taskId", ""));
            if (control != null) control.cancelled = true;
            deleteTaskFiles(task);
        }
        return success();
    }

    public JsonObject getDownloadedPhoto(String albumId, String chapterId) {
        JsonObject task = store.findByAlbumChapter(albumId, chapterId);
        if (task == null || !"completed".equals(stringValue(task, "status", ""))) {
            throw new IllegalArgumentException("downloaded chapter not found");
        }
        JsonObject photo = task.has("photo") && task.get("photo").isJsonObject()
            ? task.getAsJsonObject("photo").deepCopy()
            : new JsonObject();
        photo.add("images", publicImages(task));
        return photo;
    }

    public DesktopImageCache.ImageResource getDownloadedImage(String photoId, int sortOrder) throws IOException {
        JsonObject task = store.findCompletedByChapterId(photoId);
        if (task == null) return null;
        JsonArray images = task.has("images") && task.get("images").isJsonArray()
            ? task.getAsJsonArray("images")
            : new JsonArray();
        for (JsonElement element : images) {
            if (!element.isJsonObject()) continue;
            JsonObject image = element.getAsJsonObject();
            if (intValue(image, "sortOrder", 0) != sortOrder) continue;
            Path file = Path.of(stringValue(image, "filePath", "")).toAbsolutePath().normalize();
            if (!store.isPathInsideDownloadDir(file) || !Files.isRegularFile(file)) {
                throw new IllegalArgumentException("downloaded image not found");
            }
            return new DesktopImageCache.ImageResource(
                Files.readAllBytes(file),
                stringValue(image, "mimeType", "application/octet-stream")
            );
        }
        return null;
    }

    public void stop() {
        for (Control control : controls.values()) {
            control.cancelled = true;
        }
        executor.shutdownNow();
    }

    private void submit(String taskId) {
        Control existing = controls.get(taskId);
        if (existing != null && !existing.done) return;
        Control control = new Control();
        controls.put(taskId, control);
        executor.submit(() -> runDownload(taskId, control));
    }

    private void runDownload(String taskId, Control control) {
        try {
            JsonObject task = store.findByTaskId(taskId);
            if (task == null) return;
            store.patchStatus(
                taskId,
                "downloading",
                countDownloaded(task),
                intValue(task, "totalPages", 0),
                null,
                downloadedBytes(task),
                longValue(task, "totalSize", 0)
            );

            JsonArray images = task.has("images") && task.get("images").isJsonArray()
                ? task.getAsJsonArray("images")
                : new JsonArray();
            int totalPages = images.size();
            long lastBytes = downloadedBytes(task);
            long lastAt = System.nanoTime();

            for (JsonElement element : images) {
                if (!element.isJsonObject()) continue;
                if (control.cancelled) return;
                if (control.paused) {
                    JsonObject paused = store.patchStatus(taskId, "paused", countDownloaded(task), totalPages, null,
                        downloadedBytes(task), longValue(task, "totalSize", 0));
                    publish(paused, "paused", null, 0);
                    return;
                }

                JsonObject image = element.getAsJsonObject();
                Path file = Path.of(stringValue(image, "filePath", "")).toAbsolutePath().normalize();
                if (!Files.isRegularFile(file)) {
                    Files.createDirectories(file.getParent());
                    int sortOrder = intValue(image, "sortOrder", 0);
                    DesktopJmcomicService.ImageBytes bytes =
                        jmcomicService.fetchImageBytes(stringValue(task, "chapterId", ""), sortOrder);
                    Files.write(file, bytes.data());
                    image.addProperty("mimeType", bytes.mimeType());
                    image.addProperty("size", bytes.data().length);
                }

                task.add("images", images);
                int downloadedPages = countDownloaded(task);
                long nowBytes = downloadedBytes(task);
                long now = System.nanoTime();
                long elapsedMs = Math.max(1, (now - lastAt) / 1_000_000L);
                int speed = (int) Math.max(0, (nowBytes - lastBytes) * 1000L / elapsedMs);
                lastBytes = nowBytes;
                lastAt = now;
                task.addProperty("downloadedPages", downloadedPages);
                task.addProperty("downloadedBytes", nowBytes);
                task.addProperty("totalSize", nowBytes);
                store.upsert(task);
                JsonObject updated = store.patchStatus(taskId, "downloading", downloadedPages, totalPages, null,
                    nowBytes, nowBytes);
                publish(updated, "downloading", null, speed);
            }

            JsonObject completed = store.patchStatus(taskId, "completed", totalPages, totalPages, null,
                downloadedBytes(task), downloadedBytes(task));
            publish(completed, "completed", null, 0);
        } catch (Exception e) {
            try {
                JsonObject task = store.findByTaskId(taskId);
                if (task != null) {
                    JsonObject failed = store.patchStatus(taskId, "failed", countDownloaded(task),
                        intValue(task, "totalPages", 0), e.getMessage(),
                        downloadedBytes(task), longValue(task, "totalSize", 0));
                    publish(failed, "failed", e.getMessage(), 0);
                }
            } catch (IOException ignored) {
            }
        } finally {
            control.done = true;
            controls.remove(taskId, control);
        }
    }

    private JsonArray ensureImageFileEntries(String albumId, String chapterId, JsonArray source) {
        JsonArray result = new JsonArray();
        Path taskDir = store.resolveTaskDir(albumId, chapterId);
        for (JsonElement element : source) {
            if (!element.isJsonObject()) continue;
            JsonObject image = element.getAsJsonObject().deepCopy();
            int sortOrder = intValue(image, "sortOrder", 0);
            String fileName = sortOrder + "_" + safeFileName(stringValue(image, "filename", "image"));
            Path file = taskDir.resolve(fileName).normalize();
            if (!file.startsWith(taskDir)) {
                throw new IllegalArgumentException("invalid download image path");
            }
            image.addProperty("filePath", file.toString());
            if (!image.has("mimeType")) {
                image.addProperty("mimeType", mimeTypeFromName(fileName));
            }
            result.add(image);
        }
        return result;
    }

    private JsonArray publicImages(JsonObject task) {
        JsonArray result = new JsonArray();
        JsonArray images = task.has("images") && task.get("images").isJsonArray()
            ? task.getAsJsonArray("images")
            : new JsonArray();
        for (JsonElement element : images) {
            if (!element.isJsonObject()) continue;
            JsonObject image = element.getAsJsonObject().deepCopy();
            image.remove("filePath");
            image.remove("size");
            result.add(image);
        }
        return result;
    }

    private JsonObject publicTask(JsonObject source) {
        JsonObject task = source.deepCopy();
        task.remove("photo");
        task.remove("images");
        task.addProperty("speed", 0);
        return task;
    }

    private void publish(JsonObject task, String status, String error, int speed) {
        JsonObject event = new JsonObject();
        event.addProperty("taskId", stringValue(task, "taskId", ""));
        event.addProperty("albumId", stringValue(task, "albumId", ""));
        event.addProperty("chapterId", stringValue(task, "chapterId", ""));
        event.addProperty("downloadedPages", intValue(task, "downloadedPages", 0));
        event.addProperty("totalPages", intValue(task, "totalPages", 0));
        event.addProperty("status", status);
        event.addProperty("speed", speed);
        event.addProperty("downloadedBytes", longValue(task, "downloadedBytes", 0));
        event.addProperty("totalSize", longValue(task, "totalSize", 0));
        if (error != null && !error.isBlank()) {
            event.addProperty("error", error);
        }
        events.publishDownloadProgress(event);
    }

    private int countDownloaded(JsonObject task) {
        JsonArray images = task != null && task.has("images") && task.get("images").isJsonArray()
            ? task.getAsJsonArray("images")
            : new JsonArray();
        int count = 0;
        for (JsonElement element : images) {
            if (!element.isJsonObject()) continue;
            Path file = Path.of(stringValue(element.getAsJsonObject(), "filePath", "")).toAbsolutePath().normalize();
            if (store.isPathInsideDownloadDir(file) && Files.isRegularFile(file)) count++;
        }
        return count;
    }

    private long downloadedBytes(JsonObject task) {
        JsonArray images = task != null && task.has("images") && task.get("images").isJsonArray()
            ? task.getAsJsonArray("images")
            : new JsonArray();
        long total = 0;
        for (JsonElement element : images) {
            if (!element.isJsonObject()) continue;
            Path file = Path.of(stringValue(element.getAsJsonObject(), "filePath", "")).toAbsolutePath().normalize();
            if (!store.isPathInsideDownloadDir(file) || !Files.isRegularFile(file)) continue;
            try {
                total += Files.size(file);
            } catch (IOException ignored) {
            }
        }
        return total;
    }

    private void deleteTaskFiles(JsonObject task) throws IOException {
        Path dir = store.resolveTaskDir(stringValue(task, "albumId", ""), stringValue(task, "chapterId", ""));
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private static JsonObject success() {
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        return result;
    }

    private static String safeFileName(String value) {
        String raw = value == null || value.isBlank() ? "image" : value;
        return raw.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String mimeTypeFromName(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
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

    private static final class Control {
        private volatile boolean paused;
        private volatile boolean cancelled;
        private volatile boolean done;
    }
}
