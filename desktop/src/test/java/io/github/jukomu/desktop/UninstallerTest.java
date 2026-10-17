package io.github.jukomu.desktop;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

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
            Uninstaller.parseProductCode(output, installationDirectory));
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

        assertNull(Uninstaller.parseProductCode(output, installationDirectory));
    }
}
