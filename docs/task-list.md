# 功能缺口任务清单

> 创建时间：2026-05-10
> 用途：新对话的入口文档。阅读本文即可了解项目当前状态和待完成工作。

---

## 快速了解项目

### 技术栈
- 前端：Vue 3 + Ionic Vue 8 + Capacitor 8 + TypeScript ~5.9
- Android：Java + jmcomic-api-java 1.1.0
- 存储：SQLite（下载任务）+ localStorage（缓存/离线收藏夹）
- 图片：WebView 资源拦截（ImageCache LRU 内存缓存） + FileStore（离线文件）

### 推荐阅读顺序（首次接手项目）
1. `docs/project-state-2026-05-21.md` — 模块完成度快照（最新）
2. `docs/log.md` — 完整变更日志（了解历史演进）
3. `.claude/CLAUDE.md` — 项目工作规范
4. 然后回到本文档选择任务

### 目录结构关键位置
```
src/
  views/          — 9个页面（Home/Search/Category/Favorite/Download/Setting/AlbumDetail/PreviewAll/ReaderPage）
  components/     — 按功能分目录（album/reader/search/favorite/download/common/menu）
  services/       — JmcomicService.ts（插件桥接）、JmcomicTypes.ts（所有类型）、OfflineDownloadService.ts、OfflineFavoriteService.ts
  composables/    — sideMenuState.ts（左右菜单互斥状态）
  router/         — 9条路由

android/.../bridge/               Capacitor 入口层
  JmcomicPlugin.java          — 26个@PluginMethod 薄门面 + ServiceListener 事件转发
android/.../service/              业务逻辑层 (零Capacitor依赖)
  ApiService.java             — 搜索/详情/评论/收藏 API + 数据转换
  DownloadService.java        — 下载编排 + 进度推送
  DownloadObserver.java       — 库 TaskObserver 适配
  PreloadService.java         — 图片预加载 + 缓存管理
  SettingsService.java        — 设置读写 + 公开/私有搬迁
  PermissionService.java      — 权限 API 版本适配
android/.../data/                 持久化层
  ImageCache.java             — LRU图片缓存 + WebView虚拟URL拦截 (原ImageRegistry)
  DownloadStore.java          — SQLite下载表 (原DownloadDatabase)
  FileStore.java              — 文件系统 (原FileStorage)
  SettingsStore.java          — SQLite设置表 (原SettingsDatabase)

JMComic-Api-Java/   — 库源码（jmcomic-api接口 + jmcomic-core实现 + jmcomic-android-support）
```

---

## 任务列表

### 任务1：设置页 ✅ 已完成 (2026-05-10~13)

| 属性 | 内容 |
|------|------|
| **现状** | 已完成：缓存/阅读/下载/关于 4 分组 7 设置项 |
| **完成内容** | 缓存容量设置、阅读预加载页数/并发数、下载并发数/公开下载、版本号 |

**关键文件：**
- `src/views/SettingPage.vue` — 完整设置页 UI
- `src/services/JmcomicService.ts` — 设置相关桥接方法
- `src/services/SettingsService.ts` — SettingsStore 内存缓存 + DB 持久化
- `src/services/JmcomicTypes.ts` — 设置相关类型定义
- `android/.../data/SettingsStore.java` — SQLite 设置表
- `android/.../data/FileStore.java` — 公开下载文件搬迁 + MediaStore 通知

**2026-05-13 补充：** 公开下载功能完善——跨存储卷搬迁（复制+校验+删除）、checkpoint 断点续迁、进度弹窗、MediaStore 通知、非终态任务拦截

---

### 任务2：首页内容

| 属性 | 内容 |
|------|------|
| **现状** | `HomePage.vue` 只有一个 `KeywordSearchBar` |
| **目标** | 添加首页推荐/排行/最新内容区 |
| **工作量** | 中等（~200行Vue + 可能需要库接口） |
| **依赖** | 无 |

**现有基础设施：**
- `JmcomicService.search()` / `categories()` — 可用于获取排行/最新内容
- `SearchResultContainer` 组件 — 已支持结果展示，可直接复用

**关键文件：**
- `src/views/HomePage.vue` — 需要扩写
- `src/components/search/SearchResultContainer.vue` — 可复用

**建议内容：**
- 推荐/热门本子横向滚动卡片
- 最新更新列表
- 分类快捷入口

---

### 任务3：登录页

| 属性 | 内容 |
|------|------|
| **现状** | 无任何用户认证流程，部分需要登录态的功能入口缺失 |
| **目标** | 添加登录页，实现用户名/密码登录 |
| **工作量** | 中等（~200行 + 库客户端登录方法） |
| **依赖** | 完成后可解锁评论发表等功能 |

**现有基础设施：**
- `JmApiClient` 构造时可传入 `JmConfiguration`（支持用户名/密码配置）
- `bridge/JmcomicPlugin.java` — `getClient()` 使用空配置创建客户端（无认证）

**关键文件：**
- `android/.../bridge/JmcomicPlugin.java` — `getClient()` 需要支持登录态
- `JMComic-Api-Java/jmcomic-core/.../config/JmConfiguration.java` — 查看配置选项

