# 阅读页设计方案

## 1. 概述

在现有详情页、预览页基础上，新增阅读页。用户从详情页"开始阅读"按钮或预览页点击图片进入，支持纵向滚动和横向翻页两种阅读模式。

## 2. 方案选择

采用**方案A：单页双模式**——单个 `ReaderPage.vue` 作为主容器，内部 `v-if` 切换 `VerticalScrollView` 和 `HorizontalPageView` 两个子组件。共享同一个图片窗口缓存和当前页码状态，模式切换瞬间完成。

## 3. 路由设计

### 3.1 路由定义

- **路径**：`/album/:albumId/read/:chapterId`
- **query 参数**：
  - `page`（可选，number）：初始定位的图片序号（1-based），不传则从第 1 页开始
  - `title`（可选，string）：章节标题，用于顶部栏显示

### 3.2 入口

| 入口 | 行为 |
|------|------|
| 详情页 `AlbumDetailPage` "开始阅读"按钮 | `router.push({ path: /album/${albumId}/read/${selectedChapterId}, query: { title } })`，从第 1 页开始 |
| 预览 Tab `AlbumPreviewTab` 点击图片 | 带 `page` 参数定位到该图片 |
| 全量预览 `PreviewAllPage` 点击图片 | 带 `page` 参数定位到该图片 |

## 4. 页面布局

```
┌─────────────────────────────┐
│  顶部工具栏（可收起）         │  ← 返回按钮 + 章节标题
│  半透明暗色背景覆盖          │
├─────────────────────────────┤
│                             │
│      图片展示区域            │  ← 纵向滚动 / 横向翻页
│      （黑色背景 #000）       │
│                             │
├─────────────────────────────┤
│  底部工具栏（可收起）         │  ← 页码指示器 + 可拖动进度条 + 模式切换按钮
│  半透明暗色背景覆盖          │
└─────────────────────────────┘
```

### 4.1 工具栏行为

- 默认显示，3 秒无操作后自动隐藏（沉浸阅读）
- 点击屏幕中央 1/3 区域（左右 30%~60%）呼出/收起两个工具栏
- 工具栏覆盖在图片之上，不占用图片布局空间

### 4.2 顶部工具栏（ReaderTopToolbar）

- 左侧：返回按钮（`arrow-back` 图标）
- 中间：章节标题
- 背景：半透明暗色（`rgba(0,0,0,0.6)`）

### 4.3 底部工具栏（ReaderBottomToolbar）

- 左侧：模式切换按钮（列表图标 ↔ 单页图标）
- 中间：页码指示器（如 "12 / 45"）
- 右侧：可拖动进度条（`ion-range`，拖动跳转到指定页）

## 5. 两种视图模式

### 5.1 纵向滚动模式（VerticalScrollView）

- 图片 `width: 100%` 撑满屏幕，`height: auto` 保持原始比例
- 图片间无间隙，连续排列
- 上下滑动浏览
- 通过滚动偏移计算当前图片（`scrollTop / estimateImageHeight`），上报给父组件更新进度条

### 5.2 横向翻页模式（HorizontalPageView）

- 单张图片适配屏幕，完整显示不裁剪（`object-fit: contain`）
- 使用 swiper/手势组件实现左右划动切换，带动画过渡
- 点击左侧 1/3 区域：上一页
- 点击右侧 1/3 区域：下一页
- 点击中间 1/3 区域（30%~60%）：呼出/收起工具栏

## 6. 滑动窗口动态预加载策略

### 6.1 参数

- 预加载窗口 `N = 3`（当前页前后各 3 张）
- 最大缓存窗口 `M = 15`（超出此范围的图片释放内存）

### 6.2 逻辑

```
当前页 curr = 10, N = 3
加载窗口：[7, 8, 9, 10, 11, 12, 13]

用户滑到 curr = 11
加载窗口：[8, 9, 10, 11, 12, 13, 14]
→ 提交加载 14，卸载 7（释放 dataUrl）

用户滑到 curr = 12
加载窗口：[9, 10, 11, 12, 13, 14, 15]
→ 提交加载 15，卸载 8
```

**消抖**：300ms。用户快速连续滑动时不解码，稳定后才执行加载/卸载。防止快速划过多页时触发多余请求。

### 6.3 图片生命周期

- Native 解密后的 `dataUrl`（base64）缓存在 `Map<sortOrder, string>` 中
- 窗口外的图片调用 `URL.revokeObjectURL()` 释放内存
- 用户翻回已卸载的图片时重新请求解密

### 6.4 加载流程

