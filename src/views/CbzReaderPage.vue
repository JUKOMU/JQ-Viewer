<template>
  <IonPage>
    <div
      ref="readerRoot"
      class="reader-root"
      :tabindex="isDesktopRuntime ? -1 : undefined"
      @click="onRootClick"
    >
      <Transition name="toolbar-slide">
        <ReaderTopToolbar
          v-if="toolbarVisible"
          :title="displayTitle"
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

      <VerticalScrollView
        v-if="isVertical"
        ref="verticalViewRef"
        :image-map="imageMap"
        :failed-sort-orders="failedSortOrders"
        :failed-messages="failedMessages"
        :allow-retry="false"
        :total-count="totalCount"
        :current-index="currentIndex"
        :enable-mouse-controls="isDesktopRuntime"
        @update:current-index="onPageChange"
        @request-range="onRequestRange"
        @image-error="onImageError"
      />
      <HorizontalPageView
        v-else
        ref="horizontalViewRef"
        :image-map="imageMap"
        :failed-sort-orders="failedSortOrders"
        :failed-messages="failedMessages"
        :allow-retry="false"
        :total-count="totalCount"
        :current-index="currentIndex"
        :enable-mouse-controls="isDesktopRuntime"
        @update:current-index="onPageChange"
        @toggle-toolbar="toggleToolbar"
        @image-error="onImageError"
      />

      <Transition name="toolbar-slide">
        <ReaderBottomToolbar
          v-if="toolbarVisible"
          :current="currentIndex + 1"
          :total="totalCount"
          @click.stop
          @open-settings="settingsPanelVisible = true"
          @update:current="onProgressChange"
          @update:current-input="onProgressChange"
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
import {
  computed,
  inject,
  nextTick,
  onActivated,
  onDeactivated,
  onMounted,
  onUnmounted,
  ref,
} from 'vue'
import { IonPage } from '@ionic/vue'
import { useRoute, useRouter } from 'vue-router'
import type { ListenerHandle } from '@/runtime/BackendEvents'
import { asFileRef } from '@/runtime/FileReferences'
import { getRuntime } from '@/runtime/runtimeContext'
import { normalizeRuntimeError } from '@/runtime/errors'
import { JmcomicService, showToast } from '@/services/JmcomicService'
import { SettingsStore } from '@/services/SettingsService'
import { createLocalReaderSession, type LocalReaderSession } from '@/services/LocalReaderSession'
import { HistoryService } from '@/services/HistoryService'
import ReaderTopToolbar from '@/components/reader/ReaderTopToolbar.vue'
import ReaderBottomToolbar from '@/components/reader/ReaderBottomToolbar.vue'
import ReaderSettingsPanel from '@/components/reader/ReaderSettingsPanel.vue'
import VerticalScrollView from '@/components/reader/VerticalScrollView.vue'
import HorizontalPageView from '@/components/reader/HorizontalPageView.vue'
import { useDesktopReaderControls } from '@/composables/useDesktopReaderControls'

defineOptions({ name: 'CbzReaderPage' })

const route = useRoute()
const router = useRouter()
const runtime = getRuntime()
const readerCapabilities = runtime.services.reader
const isDesktopRuntime = runtime.platform !== 'android'
const fileRef = asFileRef((route.query.fileRef as string) || '')
const displayTitle = computed(() => (route.query.title as string) || 'CBZ')
const albumId = computed(() => (route.query.albumId as string) || '')
const chapterId = computed(() => (route.query.chapterId as string) || albumId.value)
const historyChapterId = computed(() =>
  route.query.readingContext === 'file' ? (route.query.chapterId as string) || '' : chapterId.value,
)
const albumTitle = computed(() => (route.query.albumTitle as string) || '')
const authors = computed(() => (route.query.authors as string) || '')
const coverUrl = computed(() => (route.query.coverUrl as string) || '')
const fileId = computed(() => Number(route.query.fileId) || 0)

