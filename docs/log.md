# 项目变更日志

## 2026-05-01 — 本子详情页搭建

### 概述
完成本子详情页（AlbumDetailPage）的完整搭建，包括 Native 插件扩展、前端类型定义、路由配置和页面实现。

### 变更文件

**新增：**
- `src/views/AlbumDetailPage.vue` — 详情页主组件（A/B/C 三区域布局）
- `docs/album-detail-page-design.md` — 详情页设计方案

**修改：**
- `android/app/src/main/java/io/github/jukomu/plugin/JmcomicPlugin.java` — 新增 `getAlbum`、`getPhoto`、`getComments`、`toggleAlbumLike`、`toggleAlbumFavorite` 5 个方法及序列化辅助方法
- `src/services/JmcomicTypes.ts` — 新增 `AlbumDetail`、`PhotoMeta`、`PhotoDetail`、`ImageInfo`、`CommentItem`、`CommentList`、`ForumQuery`、`AlbumMeta`、`CategoryMeta` 类型
- `src/services/JmcomicService.ts` — 新增 `getAlbum`、`getPhoto`、`getComments`、`toggleAlbumLike`、`toggleAlbumFavorite` 方法
- `src/router/index.ts` — 新增 `/album/:id` 路由
- `src/views/SearchPage.vue` — 新增 `@item-click` → 跳转详情页
- `src/views/CategoryPage.vue` — 新增 `@item-click` → 跳转详情页

### 页面结构
- **区域 A**：封面头部（模糊背景 + 封面 + 标题/作者/页数 + 开始阅读按钮）
- **区域 B**：Tab 操作栏（本子信息 | 章节 | 预览 | 评论）
- **区域 C**：Tab 内容区（信息列表/章节网格/缩略图预览/评论列表）

### 注意事项
- 预览 Tab 采用懒加载策略，切换到该 Tab 时才加载原图
- 预览加载的原图缓存在内存中，后续阅读器可直接复用
- 阅读器、下载功能、评论发表/回复为首版暂不实现

### 2026-05-01 补充 — 预览图片解密

**问题：** 预览直接使用 `JmImage.url` 展示，图片是混淆过的，无法正常显示。

**修复：**
- `JmcomicPlugin.java`：
  - 新增 `decryptImageUrl` 方法：接收图片元信息 → 构建 `JmImage` → 调用 `fetchImageBytes`（下载+解密）→ 返回 base64 data URL
  - `toImageArray` 补上 `scrambleId` 字段
  - 新增 `import JmImageTool`、`import Base64`
- `JmcomicTypes.ts`：`ImageInfo` 新增 `scrambleId` 字段
- `JmcomicService.ts`：新增 `decryptImageUrl` 方法
- `AlbumDetailPage.vue`：预览改为逐张调用 `decryptImageUrl`，用解密后的 base64 显示

### 2026-05-01 修复 — 预览加载性能（批量解密）

**问题：** 预览逐张调用 `decryptImageUrl`，每次调用都创建一个新的 `JmApiClient`（初始化 HTTP client、域名管理等），一个章节几十张图就要几十次 client 创建/销毁，加载极慢。

**修复：**
- `JmcomicPlugin.java`：新增 `decryptImageUrls` 批量方法，接收 `ImageInfo[]` 数组，单个 client 实例内循环处理全部图片，返回 `{ sortOrder, dataUrl }[]`
- `JmcomicService.ts`：新增 `decryptImageUrls` 方法
- `AlbumDetailPage.vue`：`loadPreview` 改为一次调用 `decryptImageUrls`，一次 client 创建处理整章图片

### 2026-05-01 修复 — Singleton Client 重构

**问题：** `JmcomicPlugin` 每个 `@PluginMethod` 方法都创建新的 `JmApiClient`，频繁初始化 HTTP client、域名管理等开销巨大。

**修复：**
- `JmcomicPlugin.java`：引入 `volatile + synchronized` 懒加载单例 `sharedClient`，所有方法共用同一个 client 实例
- 修复 `decryptImageUrls` 中 `JSArray.getJSObject()` 不存在的问题：改用 `getJSONObject()` + `JSObject.fromJSONObject()`（`JSArray` 继承 `org.json.JSONArray`）

### 2026-05-01 重构 — AlbumDetailPage 模块拆分

**原因：** 单文件过大，拆分为编排器 + 5 个子组件便于维护。

**新增组件：**
- `src/components/album/AlbumHeader.vue` — 封面头部（模糊背景 + 封面 + 标题/作者/页数 + 开始阅读按钮）
- `src/components/album/AlbumInfoTab.vue` — 本子信息 Tab（交互栏 + 信息列表 + 相关作品）
- `src/components/album/AlbumChaptersTab.vue` — 章节 Tab（章节网格 + 选中高亮）
- `src/components/album/AlbumPreviewTab.vue` — 预览 Tab（缩略图网格 + 页码标注）
- `src/components/album/AlbumCommentsTab.vue` — 评论 Tab（评论卡片 + 回复嵌套）

**修改：**
- `AlbumDetailPage.vue`：改写为编排器，持有所有状态，通过 props 传递数据给子组件，处理子组件事件

### 2026-05-01 修复 — 组件目录修正

**问题：** album 组件被放置在 `src/views/components/album/`，与项目其他组件（`src/components/common|search|menu/`）不一致。

**修复：** 移动到 `src/components/album/`，更新 `AlbumDetailPage.vue` 的 import 路径。

### 2026-05-01 重构 — 预览分批加载与骨架占位

**问题：** 预览一次加载全部图片，图片多时性能差；整页加载动画体验不佳。

**修改：**

**AlbumPreviewTab.vue 重构：**
- 全页加载动画改为槽位骨架占位（脉冲动画），图片加载完成逐个刷出
- 序号从绝对定位覆盖改为缩略图下方独立展示
- 最多显示 20 张，≤20 张显示"已显示所有图片"，>20 张显示"查看更多图片"按钮
- 新增 `totalCount` prop、`loadMore` emit

**AlbumDetailPage.vue 修改：**
- `loadPreview()` 改为只加载前 20 张（slice + decryptImageUrls）
- 新增 `previewImageTotal` 状态
- 新增 `navigateToFullPreview()` → 路由到全量预览页

**新增 PreviewAllPage.vue：**
- 路由 `/album/:albumId/preview/:chapterId`
- 独立 `getPhoto` 获取图片元信息，按 20 张一批无限滚动加载
- 距底部 200px 触发下一批加载，底部显示加载进度和"已显示所有图片"
- 同样采用槽位骨架占位 + 序号下方展示

**路由修改：**
- `router/index.ts` 新增 PreviewAllPage 懒加载路由

### 2026-05-01 重构 — 预览图片加载优化（并行+缓存+缩略图+流式推送）

**问题：**
1. 串行下载，20 张图逐张 HTTP 请求
2. 全部完成才返回前端，做不到逐张刷出
3. AlbumDetailPage→PreviewAllPage 重复下载 1-20 张
4. 原图 Base64 传输体积大（MB 级）

**Native 侧改动（JmcomicPlugin.java）：**
- `decryptImageUrls` 改为 `CompletableFuture` + 4 线程并行下载
- 新增 `ConcurrentHashMap<String,String>` 内存缓存，key=`photoId/sortOrder`，缓存命中直接推送，跨页面复用
- 新增 `createThumbnail()` 缩略图方法：Bitmap 缩放（宽≤300px 等比）→ JPEG compress(70%)，体积从 MB 降到几十 KB
- 新增 `pushImage()` 方法：每完成一张调用 `notifyListeners("previewImage", {photoId, sortOrder, dataUrl})` 流式推送到前端
- Promise resolve 时 results 按 sortOrder 升序排列

**前端改动：**
- `JmcomicService.ts`：新增 `addPreviewListener(photoId, handler)` 方法，按 photoId 过滤事件，避免跨章节串数据
- `AlbumDetailPage.vue`：`loadPreview` 改为先注册监听再调用 `decryptImageUrls`，每收到事件逐张 push 到 `previewImages`，通过 `slotMap` 按 sortOrder 定位槽位
- `PreviewAllPage.vue`：`onMounted` 注册监听（全局一个本章节监听），`loadNextBatch` 仅提交下载不处理结果，事件处理中 push 到 `loadedImages`，新增 `sortedImages` computed 按 sortOrder 排序渲染
- 线程池从 4 调整为 6（`Executors.newFixedThreadPool(6)`）

### 2026-05-01 修复 — 逐张显示与槽位渲染

**问题1：** `AlbumPreviewTab.vue` 中 `v-if="!loading && slotMap[i-1]"` 条件导致 loading 状态下图片不渲染，做不到逐张刷出。

**修复：** 改为 `v-if="slotMap[i-1]"`，只要槽位有数据就显示图片，不关心 loading 状态。

**问题2：** `PreviewAllPage.vue` 使用 `sortedImages` 按 sortOrder 排序后渲染列表，导致图片到达顺序随机时画面跳动。

**修复：** 改为槽位渲染模式——预先创建 `slots` 数组（长度=totalCount），监听事件中 `slots[sortOrder-1] = img`，模板按索引渲染，每张图片到达时只填充其固定位置。

### 2026-05-01 修复 — 事件丢失与加载动画

**问题1：事件丢失**
`expandBatch` 中先提交下载再延迟 0.3s 后扩展槽位。缓存命中的图片事件在槽位扩展前到达，`slots.length=0`，`idx < slots.value.length` 检查失败导致事件被丢弃。

**修复：** `expandBatch` 中先延迟 0.4s → 扩槽位 → 再提交下载。确保槽位已就绪后再触发下载。

**问题2：加载动画不可见（两个根因）**
- 根因 A：`onMounted` 中 `loading = false` 在 `expandBatch` 完成后才设置，此时模板还处于 `v-if="loading"` 分支（无 footer），spinner 未渲染。
- 根因 B：滚动触发的 `expandBatch` 中延迟在扩槽之后执行，spinner 闪烁时间过短。

**修复：**
- `onMounted`：先扩槽 + `loading = false` → `loadingMore = true`（spinner 可见）→ 提交下载 → 延迟 0.3s → `loadingMore = false`
- `expandBatch`：`loadingMore = true` → 延迟 0.4s（保证 spinner 可见）→ 扩槽 → 提交下载 → `loadingMore = false`

**最终 onMounted 执行顺序：**
1. `getPhoto` 获取元信息
2. 注册 `addPreviewListener`
3. 展开首批槽位 → `loading = false`（从骨架切换到槽位网格）
4. `loadingMore = true`（底部加载动画可见）
5. 提交首批下载
6. 延迟 0.3s → `loadingMore = false`
7. `maybeLoadMoreAfterRender`（首屏不满一屏时自动加载下一批）

**最终 expandBatch 执行顺序：**
1. `loadingMore = true`（底部加载动画可见）
2. 延迟 0.4s
3. 扩展槽位（displayCount + 20）
4. 提交下载
5. `loadingMore = false`

---

### 2026-05-01 — 阅读页搭建

**概述：**
完成阅读页（ReaderPage）搭建，支持纵向滚动和横向翻页双模式，采用滑动窗口动态预加载策略。

**设计方案：** `docs/2026-05-01-reader-page-design.md`

**新增文件：**
- `src/views/ReaderPage.vue` — 阅读页主容器（路由解析、图片缓存管理、窗口预加载、工具栏显隐/自动隐藏、模式切换）
- `src/components/reader/VerticalScrollView.vue` — 纵向滚动视图（图片100%宽撑满、无间隙、滚动偏移计算当前页）
- `src/components/reader/HorizontalPageView.vue` — 横向翻页视图（单图 contain 适配、左右划动/点击翻页、Vue Transition 滑动动画）
- `src/components/reader/ReaderTopToolbar.vue` — 顶部工具栏（返回按钮 + 章节标题、半透明暗色背景）
- `src/components/reader/ReaderBottomToolbar.vue` — 底部工具栏（模式切换按钮 + 页码指示器 + ion-range 可拖动进度条）

**修改文件：**
- `src/router/index.ts` — 新增 `/album/:albumId/read/:chapterId` 懒加载路由
- `src/views/AlbumDetailPage.vue` — "开始阅读"按钮改为跳转阅读页；新增 `onOpenReader` 处理预览 Tab 图片点击跳转
- `src/components/album/AlbumPreviewTab.vue` — 图片新增点击事件 `openReader`，携带页码参数
- `src/views/PreviewAllPage.vue` — 图片新增点击跳转阅读页，携带页码参数

**关键特性：**
- 双模式：纵向滚动（全宽无间隙）↔ 横向翻页（单图适配屏幕），底部工具栏一键切换
- 双工具栏：顶部（返回+标题）+ 底部（模式切换+页码+进度条），默认显示 3 秒后自动隐藏，点击屏幕中间 1/3 呼出/收起
- 滑动窗口预加载（前后各 N=3 张），消抖 300ms，窗口外图片自动释放 blob URL
- 入口：详情页"开始阅读"（第1页）、预览 Tab 点击图片（定位到该页）、全量预览页点击图片（定位到该页）
- Vue Transition 滑动动画（横向翻页时根据方向判断左滑/右滑）
- 章节终点停住，不自动翻章

---

### 2026-05-01 修复 — 阅读页显示缩略图问题（缓存与方法拆分）

**问题：** 阅读页进入后显示的是缩略图而非原图。`decryptImageUrls` 始终调用 `createThumbnail()` 生成缩略图，且缩略图缓存与原图混用同一个 Map。预览先加载后，阅读页从缓存直接拿到的就是缩略图。

**修复方案：**

**Java 侧（JmcomicPlugin.java）：**
- `imageCache` 拆分为 `fullImageCache` + `thumbnailCache` 两个独立 Map
- `decryptImageUrl` → 重命名为 `decryptThumbnailUrl`（单张缩略图）
- `decryptImageUrls`（旧） → 重命名为 `decryptThumbnailUrls`：下载原图后同时缓存原图 + 生成缩略图并缓存缩略图，返回缩略图
- 新增 `decryptImageUrls`（新）：下载原图后缓存原图，返回原图，不生成缩略图

**前端侧：**
- `JmcomicService.ts`：接口声明与 service 对象同步重命名 + 新增
- `AlbumDetailPage.vue` / `PreviewAllPage.vue`：`decryptImageUrls` → `decryptThumbnailUrls`
- `ReaderPage.vue`：调用新的 `decryptImageUrls`（返回原图）

