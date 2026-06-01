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
import {computed, nextTick, onMounted, onUnmounted, ref} from 'vue'
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

pdfjsLib.GlobalWorkerOptions.workerSrc = new URL(
  'pdfjs-dist/build/pdf.worker.min.mjs',
  import.meta.url,
).href

defineOptions({name: 'PdfReaderPage'})

function getN(): number {
  return SettingsStore.getReaderPreloadPages()
}

const N_FAST = 50
const M = 50
const SPEED_THRESHOLD = 10
const EXPAND_EXPIRE_MS = 2000
const OVERSCALE_RERENDER = 1.3

const route = useRoute()
const router = useRouter()

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
let pdfDoc: pdfjsLib.PDFDocumentProxy | null = null
let renderedPages = new Map<number, string>()
let lastWindowCenter = -1
let expandDirection: 'forward' | 'backward' | null = null
let lastScrollTime = 0
let lastScrollIndex = -1
let revertTimer: ReturnType<typeof setTimeout> | null = null
let pendingRender: pdfjsLib.RenderTask | null = null

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
  isVertical.value = vertical
  syncReaderState()
  nextTick(() => {
    if (vertical) {
      verticalViewRef.value?.scrollToIndex(currentIndex.value)
    } else {
      horizontalViewRef.value?.scrollToIndex(currentIndex.value)
    }
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
        horizontalViewRef.value?.scrollToIndex(currentIndex.value - 1)
        onPageChange(currentIndex.value - 1)
      } else if (direction === 'down' && currentIndex.value < totalCount.value - 1) {
        horizontalViewRef.value?.scrollToIndex(currentIndex.value + 1)
        onPageChange(currentIndex.value + 1)
      }
    }
  })
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
  const windowOrders = calcWindow(center)

  const toRender: number[] = []
  for (const so of windowOrders) {
    if (!renderedPages.has(so)) {
      toRender.push(so)
      renderedPages.set(so, '') // 占位防重复请求
    }
  }

  // 异步渲染窗口内页面
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

  for (const [so, url] of renderedPages) {
    if (!cacheSet.has(so)) {
      if (url) URL.revokeObjectURL(url)
      renderedPages.delete(so)
      imageMap.value.delete(so)
    }
  }
  applyImageMap()
}

// ---- 页码变更 ----
const onPageChange = (index: number) => {
  if (index < 0 || index >= totalCount.value) return
  currentIndex.value = index

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

  const threshold = isVertical.value ? 3 : 1
  if (Math.abs(index - lastWindowCenter) >= threshold) {
    lastWindowCenter = index
    updateWindow(index)
  }
}

const onProgressDrag = (page1Based: number) => {
  if (!isDragProgress.value) return
  const index = page1Based - 1
  if (index < 0 || index >= totalCount.value) return
  currentIndex.value = index
  lastWindowCenter = index
  expandDirection = null
  if (revertTimer) {
    clearTimeout(revertTimer)
    revertTimer = null
  }
  // 取消当前渲染，优先落点页
  if (pendingRender) {
    pendingRender.cancel()
    pendingRender = null
  }
  updateWindow(index)
  if (isVertical.value) {
    verticalViewRef.value?.scrollToIndex(index)
  } else {
    horizontalViewRef.value?.scrollToIndex(index)
  }
}

let lastUpdateWindowTime = 0
const UPDATE_WINDOW_THROTTLE_MS = 150

const onProgressInput = (page1Based: number) => {
  if (!isDragProgress.value) return
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
    if (revertTimer) {
      clearTimeout(revertTimer)
      revertTimer = null
    }
    if (pendingRender) {
      pendingRender.cancel()
      pendingRender = null
    }
    updateWindow(index)
  }
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

  applyReaderSettings()
  JmcomicService.setReaderState(true, isVertical.value).catch(() => {})

  try {
    const url = getPdfVirtualUrl(filePath)
    const response = await fetch(url)
    if (!response.ok) throw new Error('文件加载失败')
    const arrayBuffer = await response.arrayBuffer()
    if (arrayBuffer.byteLength === 0) throw new Error('文件不存在或已失效')
    pdfDoc = await pdfjsLib.getDocument({ data: arrayBuffer }).promise
    totalCount.value = pdfDoc.numPages
    toolbarVisible.value = true

    updateWindow(0)

    nextTick(() => {
      if (isVertical.value) {
        verticalViewRef.value?.scrollToIndex(0)
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

  setupVolumeKeyListener().catch(() => {})
})

onUnmounted(() => {
  volumeKeyListenerHandle?.remove()
  if (revertTimer) clearTimeout(revertTimer)
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

  restoreSystemState()
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
