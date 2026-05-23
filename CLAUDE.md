# JQViewer

Capacitor hybrid Android 漫画阅读器。Vue 3 + Ionic (TypeScript) 前端，Java 原生插件后端。

## 架构

```
src/                         # Vue 3 + Ionic 前端 (WebView)
  ├── views/                 # 页面组件
  ├── components/            # 可复用组件
  ├── composables/           # 全局状态 (module-level ref 单例)
  │   ├── useAuth.ts         # 登录态
  │   └── sideMenuState.ts   # 侧边栏状态
  ├── services/
  │   ├── JmcomicService.ts  # Capacitor 桥接层 → 原生插件
  │   └── SettingsService.ts # 设置缓存
  └── router/

android/app/src/main/java/io/github/jukomu/
  ├── bridge/JmcomicPlugin.java   # Capacitor 插件 (主入口)
  ├── data/
  │   ├── SettingsStore.java      # SQLite 键值存储
  │   ├── CredentialStore.java    # AES-256 加密凭据存储
  │   ├── FileStore.java          # 文件管理
  │   └── ImageCache.java         # 图片缓存
  └── service/
      └── ApiService.java         # API 调用封装

JMComic-Api-Java/             # JMComic API 客户端库
```

## 数据存储

- **SettingsStore**: SQLite `jq_settings.db`，key-value 表。存设置项和登录态（cookie, username, userInfo）
- **CredentialStore**: `EncryptedSharedPreferences`，Android Keystore AES-256 加密。存用户名/密码用于重启自动登录

## 登录流

1. 首次登录 → cookie + userInfo 存 SettingsStore，凭据存 CredentialStore（加密）
2. 重启 → `restoreAuthState()` 清除旧 cookie → `initAuth()` → `checkLoginState()` 返回未登录 → `autoLogin()` 用加密凭据重新获取 cookie
3. 登出 → 清除全部

## 构建

```
cd android && ./gradlew :app:compileDebugJavaWithJavac
cd .. && npm run build          # 前端 Vite 构建
```