### 2026-05-01 修复 — 缩略图生成时复用原图缓存

**问题：** `decryptThumbnailUrls` 在缩略图缓存 miss 后直接下载原图，未检查 `fullImageCache`。若阅读页先缓存了原图，预览再请求缩略图会重复下载。

**修复：** `decryptThumbnailUrls` 方法中，缩略图缓存 miss 后增加 `fullImageCache` 检查：
- 命中 → 从 base64 解码原图 → 生成缩略图 → 缓存 thumbnailCache → 返回（跳过网络请求）
- 未命中 → 下载原图 → 缓存 fullImageCache → 生成缩略图 → 缓存 thumbnailCache

### 2026-05-01 清理 — 删除未使用的 decryptThumbnailUrl 方法

删除各处未被调用的单张缩略图解密方法：
- `JmcomicPlugin.java`：删除 `decryptThumbnailUrl` 方法及 `@PluginMethod` 注解
- `JmcomicService.ts`：删除接口声明 `decryptThumbnailUrl` 和 service 对象中的对应方法

### 2026-05-01 修复 — 横向翻页视图重写：直接拖拽+吸附

**问题：** 横向翻页最初使用 Vue `<Transition>` 组件实现翻页动画，效果是淡入淡出+滑入。用户体验不符合预期。

**演进过程：**
1. 移除淡入淡出透明度变化 → 仅保留滑动位移
2. 移除所有过渡动画（用户要求点击翻页无效果）→ 点击直接切图
3. 恢复划动翻页的滑动效果，点击翻页保持直接切换（`transitionName` 动态控制：划动设为 `slide-left/right`，点击设为空串不触发过渡）
4. **最终方案：整排拖拽+吸附** — 将图片横向并排摆放，手指拖拽时整排跟随移动，松手后吸附到目标页:
   - 所有图片在 strip 中按 `position: absolute; left: idx * 100vw` 绝对定位，strip 宽 = `totalCount * 100vw`
   - `translate3d(-displayIndex * w + offsetX, 0, 0)` 控制显示位置
   - 拖拽中：`offsetX` 随手指实时变化，无 CSS transition
   - 松手：吸附到目标页，开启 transition 平滑过渡，动画结束后静默重置 `displayIndex`/`offsetX`
   - 仅渲染 3 个 DOM 节点（`visibleIndices`：前一页/当前页/下一页）
   - 基于全量 strip 虚拟坐标系，动画后重置不跳变

### 2026-05-01 修复 — 阅读页参数调整

- 消抖时间从 300ms 调整为 50ms，翻页时窗口更新更迅速

---

### 2026-05-01 — 阅读页性能优化（预加载与卡顿修复）

**概述：**
修复阅读页频繁卡顿和预加载失效问题。

**设计方案：** `docs/2026-05-01-reader-performance-fix-design.md`

**根因：**
- debounce 模式在快速滚动时 timer 被反复清除，预加载窗口永不更新（P0）
- currentIndex 更新链路延迟过大：80ms setTimeout + 50ms debounce（P0）
- 全量 DOM 渲染（200 页 → 200 骨架节点）导致滚动卡顿（P1）
- 每张图片到达时全量替换 imageMap，触发所有 computed 重算（P1）
- 预加载窗口过小 N=5（P2）

**修改文件：**

`src/views/ReaderPage.vue`：
- 移除 debounce 机制，改为距离阈值触发（竖向≥3页/横向≥1页时更新窗口）
- `imageMap` 从 `ref` 改为 `shallowRef`，图片监听改用 `requestAnimationFrame` + `triggerRef` 按帧批处理响应式更新
- N: 5→15, M: 30→60
- 删除 `DEBOUNCE_MS` 常量和 `debounceTimer` 变量，新增 `lastWindowCenter`、`frameScheduled`

### 2026-05-01 紧急修复 — 图片始终显示骨架的严重 bug

**问题：** 进入阅读页一直显示骨架/加载中，切换显示模式后暂时正常，但翻到预加载边界又卡死。

**根因：** 性能优化时将 `imageMap` 从 `ref` 改为 `shallowRef`。图片事件到达时原地修改 Map（`imageMap.value.set()`），通过 `triggerRef(imageMap)` 触发父组件重渲染。但子组件（`VerticalScrollView` / `HorizontalPageView`）通过 prop 接收的是同一个 Map 引用——Vue diff 认为 prop 未变，子组件不更新。切换模式时 v-if 销毁重建子组件，偶然绕过问题。

**修复：**
- `imageMap` 从 `shallowRef` 改回 `ref`
- 监听中使用 `pendingImages` Map 收集本帧到达的图片
- `requestAnimationFrame` 回调中将 `imageMap.value` + `pendingImages` 合并为**新 Map** 后赋值（引用变更）
- 子组件 prop 检测到引用变化，正常响应式更新

`src/components/reader/VerticalScrollView.vue`：
- `setTimeout(80)` → `requestAnimationFrame`（~16ms 响应 vs 80ms）
- 新增 `onUnmounted` 清理 `cancelAnimationFrame(rafId)`
- `.image-wrapper` 新增 CSS `content-visibility: auto; contain-intrinsic-size: auto 320px`，屏幕外元素跳过渲染
- 补充 `onUnmounted` import

---

### 2026-05-01 — 阅读页两个 Bug 修复

**问题1：纵向滚动到最后一张图片后仍能继续滚动**

**根因：** `VerticalScrollView.vue` 底部 `.scroll-spacer` 高度等于整个容器高度，导致最后一张图片下方有大量空白可继续滚动。

**修复：**
- 删除 `bottomSpacerHeight` computed
- 删除 `containerHeight` ref
- 删除模板中 `.scroll-spacer` div
- 清理未使用的 `.scroll-spacer` CSS

**问题2：从预览点击图片进入阅读页没有从被点击图片位置显示，而是从第一张显示**

**根因：** `VerticalScrollView.onMounted` 中立即执行 `scrollTop = targetTop` 跳转，但此时所有图片都还是骨架（未加载），`imageTops` 均为 0，`targetTop = 0` 导致滚动到顶部。后续 `watch(imageMap.size)` 仅重算位置，不重新滚动。

**修复：**
- `onMounted` 仅保留 `recalcImagePositions()`，移除滚动逻辑
- 新增 `hasInitialScrolled` 标记
- 在 `watch(imageMap.size)` 回调中：重算位置后检查目标图片（`initialIndex + 1`）是否已在 imageMap 中。若已加载且未执行过初始滚动，计算 `targetTop` 并执行 `scrollTop` 赋值完成定位
- 初始加载窗口（calcWindow）以 `currentIndex` 为中心，目标图片必在首批加载范围内，正常情况下始终能覆盖

**修改文件：**
- `src/components/reader/VerticalScrollView.vue` — 以上所有改动

**设计方案：** `docs/2026-05-01-reader-bugfix.md`

---

### 2026-05-01 — 阅读页四个 Bug 修复（第二轮）

**问题1：预览点击进入阅读页定位不准**
- 根因：`watch(imageMap.size)` 触发时 img 标签已插入 DOM，但图片字节尚未解码完成，`offsetTop` 基于骨架占位高度（aspect-ratio: 3/4），真实图片加载后高度变化导致偏移。
- 修复：新增 `waitForImage(sortOrder)` 函数，监听目标 img 的 `load`/`error` 事件，图片解码完成后再计算位置并滚动。

**问题2：纵向滚动进度条显示 48/49 而非 49/49**
- 根因：`findCurrentIndex` 中 `tops[i] <= st + 4`，最后一张图片 `offsetTop` 可能大于 `scrollTop + 4`（`maxScrollTop = scrollHeight - clientHeight`，可能小于最后一张图顶部），导致永远无法命中最后一页。
- 修复：`findCurrentIndex` 增加底部检测——若 `scrollHeight - scrollTop - clientHeight < 5px`，直接返回最后一页索引。

**问题3：纵向滚动时进度条拖动无效**
- 根因：`onProgressDrag` 仅更新 `currentIndex` ref，未通知 VerticalScrollView 实际滚动。
- 修复：VerticalScrollView 暴露 `scrollToIndex(index)` 方法（`defineExpose`），ReaderPage 通过 `verticalViewRef` 调用。`scrollToIndex` 内部等待图片加载 + 重算位置 + 赋值 scrollTop。

**问题4：横向翻页切换到纵向滚动时图片位置回到第一张**
- 根因：切回纵向时 VerticalScrollView 重新挂载（v-if），imageMap 已有全部数据，`watch(imageMap.size)` 值不变不触发滚动；`hasInitialScrolled` 为 false 但无人执行初始滚动。
- 修复：`onMounted` 末尾调用 `scrollToInitial()`（与 watch 复用同一函数），检查 imageMap 是否已有目标图片，有则等待加载后滚动。

**修改文件：**
- `src/components/reader/VerticalScrollView.vue` — 新增 `waitForImage`、`scrollToIndex`（defineExpose）、`scrollToInitial`；`findCurrentIndex` 增加底部边界；`onMounted` 补检；watch 复用 `scrollToInitial`
- `src/views/ReaderPage.vue` — 新增 `verticalViewRef`，`onProgressDrag` 在纵向模式调用 `verticalViewRef.scrollToIndex()`

**设计方案：** `docs/2026-05-01-reader-bugfix.md`（已更新）

---

### 2026-05-01 — 纵向滚动当前页计算修复

**概述：**
修复纵向滚动模式下进度条显示的当前页码不准确的问题。

**设计方案：** `docs/2026-05-01-vertical-current-page-fix.md`

**根因：** `findCurrentIndex` 使用 `tops[i] <= st + 4` 判断当前页，+4 容差导致图片顶部刚进入视口就切换，用户看到 99% 还是上一张图却显示已翻页。且未考虑图片高度各不相同。

**修复：** 加权区域得分算法

- `imageTops: number[]` → `imagePositions: { top: number; height: number }[]`
- `findCurrentIndex` 重写：
  - 屏幕 25%-75% 为 A 区（权重 3），0-25% 和 75%-100% 为 B 区（权重 1）
  - `score = (可见高度在A区高度/图片可见高度) × 3 + (可见高度在B区高度/图片可见高度) × 1`
  - 最高分当选；平局时距屏幕中心最近者优先
  - 底部检测（距底 <5px→最后一页）保持不变
- `recalcImagePositions` 同时记录 `offsetTop` 和 `offsetHeight`
- `scrollToIndex` / `scrollToInitial` 同步适配 `.top` 字段访问
- `scrollToIndex` 功能暂移除（VerticalScrollView 不暴露），进度条拖动仅更新页码不跳转滚动位置

---

### 2026-05-01 — 纵向滚动初始定位修复

**概述：**
修复点击预览进入阅读页时显示上一张图片的 bug。

**根因：** `onMounted` 中 `imageTops[initialIndex].top` 基于骨架/min-height 高度，跳转后前面图片加载完毕高度变化，目标位置偏移。

**修复：** 不等图片加载，直接用当前位置跳转；`watch(imageMap.size)` 中检测目标图片位置变化（delta），`scrollTop += delta` 追着走。

**修改文件：**
- `src/components/reader/VerticalScrollView.vue` — 新增 `trackedIndex`/`trackedTop`，`onMounted` 直接跳转，`watch` 中动态修正
- `src/views/ReaderPage.vue` — 移除 `verticalViewRef` 和 `scrollToIndex` 调用（VerticalScrollView 不再暴露）

**修改文件：**
- `src/components/reader/VerticalScrollView.vue` — 以上所有改动

### 2026-05-01 — 纵向滚动初始定位修复（第二版）

**概述：**
上一版修复引入两个严重问题：
1. `onMounted` 时 `totalCount` 为 0，`imageTops` 为空，`trackedIndex` 保持 -1，预览进入停在第一张图
2. 编译报错后直接删除了 `scrollToIndex` 和 `verticalViewRef`，导致进度条拖拽不再跳滚动位置

**修复：**

`VerticalScrollView.vue`:
- `onMounted` 仅做 `nextTick → recalcImagePositions()`
- 新增 `watch(totalCount)`：totalCount 从 0 变为有值时执行初始滚动（`trackedIndex = initialIndex`，基于骨架位置跳转）
- 保留 `watch(imageMap.size)` 动态 delta 修正
- 新增 `scrollToIndex(index)` 方法 + `defineExpose`，供进度条拖拽调用

`ReaderPage.vue`:
- 恢复 `verticalViewRef` ref
- `onProgressDrag` 中对纵向模式调用 `verticalViewRef.value?.scrollToIndex(index)`

**修改文件：**
- `src/components/reader/VerticalScrollView.vue`
- `src/views/ReaderPage.vue`

### 2026-05-01 — 纵向滚动初始定位改用 scrollIntoView

**概述：**
上一版仍用 `imageTops[index]` 做初始定位，但目标页前的图片若未加载则骨架高度 ≠ 真实高度，累积误差随页码增大而增大（第20页偏移一整页）。

**修复：** `watch(totalCount)` 和 `scrollToIndex` 中改用 `scrollIntoView({ block: 'start', behavior: 'instant' })`，由浏览器计算真实滚动位置，不受骨架高度误差影响。定位后从 `containerRef.scrollTop` 回读实际位置赋给 `trackedTop`。

**修改文件：**
- `src/components/reader/VerticalScrollView.vue`

### 2026-05-01 — 补全所有缺失功能 & 加权评分算法

**概述：**
全面对照讨论历史审查代码，发现 5 项缺失，全部补全。

**修复项：**

1. **加权区域评分 `findCurrentIndex`**（用户设计算法）：
   - A 区（中点 25%~75% 屏幕高度）权重 3
   - B 区（中点 0~25% 或 75%~100%）权重 1
   - 不可见（中点不在屏幕内）权重 0
   - 得分最高者为当前页，平局取靠近顶部者
   - 替换旧的 `st + 4` 简单算法

2. **`content-visibility: auto` + `contain-intrinsic-size: auto 500px`**：
   - Phase 1 性能优化方案，之前未写入代码

3. **`onScroll` 用 `requestAnimationFrame` 替代 `setTimeout(80)`**：
   - 与 ReaderPage 一致的 rAF 节流模式

4. **`onUnmounted` 清理 rAF**：
   - 防止组件卸载后残留回调

**修改文件：**
- `src/components/reader/VerticalScrollView.vue` — 以上全部改动

---

