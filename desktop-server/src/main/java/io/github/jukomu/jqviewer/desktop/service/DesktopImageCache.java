package io.github.jukomu.jqviewer.desktop.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DesktopImageCache {
    private static final int MIN_CAPACITY_MB = 16;
    private static final int MAX_CAPACITY_MB = 1024;
    private static final int THUMBNAIL_MAX_WIDTH = 300;

    private final DesktopJmcomicService jmcomicService;
    private final LinkedHashMap<String, ImageResource> cache =
        new LinkedHashMap<>(16, 0.75f, true);
    private long capacityBytes;
    private long usedBytes;

    public DesktopImageCache(DesktopJmcomicService jmcomicService, int capacityMb) {
        this.jmcomicService = jmcomicService;
        this.capacityBytes = toCapacityBytes(capacityMb);
    }

    public JsonObject preload(JsonObject body) {
        String photoId = stringValue(body, "photoId", "");
        if (photoId.isBlank()) {
            throw new IllegalArgumentException("photoId is required");
        }
        JsonArray images = body != null && body.has("images") && body.get("images").isJsonArray()
            ? body.getAsJsonArray("images")
            : new JsonArray();

        List<Integer> cached = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            if (!images.get(i).isJsonObject()) continue;
            int sortOrder = intValue(images.get(i).getAsJsonObject(), "sortOrder", 0);
            if (sortOrder > 0) {
                cached.add(sortOrder);
            }
        }

        JsonObject result = new JsonObject();
        result.add("cached", intArray(cached));
        result.add("pending", new JsonArray());
        return result;
    }

    public ImageResource get(String rawType, String photoId, int sortOrder) {
        String type = normalizeType(rawType);
        String cacheKey = key(type, photoId, sortOrder);
        ImageResource cached = getCached(cacheKey);
        if (cached != null) return cached;

        if ("thumb".equals(type)) {
            ImageResource original = getCached(key("image", photoId, sortOrder));
            if (original != null) {
                ImageResource thumb = thumbnail(original);
                put(cacheKey, thumb);
                return thumb;
            }
        }

        DesktopJmcomicService.ImageBytes fetched = jmcomicService.fetchImageBytes(photoId, sortOrder);
        ImageResource original = new ImageResource(fetched.data(), fetched.mimeType());
        put(key("image", photoId, sortOrder), original);
        if (!"thumb".equals(type)) return original;

        ImageResource thumb = thumbnail(original);
        put(cacheKey, thumb);
        return thumb;
    }

    public synchronized void setCapacityMb(int mb) {
        capacityBytes = toCapacityBytes(mb);
        evictIfNeeded();
    }

    public synchronized JsonObject capacityInfo() {
        JsonObject result = new JsonObject();
        result.addProperty("capacityMb", Math.round(capacityBytes / (1024.0 * 1024.0)));
        result.addProperty("usedMb", Math.round(usedBytes / (1024.0 * 1024.0)));
        return result;
    }

    public synchronized void clear() {
        cache.clear();
        usedBytes = 0;
    }

    private synchronized ImageResource getCached(String cacheKey) {
        return cache.get(cacheKey);
    }

    private synchronized void put(String cacheKey, ImageResource resource) {
        if (resource == null || resource.data() == null || resource.data().length == 0) return;
        if (resource.data().length > capacityBytes) return;

        ImageResource old = cache.remove(cacheKey);
        if (old != null) {
            usedBytes -= old.data().length;
        }
        cache.put(cacheKey, resource);
        usedBytes += resource.data().length;
        evictIfNeeded();
    }

    private void evictIfNeeded() {
        var iterator = cache.entrySet().iterator();
        while (usedBytes > capacityBytes && iterator.hasNext()) {
            Map.Entry<String, ImageResource> entry = iterator.next();
            usedBytes -= entry.getValue().data().length;
            iterator.remove();
        }
    }

    private static ImageResource thumbnail(ImageResource original) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(original.data())) {
            BufferedImage source = ImageIO.read(input);
            if (source == null) return original;

            int targetWidth = Math.min(THUMBNAIL_MAX_WIDTH, source.getWidth());
            int targetHeight = Math.max(1, Math.round(source.getHeight() * (targetWidth / (float) source.getWidth())));
            BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = target.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, targetWidth, targetHeight);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
            } finally {
                graphics.dispose();
            }

            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                boolean written = ImageIO.write(target, "jpg", output);
                if (!written) return original;
                return new ImageResource(output.toByteArray(), "image/jpeg");
            }
        } catch (IOException | RuntimeException e) {
            return original;
        }
    }

    private static long toCapacityBytes(int mb) {
        int bounded = Math.max(MIN_CAPACITY_MB, Math.min(MAX_CAPACITY_MB, mb));
        return bounded * 1024L * 1024L;
    }

    private static String key(String type, String photoId, int sortOrder) {
        return type + ":" + photoId + "/" + sortOrder;
    }

    private static String normalizeType(String type) {
        return "thumb".equals(type) ? "thumb" : "image";
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

    private static JsonArray intArray(List<Integer> values) {
        JsonArray arr = new JsonArray();
        for (Integer value : values) {
            arr.add(value);
        }
        return arr;
    }

    public record ImageResource(byte[] data, String mimeType) {
    }
}
