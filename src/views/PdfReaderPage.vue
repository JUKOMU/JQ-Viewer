<template>
  <IonPage>
    <div class="reader-root" @click="onRootClick">
      <Transition name="toolbar-slide">
        <ReaderTopToolbar v-if="toolbarVisible" :title="displayTitle" @back="goBack"/>
      </Transition>

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

      <Transition name="toolbar-slide">
        <ReaderBottomToolbar
          v-if="toolbarVisible"
          :current="currentIndex + 1"
          :total="totalCount"
          @open-settings="settingsPanelVisible = true"
          @update:current="onProgressDrag"
          @update:current-input="onProgressInput"
          @progress-drag-start="onDragStart"
          @progress-drag-end="onDragEnd"
        />
      </Transition>

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
import {computed, inject, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, ref} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {IonPage} from '@ionic/vue'
import type {PluginListenerHandle} from '@capacitor/core'
import {JmcomicService, showToast} from '@/services/JmcomicService'
import {SettingsStore} from '@/services/SettingsService'
import {HistoryService} from '@/services/HistoryService'
import * as pdfjsLib from 'pdfjs-dist'
import {getPdfVirtualUrl} from '@/services/PdfReaderService'
import ReaderTopToolbar from '@/components/reader/ReaderTopToolbar.vue'
import ReaderBottomToolbar from '@/components/reader/ReaderBottomToolbar.vue'
import VerticalScrollView from '@/components/reader/VerticalScrollView.vue'
import HorizontalPageView from '@/components/reader/HorizontalPageView.vue'
import ReaderSettingsPanel from '@/components/reader/ReaderSettingsPanel.vue'

defineOptions({name: 'PdfReaderPage'})

pdfjsLib.GlobalWorkerOptions.workerSrc = new URL(
  'pdfjs-dist/build/pdf.worker.min.mjs',
  import.meta.url,
).href

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
const filePath = route.query.path as string
const displayTitle = computed(() => (route.query.title as string) || 'PDF')
const albumId = computed(() => (route.query.albumId as string) || '')
const chapterId = computed(() => (route.query.chapterId as string) || albumId.value)
const albumTitle = computed(() => (route.query.albumTitle as string) || '')
const authors = computed(() => (route.query.authors as string) || '')
const coverUrl = computed(() => (route.query.coverUrl as string) || '')

// ---- 核心状态 ----
const isVertical = ref(SettingsStore.getReaderDisplayMode() === 'vertical')
const currentIndex = ref(0)
const totalCount = ref(0)
const imageMap = ref<Map<number, string>>(new Map())
const toolbarVisible = ref(false)
const isDragProgress = ref(false)
const settingsPanelVisible = ref(false)

let volumeKeyListenerHandle: PluginListenerHandle | null = null
let readerRuntimeActive = false
let pdfDoc: pdfjsLib.PDFDocumentProxy | null = null
const renderedPages = new Map<number, string>()
let lastWindowCenter = -1
let expandDirection: 'forward' | 'backward' | null = null
let lastScrollTime = 0
let lastScrollIndex = -1
let revertTimer: ReturnType<typeof setTimeout> | null = null
let pendingRender: pdfjsLib.RenderTask | null = null
let activeRenderRange: {start: number; end: number; center: number} | null = null
let pendingSeekIndex: number | null = null
let dragPreviewTimer: ReturnType<typeof setTimeout> | null = null

const verticalViewRef = ref<InstanceType<typeof VerticalScrollView> | null>(null)
const horizontalViewRef = ref<InstanceType<typeof HorizontalPageView> | null>(null)

// ---- 工具栏 ----
const toggleToolbar = () => {
  toolbarVisible.value = !toolbarVisible.value
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

const onRootClick = (ev: MouseEvent) => {
  if (!isVertical.value) return
  if (settingsPanelVisible.value) return
  const x = ev.clientX
  const sw = window.innerWidth
  if (x > sw * 0.3 && x < sw * 0.6) {
    toggleToolbar()
  }
}

// ---- 显示模式切换 ----
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

// ---- 阅读器设置 ----
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
  JmcomicService.setReaderFullscreen(true).catch(() => {})
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

const activateReaderRuntime = () => {
  if (readerRuntimeActive) return
  readerRuntimeActive = true
  applyReaderSettings()
  JmcomicService.setReaderState(true, isVertical.value).catch(() => {})
  setupVolumeKeyListener().catch(() => {})
}

const deactivateReaderRuntime = () => {
  if (!readerRuntimeActive) return
  readerRuntimeActive = false
  volumeKeyListenerHandle?.remove()
  volumeKeyListenerHandle = null
  restoreSystemState()
}

// ---- 渲染分辨率 ----
const calcBaseScale = (viewport: pdfjsLib.PageViewport): number => {
  const containerWidth = window.innerWidth
  let scale = (containerWidth / viewport.width) * 2.0
  const maxScale = (containerWidth * 2.5) / viewport.width
  if (scale > maxScale) scale = maxScale
  return scale
}

// ---- PDF 页面渲染 ----
const renderPageToBlob = async (pageNum: number): Promise<string | null> => {
  if (!pdfDoc) return null
  try {
    const page = await pdfDoc.getPage(pageNum)
    const scale = calcBaseScale(page.getViewport({ scale: 1 }))
    const viewport = page.getViewport({ scale })

    const canvas = document.createElement('canvas')
    canvas.width = viewport.width
    canvas.height = viewport.height

    pendingRender = page.render({ canvas, viewport })
    await pendingRender.promise
    pendingRender = null
    page.cleanup()

    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob((b) => resolve(b), 'image/png'),
    )
    if (!blob) return null
    return URL.createObjectURL(blob)
  } catch {
    return null
  }
}