### 2026-05-02 — WebView 资源拦截重构（内存泄漏与性能优化）

**概述：**
彻底解决 Android 侧 ConcurrentHashMap Base64 缓存无限增长导致的内存泄漏，以及 Capacitor Bridge 传输大体积 Base64 字符串导致的缓慢/卡顿问题。

**核心方案：** WebView 资源拦截 — Android 侧拦截 `http://jqviewer.local/{type}/{photoId}/{sortOrder}` 虚拟 URL，通过 `shouldInterceptRequest` 的 InputStream 直接向 WebView 渲染引擎提供原始字节，完全绕过 Capacitor Bridge。Vue 侧使用 `preloadImages` + `imageReady` 事件模式（与旧有监听模式一致），图片数据通过拦截器路径流入。

**设计方案：** `docs/2026-05-02-webview-interceptor-plan.md`

**新增文件：**
- `android/.../plugin/ImageRegistry.java` — 线程安全 LRU 单例图片注册表
  - `ReentrantReadWriteLock` + `LinkedHashMap(accessOrder=true)`
  - 字节容量制（默认 640MB），可配置（`setCacheCapacity`）
  - `handleRequest(url)` 解析虚拟 URL → 返回 `WebResourceResponse`（含 InputStream + MIME）
  - `clearByPrefix(photoId + "/")` 批量清理章节缓存
  - `getCapacityInfo()` 返回 `{capacityMb, usedMb}`
  - `isVirtualImageUrl(url)` 判断虚拟 URL

**修改文件：**

**Android 侧：**
- `android/.../MainActivity.java` — 注入自定义 `BridgeWebViewClient`，重写 `shouldInterceptRequest`：
  - `http://jqviewer.local/` → `ImageRegistry.handleRequest(url)`
  - 其他 URL → `super.shouldInterceptRequest(view, request)`
- `android/app/src/main/AndroidManifest.xml` — 添加 `android:usesCleartextTraffic="true"` 允许 HTTP 虚拟 URL
- `android/.../plugin/JmcomicPlugin.java` — 大规模重构：
  - **移除**：`fullImageCache`、`thumbnailCache` ConcurrentHashMap、Base64 编解码、`decryptThumbnailUrls()`、`decryptImageUrls()`、`pushImage()`
  - **新增**：`preloadImages(photoId, images, type)` — 检查 ImageRegistry 缓存 → 分类 cached/pending → 提交下载 → 每张完成后 `pushImageReady(photoId, sortOrder, type)` 通知前端
  - **新增**：`clearPhotoCache(photoId)` → `ImageRegistry.clearByPrefix()`
  - **新增**：`setCacheCapacity(mb)` / `getCacheCapacityInfo()` — 供设置页使用
  - **保留**：`createThumbnail()` 不变

**前端侧：**
- `src/services/JmcomicTypes.ts` — 新增 `PreloadResult { cached: number[], pending: number[] }`、`CacheCapacityInfo { capacityMb: number, usedMb: number }`
- `src/services/JmcomicService.ts` — 重写：
  - `getImageUrl(photoId, sortOrder, type)` → 返回虚拟 URL `http://jqviewer.local/{type}/{photoId}/{sortOrder}`
  - `preloadImages(photoId, images, type)` → 替代旧的 `decryptThumbnailUrls` / `decryptImageUrls`
  - `addImageReadyListener(photoId, handler)` → 替代旧的 `addPreviewListener`
  - 新增 `clearPhotoCache`、`setCacheCapacity`、`getCacheCapacityInfo`
- `src/views/ReaderPage.vue` — 改用 `preloadImages` + `imageReady` 事件 + `getImageUrl`，新增 `requestedSortOrders` 防重复提交，`onUnmounted` 调用 `clearPhotoCache`
- `src/views/AlbumDetailPage.vue` — 预览 Tab 改用 `addImageReadyListener` + `preloadImages(..., 'thumb')` + `getImageUrl`，移除 `addPreviewListener` 旧接口
- `src/views/PreviewAllPage.vue` — 同上模式：`addImageReadyListener` + `preloadImages(..., 'thumb')` + `getImageUrl`，逐批滚动加载保持不变
- 未修改：`VerticalScrollView.vue`、`HorizontalPageView.vue`、`AlbumPreviewTab.vue` — 仅消费 `dataUrl`/`imageMap` 字符串，兼容 URL 和 Base64

**前端构建验证：** `vue-tsc` + `vite build` 通过，无错误

**暂缓：** 设置页（SettingPage）由用户后续单独创建

---

### 2026-05-02 修复 — 多个问题修复

**1. Mixed Content 阻塞**
- 问题：Capacitor 页面运行在 `https://localhost`，虚拟图片 URL `http://jqviewer.local/...` 被 WebView 安全策略拦截
- 修复：`JmcomicService.ts` 中 `VIRTUAL_BASE` 改为 `https://jqviewer.local`
- Android 侧 `ImageRegistry.isVirtualImageUrl` 匹配域名，不受协议变化影响

**2. Vue 不再暴露 clearPhotoCache**
- `JmcomicPlugin.java`：`@PluginMethod` 注释掉（方法保留，后续可恢复）
- `JmcomicService.ts`：移除 `clearPhotoCache` 接口声明和方法
- `ReaderPage.vue`：移除 `onUnmounted` 中的 `clearPhotoCache` 调用
- 缓存完全由 ImageRegistry LRU 自动管理

**3. 缩略图下载时原图一并缓存**
- `JmcomicPlugin.java` `preloadImages` 中 `type='thumb'` 分支：`createThumbnail` 后同时 `put(photoId/sortOrder, decrypted, mimeType)`
- 预览后进入阅读页无需重复下载原图

**4. 缩略图未命中时检查原图缓存**
- `JmcomicPlugin.java` `preloadImages`：缩略图缓存 miss 后增加 `get(photoId/sortOrder)` 检查
- 原图已缓存则直接 `createThumbnail` 生成缩略图，跳过网络下载

**5. ReaderPage updateWindow 逻辑简化**
- 收集条件从 `!requestedSortOrders.has(so)` 改为 `loadedSortOrders.has(so) ? skip : toLoad`
- 窗口清理不再删除 `requestedSortOrders`
- `onMounted` 开头重置 `loadedSortOrders` 和 `requestedSortOrders`

**6. 阅读页快速划动动态窗口扩展**
- 问题：快速划动时图片加载跟不上，出现骨架
- 新增常量：`N_FAST=50`、`SPEED_THRESHOLD=10 页/秒`、`EXPAND_EXPIRE_MS=2000`
- `onPageChange` 中计算划动速度，超阈值时非对称扩展窗口
- 向前快速划：窗口 `[-15, 0, +50]`；向后快速划：窗口 `[-50, 0, +15]`
- 扩展方向有 pending 图片则跳过扩展（不白扩）
- 2 秒无快速划动自动回退到 `[-15, 0, +15]`
- 清理范围同步适配扩展方向
- 进度条拖拽、换章节时重置扩展状态

**修改文件：**
- `src/services/JmcomicService.ts` — VIRTUAL_BASE https、移除 clearPhotoCache
- `src/views/ReaderPage.vue` — 动态窗口扩展、逻辑简化、移除 clearPhotoCache
- `android/.../plugin/JmcomicPlugin.java` — 缩略图存原图、缩略图查原图缓存、@PluginMethod 注释

---

### 2026-05-02 — Album 详情页加载骨架屏

**概述：**
为 AlbumDetailPage 的页数区域、章节区域、预览区域添加与 AlbumInfoTab 风格一致的 shimmer 骨架屏动画，替换加载期间的空状态提示。

**修改文件：**

`src/components/album/AlbumHeader.vue`：
- 新增 `chapterLoading` prop
- 当 `loading || chapterLoading` 时，页数区域显示 shimmer 骨架条替代 `XX 页` 文字
- 骨架条：宽 40%、高 14px、圆角 6px，浅色 shimmer 动画（适配暗色背景）

`src/components/album/AlbumChaptersTab.vue`：
- 新增 `loading` prop
- `loading && 无数据` → 显示 6 张骨架卡片网格（shimmer 动画）
- `!loading && 无数据` → 显示"暂无章节"

`src/views/AlbumDetailPage.vue`：
- 新增 `chapterLoading` ref，`selectChapter` 中控制状态
- 向 AlbumHeader 传递 `:chapter-loading="chapterLoading"`
- 向 AlbumChaptersTab 传递 `:loading="loading"`
- 向 AlbumPreviewTab 传递 `:loading="loading || previewLoading"`（确保初始加载时也显示骨架而非"请先选择章节"）

**设计方案：** `docs/album-detail-loading-skeleton.md`

### 2026-05-02 修复 — 预览 Tab 在加载期间切换后显示"请先选择章节"

**问题：** 进入详情页加载中切到预览 tab，骨架屏正常显示，但加载完成后变为"请先选择章节"（需切走再切回才正常）。

**根因：** `switchTab('preview')` 触发 `loadPreview()`，此时 `selectedChapterId` 为空直接 return。`onMounted` 完成数据加载后无人重新触发预览加载。

**修复：** `src/views/AlbumDetailPage.vue` — `onMounted` 中设置 `selectedChapterId` 后，检测 `activeTab === 'preview'`，是则补调 `loadPreview()`。

### 2026-05-02 重构 — 预览 loading 状态解耦

**问题：** 预览 tab 的 `:loading="loading || previewLoading"` 混合了两个状态源，逻辑别扭。

**修改：**
- `AlbumDetailPage.vue`：
  - AlbumPreviewTab `:loading` 改为仅传 `previewLoading`（与父级 loading 解耦）
  - `loadPreview()` 中 `previewLoading = true` 提到 `chapterId` 判空之前，章节未就绪时也能展示 20 个骨架
  - `onMounted` 补调 `loadPreview()` 的修复保留（章节就绪后自动触发实际加载）

### 2026-05-02 — 评论 Tab 加载动画改为骨架屏

**概述：** AlbumCommentsTab 加载时将 `ion-spinner` 替换为与 InfoTab 风格一致的 shimmer 骨架屏。

**修改文件：**
- `src/components/album/AlbumCommentsTab.vue`：
  - 移除 `IonSpinner`，改为 4 张骨架评论卡片（圆形头像 + 用户名短条 + 内容长条）
  - 骨架条样式与 AlbumInfoTab/AlbumChaptersTab 一致（shimmer 1.4s 循环）

---

### 2026-05-02 — 阅读页初始化黑屏修复 & 骨架屏动画优化

**概述：**
修复进入阅读页时短暂黑屏（图片区全黑）+ 底部工具栏总页数显示 0 的问题，同时将骨架屏动画从几乎不可见的 opacity 脉冲改为光泽扫过（shimmer）效果。

**根因：**
- `totalCount` 初始值为 0，`imageMap` 初始为空 Map，`onMounted` 中 `getPhoto()` 和 `preloadImages()` 为异步操作，组件首次渲染时数据未就绪
- `VerticalScrollView` 的 `visibleSlots` 基于 `totalCount` 渲染，totalCount=0 时不渲染任何元素，图片区仅显示背景色 `#000`（全黑）
- `ReaderBottomToolbar` 显示 `current / total` = `1 / 0`
- 骨架屏动画 `opacity: 0.3↔0.6` + 背景 `#1a1a1a`，在黑色底色上对比度极低
- 进入阅读页有 3 个入口（AlbumDetailPage `startReading`、`onOpenReader`、PreviewAllPage `openReader`），均未传递总页数信息

**修复方案：** `docs/reader-init-fix.md`

**修改文件：**

`src/views/AlbumDetailPage.vue`：
- `onOpenReader` 和 `startReading` 路由跳转 query 增加 `total` 参数（`selectedChapterPageCount`）

`src/views/PreviewAllPage.vue`：
- `openReader` 路由跳转 query 增加 `total` 参数（`totalCount`）

`src/views/ReaderPage.vue`：
- 从 `route.query.total` 读取预估值初始化 `totalCount`（fallback 0）
- `toolbarVisible` 初始值改为 `initialTotal > 0`（有预估 total 时立即显示工具栏）
- `onMounted` 中数据加载完成后设置 `toolbarVisible = true`

`src/components/reader/VerticalScrollView.vue`：
- `.skeleton-image`：opacity 脉冲动画 → shimmer 光泽扫过动画

`src/components/reader/HorizontalPageView.vue`：
- `.skeleton-page`：opacity 脉冲动画 → shimmer 光泽扫过动画

**前端构建验证：** `vue-tsc` + `vite build` 通过，无错误

---

### 2026-05-02 — 阅读页初始化定位与加载速度修复

**概述：**
修复从预览页进入阅读页时，进度条显示正确页码但画面停留在第一页的定位错误，以及进入阅读页后长时间黑屏的加载速度问题。

**根因：**

**问题1（画面定位错误）：**
- `PreviewAllPage` 跳转时 `query.total` 传了实际总页数
- `ReaderPage` 用 `route.query.total` 初始化 `totalCount`
- 当 `query.total === 实际总页数` 时，`onMounted` 中 `totalCount.value = images.length` 值未变化
- `VerticalScrollView` 的 `watch(totalCount)` 不触发，不执行初始 `scrollIntoView`，视图停在顶部（第一页）
- `watch(imageMap.size)` 使用 `trackedIndex`（初始值 -1），也不会修正

**问题2（加载时间长）：**
- `onMounted` 中 `await setupImageReadyListener()` 和 `await preloadImages()` 阻塞后续代码
- `toolbarVisible = true` 在所有 await 完成后才执行
- 用户进入后看到全黑屏，直到 `getPhoto` + `preloadImages` 全部完成

**修复方案：** `docs/reader-init-fix-plan.md`

**修改文件：**

`src/views/ReaderPage.vue`：
- `currentIndex` 赋值移到 `totalCount` 赋值之前（防御性对齐）
- `onMounted` 从 `async/await` 改为 `.then()` 链式调用
- `setupImageReadyListener` 去掉 `await`，改为 fire-and-forget + `.catch(() => {})`
- 初始 `preloadImages` 去掉 `await`，改为 `.then()` 链式（图片通过 listener 渐进填充）
- `toolbarVisible = true` 和 `resetAutoHide()` 移到 `getPhoto` 成功后立即执行（尽快显示骨架屏 + 工具栏）
- `.then()` 内部末尾 `nextTick` + `verticalViewRef?.scrollToIndex(currentIndex)` — 覆盖 totalCount 未变化的情况
- **关键修正**：`nextTick` 必须在 `.then()` 内部（`getPhoto` 异步，外部 nextTick 触发时 currentIndex 仍为初始值 0）
- 新增 `nextTick` import

