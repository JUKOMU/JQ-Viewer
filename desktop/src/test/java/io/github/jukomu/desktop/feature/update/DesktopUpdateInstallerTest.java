package io.github.jukomu.desktop.feature.update;

import org.junit.jupiter.api.Test;

import io.github.jukomu.desktop.data.Paths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopUpdateInstallerTest {
    @Test
    void windowsPortableHelperWaitsSwapsAndRollsBack() {
        String script = DesktopUpdateInstaller.windowsScript();

        assertTrue(script.contains("Wait-Process -Id $ParentPid"));
        assertTrue(script.contains("Move-Item -LiteralPath $ApplicationRoot -Destination $BackupPath"));
        assertTrue(script.contains("Move-Item -LiteralPath $BackupPath -Destination $ApplicationRoot"));
        assertTrue(script.contains("Wait-JqViewerReady"));
        assertTrue(script.contains("JQ_VIEWER_UPDATE_READY_FILE"));
        assertTrue(script.contains("Stop-Process -Id $launched.Id"));
    }

    @Test
    void linuxPortableHelperWaitsSwapsAndRollsBack() {
        String script = DesktopUpdateInstaller.linuxScript();

        assertTrue(script.contains("while kill -0 \"$parent_pid\""));
        assertTrue(script.contains("mv -- \"$application_root\" \"$backup_path\""));
        assertTrue(script.contains("mv -- \"$backup_path\" \"$application_root\""));
        assertTrue(script.contains("wait_app_ready"));
        assertTrue(script.contains("JQ_VIEWER_UPDATE_READY_FILE"));
        assertTrue(script.contains("kill \"$launched_pid\""));
    }

    @Test
    void writesStartupConfirmationOnlyInsideUpdateStateDirectory() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-update-ready-");
        Paths paths = new Paths(root.resolve("program"), root.resolve("home"), Map.of(), "Linux");
        Path ready = paths.stateDirectory().resolve("update/startup-test.ready");

        DesktopUpdateInstaller.confirmStarted(paths, ready.toString());

        assertTrue(Files.isRegularFile(ready));
    }
}
