package io.github.jukomu.desktop.feature.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.desktop.data.Paths;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopUpdateServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] PACKAGE_BYTES = "desktop-update-package".getBytes(StandardCharsets.UTF_8);

    @Test
    void doesNotStartDownloadWhileARefreshCheckIsInProgress() throws Exception {
        try (Fixture fixture = fixture(path -> {
        }, Duration.ofSeconds(2))) {
            assertTrue(fixture.service.check().updateAvailable());
            fixture.http.blockNextGithubManifest.set(true);

            CompletableFuture<DesktopUpdateService.CheckResult> checking = CompletableFuture.supplyAsync(
                    fixture.service::check);
            assertTrue(fixture.http.blockedManifestEntered.await(2, TimeUnit.SECONDS));

            assertFalse(fixture.service.start().started());

            fixture.http.releaseBlockedManifest.countDown();
            assertTrue(checking.get(2, TimeUnit.SECONDS).updateAvailable());
        }
    }

    @Test
    void onlyOneInstallRequestCanClaimTheReadyPackage() throws Exception {
        CountDownLatch launcherEntered = new CountDownLatch(1);
        CountDownLatch releaseLauncher = new CountDownLatch(1);
        AtomicInteger launches = new AtomicInteger();
        Consumer<Path> launcher = path -> {
            launches.incrementAndGet();
            launcherEntered.countDown();
            await(releaseLauncher);
        };
        try (Fixture fixture = fixture(launcher, Duration.ofSeconds(2))) {
            fixture.service.check();
            assertTrue(fixture.service.start().started());
            awaitPhase(fixture.service, "ready_to_install");
            fixture.service.attachExitRequest(() -> {
            });

            CompletableFuture<DesktopUpdateService.InstallResult> installing =
                    CompletableFuture.supplyAsync(fixture.service::install);
            assertTrue(launcherEntered.await(2, TimeUnit.SECONDS));
            assertThrows(UpdateException.class, fixture.service::install);

            releaseLauncher.countDown();
            assertTrue(installing.get(2, TimeUnit.SECONDS).started());
            assertTrue(launches.get() == 1);
        }
    }

    @Test
    void restoresReadyPackageWhenInstallerLaunchFails() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        Consumer<Path> launcher = path -> {
            if (fail.getAndSet(false)) throw new UpdateException("launch failed");
        };
        try (Fixture fixture = fixture(launcher, Duration.ofSeconds(2))) {
            fixture.service.check();
            fixture.service.start();
            awaitPhase(fixture.service, "ready_to_install");
            fixture.service.attachExitRequest(() -> {
            });

            assertThrows(UpdateException.class, fixture.service::install);
            assertTrue(fixture.service.install().started());
        }
    }

    @Test
    void closesStalledResponseBodiesAtTheCompleteDownloadDeadline() throws Exception {
        try (Fixture fixture = fixture(path -> {
        }, Duration.ofMillis(80))) {
            fixture.service.check();
            fixture.http.stallDownloads.set(true);

            assertTrue(fixture.service.start().started());
            awaitPhase(fixture.service, "failed");

            assertTrue(fixture.service.snapshot().error().contains("响应正文读取超时"),
                    fixture.service.snapshot()::toString);
            assertThrows(UpdateException.class, fixture.service::install);
        }
    }

    @Test
    void lateDownloadCannotOverwriteTheReadyState() throws Exception {
        try (Fixture fixture = fixture(path -> {
        }, Duration.ofSeconds(2))) {
            fixture.http.blockGithubDownloadAtEof.set(true);
            fixture.service.check();

            assertTrue(fixture.service.start().started());
            assertTrue(fixture.http.githubDownloadReachedEof.await(2, TimeUnit.SECONDS));
            awaitPhase(fixture.service, "ready_to_install");

            fixture.http.releaseGithubDownloadEof.countDown();
            fixture.executor.shutdown();
            assertTrue(fixture.executor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue("ready_to_install".equals(fixture.service.snapshot().phase()),
                    fixture.service.snapshot()::toString);
        }
    }

    private static Fixture fixture(Consumer<Path> launcher, Duration downloadTimeout) throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-update-service-");
        Paths paths = new Paths(root.resolve("program"), root.resolve("home"), Map.of(), "Linux");
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        DesktopUpdateConfiguration configuration = new DesktopUpdateConfiguration(
                "1.4.6", "linux", "x64", "portable", "tar.gz",
                "jq-viewer-release-1", keyPair.getPublic().getEncoded(),
                root.resolve("JQ-Viewer/bin/JQ-Viewer"), root.resolve("JQ-Viewer"));
        byte[] manifest = manifest();
        byte[] signature = signature(manifest, keyPair);
        TestHttpClient http = new TestHttpClient(manifest, signature);
        EventHub events = new EventHub(MAPPER);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        DesktopUpdateService service = new DesktopUpdateService(
                configuration, MAPPER, events, paths, http, executor, timeoutExecutor,
                downloadTimeout, launcher);
        return new Fixture(service, events, http, executor);
    }

    private static byte[] manifest() throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("tag", "v1.4.7");
        root.put("versionName", "1.4.7");
        root.put("versionCode", 147);
        root.put("packageName", "io.github.jukomu.jqviewer");
        root.put("apkName", "JQ-Viewer-1_4_7-universal.apk");
        root.put("sizeBytes", 1);
        root.put("sha256", "a".repeat(64));
        root.put("signingCertificateSha256", "b".repeat(64));
        root.put("releaseNotes", "release");
        root.putObject("sources")
                .put("github", "https://github.com/JUKOMU/JQ-Viewer/releases/download/v1.4.7/JQ-Viewer-1_4_7-universal.apk")
                .put("gitee", "https://gitee.com/jukomu/jq-viewer/releases/download/v1.4.7/JQ-Viewer-1_4_7-universal.apk");
        ArrayNode artifacts = root.putObject("desktop").putArray("artifacts");
        artifact(artifacts, "windows", "x64", "installer", "exe", "x64", "arm64");
        artifact(artifacts, "windows", "x64", "portable", "zip", "x64", "arm64");
        for (String architecture : new String[]{"x64", "arm64"}) {
            artifact(artifacts, "linux", architecture, "installer", "deb", architecture);
            artifact(artifacts, "linux", architecture, "installer", "rpm", architecture);
            artifact(artifacts, "linux", architecture, "portable", "tar.gz", architecture);
        }
        return MAPPER.writeValueAsBytes(root);
    }

    private static void artifact(
            ArrayNode artifacts,
            String platform,
            String architecture,
            String packageType,
            String packageFormat,
            String... compatibleArchitectures
    ) throws Exception {
        String suffix = switch (packageFormat) {
            case "exe" -> "installer.exe";
            default -> packageFormat;
        };
        String separator = packageFormat.equals("exe") ? "-" : ".";
        String name = "JQ-Viewer-1.4.7-" + platform + "-" + architecture + separator + suffix;
        ObjectNode artifact = artifacts.addObject();
        artifact.put("name", name);
        artifact.put("platform", platform);
        artifact.put("architecture", architecture);
        ArrayNode compatible = artifact.putArray("compatibleArchitectures");
        for (String value : compatibleArchitectures) compatible.add(value);
        artifact.put("packageType", packageType);
        artifact.put("packageFormat", packageFormat);
        artifact.put("sizeBytes", PACKAGE_BYTES.length);
        artifact.put("sha256", HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(PACKAGE_BYTES)));
        artifact.putObject("sources")
                .put("github", "https://github.com/JUKOMU/JQ-Viewer/releases/download/v1.4.7/" + name)
                .put("gitee", "https://gitee.com/jukomu/jq-viewer/releases/download/v1.4.7/" + name);
    }

    private static byte[] signature(byte[] manifest, KeyPair keyPair) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(manifest);
        ObjectNode document = MAPPER.createObjectNode();
        document.put("schemaVersion", 1);
        document.put("algorithm", "Ed25519");
        document.put("keyId", "jq-viewer-release-1");
        document.put("manifest", "latest.json");
        document.put("signature", Base64.getEncoder().encodeToString(signer.sign()));
        return MAPPER.writeValueAsBytes(document);
    }

    private static void awaitPhase(DesktopUpdateService service, String phase) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!phase.equals(service.snapshot().phase()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(phase.equals(service.snapshot().phase()),
                () -> "expected phase " + phase + " but was " + service.snapshot());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private record Fixture(
            DesktopUpdateService service,
            EventHub events,
            TestHttpClient http,
            ExecutorService executor
    ) implements AutoCloseable {
        @Override
        public void close() {
            service.close();
            events.close();
        }
    }

    private static final class TestHttpClient extends HttpClient {
        private final byte[] manifest;
        private final byte[] signature;
        private final AtomicBoolean blockNextGithubManifest = new AtomicBoolean();
        private final AtomicBoolean stallDownloads = new AtomicBoolean();
        private final AtomicBoolean blockGithubDownloadAtEof = new AtomicBoolean();
        private final CountDownLatch blockedManifestEntered = new CountDownLatch(1);
        private final CountDownLatch releaseBlockedManifest = new CountDownLatch(1);
        private final CountDownLatch githubDownloadReachedEof = new CountDownLatch(1);
        private final CountDownLatch releaseGithubDownloadEof = new CountDownLatch(1);

        private TestHttpClient(byte[] manifest, byte[] signature) {
            this.manifest = manifest;
            this.signature = signature;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler
        ) throws IOException, InterruptedException {
            URI uri = request.uri();
            if (DesktopUpdateService.GITHUB_MANIFEST.equals(uri)
                    && blockNextGithubManifest.compareAndSet(true, false)) {
                blockedManifestEntered.countDown();
                releaseBlockedManifest.await();
            }
            InputStream body;
            if (DesktopUpdateService.GITHUB_MANIFEST.equals(uri) || uri.getPath().endsWith("latest.json")) {
                body = new ByteArrayInputStream(manifest);
            } else if (DesktopUpdateService.GITHUB_SIGNATURE.equals(uri)
                    || uri.getPath().endsWith("latest.json.sig")) {
                body = new ByteArrayInputStream(signature);
            } else if (DesktopUpdateService.GITEE_LATEST_RELEASE.equals(uri)) {
                body = new ByteArrayInputStream(giteeRelease().getBytes(StandardCharsets.UTF_8));
            } else if (uri.getPath().endsWith("JQ-Viewer-1.4.7-linux-x64.tar.gz")) {
                if (blockGithubDownloadAtEof.get() && "gitee.com".equals(uri.getHost())) {
                    githubDownloadReachedEof.await();
                }
                if (stallDownloads.get()) {
                    body = new StalledInputStream();
                } else if (blockGithubDownloadAtEof.get()
                        && "github.com".equals(uri.getHost())) {
                    body = new BlockingEofInputStream(
                            PACKAGE_BYTES, githubDownloadReachedEof, releaseGithubDownloadEof);
                } else {
                    body = new ByteArrayInputStream(PACKAGE_BYTES);
                }
            } else {
                throw new IOException("unexpected URI: " + uri);
            }
            return (HttpResponse<T>) new TestResponse(request, body);
        }

        private String giteeRelease() {
            return """
                    {"tag_name":"v1.4.7","assets":[
                      {"name":"latest.json","browser_download_url":"https://gitee.com/jukomu/jq-viewer/releases/download/v1.4.7/latest.json"},
                      {"name":"latest.json.sig","browser_download_url":"https://gitee.com/jukomu/jq-viewer/releases/download/v1.4.7/latest.json.sig"}
                    ]}
                    """;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            throw new UnsupportedOperationException();
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return defaultSslContext(); }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }

        private static SSLContext defaultSslContext() {
            try {
                return SSLContext.getDefault();
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }
    }

    private record TestResponse(HttpRequest request, InputStream body)
            implements HttpResponse<InputStream> {
        @Override public int statusCode() { return 200; }
        @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (left, right) -> true); }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    private static final class StalledInputStream extends InputStream {
        private boolean closed;

        @Override
        public synchronized int read() throws IOException {
            while (!closed) {
                try {
                    wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", exception);
                }
            }
            throw new IOException("closed");
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) throws IOException {
            return read();
        }

        @Override
        public synchronized void close() {
            closed = true;
            notifyAll();
        }
    }

    private static final class BlockingEofInputStream extends InputStream {
        private final ByteArrayInputStream delegate;
        private final CountDownLatch reachedEof;
        private final CountDownLatch releaseEof;
        private final AtomicBoolean waitingAtEof = new AtomicBoolean();

        private BlockingEofInputStream(
                byte[] bytes,
                CountDownLatch reachedEof,
                CountDownLatch releaseEof
        ) {
            this.delegate = new ByteArrayInputStream(bytes);
            this.reachedEof = reachedEof;
            this.releaseEof = releaseEof;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            return value >= 0 ? value : awaitRelease();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = delegate.read(bytes, offset, length);
            return count >= 0 ? count : awaitRelease();
        }

        private int awaitRelease() throws IOException {
            if (waitingAtEof.compareAndSet(false, true)) reachedEof.countDown();
            try {
                releaseEof.await();
                return -1;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", exception);
            }
        }

        @Override
        public void close() {
            releaseEof.countDown();
        }
    }
}
