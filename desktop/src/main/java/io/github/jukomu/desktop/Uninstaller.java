package io.github.jukomu.desktop;

import javax.swing.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.net.URISyntaxException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安装目录内的 Windows 维护入口。
 */
public final class Uninstaller {
    private static final String APPLICATION_NAME = "JQ Viewer";
    private static final Path USER_UNINSTALL_ROOT = Path.of(
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall");
    private static final Path MACHINE_UNINSTALL_ROOT = Path.of(
        "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall");
    private static final Pattern KEY_PATTERN = Pattern.compile(
        "(?i)^HKEY_[^\\r\\n]+$");
    private static final Pattern GUID_PATTERN = Pattern.compile(
        "(?i)\\{[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\}");

    private Uninstaller() {
    }

    public static void main(String[] args) {
        if (!isWindows()) {
            showError("JQ Viewer 的安装目录维护入口仅支持 Windows。");
            return;
        }

        String productCode = findProductCode();
        if (productCode == null) {
            showError("找不到 JQ Viewer 的 Windows 安装记录，请从系统设置中执行卸载。");
            return;
        }

        String[] options = {"修复/重新安装", "卸载并保留用户数据", "卸载并删除用户数据", "取消"};
        int choice = JOptionPane.showOptionDialog(
            null,
            "请选择要执行的操作。\n默认会保留 %LOCALAPPDATA%\\JQViewer 中的用户数据。",
            "JQ Viewer 维护",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            options[0]);

        try {
            switch (choice) {
                case 0 -> launchRepair(productCode);
                case 1 -> launchUninstall(productCode, false);
                case 2 -> launchUninstall(productCode, true);
                default -> {
                    // 用户取消维护操作。
                }
            }
        } catch (IOException exception) {
            showError("无法启动 Windows Installer：" + exception.getMessage());
        }
    }

    private static void launchRepair(String productCode) throws IOException {
        new ProcessBuilder("msiexec.exe", "/fa", productCode, "/passive")
            .start();
    }

    private static void launchUninstall(String productCode, boolean removeData) throws IOException {
        Path script = Files.createTempFile("jq-viewer-uninstall-", ".ps1");
        String dataDirectory = localAppData().resolve("JQViewer").toAbsolutePath().normalize().toString();
        String scriptContent = """
            $ErrorActionPreference = 'SilentlyContinue'
            $result = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/x', '%s', '/passive') -Wait -PassThru
            $removeData = %s
            $success = $null -ne $result -and ($result.ExitCode -eq 0 -or $result.ExitCode -eq 3010)
            $warningMessage = $null
            if ($success -and $removeData) {
              Remove-Item -LiteralPath '%s' -Recurse -Force -ErrorAction SilentlyContinue
              if (Test-Path -LiteralPath '%s') {
                $warningMessage = '卸载完成，但用户数据删除失败。'
              }
            } elseif (-not $success -and $removeData) {
              $warningMessage = '卸载未完成，用户数据未删除。'
            }
            if ($warningMessage) {
              Add-Type -AssemblyName System.Windows.Forms
              [System.Windows.Forms.MessageBox]::Show(
                $warningMessage, 'JQ Viewer', 'OK', 'Warning') | Out-Null
            }
            Remove-Item -LiteralPath $PSCommandPath -Force -ErrorAction SilentlyContinue
            """.formatted(
            escapePowerShell(productCode),
            removeData ? "$true" : "$false",
            escapePowerShell(dataDirectory),
            escapePowerShell(dataDirectory));
        Files.writeString(script, scriptContent, StandardCharsets.UTF_8);
        new ProcessBuilder(
            "powershell.exe",
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-WindowStyle",
            "Hidden",
            "-File",
            script.toString())
            .start();
    }

    private static String findProductCode() {
        Path installationDirectory = installationDirectory();
        if (installationDirectory == null) return null;
        for (Path root : List.of(USER_UNINSTALL_ROOT, MACHINE_UNINSTALL_ROOT)) {
            String result = queryRegistry(root);
            String productCode = parseProductCode(result, installationDirectory);
            if (productCode != null) return productCode;
        }
        return null;
    }

    private static String queryRegistry(Path root) {
        try {
            Process process = new ProcessBuilder("reg.exe", "query", root.toString(), "/s")
                .redirectErrorStream(true)
                .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            return output;
        } catch (IOException exception) {
            return "";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    static String parseProductCode(String output, Path installationDirectory) {
        String currentKey = null;
        String displayName = null;
        String uninstallString = null;
        String installLocation = null;
        for (String line : output.split("\\R")) {
            if (KEY_PATTERN.matcher(line.trim()).matches()) {
                String productCode = productCode(
                    displayName, installLocation, uninstallString, currentKey, installationDirectory);
                if (productCode != null) return productCode;
                currentKey = line.trim();
                displayName = null;
                uninstallString = null;
                installLocation = null;
                continue;
            }
            String value = value(line, "DisplayName");
            if (value != null) displayName = value;
            value = value(line, "UninstallString");
            if (value != null) uninstallString = value;
            value = value(line, "InstallLocation");
            if (value != null) installLocation = value;
        }
        return productCode(
            displayName, installLocation, uninstallString, currentKey, installationDirectory);
    }

    private static String productCode(
        String displayName,
        String installLocation,
        String uninstallString,
        String key,
        Path installationDirectory
    ) {
        if (displayName == null || !displayName.trim().replace("-", " ")
            .equalsIgnoreCase(APPLICATION_NAME)) return null;
        if (!sameInstallationDirectory(installLocation, installationDirectory)) return null;
        Matcher matcher = GUID_PATTERN.matcher(uninstallString == null ? "" : uninstallString);
        if (matcher.find()) return matcher.group();
        matcher = GUID_PATTERN.matcher(key == null ? "" : key);
        return matcher.find() ? matcher.group() : null;
    }

    private static boolean sameInstallationDirectory(String value, Path installationDirectory) {
        if (value == null || value.isBlank() || installationDirectory == null) return false;
        try {
            Path candidate = Path.of(value.trim()).toAbsolutePath().normalize();
            Path expected = installationDirectory.toAbsolutePath().normalize();
            return candidate.toString().equalsIgnoreCase(expected.toString());
        } catch (InvalidPathException exception) {
            return false;
        }
    }

    private static Path installationDirectory() {
        String launcherPathValue = System.getProperty("jpackage.app-path", "").trim();
        if (!launcherPathValue.isEmpty()) {
            Path launcherPath = Path.of(launcherPathValue).toAbsolutePath().normalize();
            Path parent = launcherPath.getParent();
            if (parent != null) return parent;
        }
        try {
            Path codeLocation = Path.of(
                Uninstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath().normalize();
            Path appDirectory = Files.isDirectory(codeLocation) ? codeLocation : codeLocation.getParent();
            if (appDirectory != null && appDirectory.getFileName() != null
                && "app".equalsIgnoreCase(appDirectory.getFileName().toString())) {
                return appDirectory.getParent();
            }
            return appDirectory;
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("无法定位卸载器安装目录", exception);
        }
    }

    private static String value(String line, String name) {
        String trimmed = line.trim();
        if (!trimmed.startsWith(name + " ")) return null;
        String[] parts = trimmed.split("\\s+", 3);
        return parts.length == 3 ? parts[2].trim() : null;
    }

    private static Path localAppData() {
        String configured = System.getenv("LOCALAPPDATA");
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        return Path.of(System.getProperty("user.home", "."), "AppData", "Local");
    }

    private static String escapePowerShell(String value) {
        return value.replace("'", "''");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void showError(String message) {
        JOptionPane.showMessageDialog(null, message, "JQ Viewer", JOptionPane.ERROR_MESSAGE);
    }
}
