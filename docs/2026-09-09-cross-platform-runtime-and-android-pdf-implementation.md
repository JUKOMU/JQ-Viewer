# 跨平台 Runtime 与 Android PDF 能力实现说明

本次改造统一了前端的平台访问边界，并将 Android PDF 的文件引用、数据存储、页面渲染和导出链路切换到新的公共契约。

## 总体架构

```text
页面、组件与业务服务
        │
        ▼
FrontendRuntime
  ├─ BackendClient      JSON command/query
  ├─ BackendEvents      具名 JSON event
  ├─ ResourceResolver   图片、PDF 与 PDF 页面资源 URL
  └─ PlatformServices   文件、通知、阅读器、更新等平台能力
        │
        ▼
Android adapter
        │
        ▼
Capacitor Plugin 与 Android 原生实现
```

`FrontendRuntime` 在启动时注入。页面与业务服务只依赖公共契约，Capacitor 方法、listener handle、Android 虚拟域名和原生 DTO 保留在 Android adapter 边界。

## 前端 Runtime

### BackendClient

`BackendClient` 承载搜索、登录、收藏、历史、下载、PDF 管理和通用设置等 JSON command/query。公共方法采用显式清单，Android 专属权限、文件选择、阅读器系统控制、OCR、启动路由和 APK 更新由平台能力提供。

### BackendEvents

原始 Capacitor event name 被收口为具名订阅接口，页面统一使用公共 `ListenerHandle`。

有快照的状态先建立订阅，再获取快照：

- 更新状态和 PDF 导出状态按单调 revision 合并。
- 下载进度使用本地 request sequence，防止旧快照覆盖较新的任务状态。

### ResourceResolver

图片、PDF 原文件和 PDF 页面图通过 `ResourceResolver` 返回资源 URL。页面不再拼接 Android 虚拟域名，也不处理原生 Base64 页面结果。

### PlatformServices

文件、通知、阅读器系统控制、更新、OCR、启动路由和公开下载使用 capability 表达。不可用能力不提供可调用 API。

通知权限流程由独立 workflow 处理，并发请求复用同一流程。更新流程使用带 `id` 和 `stateRevision` 的 `UpdateUserAction`，相同 action 去重，过期 action 被拒绝，状态按 revision 合并。

## FileRef 与 FolderRef

前端使用 branded string 表达平台持有的文件和目录引用：

```ts
type FileRef = string & { readonly __fileRef: unique symbol }
type FolderRef = string & { readonly __folderRef: unique symbol }

interface ExportTarget {
  folder: FolderRef
  relativePath: string
}
```

Android 使用以下引用格式：

```text
file:path:<absolute-path>
file:saf:<content-document-uri>
folder:path:<absolute-path>
folder:saf:<content-tree-uri>
```

引用由 Android 创建和解析，TypeScript 只存储和传递。`displayPath` 只用于展示和确认，不作为打开、删除或覆盖目标。

PDF 扫描、导入、列表、存在性检查、打开、阅读和删除使用 `FileRef`；目录选择、扫描和导出目标使用 `FolderRef`。文件和目录引用不能互换，Android adapter 不使用 `displayPath` 回退。

PDF 原文件通过以下 WebView URL 提供：

```text
https://jqviewer.local/pdf/<base64url(FileRef)>
```

`PdfServer` 解码并解析 path 或 SAF 文件引用，将非法引用、文件缺失、权限错误和打开失败映射为对应 HTTP 响应。

## Android PDF 数据迁移

PDF 数据库升级到 v10。`pdf_files` 使用唯一的 `file_ref` 定位文件，并保留 `display_path` 供界面展示。

迁移规则：

- 先迁移 `pdf_files`，再迁移同库中可能存在的 `imported_pdfs`。
- 普通绝对路径转换为 `file:path:`，content document URI 转换为 `file:saf:`。
- 迁移期间不访问实际文件或 SAF provider。
- 缺失的 availability 和 verification 状态使用新表允许的默认值。
- 无法转换的单条记录被跳过，其余记录继续迁移。
- 重复 `file_ref` 使用 `INSERT OR IGNORE`，优先保留原 `pdf_files` 记录。
- 旧导出任务、章节快照和分卷快照不迁移，新表从空状态开始。
- 旧导出目录设置被清除，下一次导出重新选择目录。

升级后只使用 v10 schema 和新引用，不保留旧 locator 双读、双写或 `displayPath` fallback。

## PDF 导入、管理与错误处理

- 文件夹选择返回 `FolderRef` 和展示路径。
- 扫描返回 `FileRef`、文件名和展示路径；名称以 `.pdf` 结尾的目录不会被识别为文件。
- 导入、阅读、存在性检查、打开和删除只提交 `FileRef`。
- SAF 权限失效返回 `permission-denied`，文件不存在返回 `not-found`。
- 删除、任务查询、取消和重试中的缺失或状态冲突使用结构化错误码，并保留错误消息。
- 未识别的原生 rejection 归一为 `internal`，不根据异常文本推断错误类型。

## Android PDF 页面资源

Android native reader 的页面渲染链路为：

```text
ResourceResolver.renderPdfPage
  → Android renderPdfPage
  → pdfCommandExecutor 单线程生成 PNG
  → 返回 https://jqviewer.local/pdf-page/<id>.png
  → WebView 拦截器读取 PNG
```

`id` 是 64 位小写 SHA-256 十六进制字符串，由 FileRef、页码、目标宽度和源文件长度、修改时间生成。页面文件保存在：

```text
<cacheDir>/pdf-pages/<id>.png
```

缓存规则：

- 初始化时清除上一进程留下的 PNG 和临时文件。
- PNG 写入临时文件后再替换为最终文件。
- 缓存上限为 128 MiB，超限时删除最旧页面。
- 单页最多 8,000,000 个像素，超限时按比例缩小。
- WebView 只接受 `/pdf-page/[0-9a-f]{64}.png`，并返回 200、400、404 或 500。

前端最多保留两个活跃渲染请求。过期 generation 的结果不会写回界面；Plugin rejection 进入失败状态；页面资源加载失败时只重建一次，第二次失败显示错误占位。

非原生 PDF 阅读继续使用 pdf.js 和 blob URL。

## PDF 导出

导出目标使用 `FolderRef + relativePath`。Android 校验空段、绝对路径、`.`、`..` 和越界路径。

path 目录直接生成最终文件。SAF 目录先在应用私有目录生成 staging PDF，再按完整 `targetName` 创建目录并复制到 DocumentsProvider；嵌套目录和分卷文件保留相对路径。

覆盖流程：

1. 首次提交使用 `allowOverwrite=false`。
2. 目标已存在时返回 `PDF_OUTPUT_EXISTS`，不创建失败任务。
3. DownloadPage 汇总冲突项并请求覆盖确认。
4. 确认后只以 `allowOverwrite=true` 重提冲突任务。
5. 发布前再次检查目标，处理预检后的竞态。

SAF staging 在成功、失败、取消和启动恢复时清理。复制失败或取消时清理未完成的目标文件，不删除已注册的最终输出。

导出完成通知使用最终 `outputFileRef`：path 输出通过 FileProvider 打开，SAF 输出直接打开最终 document URI。

## Desktop 接入边界

前端 Runtime 契约可供 Desktop adapter 实现。Desktop adapter、HTTP/JSON client、SSE、本机资源服务、host、托盘、安装器、系统通知和 updater 不在本次实现中，旧 `desktop` 分支协议也未恢复。
