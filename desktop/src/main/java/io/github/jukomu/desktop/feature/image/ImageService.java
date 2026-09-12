package io.github.jukomu.desktop.feature.image;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.feature.image.model.ImageEvent;
import io.github.jukomu.desktop.feature.image.model.PreloadImagesResponse;
import io.github.jukomu.jmcomic.api.client.JmClient;
import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.api.model.JmPhoto;
import io.github.jukomu.jmcomic.core.crypto.JmImageTool;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** 管理章节图片元数据、实际下载、缩略图生成和事件发布。 */
public final class ImageService {
    private static final int THUMBNAIL_MAX_WIDTH = 300;
    private static final long CACHE_CAPACITY_MB = 256;

    private final JmClient client;
    private final Executor executor;
    private final EventHub events;
    private final ImageCache cache = new ImageCache(CACHE_CAPACITY_MB * 1024 * 1024);
    private final Map<String, Map<Integer, JmImage>> images = new ConcurrentHashMap<>();
    private final Map<String, Long> generations = new ConcurrentHashMap<>();
    private final Map<String, Long> pending = new ConcurrentHashMap<>();
    private long generation;

    public ImageService(JmClient client, Executor executor, EventHub events) {
        this.client = client;
        this.executor = executor;
        this.events = events;
        ImageIO.scanForPlugins();
    }

    public void register(JmPhoto photo) {
        Map<Integer, JmImage> photoImages = new ConcurrentHashMap<>();
        for (JmImage image : safe(photo.getImages())) photoImages.put(image.getSortOrder(), image);
        images.put(photo.getId(), photoImages);
    }

    public PreloadImagesResponse preload(
            String photoId,
            String type,
            List<JmImage> input,
            boolean replacePending
    ) {
        validateType(type);
        if (input == null) throw ApiException.invalidRequest("images必须是数组");
        Map<Integer, JmImage> photoImages = images.computeIfAbsent(photoId, ignored -> new ConcurrentHashMap<>());
        long currentGeneration;
        synchronized (this) {
            currentGeneration = replacePending ? ++generation : generations.getOrDefault(photoId + "/" + type, 0L);
            if (replacePending) generations.put(photoId + "/" + type, currentGeneration);
        }

        List<Integer> cached = new ArrayList<>();
        List<Integer> waiting = new ArrayList<>();
        for (JmImage value : input) {
            if (value == null) throw ApiException.invalidRequest("images包含无效元素");
            JmImage image = toImage(photoId, value);
            photoImages.put(image.getSortOrder(), image);
            String cacheKey = key(photoId, image.getSortOrder(), type);
            if (cache.get(cacheKey) != null) {
                cached.add(image.getSortOrder());
                continue;
            }
            ImageCache.Entry original = cache.get(key(photoId, image.getSortOrder(), "image"));
            if ("thumb".equals(type) && original != null) {
                try {
                    cache.put(cacheKey, thumbnail(original.bytes()), "image/jpeg");
                    cached.add(image.getSortOrder());
                    publish(photoId, image.getSortOrder(), type);
                    continue;
                } catch (IOException exception) {
                    // 原图不可解析时按普通网络下载继续处理。
                }
            }
            waiting.add(image.getSortOrder());
            schedule(photoId, type, image, currentGeneration);
        }
        return new PreloadImagesResponse(List.copyOf(cached), List.copyOf(waiting));
    }

    public SuccessResponse retry(String photoId, JmImage value) {
        if (value == null) throw ApiException.invalidRequest("image必须是对象");
        JmImage image = toImage(photoId, value);
        cache.remove(key(photoId, image.getSortOrder(), "image"));
        cache.remove(key(photoId, image.getSortOrder(), "thumb"));
        images.computeIfAbsent(photoId, ignored -> new ConcurrentHashMap<>()).put(image.getSortOrder(), image);
        long currentGeneration;
        synchronized (this) {
            currentGeneration = ++generation;
            generations.put(photoId + "/image", currentGeneration);
        }
        schedule(photoId, "image", image, currentGeneration);
        return SuccessResponse.ok();
    }

