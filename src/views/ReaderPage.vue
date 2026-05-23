<template>
  <IonPage>
    <div class="reader-root" @click="onRootClick">
      <!-- 顶部工具栏 -->
      <Transition name="toolbar-slide">
        <ReaderTopToolbar
          v-if="toolbarVisible"
          :title="chapterTitle"
          @back="goBack"
        />
      </Transition>

      <!-- 图片视图 -->
      <VerticalScrollView
        v-if="isVertical"
        ref="verticalViewRef"
        :image-map="imageMap"
        :total-count="totalCount"
        :initial-index="currentIndex"
        @update:current-index="onPageChange"
      />
      <HorizontalPageView
        v-else
        ref="horizontalViewRef"
        :image-map="imageMap"
        :total-count="totalCount"
        :current-index="currentIndex"
        @update:current-index="onPageChange"
        @toggle-toolbar="toggleToolbar"
      />

      <!-- 底部工具栏 -->
      <Transition name="toolbar-slide">
        <ReaderBottomToolbar
          v-if="toolbarVisible"
          :current="currentIndex + 1"
          :total="totalCount"
          :is-vertical="isVertical"
          @toggle-mode="toggleMode"
          @update:current="onProgressDrag"
          @update:current-input="onProgressInput"
          @progress-drag-start="onDragStart"
          @progress-drag-end="onDragEnd"
        />
      </Transition>
    </div>
  </IonPage>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { IonPage } from '@ionic/vue'
import type { PluginListenerHandle } from '@capacitor/core'
import {getImageUrl, JmcomicService, showToast} from '@/services/JmcomicService'
import type { ImageInfo, PhotoDetail, PreloadResult } from '@/services/JmcomicTypes'
import { SettingsStore } from '@/services/SettingsService'
import { HistoryService } from '@/services/HistoryService'
import ReaderTopToolbar from '@/components/reader/ReaderTopToolbar.vue'
import ReaderBottomToolbar from '@/components/reader/ReaderBottomToolbar.vue'
import VerticalScrollView from '@/components/reader/VerticalScrollView.vue'
import HorizontalPageView from '@/components/reader/HorizontalPageView.vue'

function getN(): number { return SettingsStore.getReaderPreloadPages() }
const N_FAST = 50           // 快速划动方向预加载半径
const M = 50               // 最大缓存窗口
const AUTO_HIDE_MS = 3000
const SPEED_THRESHOLD = 10  // 页/秒，超过视为快速划动
const EXPAND_EXPIRE_MS = 2000  // 快速划动停止后回退延时

const route = useRoute()
const router = useRouter()

// ---- 路由参数 ----
const albumId = computed(() => route.params.albumId as string)
const chapterId = computed(() => route.params.chapterId as string)
const chapterTitle = computed(() => (route.query.title as string) || chapterId.value)
const isOffline = computed(() => route.query.source === 'download')

// ---- 核心状态 ----
const isVertical = ref(true)
const initialTotal = Number(route.query.total) || 0
const currentIndex = ref(0)
const totalCount = ref(initialTotal)
const imageMap = ref<Map<number, string>>(new Map())  // sortOrder -> dataUrl
const toolbarVisible = ref(initialTotal > 0)  // 有预估 total 时立即显示工具栏，否则等数据加载完再显示
const isDragProgress = ref(false)
let photoDetail: PhotoDetail | null = null
let imageReadyListenerHandle: PluginListenerHandle | null = null
let loadedSortOrders = new Set<number>()     // 已加载完成的 sortOrder
let requestedSortOrders = new Set<number>()  // 已提交预加载的 sortOrder（避免重复请求）
let sortOrderToImage = new Map<number, ImageInfo>()  // sortOrder → ImageInfo 查找表
let lastWindowCenter = -1
let triggerRafId = 0
let autoHideTimer: ReturnType<typeof setTimeout> | null = null
let expandDirection: 'forward' | 'backward' | null = null
let lastScrollTime = 0
let lastScrollIndex = -1
let revertTimer: ReturnType<typeof setTimeout> | null = null
const verticalViewRef = ref<InstanceType<typeof VerticalScrollView> | null>(null)
const horizontalViewRef = ref<InstanceType<typeof HorizontalPageView> | null>(null)

