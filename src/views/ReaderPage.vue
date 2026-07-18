<template>
  <IonPage>
    <div class="reader-root" @click="onRootClick">
      <!-- 顶部工具栏 -->
      <Transition name="toolbar-slide">
        <ReaderTopToolbar v-if="toolbarVisible" :title="chapterTitle" @click.stop @back="goBack"/>
      </Transition>

      <!-- 图片视图 -->
      <VerticalScrollView
        v-if="isVertical"
        ref="verticalViewRef"
        :image-map="imageMap"
        :total-count="totalCount"
        :current-index="currentIndex"
        @update:current-index="onPageChange"
        @request-range="onVerticalRequestRange"
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
          @click.stop
          @open-settings="settingsPanelVisible = true"
          @update:current="onProgressDrag"
          @update:current-input="onProgressInput"
          @progress-drag-start="onDragStart"
          @progress-drag-end="onDragEnd"
        />
      </Transition>

      <!-- 阅读页设置面板 -->
      <ReaderSettingsPanel
        v-if="settingsPanelVisible"
        :is-vertical="isVertical"
        @close="settingsPanelVisible = false"
        @update:display-mode="onDisplayModeChange"
      />
    </div>
  </IonPage>
</template>

<script setup lang="ts">
import {computed, inject, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, ref, shallowRef} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {IonPage} from '@ionic/vue'
import type {PluginListenerHandle} from '@capacitor/core'
import {getImageUrl, JmcomicService, showToast} from '@/services/JmcomicService'
import type {ImageInfo, PhotoDetail, PreloadResult} from '@/services/JmcomicTypes'
import {SettingsStore} from '@/services/SettingsService'
import {HistoryService} from '@/services/HistoryService'
import {ReadingProgressService} from '@/services/ReadingProgressService'
import ReaderTopToolbar from '@/components/reader/ReaderTopToolbar.vue'
import ReaderBottomToolbar from '@/components/reader/ReaderBottomToolbar.vue'
import VerticalScrollView from '@/components/reader/VerticalScrollView.vue'
import HorizontalPageView from '@/components/reader/HorizontalPageView.vue'
import ReaderSettingsPanel from '@/components/reader/ReaderSettingsPanel.vue'

defineOptions({name: 'ReaderPage'})

function getN(): number {
  return SettingsStore.getReaderPreloadPages()
}

const N_FAST = 50
const M = 50
const SPEED_THRESHOLD = 10
const EXPAND_EXPIRE_MS = 2000
const DRAG_PREVIEW_DELAY_MS = 500

const route = useRoute()
const router = useRouter()

const updateReaderCurrentPage = inject<(page: number) => void>('updateReaderCurrentPage', () => {})

// ---- 路由参数 ----
const albumId = computed(() => route.params.albumId as string)
const chapterId = computed(() => route.params.chapterId as string)
const chapterTitle = computed(() => (route.query.title as string) || chapterId.value)
const isOffline = ref(route.query.source === 'download')

// ---- 核心状态 ----
const isVertical = ref(SettingsStore.getReaderDisplayMode() === 'vertical')
const currentIndex = ref(0)
const totalCount = ref(0)
const imageMap = shallowRef<Map<number, string>>(new Map())
const toolbarVisible = ref(true)
const isDragProgress = ref(false)
const settingsPanelVisible = ref(false)
const TOOLBAR_TAP_DELAY_MS = 280
const TOOLBAR_DOUBLE_TAP_DIST = 30
let toolbarTapTimer: ReturnType<typeof setTimeout> | null = null
let lastToolbarTapTime = 0
let lastToolbarTapX = 0
let lastToolbarTapY = 0
let photoDetail: PhotoDetail | null = null
let imageReadyListenerHandle: PluginListenerHandle | null = null
let volumeKeyListenerHandle: PluginListenerHandle | null = null
let readerRuntimeActive = false
let loadedSortOrders = new Set<number>()
let requestedSortOrders = new Set<number>()
let sortOrderToImage = new Map<number, ImageInfo>()
let lastWindowCenter = -1
const triggerRafId = 0
let expandDirection: 'forward' | 'backward' | null = null
let lastScrollTime = 0
let lastScrollIndex = -1
let revertTimer: ReturnType<typeof setTimeout> | null = null
let activeRenderRange: {start: number; end: number; center: number} | null = null
let pendingSeekIndex: number | null = null
let dragPreviewTimer: ReturnType<typeof setTimeout> | null = null
const verticalViewRef = ref<InstanceType<typeof VerticalScrollView> | null>(null)
const horizontalViewRef = ref<InstanceType<typeof HorizontalPageView> | null>(null)

