# Desktop 发布配置（GitHub 网页手动配置）

Desktop 正式发布仅使用 GitHub Release；Gitee Release 保存同版本、同字节资产作为备份。Windows on ARM 使用 Windows x64 包和 Windows 11 的 x64 应用仿真，不发布或标注为原生 Windows ARM64。

本文只使用 GitHub 网页配置 Secret 和 Variable，不要求安装或使用 GitHub CLI。密钥仍需在离线或受控主机生成，生成命令不会上传任何内容。

## 配置位置

发布流程使用三类配置位置，不能混用：

| 位置                                    | GitHub 页面路径                                                | 用途                                    |
| --------------------------------------- | -------------------------------------------------------------- | --------------------------------------- |
| Repository secrets                      | `Settings` → `Secrets and variables` → `Actions` → `Secrets`   | Android 签名、GitHub/Gitee 发布令牌     |
| Repository variables                    | `Settings` → `Secrets and variables` → `Actions` → `Variables` | Desktop 客户端内置的 Ed25519 公开信任根 |
| `release` environment secrets/variables | `Settings` → `Environments` → `release`                        | Desktop 正式发布私钥、RPM 签名身份      |

Secret 保存后无法在 GitHub 页面重新查看原值，只能覆盖。不要把私钥、口令或令牌写入仓库、Issue、PR、Actions 日志或 Variable。

## 1. 核对现有 Android Repository Secrets

打开 `Settings` → `Secrets and variables` → `Actions` → `Secrets`。当前发布流程需要以下仓库级 Secret：

| Secret              | 应填写的内容                                                                                            |
| ------------------- | ------------------------------------------------------------------------------------------------------- |
| `KEYSTORE_BASE64`   | `android/app/release.keystore` 二进制文件的单行 Base64，不是文件路径，也不是 `keystore.properties` 文本 |
| `KEYSTORE_PASSWORD` | 本地 `android/keystore.properties` 的 `storePassword` 值                                                |
| `KEY_ALIAS`         | 本地 `android/keystore.properties` 的 `keyAlias` 值                                                     |
| `KEY_PASSWORD`      | 本地 `android/keystore.properties` 的 `keyPassword` 值                                                  |
| `GITEE_TOKEN`       | 用于写入 Gitee 备份 Release 的 token                                                                    |

GitHub 页面只显示 Secret 名称，不显示值。名称已经存在且 Android keystore 未更换时，不需要重新填写。`android/keystore.properties` 和 `android/app/release.keystore` 均为本地文件，不提交到仓库。

如需重新生成 `KEYSTORE_BASE64`，在仓库根目录执行以下任一命令，然后把输出完整复制到 GitHub Secret：

Linux：

```bash
base64 --wrap=0 android/app/release.keystore
```

macOS：

```bash
base64 < android/app/release.keystore | tr -d '\n'
```

PowerShell：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('android/app/release.keystore'))
```

发布 workflow 会用这四个 Android Secret 还原 keystore 和 `keystore.properties`，并校验 APK 签名证书。它们保持仓库级 Secret，不移动到 `release` environment。GitHub Release 使用 job 自动生成的 `github.token` 和 `contents: write` 权限，不需要额外配置长期 GitHub token。

## 2. 创建 release Environment

1. 打开仓库 `Settings` → `Environments`。
2. 点击 `New environment`。
3. 名称填写 `release`，点击 `Configure environment`。
4. 本项目暂不要求额外审批规则；如以后增加保护规则，发布 job 会在进入该 environment 前等待审批。

## 3. 生成 Ed25519 发布清单密钥

在离线或受控主机执行：

```bash
openssl genpkey -algorithm ED25519 -out jq-viewer-desktop-ed25519.pem
openssl base64 -A -in jq-viewer-desktop-ed25519.pem \
  > jq-viewer-desktop-ed25519.pem.base64
openssl pkey -in jq-viewer-desktop-ed25519.pem -pubout -outform DER \
  | openssl base64 -A \
  > jq-viewer-desktop-ed25519-public-spki.base64