// ---- 工具栏 ----
const toggleToolbar = () => {
  toolbarVisible.value = !toolbarVisible.value
  if (toolbarVisible.value) {
    resetAutoHide()
  }
}

const resetAutoHide = () => {
  if (autoHideTimer) clearTimeout(autoHideTimer)
  if (toolbarVisible.value && !isDragProgress.value) {
    autoHideTimer = setTimeout(() => {
      toolbarVisible.value = false
    }, AUTO_HIDE_MS)
  }
}

const onDragStart = () => {
  isDragProgress.value = true
}

const onDragEnd = () => {
  isDragProgress.value = false
  resetAutoHide()
}

// 点击中间 1/3 呼出/收起工具栏（纵向滚动模式下由 root 层处理）
const onRootClick = (ev: MouseEvent) => {
  if (!isVertical.value) return  // 横向模式下由 HorizontalPageView 处理
  const x = ev.clientX
  const sw = window.innerWidth
  if (x > sw * 0.3 && x < sw * 0.6) {
    toggleToolbar()
  }
}

// ---- 模式切换 ----
const toggleMode = () => {
  isVertical.value = !isVertical.value
}

// ---- 滑动窗口预加载 ----
const calcWindow = (center: number): number[] => {
  const result: number[] = []
  let start: number, end: number
  if (expandDirection === 'forward') {
    start = Math.max(0, center - getN())
    end = Math.min(totalCount.value, center + N_FAST + 1)
  } else if (expandDirection === 'backward') {
    start = Math.max(0, center - N_FAST)
    end = Math.min(totalCount.value, center + getN() + 1)
  } else {
    start = Math.max(0, center - getN())
    end = Math.min(totalCount.value, center + getN() + 1)
  }
  for (let i = start; i < end; i++) {
    result.push(i + 1)  // sortOrder = index + 1
  }
  return result
}

const applyImageMap = () => {
  imageMap.value = new Map(imageMap.value)
}

// 检查扩展方向区间内是否有正在加载的图片
const hasPendingInRange = (center: number, dir: 'forward' | 'backward'): boolean => {
  let checkStart: number, checkEnd: number
  if (dir === 'forward') {
    checkStart = center + getN() + 1
    checkEnd = Math.min(totalCount.value, center + N_FAST + 1)
  } else {
    checkStart = Math.max(0, center - N_FAST)
    checkEnd = center - getN()
  }
  for (let i = checkStart; i < checkEnd; i++) {
    const so = i + 1
    if (requestedSortOrders.has(so) && !loadedSortOrders.has(so)) return true
  }
  return false
}

const updateWindow = (center: number) => {
  if (!photoDetail) return
  const windowOrders = new Set(calcWindow(center))

  if (isOffline.value) {
    // 离线模式：直接设置 URL（ImageRegistry.handleRequest 自动从 FileStorage 加载）
    for (const so of windowOrders) {
      if (!loadedSortOrders.has(so)) {
        imageMap.value.set(so, getImageUrl(photoDetail.id, so, 'image'))
        loadedSortOrders.add(so)
      }
    }
  } else {
    // 在线模式：通过 preloadImages 预加载
    const toLoad: ImageInfo[] = []
    for (const so of windowOrders) {
      if (loadedSortOrders.has(so)) continue
      const img = sortOrderToImage.get(so)
      if (!img) continue
      toLoad.push(img)
      if (!requestedSortOrders.has(so)) {
        requestedSortOrders.add(so)
      }
    }

    if (toLoad.length > 0) {
      JmcomicService.preloadImages(photoDetail.id, toLoad, 'image').then((result: PreloadResult) => {
        for (const so of result.cached) {
          imageMap.value.set(so, getImageUrl(photoDetail!.id, so, 'image'))
          loadedSortOrders.add(so)
        }
        applyImageMap()
      }).catch(() => {})
    }
  }

  // 卸载窗口外的旧图片（扩展时清理范围覆盖扩展方向）
  const cleanBackward = expandDirection === 'backward' ? N_FAST : Math.floor(M / 2)
  const cleanForward = expandDirection === 'forward' ? N_FAST : Math.floor(M / 2)
  const cacheMin = Math.max(0, center - cleanBackward)
  const cacheMax = Math.min(totalCount.value, center + cleanForward + 1)
  const cacheSet = new Set<number>()
  for (let i = cacheMin; i < cacheMax; i++) cacheSet.add(i + 1)

  for (const so of loadedSortOrders) {
    if (!cacheSet.has(so)) {
      imageMap.value.delete(so)
      loadedSortOrders.delete(so)
    }
  }
  applyImageMap()
}