// ---- 工具栏 ----
// 工具栏显示时仅恢复系统栏；阅读内容始终保持 edge-to-edge，不随系统栏改变尺寸。
const syncReaderFullscreen = () => {
  if (!readerRuntimeActive) return
  JmcomicService.setReaderFullscreen(!toolbarVisible.value).catch(() => {})
}

const setToolbarVisible = (visible: boolean) => {
  toolbarVisible.value = visible
  syncReaderFullscreen()
}

const toggleToolbar = () => {
  setToolbarVisible(!toolbarVisible.value)
}

const onDragStart = () => {
  isDragProgress.value = true
}

const onDragEnd = () => {
  isDragProgress.value = false
  if (dragPreviewTimer) {
    clearTimeout(dragPreviewTimer)
    dragPreviewTimer = null
  }
  if (pendingSeekIndex !== null) {
    goToIndex(pendingSeekIndex, 'slider')
  }
}

const clearToolbarTapTimer = () => {
  if (!toolbarTapTimer) return
  clearTimeout(toolbarTapTimer)
  toolbarTapTimer = null
}

// 纵向模式由页面根层区分单击与双击；横向模式由 HorizontalPageView 自行处理
const onRootClick = (ev: MouseEvent) => {
  if (!isVertical.value) return
  if (settingsPanelVisible.value) return

  const now = Date.now()
  const tapDist = Math.abs(ev.clientX - lastToolbarTapX) + Math.abs(ev.clientY - lastToolbarTapY)
  const isDoubleTap = toolbarTapTimer
    && now - lastToolbarTapTime < TOOLBAR_TAP_DELAY_MS
    && tapDist < TOOLBAR_DOUBLE_TAP_DIST

  if (isDoubleTap) {
    clearToolbarTapTimer()
  } else {
    if (toolbarTapTimer) {
      clearToolbarTapTimer()
      toggleToolbar()
    }
    toolbarTapTimer = setTimeout(() => {
      toolbarTapTimer = null
      if (isVertical.value && !settingsPanelVisible.value) toggleToolbar()
    }, TOOLBAR_TAP_DELAY_MS)
  }

  lastToolbarTapTime = now
  lastToolbarTapX = ev.clientX
  lastToolbarTapY = ev.clientY
}

const onDisplayModeChange = (vertical: boolean) => {
  const targetIndex = currentIndex.value
  isVertical.value = vertical
  syncReaderState()
  nextTick(() => {
    requestAnimationFrame(() => {
      goToIndex(targetIndex, 'mode')
    })
  })
}

const syncReaderState = () => {
  JmcomicService.setReaderState(true, isVertical.value).catch(() => {})
}

// ---- 阅读器设置应用 / 恢复 ----

const applyReaderSettings = () => {
  const orientation = SettingsStore.getReaderScreenOrientation()
  if (orientation !== 'auto') {
    JmcomicService.setReaderScreenOrientation(orientation).catch(() => {})
  }
  const brightness = SettingsStore.getReaderBrightness()
  if (brightness >= 0) {
    JmcomicService.setReaderBrightness(brightness).catch(() => {})
  }
  if (SettingsStore.getReaderKeepScreenOn()) {
    JmcomicService.setReaderKeepScreenOn(true).catch(() => {})
  }
  syncReaderFullscreen()
}

const restoreSystemState = () => {
  JmcomicService.setReaderBrightness(-1).catch(() => {})
  JmcomicService.setReaderScreenOrientation('auto').catch(() => {})
  JmcomicService.setReaderKeepScreenOn(false).catch(() => {})
  JmcomicService.setReaderFullscreen(false).catch(() => {})
  JmcomicService.setReaderState(false, false).catch(() => {})
}

// ---- 音量键 ----
const setupVolumeKeyListener = async () => {
  if (volumeKeyListenerHandle) return
  volumeKeyListenerHandle = await JmcomicService.addVolumeKeyListener((direction) => {
    if (isVertical.value) {
      const scrollAmount = window.innerHeight / 3
      const el = verticalViewRef.value
      if (el && 'containerRef' in el) {
        const container = (el as any).containerRef as HTMLElement | null
        if (container) {
          container.scrollBy({
            top: direction === 'up' ? -scrollAmount : scrollAmount,
            behavior: 'smooth',
          })
        }
      }
    } else {
      if (direction === 'up' && currentIndex.value > 0) {
        goToIndex(currentIndex.value - 1, 'volume')
      } else if (direction === 'down' && currentIndex.value < totalCount.value - 1) {
        goToIndex(currentIndex.value + 1, 'volume')
      }
    }
  })
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
    result.push(i + 1)
  }
  return result
}