**前端构建验证：** `vue-tsc --noEmit` 通过，无错误

---

### 2026-05-03 — 修复首次进入阅读页跳页不准确

**问题：**
场景 B/C（携带 page 参数进入阅读页）首次进入时跳页不准确，偶尔往前或往后偏移 2~4 页。后续进入正常。

**根因：**
`VerticalScrollView` 中三个机制的竞态：
1. `scrollToIndex` 未设置 `lastEmitIndex`
2. `watch(imageMap.size)` 修正 `scrollTop` 时触发原生 `scroll` 事件
3. `onScroll` 在**部分图片加载**的布局上运行 `findCurrentIndex`（各骨架与真实图片高度不同），返回错误页码并 emit 到 ReaderPage

后续进入图片缓存命中、一次性加载完毕，布局无中间态，故不出错。

**修复：** `src/components/reader/VerticalScrollView.vue` 4 处修改：
1. 新增 `let isAdjustingScroll = false` 状态变量
2. `onScroll` 开头增加 `if (isAdjustingScroll) return` 早退
3. `scrollToIndex` 中在 `scrollIntoView` 之前设置 `lastEmitIndex = index`
4. `watch(imageMap.size)` 中 `scrollTop` 修正用 `isAdjustingScroll` 旗标包裹，并取消飞行中的 rAF

**设计方案：** `docs/fix-reader-page-jump.md`

**前端构建验证：** `vue-tsc` + `vite build` 通过，无错误

---

### 2026-05-04 — 搜索页后退状态保持 (keep-alive)

**概述：**
修复从搜索页/分类页进入详情页后，后退回到原页面时状态丢失、重新触发搜索的问题。滚动位置也一并修复。

**根因：**
`App.vue` 使用 `<ion-router-outlet>` 渲染页面，路由切换时旧页面组件被销毁。`SearchPage.vue` 的 `watch(currentQuery, ..., {immediate: true})` 在重建时触发 `resetWithPage` 重新搜索。

**方案：**
使用 Vue `<keep-alive>` 缓存 SearchPage 和 CategoryPage 组件，后退时组件不销毁，状态和滚动位置完整保留。

**设计方案：** `docs/keep-alive-plan.md`

**修改文件：**
- `src/router/index.ts` — SearchPage/CategoryPage 路由添加 `name` 和 `meta.keepAlive: true`
- `src/App.vue` — `<ion-router-outlet id="main-content">` 替换为 `<div id="main-content"><router-view v-slot>` + `<keep-alive :include="keepAliveNames">`；新增 `keepAliveNames` computed（从路由 meta 收集 keepAlive 页面名）；移除 `IonRouterOutlet` 导入
- `src/views/SearchPage.vue` — 新增 `defineOptions({ name: 'SearchPage' })`
- `src/views/CategoryPage.vue` — 新增 `defineOptions({ name: 'CategoryPage' })`

**编译验证：** `vite build` 通过，无错误

### 2026-05-04 修复 — keep-alive 造成 watch 离开/返回时重复触发搜索

**问题：**
keep-alive 缓存 SearchPage 后，`watch(currentQuery)` 在导航离开（URL 变为详情页，keyword 清空）和返回（URL 恢复 search?keyword=xxx）时各触发一次搜索。离开时触发的是无用请求，返回时重复搜索失去了 keep-alive 的意义。

**修复：** `src/views/SearchPage.vue`

- 新增 `lastSearchedQuery` ref + `queryEqual` 工具函数
- `watch` 回调增加两个防护：
  1. `route.name !== 'SearchPage'` 时跳过（正在离开搜索页）
  2. `queryEqual(query, lastSearchedQuery)` 时跳过（缓存仍有效，无需重新搜索）

**编译验证：** `vite build` 通过，无错误

### 2026-05-04 紧急修复 — 首页和分类页搜索组件不可见

**问题：**
`App.vue` 中 `<ion-router-outlet>` 替换为 `<div id="main-content">` 后，所有页面组件不可见。

**根因：**
`ion-router-outlet` 在 Ionic CSS 中有内置布局样式（`position: absolute; left:0; right:0; top:0; bottom:0; contain: layout size style; z-index:0`），使其填满 `ion-app` 视口。裸 `<div>` 无此样式，内容高度塌陷为 0。

**修复：** `src/App.vue`
- 给 `<div id="main-content">` 添加 `class="ion-page-container"`
- 新增非 scoped `<style>` 块，定义 `.ion-page-container` 布局样式（对齐 `ion-router-outlet` 的内置 CSS）

**编译验证：** `vite build` 通过，无错误

### 2026-05-04 修复 — keep-alive 未保留滚动位置

**问题：**
keep-alive 缓存组件后，从详情页返回搜索页/分类页时，滚动位置回到顶部。

**根因：**
`keep-alive` 缓存 Vue 组件但 DOM 被分离再附着，`ion-content` 内部滚动元素的 `scrollTop` 被浏览器重置。

**修复：** `src/views/SearchPage.vue`、`src/views/CategoryPage.vue`
- 新增 `onDeactivated`：保存 `scrollElementRef.scrollTop` 到 `savedScrollTop`
- 新增 `onActivated`：`nextTick` 后解析滚动元素并恢复 `scrollTop`

**编译验证：** `vite build` 通过，无错误

### 2026-05-04 修复 — 恢复页面过渡动画

**问题：** 替换为 `router-view` 后丢失 `ion-router-outlet` 的 iOS 风格 slide 过渡动画。

**修复：** `src/App.vue`
- `<keep-alive>` 外层包裹 `<Transition name="page-slide" mode="out-in">`
- 新增 enter/leave CSS（translateX + opacity，cubic-bezier 缓动模拟 Ionic 原生动画曲线）

**编译验证：** `vite build` 通过，无错误

### 2026-05-04 修复 — 过渡动画方向与时长优化

**问题：**
1. 后退时动画方向错误（仍是从右滑入，应为从左滑入）
2. 动画时间 0.26s 偏长

**修复：** `src/App.vue`
- `router.beforeEach` 维护路由历史栈 `routeStack`，检测导航方向，设置 `isBack` ref
- Transition name 改为动态计算 `:name="transitionName"`（前进 `page-slide-forward`，后退 `page-slide-back`）
- 后退 CSS：页面从左(-24px)滑入，旧页向右(36px)退出
- 动画时长从 0.26s 缩到 0.2s

**编译验证：** `vite build` 通过，无错误

---

### 2026-05-04 — 收藏夹页面搭建

**概述：**
完成收藏夹页面完整搭建，包括前端组件、类型定义、服务层、Android 原生插件扩展。

**设计方案：** `docs/favorite-page-plan.md`

**新增文件：**
- `src/components/favorite/FavoriteSearchBar.vue` — 收藏夹搜索栏（搜索框+搜索按钮，仅搜索收藏夹内）
- `src/components/favorite/FavoriteSideMenu.vue` — 右侧收藏夹菜单侧边栏（在线/离线文件夹列表，手势开关，根据登录态显示不同内容）
- `src/services/OfflineFavoriteService.ts` — 离线收藏夹服务（localStorage 增删改查+分页搜索）

**修改文件：**
- `src/services/JmcomicTypes.ts` — 新增 `FavoriteQuery`、`FavoriteResult` 类型
- `src/services/JmcomicService.ts` — 新增 `favorites()` 方法，扩展 `JmcomicPlugin` 接口
- `android/app/src/main/java/io/github/jukomu/plugin/JmcomicPlugin.java` — 新增 `getFavorites` @PluginMethod + `toFavoritePage` 序列化助手 + `Map` import
- `src/views/FavoritePage.vue` — 完整重写：工具栏、搜索、结果展示、右侧菜单、手势、keep-alive、在线/离线双模式
- `src/router/index.ts` — FavoritePage 路由添加 `meta.keepAlive: true`

**关键特性：**
- 在线收藏夹：通过 Android 原生 `getFavorites` → `JmFavoritePage` 获取，返回 `folderList` 作为文件夹列表
- 离线收藏夹：localStorage 管理，支持多文件夹、关键词搜索、分页
- 右侧菜单：已登录显示「在线收藏夹」+「离线收藏夹」两栏，未登录仅显示「离线收藏夹」
- 手势支持：从右边缘左划打开菜单，点击遮罩或选择文件夹关闭
- 复用 SearchResultContainer 展示结果，支持列表/网格切换
- keep-alive 状态保持，支持滚动位置恢复

**编译验证：** `vue-tsc --noEmit` + `vite build` 通过，无错误

---

### 2026-05-04 — 收藏夹页面 Bug 修复（搜索框对齐、按钮配色、手势互斥）

**问题1：搜索框对齐**
- 根因：FavoriteSearchBar 有 `expanded-panel` 橙色背景面板包裹，面板顶部与菜单按钮对齐，但搜索框实际边框被面板 padding 推下去
- 修复：移除 `expanded-panel` 包装 div 及其 CSS，`search-row` 直接作为根子元素，边框与 MenuToggleButton 顶部对齐

**问题2：右侧栏按钮配色不一致**
- 修复：FavoriteSideMenu 的 `folder-item` 样式对齐 MainMenu 的 `menu-item`：
  - border-radius: 14px → 20px
  - padding: 10px 12px → 12px 14px
  - min-height: 48px
  - box-shadow 对齐（0 12px 28px rgb(115 67 38 / 0.08)）
  - selected box-shadow 对齐（0 16px 34px rgb(240 126 73 / 0.26)）
- `folders-toggle-btn` 改用渐变背景 `linear-gradient(145deg, #fa9c69, #f28752)` 和匹配的 box-shadow

**问题3：手势互斥**
- 根因：左侧菜单用 IonMenu+menuController 管理，右侧用自定义 overlay+menuVisible 管理，两个系统独立，可同时打开
- 修复：
  - 新增 `src/composables/sideMenuState.ts` 共享状态 — `leftMenuOpen` / `rightMenuOpen` 响应式变量
  - `App.vue`：用 `leftMenuOpen` 替代私有 `isMenuOpen`；左侧打开手势 `canStart` 增加 `rightMenuOpen.value` 检查；左侧打开时设置 `rightMenuOpen.value = false`（互斥关闭）
  - `FavoritePage.vue`：`menuVisible` 替换为 `rightMenuOpen`；右侧打开手势 `canStart` 增加 `leftMenuOpen.value` 检查；打开时先 `menuController.close()` 关闭左侧菜单再设置 `rightMenuOpen = true`
  - 新增右侧面板关闭手势（右划 dx>14 or vx>0.18 关闭），通过 `watch(rightMenuOpen)` 动态绑定/解绑手势（面板条件渲染，DOM 需等待 nextTick）
  - keep-alive 失活时重置 `rightMenuOpen = false`

**修改文件：**
- `src/composables/sideMenuState.ts` — 新增共享状态模块
- `src/components/favorite/FavoriteSearchBar.vue` — 移除 expanded-panel
- `src/components/favorite/FavoriteSideMenu.vue` — 按钮样式对齐 MainMenu
- `src/views/FavoritePage.vue` — 手势互斥、shared state、close gesture
- `src/App.vue` — 手势互斥、shared state

**编译验证：** `vue-tsc --noEmit` + `vite build` 通过，无错误

---

### 2026-05-04 — 收藏夹右侧侧边栏重构（改用 IonMenu + 手势完善）

**改动内容：**

1. **右侧侧边栏改用 IonMenu**（`side="end" menu-id="favorite-menu" type="overlay"`）
   - 动画由 Ionic 内置 overlay 处理，与左侧侧边栏完全一致
   - 手势模式与左侧对应（方向相反）

2. **手势适配完善**
   - 打开手势绑定 `#main-content`（通过 `canStart` 区域区分左右）
   - 关闭手势绑定 `ion-menu[menu-id="favorite-menu"]`
   - 手势 priority：打开 29、关闭 31，与左侧一致

3. **互斥逻辑增强**
   - 右侧打开时自动关闭左侧（`menuController.close()`）
   - 左侧打开时自动关闭右侧（`menuController.close('favorite-menu')`）

4. **按钮配色统一**
   - `.folders-toggle-btn` 改为 `#fff3ea` 背景 + `#7f553b` 图标 + 同款阴影

**修改文件：**
- `src/components/favorite/FavoriteSideMenu.vue` — 去遮罩层/Transition/v-model，用 menuController 控制关闭
- `src/views/FavoritePage.vue` — 模板加 IonMenu；手势重写；按钮配色；ionDidOpen/ionDidClose 监听
- `src/App.vue` — handleMenuDidOpen 加 close('favorite-menu')
- `docs/2026-05-04-favorite-sidebar-refactor.md` — 方案存档

**编译验证：** `vue-tsc --noEmit` + `vite build` 通过，无错误

### 2026-05-04 — 收藏夹右侧侧边栏 Bug 修复（两个问题）

**Bug 1：呼出后自动收起无法使用**
- 根因：`handleRightMenuDidOpen` 中无条件调用 `menuController.close()`（关闭"任意打开的菜单"），左侧未打开时关闭了刚打开的右侧菜单自身
- 修复：加 `if (leftMenuOpen.value)` 判断，仅左菜单真的打开时才关闭

**Bug 2：手势触发区域过窄**
- 根因：右侧手势 `canStart` 中 `startX < 70%` → false，只能从屏幕右侧 30% 触发；而左侧是 `startX <= 70%`（左侧 70% 可触发）
- 修复：`0.7` → `0.3`，改为 `startX < 30%` → false（右侧 70% 可触发），与左侧完全镜像，中间重叠区域由滑动方向区分

**修改文件：**
- `src/views/FavoritePage.vue` — `handleRightMenuDidOpen` 加 `leftMenuOpen` 判断；`setupOpenGesture` canStart 阈值 0.7→0.3

**编译验证：** `vue-tsc --noEmit` + `vite build` 通过，无错误

### 2026-05-04 — 收藏夹右侧侧边栏 Bug 修复（第二轮）

