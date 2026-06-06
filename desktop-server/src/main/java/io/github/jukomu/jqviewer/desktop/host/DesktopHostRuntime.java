package io.github.jukomu.jqviewer.desktop.host;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jukomu.jqviewer.desktop.config.DesktopServerConfig;
import io.github.jukomu.jqviewer.desktop.http.DesktopServer;
import io.github.jukomu.jqviewer.desktop.util.JsonFiles;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.BindException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

final class DesktopHostRuntime {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int PORT_ATTEMPTS = 20;

    private DesktopServer server;
    private DesktopServerConfig config;
    private URI homeUri;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Path stateFile;
    private final Path logFile;
    private final boolean printSensitiveUrls;
    private final CountDownLatch stoppedLatch = new CountDownLatch(1);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private DesktopTrayController tray;

    private DesktopHostRuntime(ServerLaunch launch, FileChannel lockChannel, FileLock lock,
                               Path stateFile, Path logFile, boolean printSensitiveUrls) {
        this.server = launch.server();
        this.config = launch.config();
        this.homeUri = pageUri(config.port(), config.token(), "/");
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.stateFile = stateFile;
        this.logFile = logFile;
        this.printSensitiveUrls = printSensitiveUrls;
    }

    static DesktopHostRuntime start(String[] args, Map<String, String> env) throws Exception {
        DesktopServerConfig config = DesktopServerConfig.from(args, env);
        boolean printSensitiveUrls = shouldPrintSensitiveUrls(args, env);
        Files.createDirectories(config.dataDir());
        Files.createDirectories(config.cacheDir());
        Files.createDirectories(config.logDir());
        System.setProperty("org.slf4j.simpleLogger.logFile", config.logDir().resolve("desktop-server.log").toString());

        Path lockFile = config.dataDir().resolve("desktop-host.lock");
        Path stateFile = config.dataDir().resolve("desktop-host.json");
        Path logFile = config.logDir().resolve("desktop-host.log");
        FileChannel lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = tryLock(lockChannel);
        if (lock == null) {
            closeQuietly(lockChannel);
            openExistingInstance(stateFile, logFile, printSensitiveUrls);
            return null;
        }

        ServerLaunch launch = null;
        try {
            launch = startServer(config, logFile);
            URI pageUri = pageUri(launch.server().port(), launch.config().token(), "/");
            writeState(stateFile, launch.config(), pageUri);

            DesktopHostRuntime runtime = new DesktopHostRuntime(launch, lockChannel, lock, stateFile, logFile, printSensitiveUrls);
            runtime.tray = DesktopTrayController.install(
                runtime::openHomePage,
                runtime::openDownloadPage,
                runtime::openDataDir,
                runtime::openLogDir,
                runtime::restartService,
                () -> {
                    runtime.shutdown();
                    System.exit(0);
                }
            );
            Runtime.getRuntime().addShutdownHook(new Thread(runtime::shutdown, "desktop-host-shutdown"));

            openBrowser(pageUri, printSensitiveUrls);
            logLine(logFile, "Started desktop host on " + displayUri(pageUri, false));
            System.out.println("JQViewer desktop host listening on http://127.0.0.1:" + launch.server().port());
            System.out.println("JQViewer desktop page: " + displayUri(pageUri, printSensitiveUrls));
            if (!printSensitiveUrls) {
                System.out.println("Set JQ_DESKTOP_PRINT_TOKEN=true or pass --print-token to print token-bearing URLs for debugging.");
            }
            System.out.println("JQViewer desktop data dir: " + launch.config().dataDir());
            System.out.println("JQViewer desktop cache dir: " + launch.config().cacheDir());
            System.out.println("JQViewer desktop download dir: " + launch.config().downloadDir());
            System.out.println("JQViewer desktop PDF import root: " + launch.config().pdfRootDir());
            System.out.println("JQViewer desktop PDF export dir: " + launch.config().pdfExportDir());
            System.out.println("JQViewer desktop log dir: " + launch.config().logDir());
            return runtime;
        } catch (Exception e) {
            if (launch != null) {
                launch.server().stop();
            }
            releaseQuietly(lock);
            closeQuietly(lockChannel);
            throw e;
        }
    }

    void await() throws InterruptedException {
        stoppedLatch.await();
    }

