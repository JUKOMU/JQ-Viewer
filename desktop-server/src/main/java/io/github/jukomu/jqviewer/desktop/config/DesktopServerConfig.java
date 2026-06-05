package io.github.jukomu.jqviewer.desktop.config;

import java.net.InetAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class DesktopServerConfig {
    private static final int DEFAULT_PORT = 18080;

    private final InetAddress bindAddress;
    private final int port;
    private final String token;
    private final Path dataDir;
    private final Set<String> allowedOrigins;

    private DesktopServerConfig(InetAddress bindAddress, int port, String token,
                                Path dataDir, Set<String> allowedOrigins) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.token = token;
        this.dataDir = dataDir;
        this.allowedOrigins = allowedOrigins;
    }

    public static DesktopServerConfig from(String[] args, Map<String, String> env) throws Exception {
        String token = value(env, "JQ_DESKTOP_TOKEN", "");
        String dataDirRaw = value(env, "JQ_DESKTOP_DATA_DIR", "");
        String originsRaw = value(env, "JQ_DESKTOP_DEV_ORIGINS",
            "http://localhost:5173,http://127.0.0.1:5173");
        int port = parseInt(value(env, "JQ_DESKTOP_PORT", ""), DEFAULT_PORT);

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--port=")) {
                port = parseInt(arg.substring("--port=".length()), DEFAULT_PORT);
            } else if ("--port".equals(arg) && i + 1 < args.length) {
                port = parseInt(args[++i], DEFAULT_PORT);
            } else if (arg.startsWith("--token=")) {
                token = arg.substring("--token=".length()).trim();
            } else if ("--token".equals(arg) && i + 1 < args.length) {
                token = args[++i].trim();
            } else if (arg.startsWith("--data-dir=")) {
                dataDirRaw = arg.substring("--data-dir=".length()).trim();
            } else if ("--data-dir".equals(arg) && i + 1 < args.length) {
                dataDirRaw = args[++i].trim();
            }
        }

        if (token.isEmpty()) {
            token = generateToken();
        }

        return new DesktopServerConfig(
            InetAddress.getByName("127.0.0.1"),
            port,
            token,
            resolveDataDir(dataDirRaw, env),
            parseOrigins(originsRaw)
        );
    }

    public InetAddress bindAddress() {
        return bindAddress;
    }

    public int port() {
        return port;
    }

    public String token() {
        return token;
    }

    public Path dataDir() {
        return dataDir;
    }

    public Set<String> allowedOrigins() {
        return allowedOrigins;
    }

    private static String value(Map<String, String> env, String key, String fallback) {
        String value = env.get(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.trim().isEmpty()) return fallback;
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Path resolveDataDir(String explicit, Map<String, String> env) {
        if (explicit != null && !explicit.trim().isEmpty()) {
            return Path.of(explicit.trim()).toAbsolutePath().normalize();
        }
        String appData = env.get("APPDATA");
        if (appData != null && !appData.trim().isEmpty()) {
            return Path.of(appData, "JQViewer", "desktop").toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".jqviewer", "desktop")
            .toAbsolutePath()
            .normalize();
    }

    private static Set<String> parseOrigins(String raw) {
        Set<String> origins = new LinkedHashSet<>();
        if (raw == null || raw.trim().isEmpty()) return origins;
        for (String part : raw.split(",")) {
            String origin = part.trim();
            if (!origin.isEmpty()) {
                origins.add(origin);
            }
        }
        return origins;
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