**Bug 1：手势死区（30%-70% 区域两个手势都不响应）**
- 根因：两个 gesture 同 priority 29，startX 范围重叠但互不覆盖。30%-70% 区域：左侧 `startX > 70%` → false，右侧 `startX < 30%` → false，两个都不捕获
- 修复：去掉 startX 限制，改用方向区分
  - 左侧 canStart：`deltaX <= 0 && velocityX <= 0` → false（仅右划）
  - 右侧 canStart：`deltaX >= 0 && velocityX >= 0` → false（仅左划）
  - 全屏任意位置：右划=左侧，左划=右侧

**Bug 2：只能通过手势关闭，点击遮罩不响应**
- 根因：IonMenu 在 `<IonPage>` 内部，IonPage 层级限制遮罩交互
- 修复：IonMenu 提到 `<IonPage>` 外并列，与左侧 MainMenu 一级

**修改文件：**
- `src/views/FavoritePage.vue` — IonMenu 移到 IonPage 外；canStart 去 startX 改用方向
- `src/App.vue` — 左侧 canStart 去 startX 改用方向

**编译验证：** `vue-tsc --noEmit` + `vite build` 通过，无错误

### 2026-05-04 — 收藏夹右侧侧边栏：放弃 IonMenu，回归自定义面板

**根因：** IonMenu 必须放在 `ion-app` 直接子级才能正常工作（遮罩、动画、层级），右侧菜单在 router-view 内的 FavoritePage 中无法做到，导致遮罩点击不响应+黄色边框。

**方案：** 回归 `position: fixed` + Vue `<Transition>` 自定义面板，保留手势和互斥修复：

- **关闭方式**：遮罩点击 + 关闭按钮 + 手势右划 + 选择文件夹后自动关闭
- **手势**：`#main-content` 方向区分（左划=右侧，右划=左侧）+ 面板元素关闭手势
- **互斥**：`sideMenuState` 共享状态 + 打开前先关对方
- **动画**：`<Transition>` fade+slide

**修改文件：**
- `src/components/favorite/FavoriteSideMenu.vue` — 恢复自定义面板（遮罩+Transition+v-model+panelRef expose）
- `src/views/FavoritePage.vue` — 去 IonMenu，回 v-model；openRightMenu 先关左再开右；手势写 rightMenuOpen；watch 动态关手势
- `src/App.vue` — 去 `menuController.close('favorite-menu')`

**编译验证：** `vue-tsc --noEmit` + `vite build` 通过，无错误

### 2026-05-04 — 收藏夹右侧侧边栏修复（手势穿透 + 动画）

**问题1：** 右侧侧边栏打开时，在遮罩区域右划仍触发左侧侧边栏打开手势
**修复：** `FavoriteSideMenu.vue` 遮罩层添加 `@touchstart.stop` 阻止事件冒泡

**问题2：** 右侧侧边栏动画为嵌套 Transition 淡入淡出，效果差
**修复：** 改为单层 Transition，遮罩 opacity + 面板 translateX 同步播放，与左侧 IonMenu overlay 效果一致

**修改文件：**
- `src/components/favorite/FavoriteSideMenu.vue` — 模板去嵌套 Transition + 遮罩 @touchstart.stop；CSS 合并动画

**设计方案：** `docs/2026-05-04-favorite-right-sidebar-fix.md`

**编译验证：** `vue-tsc --noEmit` + `vite build` 通过，无错误

---

### 2026-05-04 — 手势失效修复（canStart 方向判断时机错误）

**根因：** `canStart` 在手势刚开始就触发，此时 `deltaX` 和 `velocityX` 都是 0。旧判断 `deltaX <= 0 && velocityX <= 0` 在两者都为 0 时 = true，导致左右两侧 canStart 都返回 false，所有手势被拦截无法启动。

**修复：** 去掉 canStart 中的方向判断，改用非重叠 50% 分界：
- 左侧：`startX > 50%` → false（左半屏）
- 右侧：`startX < 50%` → false（右半屏）
- 方向判断由 onEnd 中的 deltaX 阈值/速度阈值处理

**修改文件：**
- `src/App.vue` — canStart 分界 0.7→0.5，去方向判断
- `src/views/FavoritePage.vue` — canStart 分界 0.3→0.5，去方向判断

**编译验证：** `vue-tsc --noEmit` + `vite build` 通过，无错误

### 2026-05-04 — 收藏夹搜索框高度对齐修复

**问题：** 搜索框 `.search-row` 底部与侧边栏按钮 `.folders-toggle-btn` 底部未齐平。

**修复：** `src/components/favorite/FavoriteSearchBar.vue` — `.search-row`：
- 垂直 padding 去除（`2px 2px 2px 8px` → `0 2px 0 8px`）
- 新增 `height: 40px; box-sizing: border-box;`，与侧边栏按钮高度一致
- 两者同为 40px，父级 `align-items: flex-start` 下顶部和底部均齐平

---

### 2026-05-04 — 下载功能完整实现

**概述：**
按照 `docs/download-page-plan.md` 设计方案，完成以章节为下载单位、支持下载页管理（队列/进度/删除/取消）、下载完成后离线阅读的完整下载功能。

**核心架构：**
- SQLite (DownloadDatabase) — 权威元数据存储（download_tasks + download_images 表，启动校验+僵尸恢复）
- FileStorage — 图片文件读写 + meta.json 备份 + 空间查询 + 内存索引（chapterId→albumId, sortOrder→filename）
- ImageRegistry 统一路径 — handleRequest 缓存 miss 后回退 FileStorage，在线/离线使用相同 URL 格式
- 库 `client.download(photo).withPath/withExecutor/withProgress/execute()` 处理下载+解密+保存
- 独立单线程队列 Executor 串行处理章节下载，章节内使用 imageExecutor 并行下载图片
- localStorage (OfflineDownloadService) — 前端快速首屏渲染缓存

**新增文件：**
- `android/.../plugin/DownloadDatabase.java` — SQLite 数据库（tasks + images 两表，事务写入，启动校验）
- `android/.../plugin/FileStorage.java` — 文件存储管理（单例，内存索引，图片/元数据读写，空间查询）
- `src/services/OfflineDownloadService.ts` — 前端下载任务 localStorage CRUD（全量覆盖 + 增量更新）
- `src/components/favorite/DownloadTaskCard.vue` — 下载任务卡片组件（封面/标题/进度条/状态/操作按钮）

**修改文件（Android 侧）：**
- `JmcomicPlugin.java` — 新增 downloadChapter/getDownloadTasks/cancelDownload/deleteDownloaded/getDownloadedPhoto 5 个 @PluginMethod；新增独立单线程 downloadQueueExecutor + BlockingQueue；新增 cancelFlags；新增 processDownloadQueue 消费者线程；新增 pushDownloadProgress 事件推送；覆盖 load() 生命周期
- `ImageRegistry.java` — handleRequest 新增 FileStorage 回退（缓存 miss→FileStorage→内存缓存）；新增 guessFormatName 文件头魔数判断

**修改文件（前端侧）：**
- `JmcomicTypes.ts` — 新增 DownloadStatus/DownloadTask/DownloadTasksResult/DownloadProgressEvent 类型
- `JmcomicService.ts` — 新增下载相关 6 个方法 + 接口扩展
- `DownloadPage.vue` — 完整重写：存储信息条 + 下载中/已完成分组 + 进度/状态卡片 + 取消/重试/阅读/删除操作 + 空状态
- `AlbumDetailPage.vue` — handleDownload 改为真实下载逻辑（检查重复→downloadChapter→乐观写入→toast）
- `ReaderPage.vue` — 新增离线阅读分支（isOffline 检测 source=download；getDownloadedPhoto→滑动窗口直接 fill imageMap→跳过 preloadImages 和 listener）

路由无需修改。

**编译验证：** `vue-tsc --noEmit` + `vite build` 通过，无错误

---

### 2026-05-04 — 下载功能代码审查与修复

**概述：**
对下载功能实现的全部代码进行审查（simplify 流程），修复发现的高优先级效率、质量和代码复用问题。

**修复内容：**

**效率修复：**
1. `JmcomicPlugin.java` — 移除 `withProgress` 回调中的 `saveMeta()` 调用（每张图片完成后写入磁盘 → 仅下载前写入一次，完成后不重复写）
2. `ReaderPage.vue` — 预构建 `sortOrderToImage` Map（在线模式 `updateWindow` 中 `O(n*m) find()` → `O(1) Map.get()`）
3. `DownloadPage.vue` — 进度监听新增变更检测守卫（`status` 和 `downloadedPages` 都未变时跳过更新，减少无意义响应式触发和 localStorage 写入）
4. `FileStorage.java` — `getTotalUsedBytes()` 增加缓存（`cachedTotalBytes` + `sizeNeedsRecalc` 标记，仅删除章节后重新计算，避免每次查询递归 `dirSize`）
5. `DownloadDatabase.java` — `getAllTasks()` 添加 `LIMIT 500`

**质量修复：**
6. `JmcomicPlugin.java` — 定义下载状态常量 `STATUS_QUEUED`/`STATUS_DOWNLOADING`/`STATUS_COMPLETED`/`STATUS_FAILED`，替换全部字符串字面量

**代码复用修复：**
7. `JmcomicTypes.ts` — 新增 `makeTaskId(albumId, chapterId)` 辅助函数
8. `JmcomicService.ts` — 新增 `showToast(message, color)` 辅助函数
9. `AlbumDetailPage.vue` — `handleDownload` 使用 `makeTaskId` 替代字符串拼接；toast 改用 `showToast` 辅助函数
10. `DownloadPage.vue` — `onCancel`/`onRetry`/`onDelete` 的 toast 改用 `showToast` 辅助函数；移除未使用的 `toastController` 导入

**修改文件：**
- `android/.../plugin/JmcomicPlugin.java`
- `android/.../plugin/FileStorage.java`
- `android/.../plugin/DownloadDatabase.java`
- `src/views/ReaderPage.vue`
- `src/views/DownloadPage.vue`
- `src/views/AlbumDetailPage.vue`
- `src/services/JmcomicTypes.ts`
- `src/services/JmcomicService.ts`

**编译验证：** `vue-tsc --noEmit` + `vite build` 通过，无错误# 项目执行日志

---

### 2026-05-04 — 下载功能全链路梳理

- 梳理了下载功能的完整链路：Vue 前端 → Capacitor Plugin → Android 原生层
- 涵盖：DownloadDatabase (SQLite)、FileStorage (文件系统)、ImageRegistry (LRU 内存缓存)、downloadQueue (BlockingQueue 串行消费)
- 覆盖流程：触发下载（AlbumDetailPage）、队列消费（processDownloadQueue）、取消、删除、重试、离线阅读
- 输出分析报告：`docs/download-analysis.md`
- 总计发现 8 个问题：
  - 中优先级 2 个：meta.json 写入失败未标记 failed、下载过程中存储使用量不更新
  - 低优先级 4 个：UX 相关 + 竞态窗口
  - 极低 1 个
  - 确认项 1 个

---

### 2026-05-10 — 下载功能重构：切换为库内置任务系统

**概述：**
将项目手动管理的下载队列替换为 jmcomic-api-java 1.1.0 提供的 DownloadManager + 任务系统（BaseDownloadTask / TaskObserver / TaskState）。

**变更内容：**

**JmcomicPlugin.java 重构：**
- **移除**：downloadQueue (LinkedBlockingQueue)、downloadQueueExecutor (单线程)、cancelFlags (ConcurrentHashMap<AtomicBoolean>)、processDownloadQueue() 方法
- **新增**：DownloadTaskObserver 内部类（实现 TaskObserver，监听 onStateChanged/onProgressUpdate/onError，同步 DB 并推送到 JS）；taskIdMap (albumId_chapterId → library taskId)；reverseTaskIdMap 反向映射；cleanupTaskMapping() 辅助方法
- **修改**：
  - `downloadChapter()` — 改为先入 DB(queued) → resolve 立即返回 → imageExecutor 异步：获取 JmPhoto → 入库 → 建目录 → `createDownloadTask(photo, path)` → 注册 Observer → `downloadManager().submit(task)`
  - `cancelDownload()` — 改为调用 `downloadManager().cancel(libTaskId)`，清理交给 Observer 处理
  - `deleteDownloaded()` — 增加对库 DownloadManager 的 cancel 调用
  - `load()` — 移除 downloadQueueExecutor 启动
- **Import 更新**：旧 API `io.github.jukomu.jmcomic.api.client.DownloadRequest/DownloadResult` → 新 API `io.github.jukomu.jmcomic.api.download.*`

**不变文件：**
- DownloadDatabase.java、FileStorage.java、ImageRegistry.java
- 所有 TS/Vue 前端文件（接口完全兼容）

**方案存档：** `docs/download-refactor-plan.md`

**编译验证：** `./gradlew assembleDebug` BUILD SUCCESSFUL，无错误

---

### 2026-05-10 — 下载功能代码审查与健壮性修复

**概述：**
对下载功能全链路代码进行审查（Java + TS/Vue），发现 4 个问题并修复。

**修复内容：**

1. **P0 — cancelDownload 竞态条件**（JmcomicPlugin.java）
   - 问题：用户在 async 块执行到 `taskIdMap.put()` 之前点击取消，`libTaskId` 为 null，取消被静默忽略
   - 修复：新增 `pendingCancel` 集合（`ConcurrentHashMap.newKeySet()`）；`cancelDownload` 中 `libTaskId` 为 null 时加入 `pendingCancel`；async 块在 `downloadManager().submit()` 前检查 `pendingCancel`，命中则直接清理不提交

2. **P1 — Observer 双重触发防护**（JmcomicPlugin.java）
   - 问题：`onError` + `onStateChanged(FAILED)` 可能连续触发，导致重复 DB 写入和进度推送
   - 修复：`DownloadTaskObserver` 新增 `AtomicBoolean finalized`，`markFinalized()` CAS 方法；终端状态 `onStateChanged` 和 `onError` 前置检查，二次触发直接 return

3. **P1 — async catch 块缺少 cleanupTaskMapping**（JmcomicPlugin.java）
   - 问题：async 块 catch 中未清理 taskId 映射，mapping 泄漏
   - 修复：catch 块末尾添加 `cleanupTaskMapping(taskId)` 调用

4. **P2 — localStorage 配额异常未捕获**（OfflineDownloadService.ts）
   - 问题：`writeTasks` 中 `localStorage.setItem` 在存储满时抛出 QuotaExceededError，crash 调用方
   - 修复：`writeTasks` 包裹 try/catch，失败时静默丢弃

