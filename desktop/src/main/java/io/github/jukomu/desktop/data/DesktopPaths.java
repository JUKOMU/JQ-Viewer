package io.github.jukomu.desktop.data;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.Map;
import java.util.Objects;

/** Centralizes program and user-data locations for the Desktop process. */
public final class DesktopPaths {
    public static final String APPLICATION_NAME = "JQViewer";

    private final Path programDirectory;
    private final Path dataDirectory;
    private final Path cacheDirectory;
    private final Path stateDirectory;
    private final Path logsDirectory;
    private final Path downloadsDirectory;
    private final Path pdfDirectory;
    private final Path databasePath;
    private final Path instanceLockPath;

    public DesktopPaths(
            Path programDirectory,
            Path userHome,
            Map<String, String> environment,
            String operatingSystem
    ) {
        this.programDirectory = normalize(programDirectory, "programDirectory");
        Path home = normalize(userHome, "userHome");
        Map<String, String> env = Objects.requireNonNull(environment, "environment");
        String os = operatingSystem == null ? "" : operatingSystem.toLowerCase();

        if (os.contains("win")) {
            Path localAppData = configuredPath(env.get("LOCALAPPDATA"), home.resolve("AppData/Local"), home);
            this.dataDirectory = localAppData.resolve(APPLICATION_NAME);
            this.cacheDirectory = this.dataDirectory.resolve("cache");
            this.stateDirectory = this.dataDirectory.resolve("state");
        } else if (os.contains("mac") || os.contains("darwin")) {
            this.dataDirectory = home.resolve("Library/Application Support").resolve(APPLICATION_NAME);
            this.cacheDirectory = home.resolve("Library/Caches").resolve(APPLICATION_NAME);
            this.stateDirectory = this.dataDirectory.resolve("state");
        } else {
            Path dataBase = configuredPath(env.get("XDG_DATA_HOME"), home.resolve(".local/share"), home);
            Path cacheBase = configuredPath(env.get("XDG_CACHE_HOME"), home.resolve(".cache"), home);
            Path stateBase = configuredPath(env.get("XDG_STATE_HOME"), home.resolve(".local/state"), home);
            this.dataDirectory = dataBase.resolve(APPLICATION_NAME);
            this.cacheDirectory = cacheBase.resolve(APPLICATION_NAME);
            this.stateDirectory = stateBase.resolve(APPLICATION_NAME);
        }

        this.logsDirectory = stateDirectory.resolve("logs");
        this.downloadsDirectory = dataDirectory.resolve("downloads");
        this.pdfDirectory = dataDirectory.resolve("pdf");
        this.databasePath = dataDirectory.resolve("desktop.sqlite3");
        this.instanceLockPath = stateDirectory.resolve("instance.lock");
    }

    public static DesktopPaths current() {
        Path userHome = Paths.get(System.getProperty("user.home", "."));
        return new DesktopPaths(
                resolveProgramDirectory(),
                userHome,
                System.getenv(),
                System.getProperty("os.name", "")
        );
    }

    public void ensureDirectories() throws IOException {
        Files.createDirectories(dataDirectory);
        Files.createDirectories(cacheDirectory);
        Files.createDirectories(stateDirectory);
        Files.createDirectories(logsDirectory);
        Files.createDirectories(downloadsDirectory);
        Files.createDirectories(pdfDirectory);
    }

    private static Path configuredPath(String value, Path fallback, Path home) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Path configured = Paths.get(value);
        return configured.isAbsolute() ? configured.normalize() : home.resolve(configured).normalize();
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }

    private static Path resolveProgramDirectory() {
        try {
            CodeSource codeSource = DesktopPaths.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                URI location = codeSource.getLocation().toURI();
                Path path = Paths.get(location).toAbsolutePath().normalize();
                return Files.isDirectory(path) ? path : path.getParent();
            }
        } catch (Exception ignored) {
            // Fall through to a deterministic path when the runtime does not expose CodeSource.
        }
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    public Path programDirectory() {
        return programDirectory;
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    public Path cacheDirectory() {
        return cacheDirectory;
    }

    public Path stateDirectory() {
        return stateDirectory;
    }

    public Path logsDirectory() {
        return logsDirectory;
    }

    public Path downloadsDirectory() {
        return downloadsDirectory;
    }

    public Path pdfDirectory() {
        return pdfDirectory;
    }

    public Path databasePath() {
        return databasePath;
    }

    public Path instanceLockPath() {
        return instanceLockPath;
    }
}
