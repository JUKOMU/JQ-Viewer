# JQViewer

Capacitor hybrid Android 漫画阅读器。Vue 3 + Ionic (TypeScript) 前端，Java 原生插件后端。

## 架构

```
src/                         # Vue 3 + Ionic 前端 (WebView)
  ├── views/                 # 页面组件
  │   └── HistoryPage.vue    # 浏览历史 + 解析历史
  ├── components/            # 可复用组件
  │   └── history/
  │       └── SearchHistoryDropdown.vue  # 搜索历史下拉
  ├── composables/           # 全局状态 (module-level ref 单例)
  │   ├── useAuth.ts         # 登录态
  │   └── sideMenuState.ts   # 侧边栏状态
  ├── services/
  │   ├── JmcomicService.ts  # Capacitor 桥接层 → 原生插件
  │   ├── JmcomicTypes.ts    # TypeScript 类型定义
  │   ├── HistoryService.ts  # 历史记录服务 (搜索/浏览/解析)
  │   └── SettingsService.ts # 设置缓存
  └── router/

android/app/src/main/java/io/github/jukomu/
  ├── bridge/JmcomicPlugin.java   # Capacitor 插件 (主入口)
  ├── data/
  │   ├── SettingsStore.java      # SQLite 键值存储
  │   ├── CredentialStore.java    # AES-256 加密凭据存储
  │   ├── HistoryStore.java       # 历史记录 SQLite (浏览+解析)
  │   ├── FileStore.java          # 文件管理
  │   └── ImageCache.java         # 图片缓存
  └── service/
      └── ApiService.java         # API 调用封装

JMComic-Api-Java/             # JMComic API 客户端库
```

## 数据存储

- **SettingsStore**: SQLite `jq_settings.db`，key-value 表。存设置项和登录态（cookie, username, userInfo）
- **CredentialStore**: `EncryptedSharedPreferences`，Android Keystore AES-256 加密。存用户名/密码用于重启自动登录
- **HistoryStore**: SQLite `jq_history.db`，两张表——`browse_history`（浏览历史，条件去重，无上限）、`parse_history`（解析历史，文本去重，无上限）。通过 Capacitor 桥接供前端调用

## 历史记录

- **搜索历史**: localStorage，每搜索框独立（keyword-search/search-page/category/favorite），500 条/上下文，去重按最新排序。下拉组件 `SearchHistoryDropdown.vue` 被 KeywordSearchBar、SearchHeaderBar、FavoriteSearchBar 复用
- **浏览历史**: 进入详情页/阅读页记录。条件去重——同 albumId+chapterId 只更新时间戳，否则新增。ReaderPage 通过 `getAlbum()` API 回退获取元数据确保不丢记录。展示在 HistoryPage 浏览 tab，按"今天/昨天/本周/更早"分组，卡片风格与收藏夹搜索结果一致（`#fffaf6` 暖色背景、强阴影、`height: 108px`、cover `object-fit: contain`），卡片信息依次为标题、ID、作者、相对时间、章节 badge。滚动到底自动加载（50 条/批）
- **解析历史**: 单个解析/批量解析模式搜索时记录文本。展示在 HistoryPage 解析 tab，暖色圆角卡片列表
- **入口**: 左侧侧边栏"历史"菜单项 → `/history`
- **页面布局**: `page-shell` 容器（左右 margin 14px，底部 86px），tab 栏为圆角浮动卡片（`#fffbf8` 背景 + 阴影，匹配搜索结果工具栏），TransitionGroup 列表动画

## 登录流

1. 首次登录 → cookie + userInfo 存 SettingsStore，凭据存 CredentialStore（加密）
2. 重启 → `restoreAuthState()` 清除旧 cookie → `initAuth()` → `checkLoginState()` 返回未登录 → `autoLogin()` 用加密凭据重新获取 cookie
3. 登出 → 清除全部

## 构建

```
cd android && ./gradlew :app:compileDebugJavaWithJavac
cd .. && npm run build          # 前端 Vite 构建
```
