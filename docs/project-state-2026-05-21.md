# JQViewer 项目状态快照

## 记录时间
2026-05-22

## 技术栈
- 前端：Vue 3 + Ionic Vue 8 + Capacitor 8 + TypeScript ~5.9
- Android：Java + jmcomic-api-java 1.1.0
- 存储：SQLite（下载任务 + 设置）+ localStorage（缓存/离线收藏夹）
- 图片：WebView 资源拦截（ImageCache LRU 内存缓存）+ FileStore（离线文件）

## Android 侧架构（2026-05-20 重构）

采用经典三层架构 bridge/service/data：

```
io.github.jukomu/
├── MainActivity.java              WebView拦截 + 权限回调
├── bridge/                         Capacitor 入口层
│   └── JmcomicPlugin.java         (~670行) 薄门面：27个@PluginMethod + ServiceListener事件转发
├── service/                        业务逻辑层（零Capacitor依赖）
│   ├── ApiService.java            搜索/详情/评论/收藏 API + 数据转换
│   ├── DownloadService.java       下载编排 + 进度推送 + 内部状态(taskIdMap等)
│   ├── DownloadObserver.java      库 TaskObserver 适配（提取的内部类）
│   ├── PreloadService.java        图片预加载 + 缓存管理
│   ├── SettingsService.java       设置读写 + 公开/私有搬迁
│   ├── PermissionService.java     权限 API 版本适配（24~30+）
│   ├── ServiceListener.java       统一事件回调接口（download/image/relocation）
│   ├── ApiCallback.java           异步API回调
│   ├── DownloadProgressData.java  下载进度 POJO
│   └── PermissionState.java       权限状态 POJO
└── data/                           持久化层
    ├── ImageCache.java            LRU图片缓存 + WebView虚拟URL（原ImageRegistry）
    ├── DownloadStore.java         SQLite下载表（原DownloadDatabase）
    ├── FileStore.java             文件系统（原FileStorage）
    └── SettingsStore.java         SQLite设置表（原SettingsDatabase）
```

### 关键设计点
- Service 层通过 `ServiceListener` 接口向上推送事件，Bridge 实现并转发 `notifyListeners()`
- Service 返回 `org.json.JSONObject`，Bridge 用 `JSObject.fromJSONObject()` 包装
- 共享资源（JmApiClient、线程池）由 Bridge 在 `load()` 中创建，构造器注入各 Service
- 同一个包(`io.github.jukomu.service`)内利用 package-private 控制访问权限

## 模块完成度

### 已完成模块（功能完整）

1. **阅读器模块 (ReaderPage)**
   - 双模式：纵向滚动 + 横向翻页
   - 滑动窗口预加载（N=15 普通，N=50 快速划动）
   - 加权区域评分当前页检测
   - 离线阅读（source=download 模式）
   - 工具栏自动隐藏 + 进度条拖动实时跟踪（150ms 节流加载）
   - 纵向模式顶部越界防护（`overscroll-behavior: none`）
   - 底部 "——E N D——" 结束标识 + 半屏留白
   - 预览页点击定位
   - 关联组件：ReaderTopToolbar, ReaderBottomToolbar, VerticalScrollView, HorizontalPageView

2. **详情页模块 (AlbumDetailPage)**
   - 四Tab：本子信息/章节/预览/评论
   - 骨架屏加载动画
   - 点赞/收藏/下载操作
   - 子组件：AlbumHeader, AlbumInfoTab, AlbumChaptersTab, AlbumPreviewTab, AlbumCommentsTab

3. **下载模块 (DownloadPage)**
   - 基于库 DownloadManager 的任务管理
   - SQLite 持久化 + FileStore 文件管理
   - 实时进度推送 + 速度显示
   - 取消/暂停/恢复/重试/删除/离线阅读
   - 部分失败增量重试

4. **收藏夹模块 (FavoritePage)**
   - 在线 + 离线双模式
   - 右侧侧边栏文件夹选择
   - 双向分页 + 搜索
   - Keep-alive 状态保持
   - 手势互斥（左右侧边栏）
   - 关联：FavoriteSideMenu, FavoriteSearchBar

5. **搜索/分类模块 (SearchPage, CategoryPage)**
   - 关键词搜索 + 分类浏览
   - 筛选器（排序/时间/标签/分类）
   - 双向无限分页
   - 列表/网格切换
   - Keep-alive 缓存 + 滚动位置恢复
   - 关联：KeywordSearchBar, SearchHeaderBar, CategorySearchToolbar, SearchResultContainer, QuickActionFab

6. **导航/菜单模块**
   - 左侧 IonMenu (MainMenu)
   - 自定义页面过渡动画（前进/后退滑动）
   - 手势：左侧右划打开，右侧左划打开收藏菜单

7. **设置页 (SettingPage)**
   - 缓存容量设置
   - 阅读预加载页数/并发数
   - 下载并发数/公开下载（含目录搬迁）
   - 版本号