const prioritizeSortOrders = (orders: number[], center: number): number[] => {
  const centerSortOrder = center + 1
  return [...new Set(orders)]
    .filter((so) => so >= 1 && so <= totalCount.value)
    .sort((a, b) => Math.abs(a - centerSortOrder) - Math.abs(b - centerSortOrder) || a - b)
}

const calcPriorityWindow = (center: number): number[] => {
  const orders = [center + 1, ...calcWindow(center)]
  if (activeRenderRange) {
    for (let i = activeRenderRange.start; i < activeRenderRange.end; i++) {
      orders.push(i + 1)
    }
  }
  return prioritizeSortOrders(orders, center)
}

const applyImageMap = () => {
  imageMap.value = new Map(imageMap.value)
}

const clampIndex = (index: number) => {
  if (totalCount.value <= 0) return 0
  return Math.min(totalCount.value - 1, Math.max(0, index))
}

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

const updateWindow = (center: number, replacePending = false) => {
  if (!photoDetail) return
  const windowOrders = calcPriorityWindow(center)

  if (isOffline.value) {
    for (const so of windowOrders) {
      if (!loadedSortOrders.has(so)) {
        imageMap.value.set(so, getImageUrl(photoDetail.id, so, 'image'))
        loadedSortOrders.add(so)
      }
    }
  } else {
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
      JmcomicService.preloadImages(photoDetail.id, toLoad, 'image', {replacePending})
        .then((result: PreloadResult) => {
          for (const so of result.cached) {
            imageMap.value.set(so, getImageUrl(photoDetail!.id, so, 'image'))
            loadedSortOrders.add(so)
          }
          applyImageMap()
        })
        .catch(() => {
        })
    }
  }

  const cleanBackward = expandDirection === 'backward' ? N_FAST : Math.floor(M / 2)
  const cleanForward = expandDirection === 'forward' ? N_FAST : Math.floor(M / 2)
  const cacheMin = Math.max(0, center - cleanBackward)
  const cacheMax = Math.min(totalCount.value, center + cleanForward + 1)
  const cacheSet = new Set<number>()
  for (let i = cacheMin; i < cacheMax; i++) cacheSet.add(i + 1)
  if (activeRenderRange) {
    for (let i = activeRenderRange.start; i < activeRenderRange.end; i++) {
      if (i >= 0 && i < totalCount.value) cacheSet.add(i + 1)
    }
  }
  for (const so of calcWindow(currentIndex.value)) cacheSet.add(so)

  for (const so of loadedSortOrders) {
    if (!cacheSet.has(so)) {
      imageMap.value.delete(so)
      loadedSortOrders.delete(so)
    }
  }
  applyImageMap()
}

const loadDragPreviewImage = (center: number) => {
  if (!photoDetail) return
  const so = center + 1
  if (so < 1 || so > totalCount.value || loadedSortOrders.has(so) || requestedSortOrders.has(so)) return

  if (isOffline.value) {
    imageMap.value.set(so, getImageUrl(photoDetail.id, so, 'image'))
    loadedSortOrders.add(so)
    applyImageMap()
    return
  }

  const img = sortOrderToImage.get(so)
  if (!img) return
  requestedSortOrders.add(so)
  JmcomicService.preloadImages(photoDetail.id, [img], 'image', {replacePending: true})
    .then((result: PreloadResult) => {
      for (const cachedSo of result.cached) {
        imageMap.value.set(cachedSo, getImageUrl(photoDetail!.id, cachedSo, 'image'))
        loadedSortOrders.add(cachedSo)
      }
      applyImageMap()
    })
    .catch(() => {
    })
}

const scheduleDragPreviewLoad = (index: number) => {
  pendingSeekIndex = clampIndex(index)
  if (dragPreviewTimer) return
  dragPreviewTimer = setTimeout(() => {
    dragPreviewTimer = null
    if (!isDragProgress.value || pendingSeekIndex === null) return
    loadDragPreviewImage(pendingSeekIndex)
    if (pendingSeekIndex !== currentIndex.value) {
      scheduleDragPreviewLoad(pendingSeekIndex)
    }
  }, DRAG_PREVIEW_DELAY_MS)
}

