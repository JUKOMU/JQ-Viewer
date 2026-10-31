<template>
  <IonPage>
    <div
      ref="readerRoot"
      class="reader-root"
      :tabindex="isDesktopRuntime ? -1 : undefined"
      @click="onRootClick"
    >
      <!-- 顶部工具栏 -->
      <Transition name="toolbar-slide">
        <ReaderTopToolbar
          v-if="toolbarVisible"
          :title="chapterTitle"
          :show-desktop-controls="isDesktopRuntime"
          :is-fullscreen="isFullscreen"
          @click.stop="restoreReaderFocus"
          @back="goBack"
          @zoom-in="zoomIn"
          @zoom-out="zoomOut"
          @reset-zoom="resetZoom"
          @toggle-fullscreen="toggleFullscreen"
        />
      </Transition>

      <!-- 图片视图 -->
      <VerticalScrollView
        v-if="isVertical"
        ref="verticalViewRef"
        :image-map="imageMap"
        :failed-sort-orders="failedSortOrders"
        :retrying-sort-orders="retryingSortOrders"
        :total-count="totalCount"
        :current-index="currentIndex"
        :enable-mouse-controls="isDesktopRuntime"
        :width-percent="readerWidthPercent"
        @update:current-index="onPageChange"
        @request-range="onVerticalRequestRange"
        @reached-bottom="scheduleToolbarAtReaderEnd"
        @image-error="onImageError"
        @retry-images="retryFailedImages"
      />
      <HorizontalPageView
        v-else
        ref="horizontalViewRef"
        :image-map="imageMap"
        :failed-sort-orders="failedSortOrders"
        :retrying-sort-orders="retryingSortOrders"
        :total-count="totalCount"
        :current-index="currentIndex"
        :enable-mouse-controls="isDesktopRuntime"
        :width-percent="readerWidthPercent"
        @update:current-index="onPageChange"
        @toggle-toolbar="toggleToolbar"
        @image-error="onImageError"
        @retry-images="retryFailedImages"
      />

      <!-- 底部工具栏 -->
      <Transition name="toolbar-slide">
        <ReaderBottomToolbar
          v-if="toolbarVisible"
          :current="currentIndex + 1"
          :total="totalCount"
          :chapters="chapters"
          :current-chapter-id="chapterId"
          @click.stop
          @open-settings="settingsPanelVisible = true"
          @select-chapter="switchChapter"
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
        :show-width-control="isDesktopRuntime"
        :width-percent="readerWidthPercent"
        @update:width-percent="readerWidthPercent = $event"
        @close="settingsPanelVisible = false"
        @update:display-mode="onDisplayModeChange"
      />
    </div>
  </IonPage>
</template>

<script setup lang="ts">
import {
  computed,
  inject,
  nextTick,
  onActivated,
  onDeactivated,
  onMounted,
  onUnmounted,
  ref,
  shallowRef,
  watch,
} from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { IonPage } from '@ionic/vue'
import type { ListenerHandle } from '@/runtime/BackendEvents'
import { getRuntime } from '@/runtime/runtimeContext'
import { getImageUrl, JmcomicService, sanitizeError, showToast } from '@/services/JmcomicService'
import type { ImageInfo, PhotoDetail, PhotoMeta, PreloadResult } from '@/services/JmcomicTypes'
import { SettingsStore } from '@/services/SettingsService'
import { HistoryService } from '@/services/HistoryService'
import { ReadingProgressService } from '@/services/ReadingProgressService'
import ReaderTopToolbar from '@/components/reader/ReaderTopToolbar.vue'
import ReaderBottomToolbar from '@/components/reader/ReaderBottomToolbar.vue'
import VerticalScrollView from '@/components/reader/VerticalScrollView.vue'
import HorizontalPageView from '@/components/reader/HorizontalPageView.vue'
import ReaderSettingsPanel from '@/components/reader/ReaderSettingsPanel.vue'
import { useDesktopReaderControls } from '@/composables/useDesktopReaderControls'

defineOptions({ name: 'ReaderPage' })

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
const runtime = getRuntime()
const readerCapabilities = runtime.services.reader
const isDesktopRuntime = runtime.platform !== 'android'

const updateReaderCurrentPage = inject<(page: number) => void>('updateReaderCurrentPage', () => {})

// ---- 路由参数 ----
const albumId = computed(() => route.params.albumId as string)
const chapterId = computed(() => route.params.chapterId as string)
const chapterTitle = computed(() => (route.query.title as string) || chapterId.value)
const isOffline = ref(route.query.source === 'download')

