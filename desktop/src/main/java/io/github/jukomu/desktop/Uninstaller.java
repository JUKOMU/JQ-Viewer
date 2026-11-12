package io.github.jukomu.desktop;

import javax.swing.*;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
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
        int systemLanguage = "zh".equals(Locale.getDefault(Locale.Category.DISPLAY).getLanguage()) ? 2052 : 1033;
        ResourceBundle messages = messagesForLanguage(systemLanguage);
        if (!isWindows()) {
            showError(messages.getString("windowsOnly"), messages);
            return;
        }

        Installation installation = findInstallation();
        if (installation == null) {
            showError(messages.getString("missingInstallation"), messages);
            return;
        }
        messages = messagesForLanguage(installation.language());
        JOptionPane.setDefaultLocale(messages.getLocale());
        String[] options = {messages.getString("repair"), messages.getString("uninstallKeep"),
            messages.getString("uninstallDelete"), messages.getString("cancel")};
        int choice = JOptionPane.showOptionDialog(null, messages.getString("prompt"), messages.getString("title"),
            JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        try {
            switch (choice) {
                case 0 -> launchRepair(installation.productCode());
                case 1 -> launchUninstall(installation.productCode(), false, messages);
                case 2 -> launchUninstall(installation.productCode(), true, messages);
                default -> {
                    // 用户取消维护操作。
                }
            }
        } catch (IOException exception) {
            showError(messages.getString("launchFailed") + " " + exception.getMessage(), messages);
        }
    }

    /** 维护界面沿用 MSI 的安装语言；其余语言使用英文基础资源。 */
    static ResourceBundle messagesForLanguage(int language) {
        Locale locale = (language & 0x3ff) == 4 ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        return ResourceBundle.getBundle("io.github.jukomu.desktop.UninstallerMessages", locale,
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    private static void launchRepair(String productCode) throws IOException {
        new ProcessBuilder("msiexec.exe", "/fa", productCode, "/passive")
            .start();
    }

    private static void launchUninstall(String productCode, boolean removeData, ResourceBundle messages) throws IOException {
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
                $warningMessage = '%s'
              }
            } elseif (-not $success -and $removeData) {
              $warningMessage = '%s'
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
            escapePowerShell(dataDirectory),
            escapePowerShell(messages.getString("deleteFailed")),
            escapePowerShell(messages.getString("uninstallFailed")));
        // Windows PowerShell 5.1 依靠 BOM 识别脚本中的中文提示。
        Files.writeString(script, "\uFEFF" + scriptContent, StandardCharsets.UTF_8);
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

    private static Installation findInstallation() {
        Path installationDirectory = installationDirectory();
        if (installationDirectory == null) return null;
        for (Path root : List.of(USER_UNINSTALL_ROOT, MACHINE_UNINSTALL_ROOT)) {
            String result = queryRegistry(root);
            Installation installation = parseInstallation(result, installationDirectory);
            if (installation != null) return installation;
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

    record Installation(String productCode, int language) {}

    static Installation parseInstallation(String output, Path installationDirectory) {
        String currentKey = null;
        String displayName = null;
        String uninstallString = null;
        String installLocation = null;
        // 注册表项的语言值只在确认匹配本安装后解析。
        String language = null;
        for (String line : output.split("\\R")) {
            if (KEY_PATTERN.matcher(line.trim()).matches()) {
                String productCode = productCode(
                    displayName, installLocation, uninstallString, currentKey, installationDirectory);
                if (productCode != null) return new Installation(productCode, language(language));
                currentKey = line.trim();
                displayName = null;
                uninstallString = null;
                installLocation = null;
                language = null;
                continue;
            }
            String value = value(line, "DisplayName");
            if (value != null) displayName = value;
            value = value(line, "UninstallString");
            if (value != null) uninstallString = value;
            value = value(line, "InstallLocation");
            if (value != null) installLocation = value;
            value = value(line, "Language");
            if (value != null) language = value;
        }
        String productCode = productCode(
            displayName, installLocation, uninstallString, currentKey, installationDirectory);
        return productCode == null ? null : new Installation(productCode, language(language));
    }

    private static int language(String value) {
        if (value == null) return 1033;
        try {
            return Integer.decode(value);
        } catch (NumberFormatException exception) {
            return 1033;
        }
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

    private static void showError(String message, ResourceBundle messages) {
        String[] options = {messages.getString("ok")};
        JOptionPane.showOptionDialog(null, message, APPLICATION_NAME, JOptionPane.DEFAULT_OPTION,
            JOptionPane.ERROR_MESSAGE, null, options, options[0]);
    }
}
