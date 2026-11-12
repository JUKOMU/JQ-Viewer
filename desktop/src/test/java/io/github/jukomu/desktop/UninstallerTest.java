package io.github.jukomu.desktop;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UninstallerTest {
    @Test
    void matchesOnlyTheApplicationWithTheExpectedInstallLocation() {
        Path installationDirectory = Path.of("target", "jq-viewer").toAbsolutePath().normalize();
        String output = """
            HKEY_LOCAL_MACHINE\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{11111111-1111-1111-1111-111111111111}
                DisplayName    REG_SZ    JQ Viewer
                InstallLocation    REG_SZ    %s
                UninstallString    REG_SZ    msiexec.exe /x {11111111-1111-1111-1111-111111111111}
            HKEY_LOCAL_MACHINE\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{22222222-2222-2222-2222-222222222222}
                DisplayName    REG_SZ    JQ-Viewer
                InstallLocation    REG_SZ    %s
                UninstallString    REG_SZ    msiexec.exe /x {22222222-2222-2222-2222-222222222222}
            """.formatted(
            installationDirectory.resolveSibling("other-app"),
            installationDirectory.resolve("..").resolve(installationDirectory.getFileName()));

        assertEquals(
            "{22222222-2222-2222-2222-222222222222}",
            Uninstaller.parseInstallation(output, installationDirectory).productCode());
    }

    @Test
    void returnsNullWhenTheInstallLocationDoesNotMatch() {
        Path installationDirectory = Path.of("target", "jq-viewer").toAbsolutePath().normalize();
        String output = """
            HKEY_LOCAL_MACHINE\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{11111111-1111-1111-1111-111111111111}
                DisplayName    REG_SZ    JQ Viewer
                InstallLocation    REG_SZ    %s
                UninstallString    REG_SZ    msiexec.exe /x {11111111-1111-1111-1111-111111111111}
            """.formatted(installationDirectory.resolveSibling("other-app"));

        assertNull(Uninstaller.parseInstallation(output, installationDirectory));
    }

    @Test
    void readsTheInstalledLanguageAlongsideTheMatchingProduct() {
        Path directory = Path.of("target", "jq-viewer").toAbsolutePath().normalize();
        String output = """
            HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{11111111-1111-1111-1111-111111111111}
                DisplayName    REG_SZ    JQ-Viewer
                InstallLocation    REG_SZ    %s
                Language    REG_DWORD    0x804
            """.formatted(directory);
        assertEquals(2052, Uninstaller.parseInstallation(output, directory).language());
        assertEquals("取消", Uninstaller.messagesForLanguage(2052).getString("cancel"));
        assertEquals("Cancel", Uninstaller.messagesForLanguage(1033).getString("cancel"));
    }

    @Test
    void ignoresInvalidLanguagesOutsideTheMatchingInstallationAndDefaultsInvalidValue() {
        Path directory = Path.of("target", "jq-viewer").toAbsolutePath().normalize();
        String output = """
            HKEY_LOCAL_MACHINE\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{11111111-1111-1111-1111-111111111111}
                DisplayName    REG_SZ    Other App
                InstallLocation    REG_SZ    %s
                Language    REG_SZ    en-US
            HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{22222222-2222-2222-2222-222222222222}
                DisplayName    REG_SZ    JQ-Viewer
                InstallLocation    REG_SZ    %s
                Language    REG_SZ    en-US
            """.formatted(directory.resolveSibling("other-app"), directory);

        Uninstaller.Installation installation = Uninstaller.parseInstallation(output, directory);

        assertEquals("{22222222-2222-2222-2222-222222222222}", installation.productCode());
        assertEquals(1033, installation.language());
    }

    @Test
    void maintenanceTranslationsContainTheSameKeys() throws Exception {
        Properties english = new Properties();
        Properties chinese = new Properties();
        try (var en = new InputStreamReader(Uninstaller.class.getResourceAsStream("UninstallerMessages.properties"), StandardCharsets.UTF_8);
             var zh = new InputStreamReader(Uninstaller.class.getResourceAsStream("UninstallerMessages_zh.properties"), StandardCharsets.UTF_8)) {
            english.load(en);
            chinese.load(zh);
        }
        assertEquals(english.keySet(), chinese.keySet());
    }
}