// ---- 核心状态 ----
const isVertical = ref(SettingsStore.getReaderDisplayMode() === 'vertical')
const readerWidthPercent = ref(isDesktopRuntime ? SettingsStore.getReaderWidthPercent() : null)
const currentIndex = ref(0)
const totalCount = ref(0)
const imageMap = shallowRef<Map<number, string>>(new Map())
const failedSortOrders = shallowRef<Set<number>>(new Set())
const retryingSortOrders = shallowRef<Set<number>>(new Set())
const chapters = ref<PhotoMeta[]>([])
const toolbarVisible = ref(true)
const isDragProgress = ref(false)
const settingsPanelVisible = ref(false)
const readerRoot = ref<HTMLElement | null>(null)
const TOOLBAR_TAP_DELAY_MS = 280
const TOOLBAR_DOUBLE_TAP_DIST = 30
const AUTO_SHOW_TOOLBAR_DELAY_MS = 1000
let toolbarTapTimer: ReturnType<typeof setTimeout> | null = null
let autoShowToolbarTimer: ReturnType<typeof setTimeout> | null = null
let lastToolbarTapTime = 0
let lastToolbarTapX = 0
let lastToolbarTapY = 0
let photoDetail: PhotoDetail | null = null
let chapterLoadVersion = 0
let chapterStateKey = ''
let chapterStateAlbumId = ''
let imageReadyListenerHandle: ListenerHandle | null = null
let imageFailedListenerHandle: ListenerHandle | null = null
let imageReadyListenerSetupPromise: Promise<void> | null = null
let imageReadyListenerSetupId = 0
let volumeKeyListenerHandle: ListenerHandle | null = null
let readerRuntimeActive = false
let readerRuntimeSessionId = 0
let loadedSortOrders = new Set<number>()
let requestedSortOrders = new Map<
  number,
  { requestId: number; priority: number; sessionId: string; previousSessionId?: string }
>()
let preloadRequestSequence = 0
let activePreloadSessionIds = new Set<string>()
let sortOrderToImage = new Map<number, ImageInfo>()
let retryBatchSequence = 0
let activeRetryBatchId: number | null = null
let retryUrlRevision = 0
let activeAllowedSortOrders = new Set<number>()
let activeDragPreviewSortOrder: number | null = null
let lastWindowCenter = -1
const triggerRafId = 0
let expandDirection: 'forward' | 'backward' | null = null
let lastScrollTime = 0
let lastScrollIndex = -1
let revertTimer: ReturnType<typeof setTimeout> | null = null
let activeRenderRange: { start: number; end: number; center: number } | null = null
let pendingSeekIndex: number | null = null
let dragPreviewTimer: ReturnType<typeof setTimeout> | null = null
const verticalViewRef = ref<InstanceType<typeof VerticalScrollView> | null>(null)
const horizontalViewRef = ref<InstanceType<typeof HorizontalPageView> | null>(null)

const sortChapters = (items: PhotoMeta[]) => [...items].sort((a, b) => a.sortOrder - b.sortOrder)

const currentChapterMeta = computed(
  () => chapters.value.find((chapter) => chapter.id === chapterId.value) ?? null,
)

const ensureCurrentChapterMeta = (pd: PhotoDetail) => {
  if (currentChapterMeta.value) return
  chapters.value = sortChapters([
    ...chapters.value,
    {
      id: chapterId.value,
      title: pd.title,
      sortOrder: pd.sortOrder,
    },
  ])
}

const switchChapter = (targetChapterId: string) => {
  if (
    targetChapterId === chapterId.value ||
    !chapters.value.some((chapter) => chapter.id === targetChapterId)
  )
    return

  settingsPanelVisible.value = false
  const query = { ...route.query }
  delete query.page
  delete query.source
  delete query.total
  void router.replace({
    name: 'ReaderPage',
    params: { albumId: albumId.value, chapterId: targetChapterId },
    query,
  })
}

// ---- 工具栏 ----
const syncReaderFullscreen = (reportFailure = false) => {
  if (isDesktopRuntime || !readerRuntimeActive || !readerCapabilities.fullscreen.available) {
    return
  }
  JmcomicService.setReaderFullscreen(!toolbarVisible.value).catch((error) => {
    if (reportFailure) {
      void showToast(sanitizeError(error, '切换全屏失败'), 'danger')
    }
  })
}

const setToolbarVisible = (visible: boolean, reportFullscreenFailure = false) => {
  toolbarVisible.value = visible
  syncReaderFullscreen(reportFullscreenFailure)
}

const clearAutoShowToolbarTimer = () => {
  if (!autoShowToolbarTimer) return
  clearTimeout(autoShowToolbarTimer)
  autoShowToolbarTimer = null
}

const isAtReaderEnd = () => {
  if (totalCount.value <= 0) return false
  if (isVertical.value) return verticalViewRef.value?.isAtBottom() === true
  return currentIndex.value === totalCount.value - 1
}

