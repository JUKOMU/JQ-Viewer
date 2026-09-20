package io.github.jukomu.desktop.feature.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.update.DesktopUpdateManifest.Artifact;
import io.github.jukomu.desktop.feature.update.DesktopUpdateManifest.VerifiedRelease;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Desktop 原生更新：双源验签检查、竞速下载、完整性校验与安装交接。 */
public final class DesktopUpdateService implements AutoCloseable {
    static final URI GITHUB_MANIFEST = URI.create(
            "https://github.com/JUKOMU/JQ-Viewer/releases/latest/download/latest.json");
    static final URI GITHUB_SIGNATURE = URI.create(
            "https://github.com/JUKOMU/JQ-Viewer/releases/latest/download/latest.json.sig");
    static final URI GITEE_LATEST_RELEASE = URI.create(
            "https://gitee.com/api/v5/repos/jukomu/jq-viewer/releases/latest");

    private static final int MANIFEST_MAX_BYTES = 1024 * 1024;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final Duration MANIFEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(30);
    private static final long PROGRESS_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    private final DesktopUpdateConfiguration configuration;
    private final ObjectMapper mapper;
    private final EventHub events;
    private final Path updateDirectory;
    private final Consumer<Path> installationLauncher;
    private final HttpClient http;
    private final ExecutorService executor;
    private final ScheduledExecutorService timeoutExecutor;
    private final Duration downloadTimeout;
    private final Object stateLock = new Object();
    private final AtomicLong revision = new AtomicLong();

    private volatile Snapshot snapshot = Snapshot.idle();
    private volatile VerifiedRelease checkedRelease;
    private volatile DownloadSession activeSession;
    private volatile Path readyPackage;
    private boolean checkInProgress;
    private Path installingPackage;
    private volatile Runnable exitRequest;
    private volatile boolean closed;

