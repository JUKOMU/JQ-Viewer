package io.github.jukomu.desktop.feature.network;

import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.api.model.JmPhoto;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import io.github.jukomu.jmcomic.core.constant.JmConstants;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 在不改变 JM 库实现的前提下测量并应用图片 CDN Host。 */
public final class ImageHostOptimizer {
    public record HostResult(String host, boolean available, long elapsedMs,
                             long bytes, long bytesPerSecond, String error) {
    }

    /** 完整测速结果；未选出 Host 时 selectedHost 为 null。 */
    public record Result(String selectedHost, List<HostResult> hosts) {
    }

    private static final String SAMPLE_CHAPTER_ID = "1064001";
    private static final int SAMPLE_IMAGE_COUNT = 5;
    private static final int SAMPLE_ROUNDS = 2;
    private static final List<String> INITIAL_HOSTS = List.copyOf(JmConstants.DEFAULT_IMAGE_DOMAINS);

    private ImageHostOptimizer() {
    }

    /** 返回客户端初始化后可用于图片请求的 Host 候选。 */
    public static List<String> candidates() {
        LinkedHashSet<String> hosts = new LinkedHashSet<>(INITIAL_HOSTS);
        hosts.addAll(JmConstants.DEFAULT_IMAGE_DOMAINS);
        return List.copyOf(hosts);
    }

    /** 判断 Host 是否仍属于客户端当前候选集合。 */
    public static boolean contains(String host) {
        return host != null && candidates().contains(host);
    }

    /** 将后续由 JM 解析生成的图片 URL 固定到指定 Host。 */
    public static void apply(String host) {
        JmConstants.DEFAULT_IMAGE_DOMAINS = new CopyOnWriteArrayList<>(List.of(host));
    }

    /** 使用固定章节前五张图片重复两轮，按有效下载吞吐量选择 Host。 */
    public static synchronized Result optimize(JmApiClient client) {
        JmPhoto photo = client.getPhoto(SAMPLE_CHAPTER_ID);
        if (photo.getImages() == null || photo.getImages().size() < SAMPLE_IMAGE_COUNT) {
            throw new IllegalStateException("固定测速章节不足五张图片");
        }
        List<JmImage> samples = photo.getImages().subList(0, SAMPLE_IMAGE_COUNT);
        List<HostResult> results = new ArrayList<>();
        String selectedHost = null;
        long selectedSpeed = -1;

        for (String host : candidates()) {
            long started = System.nanoTime();
            long bytes = 0;
            String errorMessage = null;
            try {
                for (int round = 0; round < SAMPLE_ROUNDS; round++) {
                    for (JmImage sample : samples) {
                        byte[] content = client.fetchImageBytes(withHost(sample, host));
                        if (ImageIO.read(new ByteArrayInputStream(content)) == null) {
                            throw new IllegalStateException("图片解码失败");
                        }
                        bytes += content.length;
                    }
                }
            } catch (RuntimeException | java.io.IOException error) {
                errorMessage = error.getMessage();
            }
            long elapsedMs = Math.max(1, Duration.ofNanos(System.nanoTime() - started).toMillis());
            boolean available = errorMessage == null;
            long speed = available ? bytes * 1000 / elapsedMs : 0;
            results.add(new HostResult(host, available, elapsedMs, bytes, speed, errorMessage));
            if (available && speed > selectedSpeed) {
                selectedHost = host;
                selectedSpeed = speed;
            }
        }
        return new Result(selectedHost, List.copyOf(results));
    }

    private static JmImage withHost(JmImage image, String host) {
        URI source = URI.create(image.getUrl());
        try {
            String url = new URI(source.getScheme(), source.getUserInfo(), host,
                source.getPort(), source.getPath(), source.getQuery(), source.getFragment()).toString();
            return new JmImage(image.getPhotoId(), image.getScrambleId(), image.getFilename(),
                url, image.getQueryParams(), image.getSortOrder());
        } catch (java.net.URISyntaxException error) {
            throw new IllegalArgumentException(error);
        }
    }
}