const toggleToolbar = () => {
  clearAutoShowToolbarTimer()
  setToolbarVisible(!toolbarVisible.value, true)
}

const restoreReaderFocus = (event: MouseEvent) => {
  if (event.detail === 0) return
  if (isDesktopRuntime) readerRoot.value?.focus({ preventScroll: true })
}

const scheduleToolbarAtReaderEnd = () => {
  clearAutoShowToolbarTimer()
  if (toolbarVisible.value || !SettingsStore.getReaderAutoShowToolbarAtEnd()) return
  autoShowToolbarTimer = setTimeout(() => {
    autoShowToolbarTimer = null
    if (toolbarVisible.value || !SettingsStore.getReaderAutoShowToolbarAtEnd() || !isAtReaderEnd())
      return
    setToolbarVisible(true)
  }, AUTO_SHOW_TOOLBAR_DELAY_MS)
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
  const isDoubleTap =
    toolbarTapTimer &&
    now - lastToolbarTapTime < TOOLBAR_TAP_DELAY_MS &&
    tapDist < TOOLBAR_DOUBLE_TAP_DIST

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
  clearAutoShowToolbarTimer()
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
  if (!readerCapabilities.hostState.available) return
  JmcomicService.setReaderState(true, isVertical.value).catch((error) => {
    void showToast(sanitizeError(error, '同步阅读器宿主状态失败'), 'danger')
  })
}

// ---- 阅读器设置应用 / 恢复 ----

const applyReaderSettings = () => {
  const orientation = SettingsStore.getReaderScreenOrientation()
  if (readerCapabilities.orientation.available && orientation !== 'auto') {
    JmcomicService.setReaderScreenOrientation(orientation).catch((error) => {
      void showToast(sanitizeError(error, '应用屏幕方向失败'), 'danger')
    })
  }
  const brightness = SettingsStore.getReaderBrightness()
  if (readerCapabilities.brightness.available && brightness >= 0) {
    JmcomicService.setReaderBrightness(brightness).catch((error) => {
      void showToast(sanitizeError(error, '应用阅读亮度失败'), 'danger')
    })
  }
  if (readerCapabilities.keepAwake.available && SettingsStore.getReaderKeepScreenOn()) {
    JmcomicService.setReaderKeepScreenOn(true).catch((error) => {
      void showToast(sanitizeError(error, '启用屏幕常亮失败'), 'danger')
    })
  }
  syncReaderFullscreen()
}

const restoreSystemState = () => {
  if (readerCapabilities.brightness.available) {
    JmcomicService.setReaderBrightness(-1).catch(() => {})
  }
  if (readerCapabilities.orientation.available) {
    JmcomicService.setReaderScreenOrientation('auto').catch(() => {})
  }
  if (readerCapabilities.keepAwake.available) {
    JmcomicService.setReaderKeepScreenOn(false).catch(() => {})
  }
  if (readerCapabilities.fullscreen.available) {
    JmcomicService.setReaderFullscreen(false).catch(() => {})
  }
  if (readerCapabilities.hostState.available) {
    JmcomicService.setReaderState(false, false).catch(() => {})
  }
}

// ---- 音量键与桌面方向键共用动作 ----
const handleVolumeDirection = (direction: 'up' | 'down') => {
  if (isVertical.value) {
    const scrollAmount = window.innerHeight / 3
    const el = verticalViewRef.value
    if (el && 'containerRef' in el) {
      const container = (el as { containerRef: HTMLElement | null }).containerRef
      if (container) {
        container.scrollBy({
          top: direction === 'up' ? -scrollAmount : scrollAmount,
          behavior: 'smooth',
        })
        return true
      }
    }
    return false
  }

  if (direction === 'up' && currentIndex.value > 0) {
    goToIndex(currentIndex.value - 1, 'volume')
    return true
  }
  if (direction === 'down' && currentIndex.value < totalCount.value - 1) {
    goToIndex(currentIndex.value + 1, 'volume')
    return true
  }
  return false
}