const isVertical = ref(SettingsStore.getReaderDisplayMode() === 'vertical')
const toolbarVisible = ref(true)
const settingsPanelVisible = ref(false)
const readerRoot = ref<HTMLElement | null>(null)
const currentIndex = ref(0)
const totalCount = ref(0)
const imageMap = ref<Map<number, string>>(new Map())
const failedSortOrders = ref<Set<number>>(new Set())
const failedMessages = ref<Map<number, string>>(new Map())
const verticalViewRef = ref<InstanceType<typeof VerticalScrollView> | null>(null)
const horizontalViewRef = ref<InstanceType<typeof HorizontalPageView> | null>(null)
const updateReaderCurrentPage = inject<(page: number) => void>('updateReaderCurrentPage', () => {})

let volumeKeyListener: ListenerHandle | null = null
let readerActive = false
let toolbarTimer: ReturnType<typeof setTimeout> | null = null
let readerSession: LocalReaderSession | null = null

const pageUrl = (page: number) =>
  runtime.resources.cbzPageUrl({ file: fileRef, page: readerSession?.physicalPage(page) ?? page })

const ensureWindow = (center: number, start = center, end = center + 1) => {
  const preload = Math.max(2, SettingsStore.getReaderPreloadPages())
  const first = Math.max(0, Math.min(start, center - preload))
  const last = Math.min(totalCount.value, Math.max(end, center + preload + 1))
  const next = new Map(imageMap.value)
  for (let index = first; index < last; index++) {
    const page = index + 1
    if (!next.has(page)) next.set(page, pageUrl(page))
  }
  for (const page of next.keys()) {
    if (page < first + 1 || page > last) next.delete(page)
  }
  imageMap.value = next
}

const onRequestRange = (range: { start: number; end: number; center: number }) => {
  ensureWindow(range.center, range.start, range.end)
}

const moveToIndex = (index: number) => {
  const next = Math.max(0, Math.min(index, Math.max(0, totalCount.value - 1)))
  currentIndex.value = next
  ensureWindow(next)
  updateReaderCurrentPage(next + 1)
  readerSession?.recordPage(next + 1)
  nextTick(() => {
    if (isVertical.value) verticalViewRef.value?.scrollToIndex(next)
  })
}

const onPageChange = (index: number) => moveToIndex(index)
const onProgressChange = (page: number) => moveToIndex(page - 1)

const onImageError = (page: number) => {
  const failed = new Set(failedSortOrders.value)
  const messages = new Map(failedMessages.value)
  failed.add(page)
  messages.set(page, 'CBZ 页面读取失败')
  failedSortOrders.value = failed
  failedMessages.value = messages
}

const handleVolumeDirection = (direction: 'up' | 'down') => {
  if (isVertical.value) {
    const container = (verticalViewRef.value as { containerRef?: HTMLElement | null })?.containerRef
    if (!container) return false
    container.scrollBy({
      top: direction === 'up' ? -window.innerHeight / 3 : window.innerHeight / 3,
      behavior: 'smooth',
    })
    return true
  }

  if (direction === 'up' && currentIndex.value > 0) {
    moveToIndex(currentIndex.value - 1)
    return true
  }
  if (direction === 'down' && currentIndex.value < totalCount.value - 1) {
    moveToIndex(currentIndex.value + 1)
    return true
  }
  return false
}