**防御性完善：**
- `cancelDownload` STATUS_QUEUED 分支：增加 `pendingCancel.remove()` 和使用 `cleanupTaskMapping()`（替代不完整的 `taskIdMap.remove()`）
- `deleteDownloaded`：增加 `pendingCancel.remove(taskId)` 清理

**修改文件：**
- `android/.../plugin/JmcomicPlugin.java` — 新增 `pendingCancel` Set、`AtomicBoolean` import；Observer 新增 `finalized`；async 块新增 pendingCancel 检查 + cleanupTaskMapping
- `src/services/OfflineDownloadService.ts` — `writeTasks` 添加 try/catch

**编译验证：** `./gradlew assembleDebug` BUILD SUCCESSFUL + `vue-tsc --noEmit` + `vite build` 通过

---

### 2026-05-10 — 下载页面速度显示 + 部分失败重试策略优化

**概述：**
添加实时下载速度显示，并优化部分图片下载失败后的重试策略（不再删除已成功下载的图片）。

**速度显示实现：**
- `JmcomicPlugin.java` — `DownloadTaskObserver` 新增 `lastBytes`/`lastTimestamp` 快照，`onProgressUpdate` 中计算速度 = deltaBytes / deltaTime；`pushDownloadProgress` 新增重载接收 speed 参数
- `JmcomicTypes.ts` — `DownloadProgressEvent` 和 `DownloadTask` 均新增 `speed?: number`
- `DownloadTaskCard.vue` — 新增 `speedText` computed，自动格式化 B/s→KB/s→MB/s；进度条下方显示速度文字（灰色小字）
- `DownloadPage.vue` — 监听回调中同步 `task.speed = data.speed`

**部分失败重试策略（变更）：**
- **旧策略**：`onRetry` 先调用 `deleteDownloaded`（删除已下载的全部文件）→ 重新提交下载（全部重新下载）
- **新策略**：`onRetry` 不删除已有文件 → 直接重新提交下载。库的 `ImageDownloadTask` 内置"文件已存在则跳过"逻辑，自动仅下载缺失的图片。Android 侧 `hasActiveOrCompleted` 对 `failed` 状态返回 false，允许重新提交

**修改文件：**
- `android/.../plugin/JmcomicPlugin.java` — Observer 速度计算 + pushDownloadProgress 重载
- `src/services/JmcomicTypes.ts` — `DownloadProgressEvent` 和 `DownloadTask` 新增 `speed` 字段
- `src/components/download/DownloadTaskCard.vue` — 速度格式化 + 显示
- `src/views/DownloadPage.vue` — speed 传递 + onRetry 去掉 deleteDownloaded

**编译验证：** `./gradlew assembleDebug` BUILD SUCCESSFUL + `vue-tsc --noEmit` + `vite build` 通过

---

### 2026-05-10 — 项目状态全面审查与存档

**概述：**
对项目全部代码进行系统性审查，按模块梳理完成度，识别功能缺口，存档项目状态。

**已完成模块（6个）：**
- 阅读器：双模式翻页、滑动窗口预加载、速度自适应、离线阅读
- 详情页：四Tab（信息/章节/预览/评论）、骨架屏、操作栏
- 下载：库 DownloadManager 任务系统、进度+速度显示、增量重试
- 收藏夹：在线/离线双模式、双向分页、搜索、手势互斥
- 搜索/分类：关键词/分类筛选、双向分页、keep-alive 缓存
- 导航菜单：IonMenu + 自定义过渡动画 + 左右手势互斥

**功能缺口（8项）：**
| 缺口 | 位置 |
|------|------|
| 设置页完全空白 | SettingPage.vue |
| 首页无推荐内容 | HomePage.vue |
| 批量模式无功能 | KeywordSearchBar.vue |
| 评论不能发表/回复 | 无 |
| 不支持本子级下载 | JmcomicPlugin.java |
| 下载暂停/恢复未暴露 | JmcomicPlugin.java |
| 离线收藏夹无管理UI | FavoritePage.vue |
| 无登录页面 | 无 |

**存档文件：** `docs/project-state-2026-05-10.md`

---

### 2026-05-10 — 功能缺口任务清单与入口文档

**概述：**
创建 `docs/task-list.md` 功能缺口清单，作为新对话的入口文档。包含 8 项待完成任务（含工作量估算、依赖关系、关键文件、参考文档）。更新 CLAUDE.md 新增第 11 条：新对话开始时首先阅读 task-list.md 和 project-state 文档。

**任务清单涵盖：**
1. 设置页（空白→接入缓存设置）— 小
2. 首页内容（搜索框→推荐/排行）— 中
3. 登录页（无→用户名密码登录）— 中
4. 评论发表/回复 — 中（依赖任务3）
5. 本子级下载（章节→整本）— 中
6. 下载暂停/恢复（库已支持，桥接暴露）— 小
7. 批量化搜索（决定去留）— 小
8. 离线收藏夹管理UI（增删文件夹）— 小

**修改文件：**
- `docs/task-list.md` — 新增，功能缺口任务清单（含上下文文档引用）
- `.claude/CLAUDE.md` — 新增第 11 条：新对话入口指引

---

### 2026-05-10 — 设置页完整实现

**概述：**
完成设置页（SettingPage）的完整搭建，包括缓存管理、阅读设置、下载设置、关于信息 4 个分组共 7 个设置项。

**方案存档：** `.claude/plans/3-hazy-parnas.md`

**新增文件：**
- `src/services/SettingsService.ts` — 设置持久化服务（localStorage 读写，含默认值）
- `.claude/plans/3-hazy-parnas.md` — 设置页实现方案

**修改文件：**

前端：
- `src/views/SettingPage.vue` — 完整重写（~230行），从空白页变为 4 分组 7 设置项
  - 分组一 图片缓存：缓存用量顶部进度条 + 缓存上限数字输入（64-2048MB，下次启动生效）+ 清空缓存按钮（ion-alert 确认）
  - 分组二 阅读设置：预加载页数输入（5-50，localStorage 存储）
  - 分组三 下载设置：并发线程数输入（1-12）+ 公开下载内容开关（ion-toggle）
  - 分组四 关于：版本号显示（v0.0.1）
- `src/services/JmcomicService.ts` — 接口新增 `clearImageCache`/`setDownloadConcurrency`/`setDownloadPublic`/`getDownloadPublic` 4 个方法声明 + service 对象实现
- `src/views/ReaderPage.vue` — `N` 预加载常量改为 `SettingsStore.getReaderPreloadPages()` 读取

Android 侧：
- `JmcomicPlugin.java`：
  - 新增 `clearImageCache` @PluginMethod → `ImageRegistry.clear()`
  - 新增 `setDownloadConcurrency(n)` @PluginMethod → SharedPreferences 持久化 + 优雅重启 imageExecutor
  - 新增 `setDownloadPublic(boolean)` / `getDownloadPublic` @PluginMethod → SharedPreferences 持久化
  - `setCacheCapacity` 增加 SharedPreferences 持久化
  - 新增 `SharedPreferences` + `Context` import；`imageExecutor` 改为 `volatile` 可替换
  - `load()` 中读取 SharedPreferences 初始化缓存容量、并发数和公开下载设置
- `FileStorage.java`：
  - `init()` 新增 `usePublicDir` 参数
  - 公开模式：`Environment.getExternalStoragePublicDirectory(DIRECTORY_PICTURES)/JQViewer`
  - 私有模式：`context.getFilesDir()/downloads`（原有行为）
  - 新增 `Environment` import

**编译验证：**
- `vue-tsc --noEmit` ✓ 通过
- `vite build` ✓ 通过
- `./gradlew assembleDebug` ✓ BUILD SUCCESSFUL

**注意事项：**
- 公开下载仅影响新下载任务，已有下载不受影响（目录不同）
- 预加载页数模块级 const 单次求值，修改后需重新进入阅读页生效

---

## 2026-05-10 — 下载暂停/恢复功能

### 概述
实现下载任务的暂停/恢复功能，桥接库已有的 `DownloadManager.pause()/resume()` 到 Vue 侧。

### 变更文件

Android 侧：
- `JmcomicPlugin.java`：
  - 新增 `STATUS_PAUSED` 常量
  - 新增 `pauseDownload(taskId)` @PluginMethod：仅允许 downloading→paused，防御检查 libTaskId 和 downloadManager 存在性，更新 DB + 推送状态后调用 `downloadManager().pause()`
  - 新增 `resumeDownload(taskId)` @PluginMethod：仅允许 paused→downloading，防御检查同上，更新 DB + 推送状态后调用 `downloadManager().resume()`
  - `cancelDownload` 新增 paused 分支：调用 `downloadManager().cancel(libTaskId)` 由 Observer 处理清理
  - `DownloadTaskObserver.onStateChanged` 新增 PAUSED 处理：推送当前进度状态到 JS
- `DownloadDatabase.java`：
  - `hasActiveOrCompleted()` SQL 判断加入 `"paused"`
  - `validateOnStartup()` 僵尸恢复 SQL 加入 `'paused'`（重启后库内任务丢失，标记 failed）

Vue 侧：
- `JmcomicTypes.ts`：`DownloadStatus` 增加 `'paused'`
- `JmcomicService.ts`：新增 `pauseDownload()` / `resumeDownload()` 桥接方法 + 接口声明
- `DownloadTaskCard.vue`：
  - queued/downloading 按钮区拆分：queued 仅"取消"，downloading 新增"暂停"+"取消"
  - 新增 paused 状态："已暂停"标签 + "继续"+"取消"按钮
  - 新增 emits: `pause`、`resume`
  - 新增样式：`.btn-pause`、`.btn-resume`、`.status-tag.paused`
- `DownloadPage.vue`：
  - `activeTasks` 计算属性加入 `paused` 过滤
  - 新增 `onPause()`/`onResume()` 处理函数
  - 下载进度监听新增 paused 状态更新分支
  - 模板中 DownloadTaskCard 绑定 `@pause`/`@resume`

### 关键设计决策
- 跨重启不保留 paused：app 重启后 DownloadManager 重建，库内任务丢失，paused→failed；用户"重试"自动跳过已完成文件
- paused 属于活跃状态，显示在"下载中"分区
- 仅 downloading 可暂停，queued 阶段库任务未创建无法暂停

### 编译验证
- `vue-tsc --noEmit` ✓ 通过
- `./gradlew assembleDebug` ✓ BUILD SUCCESSFUL

### 代码审查修复（2026-05-10）

审查发现4项 Important 级别问题，均已修复：

1. **DB/库调用顺序** — `pauseDownload`/`resumeDownload` 原实现先写 DB 再调库方法，若库抛异常则 DB 状态与实际不一致。修复为先调库、成功后写 DB。（`JmcomicPlugin.java:643-649, 692-698`）
2. **Observer PAUSED 未更新 DB downloadedPages** — `onStateChanged(PAUSED)` 只推送 JS 不写 DB，导致重启后恢复的 failed 任务下载页数不准。修复为增加 `downloadDb.updateProgress()` 调用。（`JmcomicPlugin.java:846`）
3. **cancel paused 无 libTaskId null 兜底** — 若 taskIdMap 缺失映射，取消操作静默无效果。修复为增加直接 DB+文件清理兜底逻辑。（`JmcomicPlugin.java:575-583`）
4. **Vue paused 监听未同步 data.downloadedPages** — paused 分支未像 downloading 分支那样更新 `task.downloadedPages`。修复为增加 `task.downloadedPages = data.downloadedPages`。（`DownloadPage.vue:151`）

编译验证：`vue-tsc --noEmit` ✓ | `./gradlew assembleDebug` ✓ BUILD SUCCESSFUL

---

### 2026-05-10 — 设置页完整实现

**概述：**
完成设置页（SettingPage）的完整搭建，含缓存管理、阅读、下载、关于 4 分组 7 设置项。同步修复了 `getClient()` 未利用 `JmConfiguration.Builder` 配置的遗漏。

**方案存档：** `.claude/plans/3-hazy-parnas.md`

**新增文件：**
- `src/services/SettingsService.ts` — 设置持久化服务（localStorage，含默认值）

**修改文件：**

前端：
- `src/views/SettingPage.vue` — 完整重写（~250行），4 分组 7 设置项
  - 分组一 图片缓存：用量进度条 + 上限输入（64-2048MB，SharedPreferences 持久化，下次启动生效）+ 清空缓存（ion-alert 确认）
  - 分组二 阅读设置：预加载页数输入（5-50，localStorage）
  - 分组三 下载设置：并发线程数输入（1-12，下次启动生效）+ 公开下载开关（ion-toggle，立即生效，移动全部已有文件）
  - 分组四 关于：版本号
- `src/services/JmcomicService.ts` — 新增 `clearImageCache`/`setDownloadConcurrency`/`setDownloadPublic`/`getDownloadPublic` 4 个方法
- `src/views/ReaderPage.vue` — `N` 预加载常量改为 `SettingsStore.getReaderPreloadPages()` 读取

Android 侧：
- `JmcomicPlugin.java`：
  - `getClient()` 修复：`new JmConfiguration.Builder().downloadThreadPoolSize(imageConcurrency).build()` — 库的 internalExecutor + DownloadManager.executor 与 imageExecutor 三池统一为配置的并发数
  - `load()` 中从 SharedPreferences 初始化缓存容量、并发数、公开下载 → FileStorage
  - 新增 `clearImageCache` @PluginMethod → `ImageRegistry.clear()`
  - 新增 `setDownloadConcurrency(n)` @PluginMethod → SharedPreferences + 优雅重启 imageExecutor（库线程池下次启动生效）
  - 新增 `setDownloadPublic(boolean)` @PluginMethod → SharedPreferences + `FileStorage.relocate()` 搬迁全部文件（有进行中任务则 reject）
  - 新增 `getDownloadPublic` @PluginMethod
  - `imageExecutor` 改为 `volatile` 可替换
- `FileStorage.java`：
  - `init()` 新增 `usePublicDir` 参数（私有=filesDir/downloads，公开=PICTURES/JQViewer）
  - 新增 `relocate()` 方法：移动全部 album 目录到目标目录，更新 baseDir

**编译验证：**
- `vue-tsc --noEmit` ✓
- `vite build` ✓
- `./gradlew assembleDebug` ✓ BUILD SUCCESSFUL

---

### 2026-05-12 — 设置页重构：配置迁移到 SQLite 数据库