    public DesktopUpdateService(
            DesktopUpdateConfiguration configuration,
            ObjectMapper mapper,
            EventHub events,
            Paths paths
    ) {
        this(configuration, mapper, events, paths,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build(),
                Executors.newFixedThreadPool(3, runnable -> {
                    Thread thread = new Thread(runnable, "jq-viewer-update");
                    thread.setDaemon(false);
                    return thread;
                }),
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "jq-viewer-update-timeout");
                    thread.setDaemon(true);
                    return thread;
                }),
                DOWNLOAD_TIMEOUT,
                new DesktopUpdateInstaller(configuration, paths)::launch);
    }

    DesktopUpdateService(
            DesktopUpdateConfiguration configuration,
            ObjectMapper mapper,
            EventHub events,
            Paths paths,
            HttpClient http,
            ExecutorService executor
    ) {
        this(configuration, mapper, events, paths, http, executor,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "jq-viewer-update-timeout");
                    thread.setDaemon(true);
                    return thread;
                }),
                DOWNLOAD_TIMEOUT,
                new DesktopUpdateInstaller(configuration, paths)::launch);
    }

    DesktopUpdateService(
            DesktopUpdateConfiguration configuration,
            ObjectMapper mapper,
            EventHub events,
            Paths paths,
            HttpClient http,
            ExecutorService executor,
            ScheduledExecutorService timeoutExecutor,
            Duration downloadTimeout,
            Consumer<Path> installationLauncher
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.events = Objects.requireNonNull(events, "events");
        this.updateDirectory = Objects.requireNonNull(paths, "paths")
                .stateDirectory().resolve("update");
        this.installationLauncher = Objects.requireNonNull(
                installationLauncher, "installationLauncher");
        this.http = Objects.requireNonNull(http, "http");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.timeoutExecutor = Objects.requireNonNull(timeoutExecutor, "timeoutExecutor");
        this.downloadTimeout = Objects.requireNonNull(downloadTimeout, "downloadTimeout");
        if (downloadTimeout.isZero() || downloadTimeout.isNegative()) {
            throw new IllegalArgumentException("downloadTimeout必须为正数");
        }
    }

    public void attachExitRequest(Runnable request) {
        exitRequest = Objects.requireNonNull(request, "request");
    }

    public void detachExitRequest() {
        exitRequest = null;
    }

    /** 并行读取 GitHub/Gitee 的同一份清单和签名，双源均可用时要求字节一致。 */
    public CheckResult check() {
        ensureOpen();
        configuration.requireConfigured();
        synchronized (stateLock) {
            if (activeSession != null) throw new UpdateException("更新下载正在进行中");
            if (checkInProgress) throw new UpdateException("更新检查正在进行中");
            if (installingPackage != null) throw new UpdateException("更新安装正在进行中");
            checkInProgress = true;
        }
        try {
            Future<ManifestAttempt> github = executor.submit(this::fetchGithubManifest);
            Future<ManifestAttempt> gitee = executor.submit(this::fetchGiteeManifest);
            ManifestAttempt githubAttempt = awaitAttempt(github, "GitHub");
            ManifestAttempt giteeAttempt = awaitAttempt(gitee, "Gitee");
            VerifiedRelease release = resolveManifests(githubAttempt, giteeAttempt);
            synchronized (stateLock) {
                checkedRelease = release;
                readyPackage = null;
                checkInProgress = false;
            }
            boolean available = compareVersions(
                    release.response().versionName(), configuration.currentVersion()) > 0;
            publish(available ? "update_available" : "up_to_date", "", 0, 0,
                    release.artifact().sizeBytes(), 0, "");
            return new CheckResult(available, release.response());
        } catch (RuntimeException exception) {
            synchronized (stateLock) {
                checkedRelease = null;
                readyPackage = null;
                checkInProgress = false;
            }
            publish("failed", "", 0, 0, 0, 0, exception.getMessage());
            throw exception;
        }
    }

    public StartResult start() {
        ensureOpen();
        configuration.requireConfigured();
        synchronized (stateLock) {
            if (checkInProgress || installingPackage != null) return new StartResult(false);
            if (activeSession != null) return new StartResult(false);
            VerifiedRelease release = checkedRelease;
            if (release == null || compareVersions(
                    release.response().versionName(), configuration.currentVersion()) <= 0) {
                return new StartResult(false);
            }
            try {
                Files.createDirectories(updateDirectory);
                cleanupDownloadFiles();
            } catch (IOException exception) {
                throw new UpdateException("无法创建 Desktop 更新目录", exception);
            }
            readyPackage = null;
            DownloadSession session = new DownloadSession(release);
            activeSession = session;
            publish("racing", "racing", 0, 0, release.artifact().sizeBytes(), 0, "");
            session.futures.add(executor.submit(() -> download(session, Source.GITHUB)));
            session.futures.add(executor.submit(() -> download(session, Source.GITEE)));
            session.futures.add(executor.submit(() -> finishSession(session)));
            return new StartResult(true);
        }
    }

    public CancelResult cancel() {
        DownloadSession session;
        synchronized (stateLock) {
            session = activeSession;
            if (session == null) return new CancelResult(false);
        }
        synchronized (session) {
            synchronized (stateLock) {
                if (activeSession != session) return new CancelResult(false);
                if (session.winner.get() != null) return new CancelResult(false);
                session.cancelled.set(true);
                activeSession = null;
            }
        }
        session.futures.forEach(future -> future.cancel(true));
        deleteQuietly(session.githubPath);
        deleteQuietly(session.giteePath);
        publish("cancelled", "", session.githubBytes.get(), session.giteeBytes.get(),
                session.release.artifact().sizeBytes(), 0, "");
        return new CancelResult(true);
    }

    public InstallResult install() {
        ensureOpen();
        Path packagePath;
        Runnable request;
        VerifiedRelease release;
        synchronized (stateLock) {
            if (installingPackage != null) throw new UpdateException("更新安装正在进行中");
            packagePath = readyPackage;
            if (packagePath == null || !Files.isRegularFile(packagePath)) {
                throw new UpdateException("没有可安装的 Desktop 更新包");
            }
            request = exitRequest;
            if (request == null) throw new UpdateException("Desktop 宿主尚未连接更新退出流程");
            release = checkedRelease;
            if (release == null) throw new UpdateException("更新清单状态已失效，请重新检查更新");
            readyPackage = null;
            installingPackage = packagePath;
        }
        try {
            installationLauncher.accept(packagePath);
        } catch (RuntimeException exception) {
            synchronized (stateLock) {
                if (Objects.equals(installingPackage, packagePath)) {
                    installingPackage = null;
                    if (Files.isRegularFile(packagePath)) readyPackage = packagePath;
                }
            }
            throw exception;
        }
        publish("installing", "", Files.exists(packagePath) ? sizeQuietly(packagePath) : 0,
                0, release.artifact().sizeBytes(), 0, "");
        request.run();
        return new InstallResult(true, false);
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    private ManifestAttempt fetchGithubManifest() {
        try {
            byte[] manifest = fetchLimited(GITHUB_MANIFEST, MANIFEST_MAX_BYTES);
            byte[] signature = fetchLimited(GITHUB_SIGNATURE, MANIFEST_MAX_BYTES);
            return ManifestAttempt.success(DesktopUpdateManifest.verifyAndParse(
                    manifest, signature, configuration, mapper));
        } catch (Exception exception) {
            return ManifestAttempt.failure(messageOf(exception));
        }
    }

    private ManifestAttempt fetchGiteeManifest() {
        try {
            JsonNode release = mapper.readTree(fetchLimited(GITEE_LATEST_RELEASE, MANIFEST_MAX_BYTES));
            String tag = release.path("tag_name").asText("").trim();
            JsonNode assets = release.path("assets");
            if (!assets.isArray() && release.path("id").canConvertToLong()) {
                URI attachments = URI.create("https://gitee.com/api/v5/repos/jukomu/jq-viewer/releases/"
                        + release.path("id").longValue() + "/attach_files?per_page=100");
                assets = mapper.readTree(fetchLimited(attachments, MANIFEST_MAX_BYTES));
            }
            URI manifestUrl = findGiteeAsset(assets, tag, "latest.json");
            URI signatureUrl = findGiteeAsset(assets, tag, "latest.json.sig");
            byte[] manifest = fetchLimited(manifestUrl, MANIFEST_MAX_BYTES);
            byte[] signature = fetchLimited(signatureUrl, MANIFEST_MAX_BYTES);
            return ManifestAttempt.success(DesktopUpdateManifest.verifyAndParse(
                    manifest, signature, configuration, mapper));
        } catch (Exception exception) {
            return ManifestAttempt.failure(messageOf(exception));
        }
    }

    private URI findGiteeAsset(JsonNode assets, String tag, String name) {
        if (!assets.isArray()) throw new UpdateException("Gitee 发布附件列表不可用");
        for (JsonNode asset : assets) {
            if (!name.equals(asset.path("name").asText())) continue;
            String value = asset.path("browser_download_url").asText("");
            DesktopUpdateManifest.requireReleaseUrl(
                    value, "gitee.com", "/jukomu/jq-viewer", tag, name);
            return URI.create(value);
        }
        throw new UpdateException("Gitee 缺少 " + name);
    }

    private VerifiedRelease resolveManifests(ManifestAttempt github, ManifestAttempt gitee) {
        if (github.release != null && gitee.release != null) {
            if (!DesktopUpdateManifest.sameRelease(github.release, gitee.release)) {
                throw new UpdateException("GitHub 与 Gitee 发布元数据不一致");
            }
            return github.release;
        }
        if (github.release != null) return github.release;
        if (gitee.release != null) return gitee.release;
        throw new UpdateException("GitHub 与 Gitee 更新元数据均不可用。GitHub: "
                + github.error + "；Gitee: " + gitee.error);
    }

    private ManifestAttempt awaitAttempt(Future<ManifestAttempt> future, String source) {
        try {
            return future.get(45, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ManifestAttempt.failure(source + " 更新检查已取消");
        } catch (Exception exception) {
            future.cancel(true);
            return ManifestAttempt.failure(source + " 更新检查失败: " + messageOf(exception));
        }
    }

    private byte[] fetchLimited(URI uri, int maximumBytes) throws IOException, InterruptedException {
        long deadline = deadlineAfter(MANIFEST_TIMEOUT);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(MANIFEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode());
        }
        try (InputStream input = response.body()) {
            AtomicBoolean timedOut = new AtomicBoolean();
            ScheduledFuture<?> timeout = closeAtDeadline(input, deadline, timedOut);
            byte[] buffer = new byte[BUFFER_SIZE];
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            try {
                for (int read; (read = input.read(buffer)) >= 0; ) {
                    if (read == 0) continue;
                    if (output.size() + read > maximumBytes) {
                        throw new IOException("响应超过允许大小");
                    }
                    output.write(buffer, 0, read);
                }
            } catch (IOException exception) {
                throw bodyReadException(timedOut, exception);
            } finally {
                timeout.cancel(false);
            }
            if (timedOut.get()) throw new IOException("响应正文读取超时");
            return output.toByteArray();
        }
    }

    private void download(DownloadSession session, Source source) {
        Artifact artifact = session.release.artifact();
        Path target = source == Source.GITHUB ? session.githubPath : session.giteePath;
        URI uri = URI.create(source == Source.GITHUB
                ? artifact.sources().github()
                : artifact.sources().gitee());
        try {
            long deadline = deadlineAfter(downloadTimeout);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(downloadTimeout)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new IOException("HTTP " + response.statusCode());
            }
            long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declaredLength > artifact.sizeBytes()) {
                response.body().close();
                throw new IOException("下载响应超过发布清单大小");
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            AtomicLong counter = source == Source.GITHUB ? session.githubBytes : session.giteeBytes;
            try (InputStream input = response.body();
                 var output = Files.newOutputStream(target, StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                AtomicBoolean timedOut = new AtomicBoolean();
                ScheduledFuture<?> timeout = closeAtDeadline(input, deadline, timedOut);
                byte[] buffer = new byte[BUFFER_SIZE];
                try {
                    for (int read; (read = input.read(buffer)) >= 0; ) {
                        if (read == 0) continue;
                        if (session.cancelled.get() || session.winner.get() != null) {
                            throw new InterruptedException("download cancelled");
                        }
                        long total = counter.addAndGet(read);
                        if (total > artifact.sizeBytes()) {
                            throw new IOException("下载文件超过发布清单大小");
                        }
                        output.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        publishProgress(session);
                    }
                } catch (IOException exception) {
                    throw bodyReadException(timedOut, exception);
                } finally {
                    timeout.cancel(false);
                }
                if (timedOut.get()) throw new IOException("响应正文读取超时");
            }
            synchronized (session) {
                if (session.cancelled.get() || session.winner.get() != null) {
                    deleteQuietly(target);
                    return;
                }
                publish("verifying", label(source), session.githubBytes.get(),
                        session.giteeBytes.get(), artifact.sizeBytes(), 0, "");
            }
            long size = Files.size(target);
            String sha256 = HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
            if (size != artifact.sizeBytes() || !sha256.equals(artifact.sha256())) {
                throw new IOException("下载包大小或 SHA-256 与发布清单不一致");
            }

            synchronized (session) {
                if (session.cancelled.get()) {
                    throw new InterruptedException("download cancelled");
                }
                if (session.winner.get() == null) {
                    Path completed = updateDirectory.resolve(artifact.name());
                    moveReplacing(target, completed);
                    synchronized (stateLock) {
                        if (activeSession != session || session.cancelled.get()) {
                            deleteQuietly(completed);
                            throw new InterruptedException("download cancelled");
                        }
                        readyPackage = completed;
                        session.winner.set(source);
                    }
                    publish("ready_to_install", label(source),
                            session.githubBytes.get(), session.giteeBytes.get(),
                            artifact.sizeBytes(), 0, "");
                } else {
                    deleteQuietly(target);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            deleteQuietly(target);
        } catch (Exception exception) {
            session.recordError(source, messageOf(exception));
            deleteQuietly(target);
        } finally {
            session.finished.countDown();
        }
    }

    private void finishSession(DownloadSession session) {
        try {
            session.finished.await();
            synchronized (session) {
                if (!session.cancelled.get() && session.winner.get() == null) {
                    publish("failed", "", session.githubBytes.get(), session.giteeBytes.get(),
                            session.release.artifact().sizeBytes(), 0,
                            "GitHub 与 Gitee 更新包均下载失败。GitHub: "
                                    + session.githubError.get() + "；Gitee: "
                                    + session.giteeError.get());
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (stateLock) {
                if (activeSession == session) activeSession = null;
            }
            if (session.winner.get() != Source.GITHUB) deleteQuietly(session.githubPath);
            if (session.winner.get() != Source.GITEE) deleteQuietly(session.giteePath);
        }
    }

    private void publishProgress(DownloadSession session) {
        synchronized (session) {
            if (session.cancelled.get() || session.winner.get() != null) return;
            long now = System.nanoTime();
            long previous = session.lastProgressNanos.get();
            if (now - previous < PROGRESS_INTERVAL_NANOS
                    || !session.lastProgressNanos.compareAndSet(previous, now)) return;
            long total = session.githubBytes.get() + session.giteeBytes.get();
            long previousTotal = session.lastProgressBytes.getAndSet(total);
            double seconds = Math.max(0.001, (now - previous) / 1_000_000_000d);
            long speed = Math.max(0, Math.round((total - previousTotal) / seconds));
            publish("racing", "racing", session.githubBytes.get(), session.giteeBytes.get(),
                    session.release.artifact().sizeBytes(), speed, "");
        }
    }

    private void publish(
            String phase,
            String source,
            long githubBytes,
            long giteeBytes,
            long totalBytes,
            long speed,
            String error
    ) {
        Snapshot next = new Snapshot(revision.incrementAndGet(), phase, source,
                githubBytes, giteeBytes, totalBytes, speed, error);
        snapshot = next;
        events.publish("updateProgress", next);
    }

    private void cleanupDownloadFiles() throws IOException {
        if (!Files.isDirectory(updateDirectory)) return;
        try (var entries = Files.list(updateDirectory)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.endsWith(".part") || name.startsWith("JQ-Viewer-")) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private long deadlineAfter(Duration timeout) {
        long timeoutNanos = timeout.toNanos();
        long now = System.nanoTime();
        return now > Long.MAX_VALUE - timeoutNanos ? Long.MAX_VALUE : now + timeoutNanos;
    }

    private ScheduledFuture<?> closeAtDeadline(
            InputStream input,
            long deadline,
            AtomicBoolean timedOut
    ) {
        long delay = Math.max(0, deadline - System.nanoTime());
        return timeoutExecutor.schedule(() -> {
            timedOut.set(true);
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }, delay, TimeUnit.NANOSECONDS);
    }

    private static IOException bodyReadException(
            AtomicBoolean timedOut,
            IOException exception
    ) {
        return timedOut.get()
                ? new IOException("响应正文读取超时")
                : exception;
    }

    static int compareVersions(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        if (leftParts.length != 3 || rightParts.length != 3) {
            throw new UpdateException("版本号格式无效");
        }
        try {
            for (int index = 0; index < 3; index++) {
                int comparison = Integer.compare(
                        Integer.parseInt(leftParts[index]), Integer.parseInt(rightParts[index]));
                if (comparison != 0) return comparison;
            }
        } catch (NumberFormatException exception) {
            throw new UpdateException("版本号格式无效", exception);
        }
        return 0;
    }

    private static String label(Source source) {
        return source == Source.GITHUB ? "GitHub" : "Gitee";
    }

    private static long sizeQuietly(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ignored) {
            return 0;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static String messageOf(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null && current != current.getCause()) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }

    private void ensureOpen() {
        if (closed) throw new UpdateException("Desktop 更新服务已关闭");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        cancel();
        executor.shutdownNow();
        timeoutExecutor.shutdownNow();
    }

    public record CheckResult(boolean updateAvailable,
                              DesktopUpdateManifest.ManifestResponse manifest) {
    }

    public record StartResult(boolean started) {
    }

    public record CancelResult(boolean cancelled) {
    }

    public record InstallResult(boolean started, boolean permissionRequired) {
    }

    public record Snapshot(
            long revision,
            String phase,
            String source,
            long githubBytes,
            long giteeBytes,
            long totalBytes,
            long speedBytesPerSecond,
            String error
    ) {
        public static Snapshot idle() {
            return new Snapshot(0, "idle", "", 0, 0, 0, 0, "");
        }
    }

    private enum Source {
        GITHUB,
        GITEE
    }

    private record ManifestAttempt(VerifiedRelease release, String error) {
        static ManifestAttempt success(VerifiedRelease release) {
            return new ManifestAttempt(release, "");
        }

        static ManifestAttempt failure(String error) {
            return new ManifestAttempt(null, error == null || error.isBlank() ? "未知错误" : error);
        }
    }

    private final class DownloadSession {
        private final VerifiedRelease release;
        private final Path githubPath;
        private final Path giteePath;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Source> winner = new AtomicReference<>();
        private final AtomicLong githubBytes = new AtomicLong();
        private final AtomicLong giteeBytes = new AtomicLong();
        private final AtomicReference<String> githubError = new AtomicReference<>("未知错误");
        private final AtomicReference<String> giteeError = new AtomicReference<>("未知错误");
        private final AtomicLong lastProgressNanos = new AtomicLong(System.nanoTime());
        private final AtomicLong lastProgressBytes = new AtomicLong();
        private final CountDownLatch finished = new CountDownLatch(2);
        private final List<Future<?>> futures = new java.util.concurrent.CopyOnWriteArrayList<>();

        private DownloadSession(VerifiedRelease release) {
            this.release = release;
            String token = UUID.randomUUID().toString();
            this.githubPath = updateDirectory.resolve(
                    release.artifact().name() + "." + token + ".github.part");
            this.giteePath = updateDirectory.resolve(
                    release.artifact().name() + "." + token + ".gitee.part");
        }

        private void recordError(Source source, String error) {
            (source == Source.GITHUB ? githubError : giteeError).set(error);
        }
    }
}