const { isFullscreen, toggleFullscreen } = useDesktopReaderControls({
  enabled: isDesktopRuntime,
  isActive: () => readerActive,
  isVertical,
  currentIndex,
  totalCount,
  fullscreenAvailable: readerCapabilities.fullscreen.available,
  onPreviousPage: () => moveToIndex(currentIndex.value - 1),
  onNextPage: () => moveToIndex(currentIndex.value + 1),
  onVolumeDirection: handleVolumeDirection,
  setFullscreen: (enabled) => JmcomicService.setReaderFullscreen(enabled),
  onFullscreenError: () => {
    void showToast('切换全屏失败', 'danger')
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
  if (isVertical.value) verticalViewRef.value?.resetZoom()
  else horizontalViewRef.value?.resetZoom()
}

const syncReaderFullscreen = () => {
  if (isDesktopRuntime || !readerActive || !readerCapabilities.fullscreen.available) {
    return
  }
  JmcomicService.setReaderFullscreen(!toolbarVisible.value).catch(() => {})
}

const toggleToolbar = () => {
  toolbarVisible.value = !toolbarVisible.value
  syncReaderFullscreen()
}

const restoreReaderFocus = () => {
  if (isDesktopRuntime) readerRoot.value?.focus({ preventScroll: true })
}

const onRootClick = () => {
  if (!isVertical.value || settingsPanelVisible.value) return
  if (toolbarTimer) clearTimeout(toolbarTimer)
  toolbarTimer = setTimeout(() => {
    toolbarTimer = null
    toggleToolbar()
  }, 220)
}

const applyReaderSettings = () => {
  const orientation = SettingsStore.getReaderScreenOrientation()
  if (readerCapabilities.orientation.available && orientation !== 'auto') {
    JmcomicService.setReaderScreenOrientation(orientation).catch(() => {})
  }
  const brightness = SettingsStore.getReaderBrightness()
  if (readerCapabilities.brightness.available && brightness >= 0) {
    JmcomicService.setReaderBrightness(brightness).catch(() => {})
  }
  if (readerCapabilities.keepAwake.available && SettingsStore.getReaderKeepScreenOn()) {
    JmcomicService.setReaderKeepScreenOn(true).catch(() => {})
  }
  syncReaderFullscreen()
}

const restoreReaderSettings = () => {
  if (readerCapabilities.brightness.available)
    JmcomicService.setReaderBrightness(-1).catch(() => {})
  if (readerCapabilities.orientation.available)
    JmcomicService.setReaderScreenOrientation('auto').catch(() => {})
  if (readerCapabilities.keepAwake.available)
    JmcomicService.setReaderKeepScreenOn(false).catch(() => {})
  if (readerCapabilities.fullscreen.available)
    JmcomicService.setReaderFullscreen(false).catch(() => {})
  if (readerCapabilities.hostState.available)
    JmcomicService.setReaderState(false, false).catch(() => {})
}

const activateReader = () => {
  if (readerActive) return
  readerActive = true
  if (readerCapabilities.hostState.available) {
    JmcomicService.setReaderState(true, isVertical.value).catch(() => {})
  }
  applyReaderSettings()
  if (readerCapabilities.volumeKeys.available) {
    JmcomicService.addVolumeKeyListener(handleVolumeDirection)
      .then((handle) => {
        volumeKeyListener = handle
      })
      .catch(() => {})
  }
}

const deactivateReader = () => {
  readerActive = false
  volumeKeyListener?.remove()
  volumeKeyListener = null
  restoreReaderSettings()
}

const onDisplayModeChange = (vertical: boolean) => {
  isVertical.value = vertical
  if (readerCapabilities.hostState.available) {
    JmcomicService.setReaderState(true, vertical).catch(() => {})
  }
  nextTick(() => moveToIndex(currentIndex.value))
}

const recordHistory = () => {
  if (!albumId.value) return
  HistoryService.recordBrowse({
    albumId: albumId.value,
    albumTitle: albumTitle.value || displayTitle.value,
    coverUrl: coverUrl.value,
    authors: authors.value,
    chapterId: historyChapterId.value,
    chapterTitle: displayTitle.value,
    ...(fileId.value > 0 ? { fileId: fileId.value } : {}),
  })
}

const goBack = () => (window.history.length > 1 ? router.back() : void router.push('/home'))

onMounted(async () => {
  if (!fileRef) {
    await showToast('缺少 CBZ 文件引用', 'danger')
    router.back()
    return
  }
  activateReader()
  try {
    const info = await JmcomicService.getCbzInfo(fileRef)
    if (info.pageCount <= 0) throw new Error('CBZ 中没有可阅读图片')
    readerSession = createLocalReaderSession(route.query, info.pageCount)
    totalCount.value = readerSession.totalPages
    const initialPage = readerSession.initialPage(route.query.page)
    moveToIndex(initialPage - 1)
    recordHistory()
  } catch (error) {
    const failure = normalizeRuntimeError(error)
    await showToast(failure.message || 'CBZ 文件无法打开', 'danger')
    router.back()
  }
})

onActivated(activateReader)
onDeactivated(deactivateReader)
onUnmounted(() => {
  if (toolbarTimer) clearTimeout(toolbarTimer)
  deactivateReader()
})
</script>

<style scoped>
.reader-root {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background: #000;
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