8. **用户认证模块**
   - LoginPage（用户名+密码登录）
   - UserPage（头像/用户名/等级/经验数值+进度条/签名/收藏容量/金币 + 个人资料卡片 + 登出）
   - useAuth 模块级单例（initAuth/login/logout/isLoggedIn）
   - MainMenu 用户头像区（默认人像/头像+用户名，点击→UserPage）
   - SettingPage 用户分组入口
   - Android 侧 4 个 @PluginMethod：login/logout/checkLoginState/getUserProfile
   - Cookie 持久化：AVS cookie → SettingsStore SQLite（auth_cookies_json/auth_username/auth_user_info_json）
   - 重启恢复：load() restoreAuthState() + checkLoginState()
   - Cookie 过期过滤

9. **网络状态模块 (NetworkStatusPage)**
   - 域名连通性列表（圆点指示 + 可达/不可达状态）
   - 事件日志（网络变化/探活/结果事件时间线）
   - networkProbeStore 模块级事件 store（启动时拉取已有状态 + 持续监听事件）
   - 手动刷新域名探活（reprobeDomains）
   - 手动域名延迟测速（OkHttp HEAD 并行计时，绿色ms/黄色等待/红色超时）
   - Android 侧 3 个 @PluginMethod：getDomainStates / reprobeDomains / measureLatency

### 待完成模块

1. **首页 (HomePage)** — 仅有关键词搜索框，缺少真正首页内容（推荐/排行/最新）

### 功能缺口

| 缺口 | 位置 | 说明 |
|------|------|------|
| 首页无内容 | HomePage.vue | 仅搜索框，无推荐/排行内容 |
| 批量模式无功能 | KeywordSearchBar.vue | 批量模式切换和上传按钮无 handler |
| 评论发表 | 无 | 只能查看评论，不能发表/回复 |
| 本子级下载 | DownloadService.java | 仅支持章节级下载，不支持整个本子 |
| 离线收藏夹管理 | FavoritePage.vue | 无创建/删除文件夹 UI |

## 模块依赖关系图

```
App.vue
├── MainMenu (导航)
├── HomePage → KeywordSearchBar
├── SearchPage → SearchHeaderBar + SearchResultContainer + QuickActionFab
├── CategoryPage → CategorySearchToolbar + SearchResultContainer + QuickActionFab
├── FavoritePage → FavoriteSideMenu + FavoriteSearchBar + SearchResultContainer
├── AlbumDetailPage → AlbumHeader/InfoTab/ChaptersTab/PreviewTab/CommentsTab
│   └── → PreviewAllPage → ReaderPage
├── DownloadPage → DownloadTaskCard
├── SettingPage
├── LoginPage
├── UserPage
├── NetworkStatusPage → networkProbeStore
└── ReaderPage ← VerticalScrollView/HorizontalPageView/ReaderTopToolbar/BottomToolbar

Services (TypeScript):
├── JmcomicService.ts ← Capacitor Plugin Bridge → JmcomicPlugin.java
├── JmcomicTypes.ts (所有类型定义)
├── OfflineDownloadService.ts (localStorage 缓存)
└── OfflineFavoriteService.ts (localStorage 离线收藏夹)

Composables:
├── sideMenuState.ts (左右菜单互斥状态)
├── useAuth.ts (用户登录态模块级单例)
└── networkProbeStore.ts (网络探活事件 store + 初始状态拉取)

Android (bridge/service/data):
bridge/JmcomicPlugin.java
  ├── ApiService        → sharedClient, apiExecutor, timeoutExecutor
  ├── DownloadService   → DownloadStore, FileStore, sharedClient, imageExecutor, ServiceListener
  │   └── DownloadObserver → DownloadStore, FileStore, ServiceListener
  ├── PreloadService    → ImageCache, FileStore, SettingsStore, sharedClient, imageExecutor, ServiceListener
  ├── SettingsService   → SettingsStore, DownloadStore, FileStore, PermissionService, ServiceListener
  └── PermissionService → Context
```

## 变更记录（2026-05-10 以后）

| 日期 | 内容 |
|------|------|
| 2026-05-20 | **Android 侧架构重构**：JmcomicPlugin.java 1552→660行，拆分为 bridge/service/data 三层9服务+4数据类 |
| 2026-05-21 | 架构审查：修复1个回归（clearPhotoCache意外暴露），移除18个未使用 import，改进 handleOnDestroy 优雅关闭、ApiService 异常传播、SettingsService 引用常量 |
| 2026-05-21 | **阅读器修复**：顶部越界防护(overscroll-behavior) + 底部 END 指示器 + 进度条拖动实时跟踪(ion-input + 150ms节流) |
| 2026-05-21 | **用户登录态功能**：LoginPage + UserPage + useAuth + 3 个 @PluginMethod + Cookie 序列化/持久化/过期过滤/恢复 + MainMenu 用户头像区 + SettingPage 用户入口 + FavoritePage 登录态检查 |
| 2026-05-22 | **用户页信息增强**：toUserInfoObject 补齐 7 字段（emailVerified/message/nextLevelExp/currentExp/maxAlbumFavorites 等）+ 新增 getUserProfile @PluginMethod + UserPage 重构（经验数值/收藏容量/签名/个人资料卡片）|
| 2026-05-22 | **网络状态页 + 域名延迟测速**：NetworkStatusPage + networkProbeStore 事件 store + getDomainStates/reprobeDomains/measureLatency 三个 @PluginMethod + 域名连通性列表 + 事件日志 + 手动测速(OkHttp HEAD 并行计时) + 冷启动空内容修复 |
