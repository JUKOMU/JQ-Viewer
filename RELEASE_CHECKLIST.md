# JQViewer 发布检查清单

发布前按本清单逐项确认。除非本次发布明确只做内部验证，否则不要跳过版本、迁移、签名和覆盖安装检查。

## 版本

- [ ] `android/app/build.gradle` 的 `versionCode` 已递增。
- [ ] `android/app/build.gradle` 的 `versionName` 已更新为目标版本。
- [ ] `package.json` 的 `version` 与 `versionName` 一致。
- [ ] `package-lock.json` 顶层版本与 root package 版本与 `package.json` 一致。
- [ ] README Version badge 与 `versionName` 一致。
- [ ] Git tag 使用 `v${versionName}` 格式。
- [ ] 已运行 `npm run check:version`。

## 数据与迁移

- [ ] 本次是否修改 SQLite schema 已确认。
- [ ] 如修改 SQLite schema，已使用增量迁移，不删除用户数据表。
- [ ] 本次是否修改 localStorage/sessionStorage key 已确认。
- [ ] 如修改 WebView 本地存储 key，已提供兼容读取或迁移路径。
- [ ] 本次是否修改文件目录结构、下载路径或 PDF 导入引用已确认。
- [ ] 如修改文件路径规则，旧文件仍可读取或可迁移。

## 权限、备份与凭据

- [ ] 本次是否新增或修改 Android 权限已确认。
- [ ] 如权限有变化，Manifest、请求时机、拒绝后的功能降级已确认。
- [ ] 备份策略已确认，不会备份敏感凭据或不可跨设备恢复的数据。
- [ ] 登录凭据保存策略已确认，加密失败路径已符合当前发布要求。

## 构建与测试

- [ ] `npm run lint` 通过。
- [ ] `npm run test:unit` 通过。
- [ ] `npm run build` 通过。
- [ ] `npm run check:version` 通过。
- [ ] `npx cap sync android` 已执行。
- [ ] Android debug 构建通过。
- [ ] release 前 Android release 构建通过。

## 真机验证

- [ ] 从上一个正式 APK 覆盖安装通过。
- [ ] 登录态、设置、收藏、历史、下载记录、PDF 导入记录仍存在。
- [ ] 首页、搜索、详情页、阅读器、PDF 阅读器、下载页、设置页、关于页可正常打开。
- [ ] 关于页版本显示正确。
- [ ] 应用内检查更新逻辑与 GitHub Release tag 匹配。

## 发布

- [ ] release APK 使用正式 keystore 签名。
- [ ] 已记录构建 commit、`versionCode`、`versionName` 和签名证书指纹。
- [ ] release notes 写明用户可感知变化、修复内容和已知问题。
- [ ] 如需回滚或紧急修复，已确认可用版本和重新发布步骤。