const onVerticalRequestRange = (range: {start: number; end: number; center: number}) => {
  activeRenderRange = {
    start: Math.max(0, range.start),
    end: Math.min(totalCount.value, range.end),
    center: clampIndex(range.center),
  }
  if (isDragProgress.value) return
  lastWindowCenter = activeRenderRange.center
  updateWindow(activeRenderRange.center)
}

// ---- 页码变更 ----
type PageChangeSource = 'scroll' | 'slider' | 'slider-input' | 'mode' | 'volume' | 'route'

const updateScrollVelocity = (index: number) => {
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
        if (revertTimer) clearTimeout(revertTimer)
        revertTimer = setTimeout(() => {
          expandDirection = null
        }, EXPAND_EXPIRE_MS)
      }
    }
  }
  lastScrollTime = now
  lastScrollIndex = index
}

const moveReaderViewToIndex = (index: number) => {
  if (isVertical.value) {
    verticalViewRef.value?.scrollToIndex(index)
  } else {
    horizontalViewRef.value?.scrollToIndex(index)
  }
}

const goToIndex = (index: number, source: PageChangeSource) => {
  if (index < 0 || index >= totalCount.value) return
  const next = clampIndex(index)
  currentIndex.value = next
  updateReaderCurrentPage(next + 1)
  ReadingProgressService.record(albumId.value, chapterId.value, next + 1, totalCount.value)

  if (source === 'slider-input') {
    scheduleDragPreviewLoad(next)
    moveReaderViewToIndex(next)
    return
  }

  pendingSeekIndex = null
  if (dragPreviewTimer) {
    clearTimeout(dragPreviewTimer)
    dragPreviewTimer = null
  }

  if (source === 'scroll') {
    updateScrollVelocity(next)
    const threshold = isVertical.value ? 3 : 1
    if (Math.abs(next - lastWindowCenter) >= threshold) {
      lastWindowCenter = next
      updateWindow(next)
    }
    return
  }

  if (revertTimer) {
    clearTimeout(revertTimer)
    revertTimer = null
  }
  expandDirection = null

  lastWindowCenter = next
  updateWindow(next, source === 'slider' || source === 'route')

  moveReaderViewToIndex(next)
}

const onPageChange = (index: number) => {
  goToIndex(index, 'scroll')
}

const onProgressDrag = (page1Based: number) => {
  goToIndex(page1Based - 1, 'slider')
}

const onProgressInput = (page1Based: number) => {
  if (!isDragProgress.value) return
  goToIndex(page1Based - 1, 'slider-input')
}

// ---- 图片就绪监听 ----
const setupImageReadyListener = async () => {
  if (imageReadyListenerHandle) return
  imageReadyListenerHandle = await JmcomicService.addImageReadyListener(
    chapterId.value,
    (sortOrder) => {
      imageMap.value.set(sortOrder, getImageUrl(chapterId.value, sortOrder, 'image'))
      loadedSortOrders.add(sortOrder)
      applyImageMap()
    },
  )
}

const activateReaderRuntime = () => {
  if (readerRuntimeActive) return
  readerRuntimeActive = true
  applyReaderSettings()
  JmcomicService.setReaderState(true, isVertical.value).catch(() => {})
  setupVolumeKeyListener().catch(() => {})
  if (!isOffline.value && photoDetail) {
    setupImageReadyListener().catch(() => {})
  }
}

const deactivateReaderRuntime = () => {
  if (!readerRuntimeActive) return
  readerRuntimeActive = false
  clearToolbarTapTimer()
  imageReadyListenerHandle?.remove()
  imageReadyListenerHandle = null
  volumeKeyListenerHandle?.remove()
  volumeKeyListenerHandle = null
  restoreSystemState()
}

