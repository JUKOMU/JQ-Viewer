package io.github.jukomu.desktop.feature.update;

import io.github.jukomu.desktop.data.Paths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 在主进程退出后安装原生包，或替换便携目录。 */
public final class DesktopUpdateInstaller {
    private static final String APPLICATION_DIRECTORY_NAME = "JQ-Viewer";
    private static final String STARTUP_READY_ENV = "JQ_VIEWER_UPDATE_READY_FILE";

    private final DesktopUpdateConfiguration configuration;
    private final Path updateDirectory;

    public DesktopUpdateInstaller(DesktopUpdateConfiguration configuration, Paths paths) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.updateDirectory = Objects.requireNonNull(paths, "paths")
                .stateDirectory().resolve("update");
    }

    public void launch(Path packagePath) {
        configuration.requireConfigured();
        requireInstallEnvironment(packagePath);
        try {
            Files.createDirectories(updateDirectory);
            Path logPath = updateDirectory.resolve("install-" + Instant.now().toEpochMilli() + ".log");
            Path readyPath = updateDirectory.resolve("startup-" + UUID.randomUUID() + ".ready");
            Files.deleteIfExists(readyPath);
            ProcessBuilder builder = configuration.platform().equals("windows")
                    ? windowsBuilder(packagePath, logPath, readyPath)
                    : linuxBuilder(packagePath, logPath, readyPath);
            builder.redirectErrorStream(true);
            builder.redirectOutput(logPath.toFile());
            builder.start();
        } catch (IOException exception) {
            throw new UpdateException("无法启动更新安装辅助程序", exception);
        }
    }

    private ProcessBuilder windowsBuilder(
            Path packagePath,
            Path logPath,
            Path readyPath
    ) throws IOException {
        Path script = updateDirectory.resolve("apply-update-" + UUID.randomUUID() + ".ps1");
        Files.writeString(script, windowsScript(), StandardCharsets.UTF_8);
        SwapPaths swap = swapPaths();
        return new ProcessBuilder(List.of(
                "powershell.exe",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.toString(),
                Long.toString(ProcessHandle.current().pid()),
                configuration.packageType(),
                packagePath.toString(),
                configuration.applicationRoot().toString(),
                configuration.launcherPath().toString(),
                swap.stage().toString(),
                swap.backup().toString(),
                logPath.toString(),
                readyPath.toString()
        ));
    }

    private ProcessBuilder linuxBuilder(
            Path packagePath,
            Path logPath,
            Path readyPath
    ) throws IOException {
        requireCommand("/bin/sh");
        if (configuration.packageType().equals("installer")) {
            requireCommand("pkexec");
            requireCommand(configuration.packageFormat().equals("deb") ? "dpkg" : "rpm");
        } else {
            requireCommand("tar");
        }

        Path script = updateDirectory.resolve("apply-update-" + UUID.randomUUID() + ".sh");
        Files.writeString(script, linuxScript(), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(script, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
            ));
        } catch (UnsupportedOperationException ignored) {
            // Linux 发布文件系统支持 POSIX 权限；测试文件系统可直接由 /bin/sh 读取。
        }
        SwapPaths swap = swapPaths();
        return new ProcessBuilder(List.of(
                "/bin/sh",
                script.toString(),
                Long.toString(ProcessHandle.current().pid()),
                configuration.packageType(),
                configuration.packageFormat(),
                packagePath.toString(),
                configuration.applicationRoot().toString(),
                configuration.launcherPath().toString(),
                swap.stage().toString(),
                swap.backup().toString(),
                logPath.toString(),
                readyPath.toString()
        ));
    }

    /** 更新辅助程序通过此标记确认新版本已完成宿主和后端初始化。 */
    public static void confirmStarted(Paths paths) {
        String configuredPath = System.getenv(STARTUP_READY_ENV);
        if (configuredPath == null || configuredPath.isBlank()) return;
        confirmStarted(paths, configuredPath);
    }

    static void confirmStarted(Paths paths, String configuredPath) {
        Path updateDirectory = Objects.requireNonNull(paths, "paths")
                .stateDirectory().resolve("update").toAbsolutePath().normalize();
        Path readyPath = Path.of(configuredPath).toAbsolutePath().normalize();
        String fileName = readyPath.getFileName() == null
                ? ""
                : readyPath.getFileName().toString();
        if (!updateDirectory.equals(readyPath.getParent())
                || !fileName.startsWith("startup-")
                || !fileName.endsWith(".ready")) {
            throw new IllegalStateException("更新启动确认文件路径无效");
        }
        try {
            Files.createDirectories(updateDirectory);
            Path temporary = updateDirectory.resolve(fileName + ".tmp-" + UUID.randomUUID());
            Files.writeString(temporary, "ready\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, readyPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, readyPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法确认更新后的 Desktop 已完成启动", exception);
        }
    }

    private void requireInstallEnvironment(Path packagePath) {
        if (!Files.isRegularFile(packagePath)) {
            throw new UpdateException("没有可安装的 Desktop 更新包");
        }
        Path applicationRoot = configuration.applicationRoot();
        Path launcher = configuration.launcherPath();
        if (!Files.isDirectory(applicationRoot) || !Files.isRegularFile(launcher)) {
            throw new UpdateException("无法定位当前 Desktop 安装目录");
        }
        if (configuration.packageType().equals("portable")) {
            Path parent = applicationRoot.getParent();
            if (parent == null || !Files.isWritable(parent)) {
                throw new UpdateException("便携版所在目录不可写，无法替换");
            }
        }
    }

    private void requireCommand(String command) {
        if (command.startsWith("/") && Files.isExecutable(Path.of(command))) return;
        try {
            Process process = new ProcessBuilder("/bin/sh", "-c", "command -v -- \"$1\"", "sh", command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            if (!finished || process.exitValue() != 0) {
                throw new UpdateException("系统缺少更新所需命令: " + command);
            }
        } catch (IOException exception) {
            throw new UpdateException("无法检查更新安装环境", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UpdateException("更新安装环境检查已取消", exception);
        }
    }

    private SwapPaths swapPaths() {
        Path root = configuration.applicationRoot();
        Path parent = root.getParent();
        String token = UUID.randomUUID().toString();
        return new SwapPaths(
                parent.resolve("." + APPLICATION_DIRECTORY_NAME + ".update-" + token),
                parent.resolve("." + APPLICATION_DIRECTORY_NAME + ".backup-" + token)
        );
    }

    static String windowsScript() {
        return """
                param(
                  [long]$ParentPid,
                  [string]$PackageType,
                  [string]$PackagePath,
                  [string]$ApplicationRoot,
                  [string]$LauncherPath,
                  [string]$StagePath,
                  [string]$BackupPath,
                  [string]$LogPath,
                  [string]$ReadyPath
                )
                $ErrorActionPreference = 'Stop'
                function Start-JqViewer([bool]$UseHandshake) {
                  if (Test-Path -LiteralPath $LauncherPath) {
                    if ($UseHandshake) {
                      Remove-Item -LiteralPath $ReadyPath -Force -ErrorAction SilentlyContinue
                      $env:JQ_VIEWER_UPDATE_READY_FILE = $ReadyPath
                    }
                    try {
                      return Start-Process -FilePath $LauncherPath -PassThru
                    } finally {
                      if ($UseHandshake) {
                        Remove-Item Env:JQ_VIEWER_UPDATE_READY_FILE -ErrorAction SilentlyContinue
                      }
                    }
                  }
                  return $null
                }
                function Wait-JqViewerReady {
                  for ($attempt = 0; $attempt -lt 120; $attempt++) {
                    if (Test-Path -LiteralPath $ReadyPath -PathType Leaf) { return $true }
                    Start-Sleep -Milliseconds 500
                  }
                  return $false
                }
                try {
                  Wait-Process -Id $ParentPid -ErrorAction SilentlyContinue
                  if ($PackageType -eq 'installer') {
                    $installer = Start-Process -FilePath $PackagePath -Wait -PassThru
                    if ($installer.ExitCode -ne 0) {
                      throw "Installer exited with code $($installer.ExitCode)"
                    }
                    [void](Start-JqViewer $false)
                  } else {
                    Remove-Item -LiteralPath $StagePath -Recurse -Force -ErrorAction SilentlyContinue
                    Remove-Item -LiteralPath $BackupPath -Recurse -Force -ErrorAction SilentlyContinue
                    New-Item -ItemType Directory -Path $StagePath | Out-Null
                    Expand-Archive -LiteralPath $PackagePath -DestinationPath $StagePath -Force
                    $incoming = Join-Path $StagePath 'JQ-Viewer'
                    if (-not (Test-Path -LiteralPath $incoming -PathType Container)) {
                      throw 'Portable archive root is invalid'
                    }
                    Move-Item -LiteralPath $ApplicationRoot -Destination $BackupPath
                    $launched = $null
                    try {
                      Move-Item -LiteralPath $incoming -Destination $ApplicationRoot
                      $launched = Start-JqViewer $true
                      if ($null -eq $launched) { throw 'Updated launcher is missing' }
                      if (-not (Wait-JqViewerReady)) {
                        throw 'Updated application did not confirm startup'
                      }
                      Remove-Item -LiteralPath $BackupPath -Recurse -Force
                      Remove-Item -LiteralPath $ReadyPath -Force -ErrorAction SilentlyContinue
                    } catch {
                      if ($null -ne $launched -and -not $launched.HasExited) {
                        Stop-Process -Id $launched.Id -Force -ErrorAction SilentlyContinue
                        Wait-Process -Id $launched.Id -ErrorAction SilentlyContinue
                      }
                      Remove-Item -LiteralPath $ApplicationRoot -Recurse -Force -ErrorAction SilentlyContinue
                      if (Test-Path -LiteralPath $BackupPath) {
                        Move-Item -LiteralPath $BackupPath -Destination $ApplicationRoot
                      }
                      throw
                    } finally {
                      Remove-Item -LiteralPath $StagePath -Recurse -Force -ErrorAction SilentlyContinue
                    }
                  }
                  Remove-Item -LiteralPath $PackagePath -Force -ErrorAction SilentlyContinue
                } catch {
                  Add-Content -LiteralPath $LogPath -Value $_.Exception.ToString()
                  [void](Start-JqViewer $false)
                } finally {
                  Remove-Item -LiteralPath $ReadyPath -Force -ErrorAction SilentlyContinue
                  Start-Sleep -Milliseconds 200
                  Remove-Item -LiteralPath $PSCommandPath -Force -ErrorAction SilentlyContinue
                }
                """;
    }

    static String linuxScript() {
        return """
                #!/bin/sh
                set -u
                parent_pid="$1"
                package_type="$2"
                package_format="$3"
                package_path="$4"
                application_root="$5"
                launcher_path="$6"
                stage_path="$7"
                backup_path="$8"
                log_path="$9"
                ready_path="${10}"
                exec >>"$log_path" 2>&1

                start_app() {
                  use_handshake="$1"
                  if [ -x "$launcher_path" ]; then
                    if [ "$use_handshake" -eq 1 ]; then
                      rm -f -- "$ready_path"
                      env JQ_VIEWER_UPDATE_READY_FILE="$ready_path" nohup "$launcher_path" >/dev/null 2>&1 &
                      launched_pid=$!
                    else
                      nohup "$launcher_path" >/dev/null 2>&1 &
                    fi
                    return 0
                  fi
                  return 1
                }

                wait_app_ready() {
                  attempt=0
                  while [ "$attempt" -lt 120 ]; do
                    [ -f "$ready_path" ] && return 0
                    attempt=$((attempt + 1))
                    sleep 0.5
                  done
                  return 1
                }

                while kill -0 "$parent_pid" 2>/dev/null; do sleep 0.2; done

                if [ "$package_type" = installer ]; then
                  status=1
                  if [ "$package_format" = deb ]; then
                    pkexec dpkg -i "$package_path" && status=0
                  elif [ "$package_format" = rpm ]; then
                    pkexec rpm -U --replacepkgs "$package_path" && status=0
                  fi
                  if [ "$status" -eq 0 ]; then
                    start_app 0 || true
                    rm -f -- "$package_path"
                  else
                    echo "Installer failed or was cancelled"
                    start_app 0 || true
                  fi
                else
                  rm -rf -- "$stage_path" "$backup_path"
                  mkdir -p -- "$stage_path" || exit 1
                  if ! tar -xzf "$package_path" -C "$stage_path"; then
                    echo "Portable archive extraction failed"
                    start_app 0 || true
                    rm -rf -- "$stage_path"
                    exit 1
                  fi
                  incoming="$stage_path/JQ-Viewer"
                  if [ ! -d "$incoming" ]; then
                    echo "Portable archive root is invalid"
                    start_app 0 || true
                    rm -rf -- "$stage_path"
                    exit 1
                  fi
                  if ! mv -- "$application_root" "$backup_path"; then
                    echo "Unable to create portable backup"
                    start_app 0 || true
                    rm -rf -- "$stage_path"
                    exit 1
                  fi
                  launched_pid=""
                  if mv -- "$incoming" "$application_root" \
                    && start_app 1 \
                    && wait_app_ready; then
                    rm -rf -- "$backup_path" "$stage_path"
                    rm -f -- "$ready_path"
                    rm -f -- "$package_path"
                  else
                    echo "Portable replacement failed; rolling back"
                    if [ -n "$launched_pid" ]; then
                      kill "$launched_pid" 2>/dev/null || true
                      wait "$launched_pid" 2>/dev/null || true
                    fi
                    rm -rf -- "$application_root"
                    mv -- "$backup_path" "$application_root" || exit 1
                    start_app 0 || true
                    rm -rf -- "$stage_path"
                  fi
                fi

                rm -f -- "$ready_path"
                rm -f -- "$0"
                """;
    }

    private record SwapPaths(Path stage, Path backup) {
    }
}
