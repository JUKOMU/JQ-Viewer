# 前端跨平台桥接实现说明

本次实现对应 ASTRA-86 已批准的 `2026-09-06-jq-viewer-frontend-platform-bridge-plan-v2.md`。

## 已实现边界

- `FrontendRuntime` 负责一次性注入 common `BackendClient`、具名 `BackendEvents`、`ResourceResolver` 和 `PlatformServices`。
- Android adapter 继续调用现有 `JmcomicNativeClient`，并将事件 handle、资源 URL、错误和平台能力转换为 common contract。
- 页面与业务服务不再导入 Capacitor listener 类型、`@capacitor/app` 或固定 Android 资源域名。
- PDF 扫描、导入、阅读、文件存在性检查、删除和导出任务在 common 层使用 `FileRef`/`FolderRef`；`displayPath` 只用于展示和用户确认。
- Android Plugin 的方法、事件、数据库、localStorage 和原有 path/SAF 参数均保持不变，raw DTO 只存在于 Plugin contract 与 Android adapter 边界。
- 更新状态使用 revision 合并；安装权限通过带 `id` 和 `stateRevision` 的 `UpdateUserAction` 执行，未知 native rejection 归一为 `internal`。

## 明确不在本次范围

- 没有实现 Desktop adapter、HTTP/JSON client、SSE、host、系统托盘、安装器或后端。
- 没有搬回旧 `desktop` 分支代码，也没有兼容旧 HTTP/token/SSE 协议。
- 没有修改 Android Java/Kotlin、Plugin public contract、数据库或持久化格式。

Desktop 后续只需要实现同一 runtime contract 的 adapter，并为实际 HTTP/SSE/resource 协议单独建立契约测试。