```
进入阅读页
  ↓
获取 PhotoDetail（images[]，含所有图片元信息）
  ↓
确定初始 currentIndex（route.query.page - 1）
  ↓
计算初始加载窗口：[curr-N, curr+N] 与 [0, total-1] 取交集
  ↓
批量调用 decryptImageUrls 解码窗口内图片
  ↓
渲染图片，注册 PreviewListener 监听逐张完成事件
  ↓
用户滑动 → 消抖 300ms → 更新 curr → 重算窗口 → 加载新进入的、卸载窗口外的
```

## 7. 章节终点行为

到达章节最后一页时就此停住，不自动进入下一章。后续可扩展跨章节阅读功能。

## 8. 组件结构

```
src/
├── views/
│   └── ReaderPage.vue                ← 新增：阅读页主容器
├── components/reader/
│   ├── VerticalScrollView.vue        ← 新增：纵向滚动视图
│   ├── HorizontalPageView.vue        ← 新增：横向翻页视图
│   ├── ReaderTopToolbar.vue          ← 新增：顶部工具栏
│   └── ReaderBottomToolbar.vue       ← 新增：底部工具栏
```

### 8.1 组件职责

| 组件 | 职责 |
|------|------|
| `ReaderPage` | 主容器。管理图片加载/缓存（窗口策略 + 消抖）、当前页状态、工具栏显隐/自动隐藏、模式切换（纵向/横向）、路由 query 解析 |
| `VerticalScrollView` | 接收 `images[]`（Map），纵向排列渲染 `<img>`，通过滚动位置推算当前页并 emit `update:currentIndex` |
| `HorizontalPageView` | 接收 `images[]`（Map），单页展示，swipe 翻页 + 点击区域翻页，emit `update:currentIndex` |
| `ReaderTopToolbar` | 返回按钮 + 章节标题，emit `back` |
| `ReaderBottomToolbar` | 页码文字（current/total）+ 可拖动进度条（`ion-range`）+ 模式切换按钮（列表/单页图标），emit `update:page` 和 `toggle-mode` |

## 9. 数据流

```
AlbumDetailPage / PreviewAllPage 点击
  ↓
router.push({ path: '/album/:albumId/read/:chapterId', query: { page?, title } })
  ↓
ReaderPage 挂载
  ├─ 解析 route.params (albumId, chapterId) + route.query (page, title)
  ├─ 调用 JmcomicService.getPhoto(chapterId) → PhotoDetail（images[] 元信息）
  ├─ 计算初始 currentIndex = (query.page - 1) || 0
  ├─ 计算初始加载窗口 → decryptImageUrls(windowImages)
  ├─ 注册 addPreviewListener 监听逐张解密完成
  ├─ 渲染 VerticalScrollView（默认模式）
  └─ 工具栏 3 秒倒计时启动
  ↓
用户滑动/翻页
  ↓
子组件 emit update:currentIndex
  ↓
ReaderPage 消抖 300ms → 重算窗口 → 加载新入窗口的图片 / 卸载离开窗口的图片
  ↓
底栏进度条 + 页码同步更新
```

## 10. 文件变更清单

| 操作 | 文件 | 说明 |
|------|------|------|
| 新增 | `src/views/ReaderPage.vue` | 阅读页主容器 |
| 新增 | `src/components/reader/VerticalScrollView.vue` | 纵向滚动视图 |
| 新增 | `src/components/reader/HorizontalPageView.vue` | 横向翻页视图 |
| 新增 | `src/components/reader/ReaderTopToolbar.vue` | 顶部工具栏 |
| 新增 | `src/components/reader/ReaderBottomToolbar.vue` | 底部工具栏 |
| 修改 | `src/router/index.ts` | 新增 `/album/:albumId/read/:chapterId` 路由 |
| 修改 | `src/views/AlbumDetailPage.vue` | "开始阅读"按钮改为跳转阅读页 |
| 修改 | `src/components/album/AlbumPreviewTab.vue` | 图片点击跳转阅读页（带 page 参数） |
| 修改 | `src/views/PreviewAllPage.vue` | 图片点击跳转阅读页（带 page 参数） |

## 11. 不在首版范围

- 自动进入下一章
- 缩略图滑动条
- 图片缩放手势（pinch zoom）
- 其他底部工具栏按钮

## 12. 设计确认

- [x] 方案 A：单页双模式（v-if 切换纵向/横向）
- [x] 路由 `/album/:albumId/read/:chapterId?page=&title=`
- [x] 黑色背景沉浸式阅读
- [x] 双工具栏（顶部返回 + 底部页码/进度条/模式切换），3 秒自动隐藏，点击中间 1/3 呼出/收起
- [x] 纵向滚动：图片 100% 宽撑满、保持比例、无间隙
- [x] 横向翻页：单图适配屏幕不裁剪，左右划动翻页，左/右 1/3 点击翻页
- [x] 滑动窗口动态预加载（前后各 3 张），消抖 300ms
- [x] 章节终点停住，不自动翻章
- [x] 详情页按钮 + 预览页点击图片双入口