const setupVolumeKeyListener = async () => {
  if (volumeKeyListenerHandle || !readerCapabilities.volumeKeys.available) return
  volumeKeyListenerHandle = await JmcomicService.addVolumeKeyListener(handleVolumeDirection)
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

const setFailedSortOrder = (sortOrder: number, failed: boolean) => {
  const next = new Set(failedSortOrders.value)
  if (failed) next.add(sortOrder)
  else next.delete(sortOrder)
  failedSortOrders.value = next
}

const onImageError = (sortOrder: number, failedUrl: string) => {
  if (
    !photoDetail ||
    sortOrder < 1 ||
    sortOrder > totalCount.value ||
    imageMap.value.get(sortOrder) !== failedUrl
  )
    return

  imageMap.value.delete(sortOrder)
  loadedSortOrders.delete(sortOrder)
  setFailedSortOrder(sortOrder, true)
  applyImageMap()
}

interface ImageExposureContext {
  version: number
  sessionId: number
  albumId: string
  chapterId: string
  photoId: string
}

const createImageExposureContext = (): ImageExposureContext | null => {
  if (!photoDetail) return null
  return {
    version: chapterLoadVersion,
    sessionId: readerRuntimeSessionId,
    albumId: albumId.value,
    chapterId: chapterId.value,
    photoId: photoDetail.id,
  }
}

const isCurrentImageExposureContext = (context: ImageExposureContext) =>
  context.version === chapterLoadVersion &&
  context.sessionId === readerRuntimeSessionId &&
  readerRuntimeActive &&
  context.albumId === albumId.value &&
  context.chapterId === chapterId.value &&
  context.photoId === photoDetail?.id

const retryFailedImages = () => {
  const context = createImageExposureContext()
  if (!context || activeRetryBatchId !== null || failedSortOrders.value.size === 0) return

  const failedSnapshot = [...failedSortOrders.value].filter(
    (sortOrder) => sortOrder >= 1 && sortOrder <= totalCount.value,
  )
  if (failedSnapshot.length === 0) return

  const batchId = ++retryBatchSequence
  activeRetryBatchId = batchId
  retryingSortOrders.value = new Set(failedSnapshot)

  void (async () => {
    let failedCount = 0
    try {
      const latestPhoto = await JmcomicService.getPhoto(context.chapterId)
      if (
        activeRetryBatchId !== batchId ||
        !isCurrentImageExposureContext(context)
      )
        return
      if (latestPhoto.id !== context.photoId) {
        failedCount = failedSnapshot.length
        return
      }

      const latestImages = new Map<number, ImageInfo>()
      const duplicateSortOrders = new Set<number>()
      for (const image of latestPhoto.images) {
        if (latestImages.has(image.sortOrder)) duplicateSortOrders.add(image.sortOrder)
        latestImages.set(image.sortOrder, image)
      }

      await Promise.all(
        failedSnapshot.map(async (sortOrder) => {
          const image = latestImages.get(sortOrder)
          let success = false
          if (image && !duplicateSortOrders.has(sortOrder)) {
            try {
              await JmcomicService.retryImage(context.photoId, image)
              success = true
            } catch {
              success = false
            }
          }

          if (activeRetryBatchId !== batchId || !isCurrentImageExposureContext(context)) return

          const nextRetrying = new Set(retryingSortOrders.value)
          nextRetrying.delete(sortOrder)
          retryingSortOrders.value = nextRetrying

          if (!success || !image) {
            failedCount++
            return
          }

          const nextFailed = new Set(failedSortOrders.value)
          nextFailed.delete(sortOrder)
          failedSortOrders.value = nextFailed
          sortOrderToImage.set(sortOrder, image)
          const request = requestedSortOrders.get(sortOrder)
          requestedSortOrders.delete(sortOrder)
          if (request) {
            cancelPreloadSessions(
              new Set([
                request.sessionId,
                ...(request.previousSessionId ? [request.previousSessionId] : []),
              ]),
            )
          }
          retryUrlRevision = Math.max(Date.now(), retryUrlRevision + 1)
          if (activeAllowedSortOrders.has(sortOrder)) {
            imageMap.value.set(
              sortOrder,
              `${getImageUrl(context.photoId, sortOrder, 'image')}?retry=${retryUrlRevision}`,
            )
            loadedSortOrders.add(sortOrder)
            applyImageMap()
          }
        }),
      )
    } catch {
      if (activeRetryBatchId !== batchId || !isCurrentImageExposureContext(context)) return
      failedCount = failedSnapshot.length
    } finally {
      if (activeRetryBatchId === batchId) {
        activeRetryBatchId = null
        if (isCurrentImageExposureContext(context)) {
          retryingSortOrders.value = new Set()
          if (failedCount > 0) {
            const message =
              failedCount === failedSnapshot.length
                ? '图片重试失败，请检查网络后重试'
                : `${failedCount} 张图片重试失败`
            void showToast(message, 'danger')
          }
        }
      }
    }
  })()
}

const exposeImageIfAllowed = (
  context: ImageExposureContext,
  sortOrder: number,
  type: 'image' | 'thumb',
) => {
  if (
    type !== 'image' ||
    !isCurrentImageExposureContext(context) ||
    !activeAllowedSortOrders.has(sortOrder)
  )
    return false

  imageMap.value.set(sortOrder, getImageUrl(context.photoId, sortOrder, 'image'))
  loadedSortOrders.add(sortOrder)
  return true
}

const exposeImagesIfAllowed = (
  context: ImageExposureContext,
  sortOrders: number[],
  type: 'image' | 'thumb',
) => {
  let changed = false
  for (const sortOrder of sortOrders) {
    changed = exposeImageIfAllowed(context, sortOrder, type) || changed
  }
  if (changed) applyImageMap()
}

const onPreloadImageReady = (
  context: ImageExposureContext,
  sortOrder: number,
  sessionId: string,
) => {
  if (!isCurrentImageExposureContext(context)) return
  const request = requestedSortOrders.get(sortOrder)
  if (!request || request.sessionId !== sessionId) return
  requestedSortOrders.delete(sortOrder)
  activePreloadSessionIds.delete(request.sessionId)
  if (request.previousSessionId) activePreloadSessionIds.delete(request.previousSessionId)
  if (failedSortOrders.value.has(sortOrder)) setFailedSortOrder(sortOrder, false)
  exposeImagesIfAllowed(context, [sortOrder], 'image')
}

const onPreloadImageFailed = (
  context: ImageExposureContext,
  sortOrder: number,
  sessionId: string,
) => {
  if (!isCurrentImageExposureContext(context) || sortOrder < 1 || sortOrder > totalCount.value)
    return

  const request = requestedSortOrders.get(sortOrder)
  if (!request || request.sessionId !== sessionId) return
  requestedSortOrders.delete(sortOrder)
  activePreloadSessionIds.delete(request.sessionId)
  if (request.previousSessionId) activePreloadSessionIds.delete(request.previousSessionId)
  imageMap.value.delete(sortOrder)
  loadedSortOrders.delete(sortOrder)
  if (!failedSortOrders.value.has(sortOrder)) setFailedSortOrder(sortOrder, true)
  applyImageMap()
}

const setActiveAllowedSortOrders = (
  sortOrders: Iterable<number>,
  dragPreviewSortOrder: number | null,
) => {
  activeAllowedSortOrders = new Set(sortOrders)
  if (dragPreviewSortOrder !== null) activeAllowedSortOrders.add(dragPreviewSortOrder)
}

const cleanupRequestedSortOrdersOutsideAllowed = () => {
  for (const [sortOrder, request] of requestedSortOrders) {
    if (!activeAllowedSortOrders.has(sortOrder) && !loadedSortOrders.has(sortOrder)) {
      requestedSortOrders.delete(sortOrder)
      cancelPreloadSessions(
        new Set([request.sessionId, ...(request.previousSessionId ? [request.previousSessionId] : [])]),
      )
    }
  }
}

const preloadSortOrders = (sortOrders: number[]) => {
  const context = createImageExposureContext()
  if (!context || !readerRuntimeActive || !imageReadyListenerHandle || !imageFailedListenerHandle)
    return

  const desired = new Map<number, { image: ImageInfo; priority: number }>()
  const orderedSortOrders = prioritizeSortOrders(sortOrders, currentIndex.value)
  for (const sortOrder of orderedSortOrders) {
    if (!activeAllowedSortOrders.has(sortOrder) || loadedSortOrders.has(sortOrder)) continue
    const priority =
      sortOrder === currentIndex.value + 1 || sortOrder === activeDragPreviewSortOrder ? 300 : 200
    const image = sortOrderToImage.get(sortOrder)
    if (!image) continue
    desired.set(sortOrder, { image, priority })
  }

  for (const [sortOrder, { image, priority }] of desired) {
    const previousRequest = requestedSortOrders.get(sortOrder)
    if (previousRequest?.priority === priority) continue

    const requestId = ++preloadRequestSequence
    const sessionId = `reader-${context.sessionId}-${requestId}-${crypto.randomUUID()}`
    requestedSortOrders.set(sortOrder, {
      requestId,
      priority,
      sessionId,
      previousSessionId: previousRequest?.sessionId,
    })
    activePreloadSessionIds.add(sessionId)

    void JmcomicService.preloadImages(context.photoId, [image], 'image', {
      sessionId,
      priority,
    }).then(
      (result: PreloadResult) => {
        if (
          !isCurrentImageExposureContext(context) ||
          requestedSortOrders.get(sortOrder)?.requestId !== requestId
        ) {
          cancelPreloadSessions(new Set([sessionId]))
          return
        }

        if (previousRequest) {
          cancelPreloadSessions(new Set([previousRequest.sessionId]))
          requestedSortOrders.set(sortOrder, { requestId, priority, sessionId })
        }
        if (result.cached.includes(sortOrder)) {
          requestedSortOrders.delete(sortOrder)
          activePreloadSessionIds.delete(sessionId)
          exposeImagesIfAllowed(context, [sortOrder], 'image')
        }
      },
      (error: unknown) => {
        if (requestedSortOrders.get(sortOrder)?.requestId === requestId) {
          if (previousRequest) requestedSortOrders.set(sortOrder, previousRequest)
          else requestedSortOrders.delete(sortOrder)
        }
        cancelPreloadSessions(new Set([sessionId]))
        console.warn('阅读页图片预载提交失败', error)
      },
    )
  }
}

const cancelPreloadSessions = (sessionIds: Set<string>) => {
  for (const sessionId of sessionIds) {
    activePreloadSessionIds.delete(sessionId)
    void JmcomicService.cancelPreload(sessionId).catch((error: unknown) => {
      console.warn('取消阅读页图片预载失败', error)
    })
  }
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

const updateWindow = (center: number) => {
  if (!photoDetail) return
  const windowOrders = calcPriorityWindow(center)
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

  setActiveAllowedSortOrders(cacheSet, activeDragPreviewSortOrder)
  cleanupRequestedSortOrdersOutsideAllowed()

  for (const so of loadedSortOrders) {
    if (!activeAllowedSortOrders.has(so)) {
      imageMap.value.delete(so)
      loadedSortOrders.delete(so)
    }
  }
  applyImageMap()
  preloadSortOrders(windowOrders)
}

const loadDragPreviewImage = (center: number) => {
  if (!photoDetail) return
  const so = center + 1
  if (so < 1 || so > totalCount.value) return

  activeDragPreviewSortOrder = so
  setActiveAllowedSortOrders(calcPriorityWindow(currentIndex.value), activeDragPreviewSortOrder)
  cleanupRequestedSortOrdersOutsideAllowed()
  if (loadedSortOrders.has(so)) return
  preloadSortOrders([...calcPriorityWindow(currentIndex.value), so])
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

const onVerticalRequestRange = (range: { start: number; end: number; center: number }) => {
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
  const previous = currentIndex.value
  currentIndex.value = next
  updateReaderCurrentPage(next + 1)
  if (!isVertical.value && previous !== next && next === totalCount.value - 1) {
    scheduleToolbarAtReaderEnd()
  }
  ReadingProgressService.recordChapter(albumId.value, chapterId.value, next + 1, totalCount.value)

  if (source === 'slider-input') {
    scheduleDragPreviewLoad(next)
    moveReaderViewToIndex(next)
    return
  }

  activeDragPreviewSortOrder = null
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
  updateWindow(next)

  moveReaderViewToIndex(next)
}

const { isFullscreen, toggleFullscreen } = useDesktopReaderControls({
  enabled: isDesktopRuntime,
  isActive: () => readerRuntimeActive,
  isVertical,
  currentIndex,
  totalCount,
  fullscreenAvailable: readerCapabilities.fullscreen.available,
  onPreviousPage: () => goToIndex(currentIndex.value - 1, 'volume'),
  onNextPage: () => goToIndex(currentIndex.value + 1, 'volume'),
  onVolumeDirection: handleVolumeDirection,
  setFullscreen: (enabled) => JmcomicService.setReaderFullscreen(enabled),
  onFullscreenError: (error) => {
    void showToast(sanitizeError(error, '切换全屏失败'), 'danger')
  },
})

const zoomIn = () => {
  if (isVertical.value) verticalViewRef.value?.zoomIn()
  else horizontalViewRef.value?.zoomIn()
}

const zoomOut = () => {
  if (isVertical.value) verticalViewRef.value?.zoomOut()
  else horizontalViewRef.value?.zoomOut()
}

const resetZoom = () => {
  if (isVertical.value) verticalViewRef.value?.resetZoom(true)
  else horizontalViewRef.value?.resetZoom()
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
const removeListenerSafely = (handle: ListenerHandle | null) => {
  if (!handle) return
  void handle.remove().catch(() => {})
}

const setupImageReadyListener = async () => {
  if (imageReadyListenerHandle && imageFailedListenerHandle) return
  if (imageReadyListenerSetupPromise) return imageReadyListenerSetupPromise
  const context = createImageExposureContext()
  if (!readerRuntimeActive || !context) return

  const setupId = ++imageReadyListenerSetupId
  const setupPromise = (async () => {
    let readyHandle: ListenerHandle | null = null
    let failedHandle: ListenerHandle | null = null
    const isCurrentSetup = () =>
      setupId === imageReadyListenerSetupId &&
      readerRuntimeActive &&
      isCurrentImageExposureContext(context)

    try {
      readyHandle = await JmcomicService.addImageReadyListener(
        context.photoId,
        (sortOrder, sessionId) => onPreloadImageReady(context, sortOrder, sessionId),
        { type: 'image' },
      )
      if (!isCurrentSetup()) {
        removeListenerSafely(readyHandle)
        return
      }

      failedHandle = await JmcomicService.addImageFailedListener(
        context.photoId,
        (sortOrder, sessionId) => onPreloadImageFailed(context, sortOrder, sessionId),
        { type: 'image' },
      )
      if (!isCurrentSetup()) {
        removeListenerSafely(readyHandle)
        removeListenerSafely(failedHandle)
        return
      }

      imageReadyListenerHandle = readyHandle
      imageFailedListenerHandle = failedHandle
    } catch (error) {
      removeListenerSafely(readyHandle)
      removeListenerSafely(failedHandle)
      throw error
    }
  })()
  imageReadyListenerSetupPromise = setupPromise
  try {
    await setupPromise
  } finally {
    if (imageReadyListenerSetupPromise === setupPromise) {
      imageReadyListenerSetupPromise = null
    }
  }
}

const activateReaderRuntime = (): Promise<void> => {
  if (!readerRuntimeActive) {
    readerRuntimeSessionId++
    readerRuntimeActive = true
    if (readerCapabilities.hostState.available) {
      JmcomicService.setReaderState(true, isVertical.value).catch((error) => {
        void showToast(sanitizeError(error, '同步阅读器宿主状态失败'), 'danger')
      })
    }
    applyReaderSettings()
    setupVolumeKeyListener().catch(() => {})
  }
  if (!photoDetail) return Promise.resolve()
  return setupImageReadyListener().catch(() => {})
}

const deactivateReaderRuntime = () => {
  if (!readerRuntimeActive) return
  readerRuntimeActive = false
  cancelPreloadSessions(new Set(activePreloadSessionIds))
  readerRuntimeSessionId++
  retryBatchSequence++
  activeRetryBatchId = null
  retryingSortOrders.value = new Set()
  requestedSortOrders = new Map()
  activePreloadSessionIds = new Set()
  imageReadyListenerSetupId++
  imageReadyListenerSetupPromise = null
  clearToolbarTapTimer()
  removeListenerSafely(imageReadyListenerHandle)
  imageReadyListenerHandle = null
  removeListenerSafely(imageFailedListenerHandle)
  imageFailedListenerHandle = null
  removeListenerSafely(volumeKeyListenerHandle)
  volumeKeyListenerHandle = null
  restoreSystemState()
}

// ---- 浏览历史记录 ----
const recordBrowseHistory = () => {
  const aId = albumId.value
  const cId = chapterId.value
  if (!aId) return
  const cTitle = currentChapterMeta.value?.title || photoDetail?.title || chapterTitle.value

  JmcomicService.getAlbum(aId)
    .then((album) => {
      if (albumId.value === aId) {
        chapters.value = sortChapters(album.photoMetas)
        if (photoDetail) ensureCurrentChapterMeta(photoDetail)
      }
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
        albumTitle: (route.query.title as string) || cTitle || aId,
        coverUrl: '',
        authors: '',
        chapterId: cId,
        chapterTitle: cTitle,
      })
    })
}

const applyPhotoBaseState = (pd: PhotoDetail) => {
  photoDetail = pd
  ensureCurrentChapterMeta(pd)

  const total = pd.images.length
  const initialPage = ReadingProgressService.getInitialChapterPage(
    route.query.page,
    albumId.value,
    chapterId.value,
    total,
  )
  currentIndex.value = Math.min(Math.max(initialPage - 1, 0), Math.max(0, total - 1))
  totalCount.value = total
  updateReaderCurrentPage(currentIndex.value + 1)
  ReadingProgressService.recordChapter(
    albumId.value,
    chapterId.value,
    currentIndex.value + 1,
    total,
  )
  setToolbarVisible(true)
}

const scrollToInitialIndex = () => {
  nextTick(() => {
    if (isVertical.value) {
      verticalViewRef.value?.scrollToIndex(currentIndex.value)
    }
  })
}

const isCurrentChapterLoad = (version: number, targetAlbumId: string, targetChapterId: string) =>
  version === chapterLoadVersion &&
  albumId.value === targetAlbumId &&
  chapterId.value === targetChapterId

const loadDownloadedChapter = async (
  targetAlbumId: string,
  targetChapterId: string,
  version: number,
): Promise<'loaded' | 'missing' | 'stale'> => {
  try {
    const pd = await JmcomicService.getDownloadedPhoto(targetAlbumId, targetChapterId)
    if (!isCurrentChapterLoad(version, targetAlbumId, targetChapterId)) return 'stale'
    isOffline.value = true
    applyPhotoBaseState(pd)
    sortOrderToImage = new Map(pd.images.map((i) => [i.sortOrder, i]))

    if (readerRuntimeActive) {
      await setupImageReadyListener().catch(() => {})
    }
    if (!isCurrentChapterLoad(version, targetAlbumId, targetChapterId) || photoDetail?.id !== pd.id)
      return 'stale'

    const priorityInitOrders = prioritizeSortOrders(
      calcWindow(currentIndex.value),
      currentIndex.value,
    )
    setActiveAllowedSortOrders(priorityInitOrders, null)
    preloadSortOrders(priorityInitOrders)
    scrollToInitialIndex()
    recordBrowseHistory()
    return 'loaded'
  } catch {
    if (!isCurrentChapterLoad(version, targetAlbumId, targetChapterId)) return 'stale'
    isOffline.value = false
    return 'missing'
  }
}

const loadOnlineChapter = async (
  targetAlbumId: string,
  targetChapterId: string,
  version: number,
) => {
  try {
    const pd = await JmcomicService.getPhoto(targetChapterId)
    if (!isCurrentChapterLoad(version, targetAlbumId, targetChapterId)) return
    isOffline.value = false
    applyPhotoBaseState(pd)
    sortOrderToImage = new Map(pd.images.map((i) => [i.sortOrder, i]))

    if (readerRuntimeActive) {
      await setupImageReadyListener().catch(() => {})
    }
    if (!isCurrentChapterLoad(version, targetAlbumId, targetChapterId) || photoDetail?.id !== pd.id)
      return

    const initOrders = prioritizeSortOrders(calcWindow(currentIndex.value), currentIndex.value)
    setActiveAllowedSortOrders(initOrders, null)
    preloadSortOrders(initOrders)

    scrollToInitialIndex()
    recordBrowseHistory()
  } catch {
    if (!isCurrentChapterLoad(version, targetAlbumId, targetChapterId)) return
    showToast('章节加载失败', 'danger')
    totalCount.value = 0
    recordBrowseHistory()
  }
}

const loadChapter = async () => {
  const targetAlbumId = albumId.value
  const targetChapterId = chapterId.value
  const requestedDownload = route.query.source === 'download'
  const version = chapterLoadVersion
  const downloadResult = await loadDownloadedChapter(targetAlbumId, targetChapterId, version)
  if (downloadResult !== 'missing') return

  if (requestedDownload) {
    showToast('离线章节加载失败', 'danger')
  }
  await loadOnlineChapter(targetAlbumId, targetChapterId, version)
}

const resetChapterState = () => {
  chapterLoadVersion++
  cancelPreloadSessions(new Set(activePreloadSessionIds))
  activePreloadSessionIds = new Set()
  readerRuntimeSessionId++
  imageReadyListenerSetupId++
  imageReadyListenerSetupPromise = null
  clearAutoShowToolbarTimer()
  removeListenerSafely(imageReadyListenerHandle)
  imageReadyListenerHandle = null
  removeListenerSafely(imageFailedListenerHandle)
  imageFailedListenerHandle = null
  photoDetail = null
  currentIndex.value = 0
  totalCount.value = 0
  imageMap.value = new Map()
  failedSortOrders.value = new Set()
  retryingSortOrders.value = new Set()
  isOffline.value = route.query.source === 'download'
  isDragProgress.value = false
  settingsPanelVisible.value = false
  loadedSortOrders = new Set()
  requestedSortOrders = new Map()
  sortOrderToImage = new Map()
  retryBatchSequence++
  activeRetryBatchId = null
  activeAllowedSortOrders = new Set()
  activeDragPreviewSortOrder = null
  lastWindowCenter = -1
  activeRenderRange = null
  pendingSeekIndex = null
  expandDirection = null
  lastScrollTime = 0
  lastScrollIndex = -1
  if (revertTimer) {
    clearTimeout(revertTimer)
    revertTimer = null
  }
  if (dragPreviewTimer) {
    clearTimeout(dragPreviewTimer)
    dragPreviewTimer = null
  }
  setToolbarVisible(true)
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
  chapterStateKey = `${albumId.value}:${chapterId.value}`
  chapterStateAlbumId = albumId.value
  resetChapterState()
  void activateReaderRuntime()
  void loadChapter()
})

watch([albumId, chapterId], ([newAlbumId, newChapterId]) => {
  if (route.name !== 'ReaderPage' || !newAlbumId || !newChapterId) return
  const nextStateKey = `${newAlbumId}:${newChapterId}`
  if (nextStateKey === chapterStateKey) return

  const albumChanged = newAlbumId !== chapterStateAlbumId
  chapterStateKey = nextStateKey
  chapterStateAlbumId = newAlbumId
  if (albumChanged) {
    chapters.value = []
  }

  resetChapterState()
  void loadChapter()
})

onActivated(() => {
  void activateReaderRuntime().then(() => {
    nextTick(() => {
      goToIndex(currentIndex.value, 'route')
    })
  })
})

onDeactivated(() => {
  clearAutoShowToolbarTimer()
  deactivateReaderRuntime()
})

onUnmounted(() => {
  chapterLoadVersion++
  clearToolbarTapTimer()
  clearAutoShowToolbarTimer()
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
  transition:
    opacity 0.22s ease,
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
