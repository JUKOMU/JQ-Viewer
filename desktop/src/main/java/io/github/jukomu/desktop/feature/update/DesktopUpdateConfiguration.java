package io.github.jukomu.desktop.feature.update;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Desktop 更新客户端的构建时固定配置。
 */
public record DesktopUpdateConfiguration(
    String currentVersion,
    String platform,
    String architecture,
    String packageType,
    String packageFormat,
    String keyId,
    byte[] publicKeySpki,
    Path launcherPath,
    Path applicationRoot
) {
    private static final Pattern VERSION_PATTERN = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+$");
    private static final Pattern KEY_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    public DesktopUpdateConfiguration {
        currentVersion = normalized(currentVersion);
        platform = normalized(platform).toLowerCase(Locale.ROOT);
        architecture = normalized(architecture).toLowerCase(Locale.ROOT);
        packageType = normalized(packageType).toLowerCase(Locale.ROOT);
        packageFormat = normalized(packageFormat).toLowerCase(Locale.ROOT);
        keyId = normalized(keyId);
        publicKeySpki = publicKeySpki == null ? new byte[0] : publicKeySpki.clone();
        launcherPath = launcherPath == null ? null : launcherPath.toAbsolutePath().normalize();
        applicationRoot = applicationRoot == null
            ? null
            : applicationRoot.toAbsolutePath().normalize();
    }

    public static DesktopUpdateConfiguration fromSystemProperties() {
        String appPathValue = System.getProperty("jpackage.app-path", "").trim();
        Path launcherPath = appPathValue.isEmpty() ? null : Path.of(appPathValue);
        Path applicationRoot = resolveApplicationRoot(launcherPath);
        byte[] publicKey = decodeBase64(
            System.getProperty("jqviewer.update.publicKeySpkiBase64", ""));
        return new DesktopUpdateConfiguration(
            System.getProperty("jqviewer.update.version", ""),
            System.getProperty("jqviewer.update.platform", ""),
            System.getProperty("jqviewer.update.architecture", ""),
            System.getProperty("jqviewer.update.packageType", ""),
            System.getProperty("jqviewer.update.packageFormat", ""),
            System.getProperty("jqviewer.update.keyId", ""),
            publicKey,
            launcherPath,
            applicationRoot
        );
    }

    public boolean isConfigured() {
        return VERSION_PATTERN.matcher(currentVersion).matches()
            && (platform.equals("windows") || platform.equals("linux"))
            && (architecture.equals("x64") || architecture.equals("arm64"))
            && (packageType.equals("installer") || packageType.equals("portable"))
            && supportedFormat()
            && KEY_ID_PATTERN.matcher(keyId).matches()
            && publicKeySpki.length > 0
            && launcherPath != null
            && applicationRoot != null;
    }

    public void requireConfigured() {
        if (!isConfigured()) {
            throw new UpdateException("当前 Desktop 构建未配置正式更新信任根或包类型");
        }
    }

    @Override
    public byte[] publicKeySpki() {
        return publicKeySpki.clone();
    }

    private boolean supportedFormat() {
        if (platform.equals("windows")) {
            return packageFormat.equals("exe") || packageFormat.equals("zip");
        }
        return packageFormat.equals("deb")
            || packageFormat.equals("rpm")
            || packageFormat.equals("tar.gz");
    }

    private static Path resolveApplicationRoot(Path launcherPath) {
        if (launcherPath == null) return null;
        Path normalized = launcherPath.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) return null;
        if (parent.getFileName() != null && "bin".equals(parent.getFileName().toString())) {
            return parent.getParent();
        }
        return parent;
    }

    private static byte[] decodeBase64(String value) {
        String normalized = normalized(value);
        if (normalized.isEmpty()) return new byte[0];
        try {
            return Base64.getDecoder().decode(normalized);
        } catch (IllegalArgumentException ignored) {
            return new byte[0];
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