    private synchronized void shutdown() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }

        logLine(logFile, "Stopping desktop host");
        if (tray != null) {
            tray.close();
        }
        server.stop();
        deleteQuietly(stateFile);
        releaseQuietly(lock);
        closeQuietly(lockChannel);
        stoppedLatch.countDown();
    }

    private synchronized void openHomePage() {
        openBrowser(homeUri, printSensitiveUrls);
    }

    private synchronized void openDownloadPage() {
        openBrowser(pageUri(config.port(), config.token(), "/download"), printSensitiveUrls);
    }

    private synchronized void openDataDir() {
        openDirectory(config.dataDir(), logFile);
    }

    private synchronized void openLogDir() {
        openDirectory(config.logDir(), logFile);
    }

    private synchronized void restartService() {
        if (stopped.get()) {
            return;
        }

        logLine(logFile, "Restarting desktop server");
        DesktopServer oldServer = server;
        ServerLaunch launch = null;
        try {
            launch = startServer(config, logFile);
            oldServer.stop();
            server = launch.server();
            config = launch.config();
            homeUri = pageUri(config.port(), config.token(), "/");
            writeState(stateFile, config, homeUri);
            openBrowser(homeUri, printSensitiveUrls);
            logLine(logFile, "Restarted desktop server on " + displayUri(homeUri, false));
        } catch (Exception e) {
            if (launch != null) {
                launch.server().stop();
            }
            logLine(logFile, "Failed to restart desktop server: " + e.getMessage());
            System.out.println("Failed to restart JQViewer desktop service: " + e.getMessage());
        }
    }

    private static ServerLaunch startServer(DesktopServerConfig config, Path logFile) throws Exception {
        int firstPort = config.port();
        for (int attempt = 0; attempt < PORT_ATTEMPTS; attempt++) {
            int port = firstPort + attempt;
            if (port > 65535) break;
            DesktopServerConfig candidate = config.withPort(port);
            try {
                DesktopServer server = new DesktopServer(candidate);
                server.start();
                return new ServerLaunch(server, candidate);
            } catch (BindException e) {
                logLine(logFile, "Port " + port + " is busy, trying next port");
            }
        }
        throw new BindException("No available desktop server port from " + firstPort);
    }

    private static void openExistingInstance(Path stateFile, Path logFile, boolean printSensitiveUrls) throws IOException {
        URI uri = readStateUri(stateFile);
        if (uri == null) {
            System.out.println("JQViewer desktop host is already running, but no readable state file was found.");
            return;
        }
        openBrowser(uri, printSensitiveUrls);
        logLine(logFile, "Opened existing desktop host " + displayUri(uri, false));
        System.out.println("Opened existing JQViewer desktop page: " + displayUri(uri, printSensitiveUrls));
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }

    private static URI readStateUri(Path stateFile) throws IOException {
        if (!Files.isRegularFile(stateFile)) return null;
        try {
            JsonObject state = JsonParser.parseString(Files.readString(stateFile, StandardCharsets.UTF_8)).getAsJsonObject();
            if (state == null || !state.has("url") || state.get("url").isJsonNull()) return null;
            return URI.create(state.get("url").getAsString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void writeState(Path stateFile, DesktopServerConfig config, URI pageUri) throws IOException {
        JsonObject state = new JsonObject();
        state.addProperty("url", pageUri.toString());
        state.addProperty("port", config.port());
        state.addProperty("dataDir", config.dataDir().toString());
        state.addProperty("cacheDir", config.cacheDir().toString());
        state.addProperty("downloadDir", config.downloadDir().toString());
        state.addProperty("pdfRootDir", config.pdfRootDir().toString());
        state.addProperty("pdfExportDir", config.pdfExportDir().toString());
        state.addProperty("logDir", config.logDir().toString());
        state.addProperty("startedAt", Instant.now().toString());
        JsonFiles.writeJson(stateFile, GSON, state);
    }

    private static URI pageUri(int port, String token, String path) {
        String encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8);
        String normalizedPath = path == null || path.isBlank() ? "/" : path;
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        return URI.create("http://127.0.0.1:" + port + normalizedPath + "?jqDesktopToken=" + encodedToken);
    }

    private static void openBrowser(URI uri, boolean printSensitiveUrls) {
        if (!GraphicsEnvironment.isHeadless()
            && Desktop.isDesktopSupported()
            && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(uri);
                return;
            } catch (IOException ignored) {
                // Fall through to console output for headless or restricted desktops.
            }
        }
        System.out.println("Open JQViewer desktop page: " + displayUri(uri, printSensitiveUrls));
    }

    private static void openDirectory(Path path, Path logFile) {
        if (!GraphicsEnvironment.isHeadless()
            && Desktop.isDesktopSupported()
            && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            try {
                Files.createDirectories(path);
                Desktop.getDesktop().open(path.toFile());
                return;
            } catch (IOException e) {
                logLine(logFile, "Failed to open directory " + path + ": " + e.getMessage());
            }
        }
        System.out.println("Open JQViewer desktop directory: " + path);
    }

    private static void logLine(Path logFile, String message) {
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(
                logFile,
                Instant.now() + " " + message + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
        }
    }

    private static String displayUri(URI uri, boolean includeToken) {
        if (includeToken) return uri.toString();
        return uri.toString().replaceAll("([?&]jqDesktopToken=)[^&#]*", "$1<redacted>");
    }

    private static boolean shouldPrintSensitiveUrls(String[] args, Map<String, String> env) {
        String value = env.get("JQ_DESKTOP_PRINT_TOKEN");
        return "true".equalsIgnoreCase(value) || Arrays.asList(args).contains("--print-token");
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static void releaseQuietly(FileLock lock) {
        try {
            lock.release();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    private record ServerLaunch(DesktopServer server, DesktopServerConfig config) {
    }
}