**概述：**
将设置配置从 localStorage（Vue侧）+ SharedPreferences（Android侧）双存储统一迁移到独立 SQLite 数据库 `jq_settings.db`。同时拆分 `download_concurrency` 为 `preload_concurrency`（预加载并发）和 `download_concurrency`（下载并发），并发类设置改为仅下次启动生效。修复 BUG-1（pref/relocate 持久化顺序）、BUG-2（整型除法截断）、BUG-4（缺少错误处理）。

**方案存档：** `.claude/plans/polished-knitting-sprout.md`

**新增文件：**
- `android/.../storage/SettingsDatabase.java` — 设置 SQLite 数据库（key-value 表，单例模式，5项默认值）
  - 方法：getString/getInt/getLong/getBoolean/putString
  - putString 使用 `INSERT ON CONFLICT REPLACE`

**修改文件：**

前端：
- `src/services/SettingsService.ts` — 完全重写：localStorage → 同步内存缓存 + `initSettings()` 幂等加载
- `src/App.vue` — `onMounted` 中 `await initSettings()`，确保缓存在页面渲染前就绪
- `src/views/SettingPage.vue`
  - 新增 "预加载并发数"（阅读设置区，下次启动生效）
  - "并发下载数" → "下载并发数"（下载设置区，下次启动生效）
  - ref 初始值全部从 SettingsStore 读取
  - `onCacheCapacityChange` / `onConcurrencyChange` / `onPreloadConcurrencyChange` 加 try/catch
  - `appVersion` → `App.getInfo().version`（从 Capacitor App 插件获取）
  - 移除旧的 `getDownloadPublic()` Android 回读逻辑
- `src/views/ReaderPage.vue` — `const N` → `getN()` 函数（6处引用替换），修复模块级常量无法动态更新
- `src/services/JmcomicService.ts` — 新增 `getAllSettings`/`setReaderPreloadPages`/`setPreloadConcurrency` 方法
- `src/services/JmcomicTypes.ts` — 新增 `AllSettings` 接口

Android 侧：
- `JmcomicPlugin.java`
  - 移除 `SharedPreferences prefs` 字段和 import，全部改读 `SettingsDatabase`
  - 新增 `downloadConcurrency` 字段，`getClient()` 改用新字段（修正 A）
  - `load()` 从 DB 读取 preload_concurrency→imageExecutor, download_concurrency→sharedClient
  - `setDownloadConcurrency` 改为仅写 DB（不再重建线程池）
  - 新增 `setPreloadConcurrency` @PluginMethod（仅写 DB）
  - 新增 `getAllSettings` @PluginMethod（返回全部5项设置）
  - 新增 `setReaderPreloadPages` @PluginMethod（之前仅 localStorage 存）
  - `setDownloadPublic`：relocate 移到 putString 之前（BUG-1）
  - `getCacheCapacityInfo`：整数除法 → `Math.round` + double（BUG-2）

**编译验证：**
- `vue-tsc --noEmit` ✓
- `vite build` ✓ (12.47s)
- `./gradlew assembleDebug` ✓ BUILD SUCCESSFUL in 15s

---

## 2026-05-13 — 公开下载功能完善

### 概述
修复设置页"公开下载内容"开关的 4 个问题：跨文件系统搬迁失败（renameTo）、无 MediaStore 通知、无搬迁进度反馈、paused 状态任务未被拦截。

### 方案设计
`docs/2026-05-13-download-public-fix-plan.md`

### 变更文件

**修改：**
- `src/services/JmcomicTypes.ts` — 新增 `RelocationProgress` 接口（current/total/phase/currentFile）
- `android/.../storage/FileStorage.java` — 重写 `relocate()`：分批复制→校验→删除（每20文件一批）+ 新增 `RelocationListener` 回调接口 + 新增 `scanPublicDir()` MediaStore 分批扫描（每100文件一批）+ 新增 checkpoint 断点续迁机制
- `android/.../plugin/JmcomicPlugin.java` — `setDownloadPublic` 改为后台线程异步执行 + 推送 `relocationProgress` 事件 + 任务检查统一拦截非终态（completed/failed 才允许切换）
- `src/services/JmcomicService.ts` — 新增 `addRelocationProgressListener` + `setDownloadPublic` 返回类型增加 `moved` 字段
- `src/views/SettingPage.vue` — 新增阻塞式搬迁进度弹窗 overlay（进度条 + 文件数 + 阶段文字 + 当前文件）

### 核心改进点
1. **搬迁机制**：`File.renameTo()` → `FileInputStream→FileOutputStream` 逐文件复制→校验大小→删除源文件，支持跨存储卷
2. **断点续迁**：SettingsDatabase `relocation_checkpoint` key 记录进度，中断后自动恢复
3. **进度可视化**：Capacitor `notifyListeners`/`addListener` 推送进度，前端弹窗阻塞式展示
4. **任务拦截**：非终态（queued/downloading/paused）统一拒绝，防止库内部状态不一致
5. **MediaStore 通知**：搬迁到公开目录后分批 `MediaScannerConnection.scanFile()`

**编译验证：**
- `vue-tsc --noEmit` ✓
- `./gradlew assembleDebug` ✓ BUILD SUCCESSFUL in 13s

### 2026-05-13 代码审查修复
- **修复**: `scanPublicDir` 进度公式错误：`total - (end - i)` → `end`，使扫描阶段进度条平滑递增（之前全程显示约77%然后跳100%）
- **已知不修复**: 设置持久化顺序崩溃窗口（无论先/后写 DB 都有窗口，启动自愈逻辑作为独立改进项）
- `./gradlew assembleDebug` ✓ BUILD SUCCESSFUL in 2s

---

## 2026-05-13 — 下载功能完善（7项修复）

### 概述
基于代码审查发现的 23 个问题，修复 2 个 Critical + 2 个 High + 2 个 Medium + 1 个显示 bug。

### 方案设计
`docs/2026-05-13-download-fix-plan.md`

### 变更文件

**修改：**
- `android/.../plugin/JmcomicPlugin.java` — 4 项修复：
  1. (Critical) 多章本子路径：`createDownloadTask` 传 `isSingleAlbum()` 判断的路径，抵消库的目录追加
  2. (Critical) SKIPPED 分支：observer 新增 `SKIPPED` 终端状态处理，等同完成
  3. (High) CANCELLED 幂等：observer 清理前先检查 DB 记录是否存在，避免双重推送
  4. (High) 取消竞态：`submit()` 后二次检查 `pendingCancel`，覆盖 TOCTOU 窗口
  5. (Medium) 僵尸恢复：`load()` 中标记 failed 前调用 `deleteChapter()` 清理部分下载文件
- `src/views/DownloadPage.vue` — 2 项修复：
  6. (Medium) 进度事件丢失：调换 listener 注册和 `syncDownloadState` 顺序
  7. (Bug) 速度闪烁：删除一刀切的变更检测 `if (downloadedPages 不变) return`

**编译验证：**
- `vue-tsc --noEmit` ✓
- `./gradlew assembleDebug` ✓ BUILD SUCCESSFUL in 2s

---

## 2026-05-13 — 搜索功能完善：ID直跳 + 单个解析

### 概述
当搜索关键词为纯数字 ID 时直接跳转详情页（本子存在则进详情，不存在则显示空搜索页）；修复首页搜索框"单个解析/批量解析"模式按钮逻辑（互斥但各自可取消，默认都不选中）；实现单个解析功能（从输入文本提取所有数字组合成 ID 搜索）。

### 变更文件

**修改：**
- `src/components/search/KeywordSearchBar.vue`
  - `mode` 默认值：`'single-mode'` → `''`（两个都不选中）
  - 模式按钮点击改为 toggle：`mode = mode === 'single-mode' ? '' : 'single-mode'`（同理 batch-mode），实现互斥+各自可取消
  - `emitSearch`：`single-mode` 下对 keyword 执行 `replace(/\D/g, '')` 提取所有数字
- `src/views/SearchPage.vue`
  - `resetWithPage`：新增 ID 检测逻辑，`/^\d+$/.test(keyword)` 通过时调用 `JmcomicService.getAlbum(id)`，成功则直接 `router.push(/album/:id)` 退出，失败则继续正常搜索

**编译验证：**
- `vue-tsc --noEmit` ✓
- `vite build` ✓ (9.56s)

## 2026-05-13（下午）— 下载页面交互重构

### 概述
重构下载页面交互：移除卡片操作按钮改为点击/长按+菜单模式，增加文件大小显示，优化部分失败状态展示，增加排序/清空/下拉刷新/滑动删除。

### 变更文件

**修改：**
- `src/components/download/DownloadTaskCard.vue` — 移除6个emit（cancel/pause/resume/retry/read/delete），新增3个emit（more/click/longpress）；移除所有操作按钮，添加右上角⋮按钮；整卡点击进入阅读（completed状态）；支持长按弹出菜单；部分失败显示进度条+红色失败数；新增文件大小显示；新增totalSize prop
- `src/views/DownloadPage.vue` — 集成IonActionSheet操作菜单（按状态动态选项）；卡片事件绑定改为@more/@click/@longpress；添加IonRefresher下拉刷新；添加IonItemSliding左滑删除（completed/failed）；section标题旁添加"清空"按钮（批量删除）；工具栏添加排序切换按钮（时间/标题）
- `src/services/JmcomicTypes.ts` — DownloadTask和DownloadProgressEvent各增加totalSize?: number字段
- `android/.../plugin/JmcomicPlugin.java` — DownloadTaskObserver在COMPLETED/COMPLETED_WITH_ERRORS/SKIPPED时计算章节目录文件大小并存入DB；pushDownloadProgress增加totalSize重载；新增calcChapterFileSize辅助方法；增加java.io.File导入
- `android/.../storage/DownloadDatabase.java` — DB版本1→2，新增total_size列（ALTER TABLE迁移）；新增updateSize方法；cursorToTaskJson包含totalSize
- `android/.../storage/FileStorage.java` — getChapterDir方法可见性从package-private改为public

### 编译验证
- `vue-tsc --noEmit` ✓
- `vite build` ✓ (8.93s)
- `./gradlew assembleDebug` ✓
- 计划文件: `G:\project\AndroidProjects\JQViewer_new\jq-viewer\docs\download-page-redesign-plan.md`

## 2026-05-13（下午二）— 下载页面代码审查修复

- 修复长按已完成任务同时弹出菜单和跳转阅读页的冲突
- 合并重复的 ionicons import 语句
- 排序图标改为 timeOutline / textOutline，更直观
- 批量清空从串行改为 Promise.all 并发，提升性能
- ActionSheet dismiss 时清理 selectedTask 引用

编译验证：vue-tsc --noEmit ✓ / vite build ✓ (9.76s)

## 2026-05-13（下午三）— 代码审查修复

- Fix 1: 批量清空加 IonAlert 确认对话框（不可逆文件删除需二次确认）
- Fix 2: 已完成/失败区域卡片禁用长按（disableLongPress prop），避免与 IonItemSliding 手势竞争
- Fix 3: DownloadTaskCard 添加 onUnmounted 清理两个 setTimeout 定时器
- Fix 4: 移除未使用的 funnelOutline import
- Fix 5: clearCompleted/clearFailed 改为 await syncDownloadState() 以 DB 为权威源，消除竞态闪烁
- Fix 6: 空状态下隐藏排序按钮

编译验证：vue-tsc --noEmit ✓ / vite build ✓ (9.90s)

## 2026-05-14 — 公开下载 EPERM 修复

- 问题：公开下载模式下操作文件报 `EPERM (Operation not permitted)`
- 原因：targetSdkVersion=36 强制 Scoped Storage，缺少 MANAGE_EXTERNAL_STORAGE 权限
- 修复：AndroidManifest 声明权限 + JmcomicPlugin 新增 requestManageStorage() + SettingPage 开启时自动检查

编译验证：vue-tsc --noEmit ✓ / vite build ✓ / assembleDebug ✓

---

## 2026-05-14 — 修复详情页标题从非搜索入口进入时一直显示"加载中"

### 概述
- 问题：从下载页"进入详情页"或直接输入 ID 进入 AlbumDetailPage 时，封面标题一直显示"加载中"，作者为空
- 原因：`albumTitle`/`coverUrl`/`albumAuthors` 三个 computed 仅从 `route.query` 读取，而 DownloadPage 跳转时不传 query 参数；API 返回的 `albumDetail` 数据（title/image/authors）未被使用
- 修复：`AlbumDetailPage.vue:90-95` — 三个 computed 改为优先使用 `albumDetail` API 数据，query 作为 fallback

### 变更文件
- `src/views/AlbumDetailPage.vue` — 修改 coverUrl/albumTitle/albumAuthors 计算属性

编译验证：vue-tsc --noEmit ✓

---

## 2026-05-14 — 修复下载页速度闪烁

### 概述
- 问题：下载过程中速度文本反复出现/消失（闪烁）
- 根因：
  1. Android 侧 speed=0 时不发送 speed 字段（`if (speed > 0)` 守卫）
  2. Vue 侧 `task.speed = data.speed` 在字段缺失时变为 `undefined`
  3. `speedText` computed 在 speed 为 0/undefined 时返回 `''`，`v-if="speedText"` 隐藏元素
- 修复：
  1. `JmcomicPlugin.java:1088-1089` — 始终发送 speed 字段（去掉 `if (speed > 0)` 守卫）
  2. `DownloadPage.vue:317` — 仅当 `data.speed > 0` 时更新，避免被 0/undefined 覆盖

### 变更文件
- `android/.../plugin/JmcomicPlugin.java` — 去掉 `if (speed > 0)` 守卫
- `src/views/DownloadPage.vue` — 仅当 speed > 0 时更新

编译验证：./gradlew assembleDebug ✓

---

## 2026-05-14 左侧侧边栏手势触发条件放宽

### 改动内容
1. 移除屏幕左半区域限制（原 `startX > window.innerWidth * 0.5`），改为屏幕任意位置向右滑动均可触发左侧菜单
2. 降低触发阈值：滑动距离 `24` → `12`，滑动速度 `0.25` → `0.15`

### 变更文件
- `src/App.vue` — 删除第 84 行位置限制及注释，降低第 89 行阈值

编译验证：npm run build ✓

## 2026-05-14 — 下载页多章节本子适配

### 概述
下载页新增多章节本子合并展示功能。同一本子的多个已下载章节合并为一张卡片，卡片显示已下载章节序号气泡；点击多章节卡进入章节选择页（ChapterSelectPage），支持已下载/全部章节切换。