// ---- 页码变更（距离阈值触发） ----
const onPageChange = (index: number) => {
  if (index < 0 || index >= totalCount.value) return
  currentIndex.value = index
  resetAutoHide()

  // 划动速度检测
  const now = performance.now()
  if (lastScrollIndex >= 0) {
    const deltaSec = (now - lastScrollTime) / 1000
    if (deltaSec > 0) {
      const speed = Math.abs(index - lastScrollIndex) / deltaSec
      if (speed >= SPEED_THRESHOLD) {
        const dir: 'forward' | 'backward' = index > lastScrollIndex ? 'forward' : 'backward'
        if (!hasPendingInRange(index, dir)) {
          expandDirection = dir
        }
        // 刷新回退定时器
        if (revertTimer) clearTimeout(revertTimer)
        revertTimer = setTimeout(() => {
          expandDirection = null
        }, EXPAND_EXPIRE_MS)
      }
    }
  }
  lastScrollTime = now
  lastScrollIndex = index

  const threshold = isVertical.value ? 3 : 1
  if (Math.abs(index - lastWindowCenter) >= threshold) {
    lastWindowCenter = index
    updateWindow(index)
  }
}

// 进度条拖动跳转（直接响应）
const onProgressDrag = (page1Based: number) => {
  const index = page1Based - 1
  if (index < 0 || index >= totalCount.value) return
  currentIndex.value = index
  lastWindowCenter = index
  expandDirection = null
  if (revertTimer) { clearTimeout(revertTimer); revertTimer = null }
  updateWindow(index)
  if (isVertical.value) {
    verticalViewRef.value?.scrollToIndex(index)
  } else {
    horizontalViewRef.value?.scrollToIndex(index)
  }
}

// 进度条拖拽中（ion-input 每像素触发）：立即滚动 + 节流加载
let lastUpdateWindowTime = 0
const UPDATE_WINDOW_THROTTLE_MS = 150

const onProgressInput = (page1Based: number) => {
  const index = page1Based - 1
  if (index < 0 || index >= totalCount.value) return
  currentIndex.value = index
  if (isVertical.value) {
    verticalViewRef.value?.scrollToIndex(index)
  } else {
    horizontalViewRef.value?.scrollToIndex(index)
  }

  const now = performance.now()
  if (now - lastUpdateWindowTime > UPDATE_WINDOW_THROTTLE_MS) {
    lastUpdateWindowTime = now
    lastWindowCenter = index
    expandDirection = null
    if (revertTimer) { clearTimeout(revertTimer); revertTimer = null }
    updateWindow(index)
  }
}

// ---- 图片就绪监听 ----
const setupImageReadyListener = async () => {
  imageReadyListenerHandle = await JmcomicService.addImageReadyListener(chapterId.value, (sortOrder) => {
    imageMap.value.set(sortOrder, getImageUrl(chapterId.value, sortOrder, 'image'))
    loadedSortOrders.add(sortOrder)
    applyImageMap()
  })
}

// ---- 浏览历史记录 ----

const recordBrowseHistory = () => {
  const aId = albumId.value
  const cId = chapterId.value
  if (!aId) return
  const cTitle = chapterTitle.value

  JmcomicService.getAlbum(aId).then(album => {
    HistoryService.recordBrowse({
      albumId: aId,
      albumTitle: album.title,
      coverUrl: album.image,
      authors: (album.authors ?? []).join(' / '),
      chapterId: cId,
      chapterTitle: cTitle,
    })
  }).catch(() => {
    HistoryService.recordBrowse({
      albumId: aId,
      albumTitle: cTitle || aId,
      coverUrl: '',
      authors: '',
      chapterId: cId,
      chapterTitle: cTitle,
    })
  })
}

// ---- 返回 ----
const goBack = () => {
  if (window.history.length > 1) {
    router.back()
  } else {
    void router.push('/home')
  }
}