```

将私钥原文件和 Base64 文件移出仓库并离线加密备份。建议使用稳定标识 `jq-viewer-release-2026` 作为当前密钥 ID；轮换密钥时使用新的标识。

## 4. 手动填写 release Environment

打开 `Settings` → `Environments` → `release`。

在 `Environment secrets` 区域逐项点击 `Add environment secret`：

| Name                                 | Secret value                                              |
| ------------------------------------ | --------------------------------------------------------- |
| `RELEASE_ED25519_PRIVATE_KEY_BASE64` | `jq-viewer-desktop-ed25519.pem.base64` 文件的完整单行内容 |
| `RPM_GPG_PRIVATE_KEY_BASE64`         | 现有 OpenPGP 发布私钥导出文件的完整单行 Base64            |
| `RPM_GPG_PASSPHRASE`                 | OpenPGP 私钥口令                                          |

在 `Environment variables` 区域点击 `Add environment variable`：

| Name                  | Value                                           |
| --------------------- | ----------------------------------------------- |
| `RPM_GPG_FINGERPRINT` | OpenPGP 主密钥的完整 fingerprint，不能使用短 ID |

RPM 私钥可在受控主机导出：

```bash
gpg --batch --armor --export-secret-keys FULL_FINGERPRINT \
  | base64 --wrap=0 \
  > jq-viewer-rpm-private-key.asc.base64
```

使用 `gpg --with-colons --list-secret-keys` 核对完整主密钥 fingerprint。私钥 Base64 填入 Secret，fingerprint 填入 Variable。

## 5. 手动填写 Repository Variables

打开 `Settings` → `Secrets and variables` → `Actions` → `Variables`，逐项点击 `New repository variable`：

| Name                                     | Value                                                             |
| ---------------------------------------- | ----------------------------------------------------------------- |
| `RELEASE_ED25519_KEY_ID`                 | 当前密钥的稳定标识，例如 `jq-viewer-release-2026`                 |
| `RELEASE_ED25519_PUBLIC_KEY_SPKI_BASE64` | `jq-viewer-desktop-ed25519-public-spki.base64` 文件的完整单行内容 |

这两个值不是秘密。Desktop 构建需要它们生成内置公开信任根，因此必须是 Repository Variables，不能只放在 `release` environment。

## 6. 配置完成后的页面核对

页面应出现以下配置名称：

- Repository secrets：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`、`GITEE_TOKEN`。
- Repository variables：`RELEASE_ED25519_KEY_ID`、`RELEASE_ED25519_PUBLIC_KEY_SPKI_BASE64`。
- `release` environment secrets：`RELEASE_ED25519_PRIVATE_KEY_BASE64`、`RPM_GPG_PRIVATE_KEY_BASE64`、`RPM_GPG_PASSPHRASE`。
- `release` environment variable：`RPM_GPG_FINGERPRINT`。

workflow 会从 Ed25519 私钥重新派生公钥并与 Repository Variable 逐字节比较；RPM 私钥导入后也会核对完整 fingerprint。缺少任一配置、密钥不匹配、RPM 签名失败或 GitHub/Gitee 双源字节不一致时都会停止，不会降级为未签名发布。

## 7. 发布或重跑 prerelease

新 prerelease 通过推送 tag 自动发布，不需要在 Actions 页面再次手动触发：

1. 确认待发布提交的项目版本与 tag 一致。
2. 创建 prerelease tag，例如 `v0.0.4-beta.1`。
3. 将 tag 推送到 GitHub；`Release` workflow 会自动开始。
4. 在运行详情中确认 Android 与 Desktop 产物并行准备，随后统一完成 RPM 签名和 GitHub/Gitee 发布。

只有重试已经存在的 tag 时才使用手动触发：

1. 先确认相同 tag 没有仍在运行或已经成功的 `Release` workflow，避免重复发布。
2. 打开仓库 `Actions` → `Release`，点击 `Run workflow`。
3. `Tag name to release` 填写已经存在的 tag。
4. prerelease tag 会根据 tag 中的后缀自动识别；也可以勾选 `Mark as pre-release?` 强制按 prerelease 发布。
5. 点击 `Run workflow`。

prerelease 用于验证构建和分发，不发布正式版 `latest.json`。正式 tag 发布时才会生成并签名共享更新清单。

## 正式发布资产

- Windows x64：EXE 安装版、ZIP 便携版；两者同时声明兼容 Windows x64 和 Windows on ARM x64 仿真。
- Linux x64/arm64：DEB、RPM、TAR.GZ 便携版。
- `latest.json`：沿用 Android 正式版更新清单，并通过 `desktop.artifacts` 记录平台、架构、兼容架构、包类型、双源 URL、大小和 SHA-256。
- `latest.json.sig`：对 `latest.json` 精确字节的 Ed25519 detached signature。公钥不会作为 Release 资产发布；客户端信任根必须由应用内固定公钥建立。
- `SHA256SUMS`：Desktop 包、共享清单和签名的附加人工校验表；prerelease 沿用现有行为，不发布 `latest.json`，校验表只包含 Desktop 包。

Windows 发布物不做 Authenticode。用户可能看到 `Unknown publisher`、SmartScreen 警告，或在受策略管理的设备上被阻止。workflow 不会声明 Windows 包已获得系统级发布者签名。