    public ImageCache.Entry read(String photoId, int sortOrder, String type) {
        validateType(type);
        ImageCache.Entry cached = cache.get(key(photoId, sortOrder, type));
        if (cached != null) return cached;
        JmImage image = images.getOrDefault(photoId, Map.of()).get(sortOrder);
        if (image == null) {
            JmPhoto photo = client.getPhoto(photoId);
            register(photo);
            image = images.getOrDefault(photoId, Map.of()).get(sortOrder);
        }
        if (image == null) throw new ApiException("not-found", 404, "图片不存在");
        try {
            byte[] bytes = client.fetchImageBytes(image);
            String mime = mime(image);
            cache.put(key(photoId, sortOrder, "image"), bytes, mime);
            if ("thumb".equals(type)) {
                byte[] thumb = thumbnail(bytes);
                cache.put(key(photoId, sortOrder, "thumb"), thumb, "image/jpeg");
                return new ImageCache.Entry(thumb, "image/jpeg");
            }
            return new ImageCache.Entry(bytes, mime);
        } catch (IOException exception) {
            throw new ApiException("internal", 502, "图片处理失败: " + exception.getMessage());
        }
    }

    public ImageCache.Entry readCached(String photoId, int sortOrder, String type) {
        return cache.get(key(photoId, sortOrder, type));
    }

    public ImageCache cache() {
        return cache;
    }

    private void schedule(String photoId, String type, JmImage image, long currentGeneration) {
        String scope = photoId + "/" + type;
        String pendingKey = key(photoId, image.getSortOrder(), type);
        Long old = pending.put(pendingKey, currentGeneration);
        if (old != null && old == currentGeneration) return;
        try {
            executor.execute(() -> {
                try {
                    if (generations.getOrDefault(scope, currentGeneration) != currentGeneration) return;
                    byte[] bytes = client.fetchImageBytes(image);
                    if (generations.getOrDefault(scope, currentGeneration) != currentGeneration) return;
                    String mime = mime(image);
                    cache.put(key(photoId, image.getSortOrder(), "image"), bytes, mime);
                    if ("thumb".equals(type)) cache.put(pendingKey, thumbnail(bytes), "image/jpeg");
                    publish(photoId, image.getSortOrder(), type);
                } catch (Exception exception) {
                    if (generations.getOrDefault(scope, currentGeneration) == currentGeneration) {
                        events.publish("imageFailed", new ImageEvent(
                                photoId,
                                image.getSortOrder(),
                                type
                        ));
                    }
                } finally {
                    pending.remove(pendingKey, currentGeneration);
                }
            });
        } catch (RejectedExecutionException exception) {
            pending.remove(pendingKey, currentGeneration);
            throw new ApiException("internal", 503, "图片任务队列已满");
        }
    }

    private void publish(String photoId, int sortOrder, String type) {
        events.publish("imageReady", new ImageEvent(photoId, sortOrder, type));
    }

    private static JmImage toImage(String photoId, JmImage value) {
        int sortOrder = value.getSortOrder();
        if (sortOrder <= 0) throw ApiException.invalidRequest("sortOrder必须是正整数");
        return new JmImage(
                photoId,
                text(value.getScrambleId()),
                text(value.getFilename()),
                text(value.getUrl()),
                text(value.getQueryParams()),
                sortOrder
        );
    }

    private static void validateType(String type) {
        if (!"image".equals(type) && !"thumb".equals(type)) {
            throw ApiException.invalidRequest("type必须是image或thumb");
        }
    }

    private static String key(String photoId, int sortOrder, String type) {
        return photoId + "/" + sortOrder + "/" + type;
    }

    private static String mime(JmImage image) {
        String format = JmImageTool.getFormatName(image.getFilename());
        return "image/" + (format == null || format.isBlank() ? "jpeg" : format);
    }

    private static byte[] thumbnail(byte[] bytes) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
        if (source == null) throw new IOException("无法解析图片");
        int width = source.getWidth();
        int height = source.getHeight();
        if (width > THUMBNAIL_MAX_WIDTH) {
            int targetHeight = Math.max(1, (int) Math.round(height * (THUMBNAIL_MAX_WIDTH / (double) width)));
            BufferedImage scaled = new BufferedImage(THUMBNAIL_MAX_WIDTH, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
            graphics.dispose();
            source = scaled;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(source, "jpg", output)) throw new IOException("JPEG编码器不可用");
        return output.toByteArray();
    }

    private static List<JmImage> safe(List<JmImage> value) {
        return value == null ? List.of() : value;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