// ---- 生命周期 ----
onMounted(() => {
  // 重置章节级状态
  loadedSortOrders = new Set()
  requestedSortOrders = new Set()
  sortOrderToImage = new Map()
  expandDirection = null
  lastScrollTime = 0
  lastScrollIndex = -1
  if (revertTimer) { clearTimeout(revertTimer); revertTimer = null }

  if (isOffline.value) {
    // === 离线模式 ===
    JmcomicService.getDownloadedPhoto(albumId.value, chapterId.value).then((pd) => {
      photoDetail = pd

      const pageParam = Number(route.query.page)
      currentIndex.value = (pageParam > 0 ? pageParam : 1) - 1

      totalCount.value = pd.images.length

      toolbarVisible.value = true
      resetAutoHide()

      // 初始填充窗口：直接用 getImageUrl（不调 preloadImages、不注册 imageReadyListener）
      const initOrders = calcWindow(currentIndex.value)
      for (const so of initOrders) {
        if (!loadedSortOrders.has(so)) {
          imageMap.value.set(so, getImageUrl(pd.id, so, 'image'))
          loadedSortOrders.add(so)
        }
      }
      applyImageMap()

      nextTick(() => {
        if (isVertical.value) {
          verticalViewRef.value?.scrollToIndex(currentIndex.value)
        }
      })
      recordBrowseHistory()
    }).catch((e: any) => {
      // showToast('[ReaderPage] getDownloadedPhoto failed:', e?.message ?? e)
      totalCount.value = 0
      recordBrowseHistory()
    })
  } else {
    // === 在线模式 ===
    JmcomicService.getPhoto(chapterId.value).then((pd) => {
      photoDetail = pd
      sortOrderToImage = new Map(pd.images.map((i) => [i.sortOrder, i]))

      // 初始定位（必须在 totalCount 之前，防止 watch 用旧值）
      const pageParam = Number(route.query.page)
      currentIndex.value = (pageParam > 0 ? pageParam : 1) - 1

      totalCount.value = pd.images.length

      // 立即显示工具栏和骨架屏
      toolbarVisible.value = true
      resetAutoHide()

      // 注册监听（fire-and-forget）
      setupImageReadyListener().catch(() => {})

      // 初始加载窗口（fire-and-forget，图片通过 listener 渐进填充）
      const initOrders = calcWindow(currentIndex.value)
      const initImages = pd.images.filter((i) => initOrders.includes(i.sortOrder))
      for (const so of initOrders) {
        requestedSortOrders.add(so)
      }
      if (initImages.length > 0) {
        JmcomicService.preloadImages(pd.id, initImages, 'image').then((result) => {
          for (const so of result.cached) {
            imageMap.value.set(so, getImageUrl(pd.id, so, 'image'))
            loadedSortOrders.add(so)
          }
          applyImageMap()
        }).catch(() => {})
      }

      // 覆盖 totalCount 未变化（route.query.total == 实际总页数）导致 VerticalScrollView watch 不触发的情况
      nextTick(() => {
        if (isVertical.value) {
          verticalViewRef.value?.scrollToIndex(currentIndex.value)
        }
      })
      recordBrowseHistory()
    }).catch(() => {
      totalCount.value = 0
      recordBrowseHistory()
    })
  }
})

onUnmounted(() => {
  imageReadyListenerHandle?.remove()
  if (triggerRafId) cancelAnimationFrame(triggerRafId)
  if (autoHideTimer) clearTimeout(autoHideTimer)
  if (revertTimer) clearTimeout(revertTimer)
})
</script>

<style scoped>
.reader-root {
  position: relative;
  width: 100%;
  height: 100%;
  background: #000;
  overflow: hidden;
}

/* 工具栏过渡 */
.toolbar-slide-enter-active,
.toolbar-slide-leave-active {
  transition: opacity 0.22s ease, transform 0.22s ease;
}

.toolbar-slide-enter-from,
.toolbar-slide-leave-to {
  opacity: 0;
}

/* 顶栏从上滑入 */
:deep(.top-toolbar).toolbar-slide-enter-from,
:deep(.top-toolbar).toolbar-slide-leave-to {
  transform: translateY(-100%);
}

/* 底栏从下滑入 */
:deep(.bottom-toolbar).toolbar-slide-enter-from,
:deep(.bottom-toolbar).toolbar-slide-leave-to {
  transform: translateY(100%);
}
</style>