// ---- 窗口管理 ----
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
    if (!renderedPages.has(i + 1)) return true
  }
  return false
}

const updateWindow = (center: number) => {
  if (!pdfDoc) return
  const windowOrders = new Set(calcWindow(center))
  if (activeRenderRange) {
    for (let i = activeRenderRange.start; i < activeRenderRange.end; i++) {
      if (i >= 0 && i < totalCount.value) windowOrders.add(i + 1)
    }
  }

  const toRender: number[] = []
  for (const so of windowOrders) {
    if (!renderedPages.has(so)) {
      toRender.push(so)
      renderedPages.set(so, '') // 占位防重复请求
    }
  }

  // 异步渲染窗口内页面
  toRender.sort((a, b) => Math.abs(a - (center + 1)) - Math.abs(b - (center + 1)))
  for (const pageNum of toRender) {
    renderPageToBlob(pageNum).then((url) => {
      if (url) {
        renderedPages.set(pageNum, url)
        imageMap.value.set(pageNum, url)
        applyImageMap()
      }
    })
  }

  // 清理窗口外页面
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

  for (const [so, url] of renderedPages) {
    if (!cacheSet.has(so)) {
      if (url) URL.revokeObjectURL(url)
      renderedPages.delete(so)
      imageMap.value.delete(so)
    }
  }
  applyImageMap()
}

const renderDragPreviewPage = (center: number) => {
  if (!pdfDoc) return
  const pageNum = center + 1
  if (pageNum < 1 || pageNum > totalCount.value || renderedPages.has(pageNum)) return
  renderedPages.set(pageNum, '')
  renderPageToBlob(pageNum).then((url) => {
    if (url) {
      renderedPages.set(pageNum, url)
      imageMap.value.set(pageNum, url)
      applyImageMap()
    }
  })
}

const scheduleDragPreviewRender = (index: number) => {
  pendingSeekIndex = clampIndex(index)
  if (dragPreviewTimer) return
  dragPreviewTimer = setTimeout(() => {
    dragPreviewTimer = null
    if (!isDragProgress.value || pendingSeekIndex === null) return
    renderDragPreviewPage(pendingSeekIndex)
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
          updateWindow(index)
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

  if (source === 'slider-input') {
    scheduleDragPreviewRender(next)
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
  if (pendingRender && source === 'slider') {
    pendingRender.cancel()
    pendingRender = null
  }
  updateWindow(next)

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

// ---- 浏览历史 ----
const recordBrowseHistory = () => {
  const aId = albumId.value
  if (!aId) return
  const cId = chapterId.value
  const aTitle = albumTitle.value
  const aCover = coverUrl.value
  const aAuthors = authors.value
  const cTitle = displayTitle.value

  JmcomicService.getAlbum(aId)
    .then((album) => {
      HistoryService.recordBrowse({
        albumId: aId,
        albumTitle: album.title || aTitle,
        coverUrl: album.image || aCover,
        authors: (album.authors ?? []).join(' / ') || aAuthors,
        chapterId: cId,
        chapterTitle: cTitle,
      })
    })
    .catch(() => {
      HistoryService.recordBrowse({
        albumId: aId,
        albumTitle: aTitle || cTitle,
        coverUrl: aCover,
        authors: aAuthors,
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
onMounted(async () => {
  if (!filePath) {
    await showToast('缺少文件路径', 'danger')
    router.back()
    return
  }

  activateReaderRuntime()
  activeRenderRange = null
  pendingSeekIndex = null
  if (dragPreviewTimer) {
    clearTimeout(dragPreviewTimer)
    dragPreviewTimer = null
  }

  try {
    const url = getPdfVirtualUrl(filePath)
    const response = await fetch(url)
    if (!response.ok) throw new Error('文件加载失败')
    const arrayBuffer = await response.arrayBuffer()
    if (arrayBuffer.byteLength === 0) throw new Error('文件不存在或已失效')
    pdfDoc = await pdfjsLib.getDocument({ data: arrayBuffer }).promise
    const total = pdfDoc.numPages
    const pageParam = Number(route.query.page)
    const initIndex = Math.min(
      Math.max((pageParam > 0 ? pageParam : 1) - 1, 0),
      Math.max(0, total - 1),
    )
    currentIndex.value = initIndex
    totalCount.value = total
    updateReaderCurrentPage(initIndex + 1)
    toolbarVisible.value = true

    updateWindow(initIndex)

    nextTick(() => {
      if (isVertical.value) {
        verticalViewRef.value?.scrollToIndex(initIndex)
      }
    })

    recordBrowseHistory()
  } catch (e: any) {
    if (e instanceof TypeError && e.message === 'Failed to fetch') {
      await showToast('文件不存在或已失效', 'danger')
    } else {
      const msg = e?.message || ''
      if (/password/i.test(msg)) {
        await showToast('此 PDF 已加密，无法打开', 'danger')
      } else {
        await showToast('PDF 文件无法打开，可能已损坏', 'danger')
      }
    }
    router.back()
  }
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
  deactivateReaderRuntime()
  if (revertTimer) clearTimeout(revertTimer)
  if (dragPreviewTimer) clearTimeout(dragPreviewTimer)
  if (pendingRender) {
    pendingRender.cancel()
    pendingRender = null
  }

  for (const url of renderedPages.values()) {
    if (url) URL.revokeObjectURL(url)
  }
  renderedPages.clear()

  if (pdfDoc) {
    pdfDoc.destroy()
    pdfDoc = null
  }
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