### 变更文件
- `android/.../storage/DownloadDatabase.java` — DB v2→v3 升级，新增 `chapter_sort_order` 列；`updateTaskDetail()` 增加 sortOrder 参数；`cursorToTaskJson()` 输出增加 chapterSortOrder
- `android/.../plugin/JmcomicPlugin.java` — `downloadChapter` 中 `updateTaskDetail` 调用传入 `photo.getSortOrder()`
- `src/services/JmcomicTypes.ts` — `DownloadTask` 增加 `chapterSortOrder` 字段；新增 `CompletedGroup` 接口
- `src/views/DownloadPage.vue` — 新增 `completedGroups` 分组逻辑（按 albumId 分组，单章= single，多章= multi）；模板改用 group 渲染；新增 `onReadGroup`、`onOpenChapterSelect`、`onDeleteGroup`；popover 适配多章（标题/阅读/删除文案）
- `src/components/download/DownloadTaskCard.vue` — 新增 `downloadedChapters` prop；多章时渲染章节序号气泡；新增 `parseSortOrder` 回退函数
- `src/views/ChapterSelectPage.vue` — 新建章节选择页；已下载/全部章节模式切换；骨架屏加载；已下载章节高亮
- `src/router/index.ts` — 新增 `/album/:albumId/download-chapters` 路由

编译验证：vue-tsc ✓ | vite build ✓ | ./gradlew assembleDebug ✓

## 2026-05-15 — 阅读页进入时页面位置未复位修复

### 概述
修复进入阅读页时偶发页面整体向左偏移的问题。根因是 App.vue 的页面过渡动画 (`mode="out-in"`) 配合异步懒加载的 ReaderPage 组件时，CSS `transitionend` 可能在 ReaderPage 的 `onMounted` 异步操作触发 DOM 重排时提前结束或被跳过，导致 `transform: translateX(...)` 未正常复位到 `none`。

### 修复内容
在 `<transition>` 上添加 `@after-enter` 钩子，动画结束后强制清除 `transform`，确保页面位置始终复位。

### 变更文件
- `src/App.vue` — 第 6 行 `<transition>` 添加 `@after-enter="onAfterEnter"`；第 41 行后新增 `onAfterEnter` 函数；第 158 行动画时长/缓动统一调整为 0.22s cubic-bezier(0.22, 0, 0, 1)

编译验证：vue-tsc ✓

## 2026-05-15 — 修复收藏页手势抢占导致屏幕右半侧无法呼出左侧菜单

### 概述
在收藏页（或其他 keepAlive 页面上）的屏幕右半侧右划无法呼出左侧主菜单。根因是 FavoritePage.vue 的右侧菜单手势（priority=29）与 App.vue 的左侧菜单手势（priority=29）在同一目标元素 `#main-content` 上优先级相同，FavoritePage 的手势在 `canStart` 中只要 `startX >= 50%` 就返回 `true` 抢占触摸，不区分划动方向；导致右划被收藏页手势吞掉但不做任何处理。

### 修复内容
在 FavoritePage.vue 的 `canStart` 中增加方向判断：`deltaX > 6`（右划）时返回 `false`，放行给左侧菜单手势。

### 变更文件
- `src/views/FavoritePage.vue` — 第 440 行后新增 `if (detail.deltaX > 6) return false`

编译验证：vue-tsc ✓

## 2026-05-15 — 章节选择页重构：缩略图 + 工具栏标准化

### 概述
ChapterSelectPage 全面重构：
1. 章节卡片新增第一张图片缩略图（已下载章节从 FileStorage 加载，未下载的不显示）
2. 工具栏标准化：`IonBackButton` 放入 `ion-buttons slot="start"`，标题改用 `ion-title`，模式按钮放入 `ion-buttons slot="end"`，移除 absolute 定位
3. Grid 设置 `align-items: start`，确保有/无图片的卡片在同一行顶部对齐
4. 新增 `downloadedMap` computed，供"全部章节"模式通过 chapterId 查找 DownloadTask 获取 firstImageSortOrder

### 变更文件
- `src/views/ChapterSelectPage.vue` — 模板/脚本/样式全面重写

编译验证：vue-tsc ✓

## 2026-05-15 — 修复下载页多章节本子大小显示不准确

### 概述
多章节已完成组卡片显示的大小仅为第一个章节的大小，而非所有章节的合计。根因是卡片传入 `group.chapters[0]` 作为 task，`sizeText` 取 `task.totalSize` 只拿到首章大小。

### 修复内容
1. `DownloadTaskCard.vue` 新增可选 `totalSize` prop，`sizeText` 优先使用 prop 值再回退到 `task.totalSize`
2. `DownloadPage.vue` 多章节卡片传入 `:total-size="group.totalSize"`

### 变更文件
- `src/components/download/DownloadTaskCard.vue` — 新增 `totalSize` prop + `sizeText` 优先取值；`displayTotalPages` 多章节汇总页数
- `src/views/DownloadPage.vue` — 第 57 行卡片加 `:total-size="group.totalSize"`

编译验证：vue-tsc ✓

## 2026-05-15 — 章节 Tab 新增下载操作栏

### 概述
详情页章节 Tab：再次点击已选中章节时，章节序号文字淡出、下载图标+返回箭头在原位淡入（替换动画）。下载图标触发章节下载，返回箭头恢复文字。已下载/下载中章节的下载图标自动置灰（opacity 0.3）。通过 `getDownloadTasks()` 初始查询 + `downloadProgress` 事件监听实时追踪章节下载状态。

### 变更文件
- `src/components/album/AlbumChaptersTab.vue` — 新增 `Transition mode="out-in"` 文字↔按钮替换、`showActions`/`chapterDownloadStatuses` props、`download-chapter`/`dismiss-actions` emits、`isDownloadDisabled()` 判断
- `src/views/AlbumDetailPage.vue` — 新增 `showChapterActions`/`chapterDownloadStatuses` 状态、`refreshDownloadStatuses()` 函数、`onDownloadChapter()` 处理、`downloadProgress` 监听、修改 `selectChapter`/`switchTab`/`handleDownload`

编译验证：vue-tsc ✓

## 2026-05-15 — 章节 Tab 操作栏：高度修复 + 已下载章节高亮

### 概述
在已实现操作栏替换动画的基础上：
1. 修复按钮切换时卡片高度跳变：`.chapter-num` 添加 `line-height: 28px` 与按钮高度一致
2. 已下载章节视觉高亮：章节卡片新增 `.downloaded` class（`chapterDownloadStatuses.get(id) === 'completed'`），橙色边框+背景，与 ChapterSelectPage 风格统一
3. 已下载章节序号文字颜色统一为 `#e07030`

下载状态追踪链路已有（`refreshDownloadStatuses` + 进度监听），下载完成后高亮自动联动。

### 变更文件
- `src/components/album/AlbumChaptersTab.vue` — 模板 `.downloaded` class 绑定；`.chapter-num` 加 `line-height: 28px`；新增 `.chapter-card.downloaded` 样式（浅绿 `#f0faf3`/`#6dbf87`）；选中+已下载时橙色优先

编译验证：vue-tsc ✓

## 2026-05-15 — 应用启动时预热 JmApiClient

### 概述
首次搜索/获取章节信息时延迟明显，原因是 `JmApiClient` 采用懒加载创建。改为在 `load()` 末尾调用 `getClient()` 预热，将 client 初始化（HTTP 引擎、配置加载等）提前到应用启动阶段，消除首次 API 调用的冷启动等待。

### 变更文件
- `android/.../plugin/JmcomicPlugin.java` — `load()` 末尾新增 `getClient()` 预热调用

编译验证：vue-tsc ✓ | ./gradlew assembleDebug ✓

---

## 2026-05-17 — 修复 PluginMethod 同步网络调用阻塞

### 概述
修复从下载页进入详情页后快速返回时，下载页无下载信息的阻塞问题。

**根因**：Capacitor 框架将 `@PluginMethod` 串行分发到单一 `HandlerThread("CapacitorPlugins")`。当 `getAlbum()`/`getPhoto()` 等方法在该线程上执行同步 HTTP 请求时（1~5秒），阻塞了整个串行队列，导致后续所有 Plugin 调用（包括轻量的 SQLite 查询 `getDownloadTasks()`）都被排队等待。

**影响范围**：不仅下载页受影响，任何需要调用 Plugin 方法的页面在任意一个 HTTP 网络请求执行期间都会被阻塞。

### 修复内容

**JmcomicPlugin.java 改造：**

1. 新增 `apiExecutor`（FixedThreadPool, 6线程）+ `apiTimeoutExecutor`（ScheduledExecutor, 1线程）
2. 新增 `runOnApiExecutor()` 辅助方法：`setKeepAlive(true)` + 提交到 apiExecutor + 5分钟超时保护
3. 新增 `ApiTask` 函数式接口
4. 覆盖 `handleOnDestroy()`：shutdownNow 三个 executor
5. 8 个涉及 HTTP 网络请求的 @PluginMethod 改为异步执行（参数校验保持同步，HTTP 调用提交到 apiExecutor）：
   - `search()` / `categories()` / `getAlbum()` / `getPhoto()` / `getComments()` / `toggleAlbumLike()` / `getFavorites()` / `toggleAlbumFavorite()`
6. 纯 DB/文件操作的快速方法（`getDownloadTasks`、`cancelDownload` 等）保持在 HandlerThread 同步执行，不经过 apiExecutor

### 变更文件
- `android/.../plugin/JmcomicPlugin.java`

### 编译验证
- `./gradlew assembleDebug` ✓ BUILD SUCCESSFUL

---

## 2026-05-20 — 修复预览页离线图片加载优化

### 概述
修复已下载章节的预览页（PreviewAllPage/AlbumPreviewTab）不使用本地已下载图片、始终从网络重新下载缩略图的问题。修复后，已下载章节的预览页可以从本地磁盘文件直接生成缩略图，大幅提升加载速度。

### 问题分析
1. `ImageRegistry.handleRequest()` 对 `thumb` 类型跳过 FileStorage 查找
2. `JmcomicPlugin.preloadImages()` 只检查内存缓存中的原图，未检查 FileStorage 磁盘文件
3. `ChapterSelectPage.vue` 对已下载章节使用 `'image'`（完整原图）而非 `'thumb'`（压缩缩略图）

### 修复内容
**ImageRegistry.java：**
- 新增 `createThumbnail()` 公共静态方法（从 JmcomicPlugin 迁入，300px/JPEG70%）
- `handleRequest()` 对 `thumb` 类型增加 FileStorage 回退：从本地原图动态生成缩略图
- `guessFormatName()` 改为包级私有，供 JmcomicPlugin 调用

**JmcomicPlugin.java：**
- `preloadImages()` 在原图缓存检查后增加 FileStorage 检查：命中后用 imageExecutor 异步从本地原图生成缩略图+缓存原图
- 删除重复的 `createThumbnail()` 和常量 `THUMBNAIL_MAX_WIDTH`/`THUMBNAIL_JPEG_QUALITY`
- 清理不再使用的 import（Bitmap, BitmapFactory, ByteArrayOutputStream）

**ChapterSelectPage.vue：**
- 已下载章节缩略图：`'image'` → `'thumb'`（第33行、第129行）

### 变更文件
- `android/.../plugin/ImageRegistry.java`
- `android/.../plugin/JmcomicPlugin.java`
- `src/views/ChapterSelectPage.vue`

### 编译验证
- `npx vue-tsc --noEmit` ✓ 无错误
- `./gradlew assembleDebug` ✓ BUILD SUCCESSFUL

### 补充修复（同日）
- **handleRequest 原图缓存优化**：thumb 缓存 miss 后先查原图内存缓存（从内存生成缩略图），再查 FileStorage
- **AlbumDetailPage 骨架屏 race condition**：`previewImages.value = cachedItems` 直接赋值会覆盖 imageReady 事件已推送的图片（FileStorage 异步任务在 preloadImages 返回前就已完成），改为 Set 去重 + push 增量方式

### 编译验证
- `npx vue-tsc --noEmit` ✓ 无错误
- `./gradlew assembleDebug` ✓ BUILD SUCCESSFUL

---

## 2026-05-20 — 文件权限优化（最小权限 + API 版本兼容）

### 概述
将公开下载权限策略按 API 级别差异化，遵循最小权限原则：
- API 24-28：`WRITE_EXTERNAL_STORAGE` 运行时弹窗（新增声明，`maxSdkVersion="28"`）
- API 29：公开下载不支持（无可用权限），回退私有目录
- API 30+：`MANAGE_EXTERNAL_STORAGE` 跳系统设置（保留），不授权则自动回退

### 变更文件
- `android/app/src/main/AndroidManifest.xml` — 新增 `WRITE_EXTERNAL_STORAGE(maxSdkVersion=28)`，保留 `MANAGE_EXTERNAL_STORAGE`
- `android/.../plugin/JmcomicPlugin.java` — `load()` API 感知权限检查 + `requestManageStorage()` 三路分支重写 + `setDownloadPublic()` 权限前置检查 + `handlePermissionResult()` + `getInstance()`
- `android/.../MainActivity.java` — `onRequestPermissionsResult()` 转发到 JmcomicPlugin
- `src/services/JmcomicService.ts` — `requestManageStorage()` 返回值扩展 `permissionType`/`apiLevel`
- `src/views/SettingPage.vue` — 根据 `permissionType` 显示不同提示文案

### 补充修复（同日）
- API 24-28 回退时补充 `settingsDb.putString("download_public", "false")`，修复权限撤销后重启时 UI 开关与实际目录状态不一致的问题

### 编译验证
- `./gradlew assembleDebug` ✓ BUILD SUCCESSFUL

---

## 2026-05-20 — 修复 File.toPath() API 26 兼容性问题

### 概述
`JmcomicPlugin.java:780` 使用 `File.toPath()` 需要 API 26，但项目 `minSdkVersion=24`，导致 IDE 标红。改用 `Paths.get()` 替代。

### 变更文件
- `android/.../plugin/JmcomicPlugin.java` — `chapterDir.toPath()` → `Paths.get(chapterDir.getAbsolutePath())`，`chapterDir.getParentFile().toPath()` → `Paths.get(chapterDir.getParentFile().getAbsolutePath())`，新增 `import java.nio.file.Paths`