**建议实现：**
- 登录页面 UI（用户名 + 密码）
- Android侧新增 `login(username, password)` @PluginMethod
- 登录成功后重建共享 client（带认证配置）
- 登录状态持久化（SharedPreferences）

---

### 任务4：评论发表/回复

| 属性 | 内容 |
|------|------|
| **现状** | `AlbumCommentsTab.vue` 只能展示评论，无发表/回复入口 |
| **目标** | 评论区底部添加输入框，支持发表和回复 |
| **工作量** | 中等（~150行Vue + Android新增方法） |
| **依赖** | 需要用户登录态（任务3） |

**现有基础设施：**
- `AlbumCommentsTab.vue` 已展示评论列表（含嵌套回复）
- `service/ApiService.java` — `getComments` 方法
- 库的 `JmClient` 接口中有 `commentAlbum()` / `replyComment()` 方法（需确认）

**关键文件：**
- `src/components/album/AlbumCommentsTab.vue` — 需添加底部输入区域
- `JMComic-Api-Java/jmcomic-api/.../client/JmClient.java` — 查看评论相关方法

---

### 任务5：本子级下载

| 属性 | 内容 |
|------|------|
| **现状** | 下载仅支持单个章节（`downloadChapter`），详情页的下载按钮只能下载选中的章节 |
| **目标** | 增加整个本子下载选项 |
| **工作量** | 中等（Android侧 ~100行 + Vue侧 ~50行） |
| **依赖** | 无 |

**现有基础设施：**
- 库的 `createDownloadTask(JmAlbum, Path)` 已实现本子级任务树
- `service/DownloadService.java` — `createDownloadTask(photo, chapterPath)` 章节级
- `AlbumDetailPage.vue:291-327` — `handleDownload()` 当前流程

**关键文件：**
- `android/.../service/DownloadService.java` — `downloadChapter` 方法（参考实现 `downloadAlbum`）
- `JMComic-Api-Java/jmcomic-core/.../client/AbstractJmClient.java:688` — `createDownloadTask(album, path)`
- `src/views/AlbumDetailPage.vue:291-327` — 下载触发逻辑

**参考文档：** `docs/download-refactor-plan.md` — 了解当前下载架构

---

### 任务6：下载暂停/恢复 ✅ 已完成 (2026-05-10)

| 属性 | 内容 |
|------|------|
| **现状** | 已完成：`pauseDownload` / `resumeDownload` @PluginMethod 已实现，Vue 侧按钮已接入 |
| **参考提交** | `0fdbb18 feat: 下载任务暂停/恢复功能`

---

### 任务7：批量化搜索

| 属性 | 内容 |
|------|------|
| **现状** | `KeywordSearchBar.vue` 有模式切换（单本/批量），但批量模式的上传按钮无功能 |
| **目标** | 决定批量模式的去向：要么实现功能，要么移除UI |
| **工作量** | 取决于走向（实现 → 中，移除 → 极小） |
| **依赖** | 无 |

**关键文件：**
- `src/components/search/KeywordSearchBar.vue` — 查看 modeSelect/batch 相关代码

**建议：** 如果批量搜索短期内没有明确需求，直接移除模式切换和上传按钮，简化 UI。

---

### 任务8：离线收藏夹管理 UI

| 属性 | 内容 |
|------|------|
| **现状** | 离线收藏夹支持增删，但 UI 上无法创建/重命名/删除文件夹 |
| **目标** | 右侧侧边栏增加管理按钮（新建文件夹、长按删除/重命名） |
| **工作量** | 小（~80行Vue） |
| **依赖** | 无 |

**现有基础设施：**
- `OfflineFavoriteService.ts` — `createFolder(name)` / `deleteFolder(id)` 方法已实现
- `FavoriteSideMenu.vue` — 已渲染在线/离线文件夹列表
- `FavoritePage.vue` — 已使用 `getFolders()` 获取文件夹列表

**关键文件：**
- `src/components/favorite/FavoriteSideMenu.vue` — 文件夹列表渲染，增加管理按钮
- `src/services/OfflineFavoriteService.ts` — 所有 CRUD 方法

**参考文档：** `docs/favorite-page-plan.md` — 收藏夹设计方案

---

## 任务依赖关系

```
无依赖，可随时开始:
  任务1(设置页)  任务2(首页)  任务5(本子下载)  任务6(暂停/恢复)  任务7(批量搜索)  任务8(离线收藏夹管理)

依赖其他任务:
  任务3(登录页) ← 任务4(评论发表)
```

---

## 附加信息

### 项目工作规范（来自 CLAUDE.md）
- 中文沟通，先计划后编码
- 方案需用户确认后才能执行，执行后记录到 `docs/log.md`
- 代码审查时先报告再修复
- 可执行 `./gradlew assembleDebug` 和 `vue-tsc --noEmit` + `vite build` 验证编译
- 仅在明确要求时才 git commit

### Claude Code 常用命令
- `/review` — 代码审查
- `/simplify` — 代码质量/复用优化
- 可用 `Skill` 工具调用 `anthropic-skills:brainstorming` 讨论设计方案