// ---- 浏览历史记录 ----
const recordBrowseHistory = () => {
  const aId = albumId.value
  const cId = chapterId.value
  if (!aId) return
  const cTitle = chapterTitle.value

  JmcomicService.getAlbum(aId)
    .then((album) => {
      HistoryService.recordBrowse({
        albumId: aId,
        albumTitle: album.title,
        coverUrl: album.image,
        authors: (album.authors ?? []).join(' / '),
        chapterId: cId,
        chapterTitle: cTitle,
      })
    })
    .catch(() => {
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

const applyPhotoBaseState = (pd: PhotoDetail) => {
  photoDetail = pd

  const total = pd.images.length
  const initialPage = ReadingProgressService.getInitialPage(
    route.query.page,
    albumId.value,
    chapterId.value,
    total,
  )
  currentIndex.value = Math.min(
    Math.max(initialPage - 1, 0),
    Math.max(0, total - 1),
  )
  totalCount.value = total
  updateReaderCurrentPage(currentIndex.value + 1)
  ReadingProgressService.record(albumId.value, chapterId.value, currentIndex.value + 1, total)
  setToolbarVisible(true)
}

const scrollToInitialIndex = () => {
  nextTick(() => {
    if (isVertical.value) {
      verticalViewRef.value?.scrollToIndex(currentIndex.value)
    }
  })
}

const loadDownloadedChapter = async (): Promise<boolean> => {
  try {
    const pd = await JmcomicService.getDownloadedPhoto(albumId.value, chapterId.value)
    isOffline.value = true
    applyPhotoBaseState(pd)

    const initOrders = calcWindow(currentIndex.value)
    const priorityInitOrders = prioritizeSortOrders(initOrders, currentIndex.value)
    for (const so of priorityInitOrders) {
      if (!loadedSortOrders.has(so)) {
        imageMap.value.set(so, getImageUrl(pd.id, so, 'image'))
        loadedSortOrders.add(so)
      }
    }
    applyImageMap()
    scrollToInitialIndex()
    recordBrowseHistory()
    return true
  } catch {
    isOffline.value = false
    return false
  }
}

const loadOnlineChapter = async () => {
  try {
    const pd = await JmcomicService.getPhoto(chapterId.value)
    isOffline.value = false
    applyPhotoBaseState(pd)
    sortOrderToImage = new Map(pd.images.map((i) => [i.sortOrder, i]))

    if (readerRuntimeActive) {
      setupImageReadyListener().catch(() => {
      })
    }

    const initOrders = prioritizeSortOrders(calcWindow(currentIndex.value), currentIndex.value)
    const initOrderSet = new Set(initOrders)
    const initImages = initOrders
      .map((so) => sortOrderToImage.get(so))
      .filter((i): i is ImageInfo => Boolean(i))
    for (const so of initOrderSet) {
      requestedSortOrders.add(so)
    }
    if (initImages.length > 0) {
      JmcomicService.preloadImages(pd.id, initImages, 'image')
        .then((result) => {
          for (const so of result.cached) {
            imageMap.value.set(so, getImageUrl(pd.id, so, 'image'))
            loadedSortOrders.add(so)
          }
          applyImageMap()
        })
        .catch(() => {
        })
    }

    scrollToInitialIndex()
    recordBrowseHistory()
  } catch {
    showToast('章节加载失败', 'danger')
    totalCount.value = 0
    recordBrowseHistory()
  }
}

const loadChapter = async () => {
  const loadedFromDownload = await loadDownloadedChapter()
  if (loadedFromDownload) return

  if (route.query.source === 'download') {
    showToast('离线章节加载失败', 'danger')
  }
  await loadOnlineChapter()
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
  activeRenderRange = null
  pendingSeekIndex = null
  if (dragPreviewTimer) {
    clearTimeout(dragPreviewTimer)
    dragPreviewTimer = null
  }
  expandDirection = null
  lastScrollTime = 0
  lastScrollIndex = -1
  if (revertTimer) {
    clearTimeout(revertTimer)
    revertTimer = null
  }

  activateReaderRuntime()
  void loadChapter()

})

onActivated(() => {
  activateReaderRuntime()
  nextTick(() => {
    goToIndex(currentIndex.value, 'route')
  })
})

onDeactivated(() => {
  deactivateReaderRuntime()
})

onUnmounted(() => {
  clearToolbarTapTimer()
  deactivateReaderRuntime()
  if (triggerRafId) cancelAnimationFrame(triggerRafId)
  if (revertTimer) clearTimeout(revertTimer)
  if (dragPreviewTimer) clearTimeout(dragPreviewTimer)
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

.toolbar-slide-enter-active,
.toolbar-slide-leave-active {
  transition: opacity 0.22s ease,
  transform 0.22s ease;
}

.toolbar-slide-enter-from,
.toolbar-slide-leave-to {
  opacity: 0;
}

:deep(.top-toolbar).toolbar-slide-enter-from,
:deep(.top-toolbar).toolbar-slide-leave-to {
  transform: translateY(-100%);
}

:deep(.bottom-toolbar).toolbar-slide-enter-from,
:deep(.bottom-toolbar).toolbar-slide-leave-to {
  transform: translateY(100%);
}
</style>
