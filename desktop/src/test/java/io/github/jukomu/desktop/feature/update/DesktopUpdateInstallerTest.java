package io.github.jukomu.desktop.feature.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jukomu.desktop.data.Paths;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void updateHelperRunsFromUpdateDirectory(@TempDir Path root) throws Exception {
        Path applicationRoot = root.resolve("JQ-Viewer");
        Path launcher = applicationRoot.resolve("JQ-Viewer.exe");
        Files.createDirectories(applicationRoot);
        Files.writeString(launcher, "launcher");

        Path localAppData = root.resolve("local-app-data");
        Paths paths = new Paths(
            applicationRoot,
            root.resolve("home"),
            Map.of("LOCALAPPDATA", localAppData.toString()),
            "Windows");
        Path updateDirectory = paths.stateDirectory().resolve("update");
        Files.createDirectories(updateDirectory);

        DesktopUpdateConfiguration configuration = new DesktopUpdateConfiguration(
            "2.1.0",
            "windows",
            "x64",
            "portable",
            "zip",
            "test-key",
            new byte[] {1},
            launcher,
            applicationRoot);
        DesktopUpdateInstaller installer = new DesktopUpdateInstaller(configuration, paths);
        Method builderMethod = DesktopUpdateInstaller.class.getDeclaredMethod(
            "windowsBuilder", Path.class, Path.class, Path.class);
        builderMethod.setAccessible(true);

        ProcessBuilder builder = (ProcessBuilder) builderMethod.invoke(
            installer,
            root.resolve("update.zip"),
            updateDirectory.resolve("install.log"),
            updateDirectory.resolve("startup.ready"));

        assertEquals(updateDirectory.toFile(), builder.directory());
    }
}
