package io.github.jukomu.desktop.feature.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopUpdateInstallerTest {
    @Test
    void windowsPortableHelperWaitsSwapsAndRollsBack() {
        String script = DesktopUpdateInstaller.windowsScript();

        assertTrue(script.contains("Wait-Process -Id $ParentPid"));
        assertTrue(script.contains("Move-Item -LiteralPath $ApplicationRoot -Destination $BackupPath"));
        assertTrue(script.contains("Move-Item -LiteralPath $BackupPath -Destination $ApplicationRoot"));
        assertTrue(script.contains("if ($launched.HasExited)"));
    }

    @Test
    void linuxPortableHelperWaitsSwapsAndRollsBack() {
        String script = DesktopUpdateInstaller.linuxScript();

        assertTrue(script.contains("while kill -0 \"$parent_pid\""));
        assertTrue(script.contains("mv -- \"$application_root\" \"$backup_path\""));
        assertTrue(script.contains("mv -- \"$backup_path\" \"$application_root\""));
        assertTrue(script.contains("kill -0 \"$launched_pid\""));
    }
}
