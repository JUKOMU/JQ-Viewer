# Desktop 发布配置

Desktop 正式发布仅使用 GitHub Release；Gitee Release 保存同版本、同字节资产作为备份。Windows on ARM 使用 Windows x64 包和 Windows 11 的 x64 应用仿真，不发布或标注为原生 Windows ARM64。

## GitHub 配置

在仓库 `Settings` → `Environments` 中创建 `release` environment。正式 Release job 只从该 environment 读取私钥；缺失或不匹配时发布失败。

### Environment secrets

| 名称                                 | 内容                                                      |
| ------------------------------------ | --------------------------------------------------------- |
| `RELEASE_ED25519_PRIVATE_KEY_BASE64` | JQ-Viewer 发布清单专用 Ed25519 PKCS#8 PEM 私钥的 Base64。 |
| `RPM_GPG_PRIVATE_KEY_BASE64`         | 现有 OpenPGP 发布私钥导出文件的 Base64。                  |
| `RPM_GPG_PASSPHRASE`                 | OpenPGP 私钥口令。                                        |

私钥不得写入仓库、Issue、PR、Actions 日志或普通 repository variable。所有者应离线保留加密备份和 OpenPGP 吊销资料。

### Repository Actions variables

Desktop 原生包在进入 `release` environment 前由可复用构建 workflow 生成，因此客户端需要内置的公开信任根必须配置在仓库 `Settings` → `Secrets and variables` → `Actions` → `Variables`。这些值不是秘密：

| 名称                                     | 内容                                                   |
| ---------------------------------------- | ------------------------------------------------------ |
| `RELEASE_ED25519_KEY_ID`                 | 稳定密钥标识，只能包含字母、数字、点、下划线和连字符。 |
| `RELEASE_ED25519_PUBLIC_KEY_SPKI_BASE64` | 与私钥对应的 Ed25519 SPKI DER 公钥 Base64。            |

### Environment variables

以下值继续配置在 `release` environment：

| 名称                  | 内容                                 |
| --------------------- | ------------------------------------ |
| `RPM_GPG_FINGERPRINT` | OpenPGP 发布密钥的完整 fingerprint。 |

workflow 会从 Ed25519 私钥重新派生公钥并与 variable 逐字节比较；RPM 私钥导入后也会核对完整 fingerprint。PR CI 使用运行时生成的临时测试密钥，不读取 `release` environment。正式版继续复用已有 `latest.json`，在其中增加 `desktop.artifacts`；不发布 Desktop 专用清单或公钥 JSON。

## 首次配置

在离线或受控主机上生成 JQ-Viewer 专用 Ed25519 密钥，不要在 GitHub Actions 中生成正式私钥：

```bash
openssl genpkey -algorithm ED25519 -out jq-viewer-desktop-ed25519.pem
openssl base64 -A -in jq-viewer-desktop-ed25519.pem > jq-viewer-desktop-ed25519.pem.base64
openssl pkey -in jq-viewer-desktop-ed25519.pem -pubout -outform DER \
  | openssl base64 -A > jq-viewer-desktop-ed25519-public-spki.base64
```

将私钥原文件和 Base64 文件移出仓库并离线加密备份。随后分别配置 `release` environment secret 与 repository Actions variables：

```bash
gh secret set --env release RELEASE_ED25519_PRIVATE_KEY_BASE64 \
  < jq-viewer-desktop-ed25519.pem.base64
gh variable set RELEASE_ED25519_KEY_ID \
  --body jq-viewer-release-2026
gh variable set RELEASE_ED25519_PUBLIC_KEY_SPKI_BASE64 \
  --body "$(cat jq-viewer-desktop-ed25519-public-spki.base64)"
```

RPM 继续复用现有 OpenPGP 发布身份。在受控主机导出完整私钥并填写同一 environment；`RPM_GPG_FINGERPRINT` 必须使用 `gpg --with-colons --list-secret-keys` 输出的完整主密钥 fingerprint，不能使用短 ID：

```bash
gpg --batch --armor --export-secret-keys FULL_FINGERPRINT \
  | base64 --wrap=0 > jq-viewer-rpm-private-key.asc.base64
gh secret set --env release RPM_GPG_PRIVATE_KEY_BASE64 \
  < jq-viewer-rpm-private-key.asc.base64
gh secret set --env release RPM_GPG_PASSPHRASE
gh variable set --env release RPM_GPG_FINGERPRINT --body FULL_FINGERPRINT
```

配置完成后先用 prerelease tag 验证完整发布流程。正式发布缺少任一值、密钥不匹配、RPM 签名失败或双源字节不一致时都会停止，不会降级为未签名发布。

## 正式发布资产

- Windows x64：EXE 安装版、ZIP 便携版；两者同时声明兼容 Windows x64 和 Windows on ARM x64 仿真。
- Linux x64/arm64：DEB、RPM、TAR.GZ 便携版。
- `latest.json`：沿用 Android 正式版更新清单，并通过 `desktop.artifacts` 记录平台、架构、兼容架构、包类型、双源 URL、大小和 SHA-256。
- `latest.json.sig`：对 `latest.json` 精确字节的 Ed25519 detached signature。公钥不会作为 Release 资产发布；客户端信任根必须由应用内固定公钥建立。
- `SHA256SUMS`：Desktop 包、共享清单和签名的附加人工校验表；prerelease 沿用现有行为，不发布 `latest.json`，校验表只包含 Desktop 包。

Windows 发布物不做 Authenticode。用户可能看到 `Unknown publisher`、SmartScreen 警告，或在受策略管理的设备上被阻止。workflow 不会声明 Windows 包已获得系统级发布者签名。
