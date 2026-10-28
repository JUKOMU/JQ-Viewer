package io.github.jukomu.desktop.feature.network;

import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.api.model.JmPhoto;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import io.github.jukomu.jmcomic.core.constant.JmConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** 在不改变 JM 库实现的前提下测量并应用图片 CDN Host。 */
public final class ImageHostOptimizer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageHostOptimizer.class);

    public record HostResult(String host, boolean available, long elapsedMs,
                             long bytes, long bytesPerSecond, String error) {
    }

    private record RoundResult(long elapsedMs, long bytes, long bytesPerSecond) {
    }

    /** 完整测速结果；未选出 Host 时 selectedHost 为 null。 */
    public record Result(String selectedHost, List<HostResult> hosts, long runId, long revision) {
    }

    /** 当前图片线路测速状态，供事件和页面快照查询共同使用。 */
    public record Progress(long runId, long revision, boolean running, String currentHost,
                           List<HostResult> hosts, String error) {
    }

    private static final String SAMPLE_CHAPTER_ID = "1064001";
    private static final int SAMPLE_IMAGE_COUNT = 5;
    private static final int SAMPLE_ROUNDS = 2;
    private static final long IMAGE_PROBE_TIMEOUT_SECONDS = 3;
    private static final long HOST_PROBE_TIMEOUT_SECONDS = 32;
    private static final HttpClient IMAGE_PROBE_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(IMAGE_PROBE_TIMEOUT_SECONDS))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private static final List<String> INITIAL_HOSTS = List.copyOf(JmConstants.DEFAULT_IMAGE_DOMAINS);
    private static final AtomicLong RUN_IDS = new AtomicLong();
    private static final AtomicLong REVISIONS = new AtomicLong();
    private static volatile Progress latestProgress = new Progress(
        0, 0, false, null, List.of(), null);

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

    /** 返回最近一次图片线路测速状态。 */
    public static Progress status() {
        return latestProgress;
    }

    /** 使用固定章节前五张图片重复两轮，按有效下载吞吐量选择 Host。 */
    public static synchronized Result optimize(JmApiClient client) {
        return optimize(client, ignored -> {
        });
    }

    /** 使用固定样本测速，并在每个 Host 开始和完成时报告进度。 */
    public static synchronized Result optimize(
        JmApiClient client, Consumer<Progress> progressListener) {
        long runId = RUN_IDS.incrementAndGet();
        long runStarted = System.nanoTime();
        List<HostResult> results = new ArrayList<>();
        List<String> hostCandidates = candidates();
        LOGGER.info("image_route operation=optimize status=started runId={} hosts={} hostCount={}",
            runId, hostCandidates, hostCandidates.size());
        publishProgress(runId, true, null, results, null, progressListener);
        try {
            long chapterStarted = System.nanoTime();
            JmPhoto photo;
            try {
                photo = client.getPhoto(SAMPLE_CHAPTER_ID);
            } catch (RuntimeException error) {
                LOGGER.warn("image_route operation=sample_chapter status=failed runId={} chapterId={} elapsedMs={} errorType={}",
                    runId, SAMPLE_CHAPTER_ID, elapsedMs(chapterStarted), error.getClass().getSimpleName());
                throw error;
            }
            LOGGER.info("image_route operation=sample_chapter status=success runId={} chapterId={} elapsedMs={}",
                runId, SAMPLE_CHAPTER_ID, elapsedMs(chapterStarted));
            if (photo.getImages() == null || photo.getImages().size() < SAMPLE_IMAGE_COUNT) {
                throw new IllegalStateException("固定测速章节不足五张图片");
            }
            List<JmImage> samples = photo.getImages().subList(0, SAMPLE_IMAGE_COUNT);
            String selectedHost = null;
            long selectedSpeed = -1;

            for (int hostIndex = 0; hostIndex < hostCandidates.size(); hostIndex++) {
                String host = hostCandidates.get(hostIndex);
                long hostStarted = System.nanoTime();
                long hostDeadline = hostStarted + Duration.ofSeconds(HOST_PROBE_TIMEOUT_SECONDS).toNanos();
                LOGGER.info("image_route operation=host_probe status=started runId={} host={} index={} total={}",
                    runId, host, hostIndex + 1, hostCandidates.size());
                publishProgress(runId, true, host, results, null, progressListener);
                List<RoundResult> rounds = new ArrayList<>();
                String errorMessage = null;
                boolean hostTimedOut = false;
                int timedOutRound = 0;
                int timedOutImage = 0;
                for (int round = 0; round < SAMPLE_ROUNDS; round++) {
                    if (System.nanoTime() >= hostDeadline) {
                        hostTimedOut = true;
                        timedOutRound = round + 1;
                        timedOutImage = 1;
                        break;
                    }
                    long started = System.nanoTime();
                    long bytes = 0;
                    int imageIndex = 0;
                    boolean requestLimitedByHostDeadline = false;
                    try {
                        for (; imageIndex < samples.size(); imageIndex++) {
                            long remainingNanos = hostDeadline - System.nanoTime();
                            if (remainingNanos <= 0) {
                                hostTimedOut = true;
                                timedOutRound = round + 1;
                                timedOutImage = imageIndex + 1;
                                break;
                            }
                            requestLimitedByHostDeadline = remainingNanos
                                <= Duration.ofSeconds(IMAGE_PROBE_TIMEOUT_SECONDS).toNanos();
                            byte[] content = fetchImageBytesWithTimeout(
                                withHost(samples.get(imageIndex), host), remainingNanos);
                            requestLimitedByHostDeadline = false;
                            bytes += content.length;
                        }
                        if (hostTimedOut) break;
                        long elapsedMs = Math.max(1,
                            Duration.ofNanos(System.nanoTime() - started).toMillis());
                        rounds.add(new RoundResult(elapsedMs, bytes, bytes * 1000 / elapsedMs));
                    } catch (IOException | RuntimeException error) {
                        if (requestLimitedByHostDeadline || System.nanoTime() >= hostDeadline) {
                            hostTimedOut = true;
                            timedOutRound = round + 1;
                            timedOutImage = imageIndex + 1;
                            break;
                        }
                        if (errorMessage == null) {
                            errorMessage = error.getMessage() == null
                                ? error.getClass().getSimpleName() : error.getMessage();
                        }
                    }
                }
                long hostElapsedMs = elapsedMs(hostStarted);
                long elapsedMs = 0;
                long bytes = 0;
                long speed = 0;
                boolean available = !hostTimedOut && !rounds.isEmpty();
                if (available) {
                    elapsedMs = rounds.stream().mapToLong(RoundResult::elapsedMs).sum()
                        / rounds.size();
                    bytes = rounds.stream().mapToLong(RoundResult::bytes).sum() / rounds.size();
                    speed = rounds.stream().mapToLong(RoundResult::bytesPerSecond).sum()
                        / rounds.size();
                }
                String resultError = hostTimedOut
                    ? "图片 Host 测速超时：" + HOST_PROBE_TIMEOUT_SECONDS + "s"
                    : (rounds.isEmpty() ? errorMessage : null);
                results.add(new HostResult(host, available, available ? elapsedMs : hostElapsedMs,
                    available ? bytes : 0, available ? speed : 0, resultError));
                if (hostTimedOut) {
                    LOGGER.warn("image_route operation=host_probe status=timeout runId={} host={} "
                            + "index={} total={} elapsedMs={} rounds={} round={} image={}",
                        runId, host, hostIndex + 1, hostCandidates.size(), hostElapsedMs, rounds.size(),
                        timedOutRound, timedOutImage);
                } else if (!available) {
                    LOGGER.warn("image_route operation=host_probe status=failed runId={} host={} "
                            + "index={} total={} elapsedMs={} rounds={} error={}",
                        runId, host, hostIndex + 1, hostCandidates.size(), hostElapsedMs,
                        rounds.size(), resultError);
                } else {
                    LOGGER.info("image_route operation=host_probe status=success runId={} host={} "
                            + "index={} total={} elapsedMs={} rounds={} bytes={} bytesPerSecond={}",
                        runId, host, hostIndex + 1, hostCandidates.size(), hostElapsedMs,
                        rounds.size(), bytes, speed);
                }
                publishProgress(runId, true, null, results, null, progressListener);
                if (available && speed > selectedSpeed) {
                    selectedHost = host;
                    selectedSpeed = speed;
                }
            }
            Progress completed = publishProgress(
                runId, false, null, results, null, progressListener);
            LOGGER.info("image_route operation=optimize status=completed runId={} hosts={} selectedHost={} elapsedMs={}",
                runId, hostCandidates.size(), selectedHost, elapsedMs(runStarted));
            return new Result(selectedHost, List.copyOf(results), runId, completed.revision());
        } catch (RuntimeException error) {
            LOGGER.warn("image_route operation=optimize status=failed runId={} elapsedMs={} errorType={}",
                runId, elapsedMs(runStarted), error.getClass().getSimpleName(), error);
            publishProgress(runId, false, null, results, error.getMessage(), progressListener);
            throw error;
        }
    }

    /** 图片测速使用短时限请求，避免 JM 正常下载策略延长单张图片的等待。 */
    private static byte[] fetchImageBytesWithTimeout(JmImage image, long remainingNanos)
        throws IOException {
        long timeoutNanos = Math.min(
            Duration.ofSeconds(IMAGE_PROBE_TIMEOUT_SECONDS).toNanos(), remainingNanos);
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create(image.getDownloadUrl()))
            .timeout(Duration.ofNanos(Math.max(1, timeoutNanos)))
            .header("user-agent", JmConstants.DEFAULT_API_HEADERS.get("user-agent"))
            .GET();
        try {
            HttpResponse<byte[]> response = IMAGE_PROBE_CLIENT.send(
                request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("图片测速请求失败：HTTP " + response.statusCode());
            }
            byte[] content = response.body();
            if (content.length == 0) {
                throw new IOException("图片测速响应为空");
            }
            return content;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("图片测速被中断", error);
        }
    }

    private static long elapsedMs(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private static Progress publishProgress(
        long runId, boolean running, String currentHost, List<HostResult> hosts, String error,
        Consumer<Progress> progressListener
    ) {
        Progress progress = new Progress(
            runId, REVISIONS.incrementAndGet(), running, currentHost, List.copyOf(hosts), error);
        latestProgress = progress;
        progressListener.accept(progress);
        return progress;
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
