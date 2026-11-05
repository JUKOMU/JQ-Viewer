# 2. 下载、安装与更新

## 选择发行包

前往 [JQ Viewer Releases](https://github.com/JUKOMU/JQ-Viewer/releases)，打开需要的正式版本，在 **Assets** 中选择对应平台的文件。

选择时依次确认：

1. **平台**：Android、Windows 或 Linux。
2. **架构**：例如 x64、arm64；Android 不确定架构时可以选择通用 APK。
3. **包类型**：安装包或便携压缩包。

`Source code` 是源码压缩包。安装应用请选择 APK、EXE、DEB、RPM，或相应的便携压缩包。

以下以 v2.2.2 为例，其他版本的文件名会包含不同的版本号。

## Android

最低系统版本为 **Android 7.0**。

| 选择 | v2.2.2 文件名或标识 |
| --- | --- |
| 不确定设备架构，使用通用包 | `JQ-Viewer-2_2_2-universal.apk` |
| 已知设备架构，使用对应包 | 文件名中包含 `arm64-v8a`、`armeabi-v7a`、`x86` 或 `x86_64` 的 APK |

安装步骤：

1. 下载所选 APK，在设备的下载列表或文件管理器中打开。
2. 如果系统要求安装授权，按系统提示完成授权，再返回继续安装。
3. 完成安装后，打开 JQ Viewer。

不同 Android 版本和设备厂商的安装授权提示可能不同，以设备当前显示的提示为准。

## Windows

Windows 发行包为 **x64**。Windows on ARM 也选择 x64 包，通过系统的 x64 应用仿真运行。

| 包类型 | v2.2.2 文件名 | 如何启动 |
| --- | --- | --- |
| 安装版 | `JQ-Viewer-2_2_2-windows-x64-installer.exe` | 运行安装程序，按向导完成安装后，从开始菜单或快捷方式启动 |
| 便携版 | `JQ-Viewer-2_2_2-windows-x64.zip` | 完整解压，在应用目录中运行 `JQ-Viewer.exe` |

便携版的启动程序依赖同目录中的其他文件。请保留完整目录结构，在解压后运行。

## Linux

Linux 同时提供 **x64** 和 **arm64** 包。

如果不确定架构，可以在终端执行：

```bash
uname -m
```

- 显示 `x86_64` 时，选择文件名中的 `linux-x64`。
- 显示 `aarch64` 或 `arm64` 时，选择 `linux-arm64`。

再根据发行版和使用方式选择包类型：

| 包类型 | 适用方式 | x64 文件名示例 |
| --- | --- | --- |
| DEB | 使用 DEB 软件包的发行版，例如 Debian、Ubuntu | `JQ-Viewer-2_2_2-linux-x64.deb` |
| RPM | 使用 RPM 软件包的发行版，例如 Fedora | `JQ-Viewer-2_2_2-linux-x64.rpm` |
| TAR.GZ | 完整解压后手动启动 | `JQ-Viewer-2_2_2-linux-x64.tar.gz` |

arm64 系统选择相同包类型下、文件名包含 `linux-arm64` 的版本。

**使用 DEB 或 RPM：**通过发行版的软件包安装工具安装下载的文件，完成后从应用菜单启动 JQ Viewer。

**使用 TAR.GZ：**将压缩包完整解压，在解压后的应用目录中运行 `bin/JQ-Viewer`。如果使用终端，先进入该应用目录，再执行：

```bash
./bin/JQ-Viewer
```

保留解压得到的完整目录结构。

## 桌面版是否需要开发环境

桌面发行包已经包含 Java 运行时。正常使用发行包不需要另行配置 JDK、Maven 或 Node.js；这些工具用于源码构建。

## 查看版本与检查更新

Android、Windows 和 Linux 的应用内入口相同：**侧边栏 → 设置 → 关于**。

1. 在“关于”页查看当前版本。
2. 点击 **“检查更新”**。
3. 发现新版本后，查看发布说明；确认要更新时按提示开始下载。
4. 等待下载和校验，出现 **“安装更新”** 时继续，并按当前平台的提示完成安装。

Android 更新过程中如出现通知权限或安装权限请求，需要按提示处理。桌面端出现的安装、权限或应用退出提示，也应按对应更新流程处理。

“检查失败”与“已是最新”表示不同的结果。检查失败时，可以稍后重试，或到 [Releases](https://github.com/JUKOMU/JQ-Viewer/releases) 查看发行信息。
