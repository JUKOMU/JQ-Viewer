<template>
  <IonPage>
    <div class="reader-root" @click="onRootClick">
      <!-- 顶部工具栏 -->
      <Transition name="toolbar-slide">
        <ReaderTopToolbar v-if="toolbarVisible" :title="chapterTitle" @back="goBack"/>
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
import {computed, nextTick, onMounted, onUnmounted, ref} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {IonPage} from '@ionic/vue'
import type {PluginListenerHandle} from '@capacitor/core'
import {getImageUrl, JmcomicService, showToast} from '@/services/JmcomicService'
import type {ImageInfo, PhotoDetail, PreloadResult} from '@/services/JmcomicTypes'
import {SettingsStore} from '@/services/SettingsService'
import {HistoryService} from '@/services/HistoryService'
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

const route = useRoute()
const router = useRouter()

// ---- 路由参数 ----
const albumId = computed(() => route.params.albumId as string)
const chapterId = computed(() => route.params.chapterId as string)
const chapterTitle = computed(() => (route.query.title as string) || chapterId.value)
const isOffline = computed(() => route.query.source === 'download')

// ---- 核心状态 ----
const isVertical = ref(SettingsStore.getReaderDisplayMode() === 'vertical')
const initialTotal = Number(route.query.total) || 0
const currentIndex = ref(0)
const totalCount = ref(initialTotal)
const imageMap = ref<Map<number, string>>(new Map())
const toolbarVisible = ref(initialTotal > 0)
const isDragProgress = ref(false)
const settingsPanelVisible = ref(false)
let photoDetail: PhotoDetail | null = null
let imageReadyListenerHandle: PluginListenerHandle | null = null
let volumeKeyListenerHandle: PluginListenerHandle | null = null
let loadedSortOrders = new Set<number>()
let requestedSortOrders = new Set<number>()
let sortOrderToImage = new Map<number, ImageInfo>()
let lastWindowCenter = -1
const triggerRafId = 0
let expandDirection: 'forward' | 'backward' | null = null
let lastScrollTime = 0
let lastScrollIndex = -1
let revertTimer: ReturnType<typeof setTimeout> | null = null
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

// 点击中间 1/3 呼出/收起工具栏（纵向滚动模式下由 root 层处理）
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
const toggleMode = () => {
  isVertical.value = !isVertical.value
  syncReaderState()
}

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
    const so = i + 1
    if (requestedSortOrders.has(so) && !loadedSortOrders.has(so)) return true
  }
  return false
}

const updateWindow = (center: number) => {
  if (!photoDetail) return
  const windowOrders = new Set(calcWindow(center))

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
      JmcomicService.preloadImages(photoDetail.id, toLoad, 'image')
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

  for (const so of loadedSortOrders) {
    if (!cacheSet.has(so)) {
      imageMap.value.delete(so)
      loadedSortOrders.delete(so)
    }
  }
  applyImageMap()
}

// ---- 页码变更 ----
const onPageChange = (index: number) => {
  if (index < 0 || index >= totalCount.value) return
  currentIndex.value = index

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

  const threshold = isVertical.value ? 3 : 1
  if (Math.abs(index - lastWindowCenter) >= threshold) {
    lastWindowCenter = index
    updateWindow(index)
  }
}

const onProgressDrag = (page1Based: number) => {
  const index = page1Based - 1
  if (index < 0 || index >= totalCount.value) return
  currentIndex.value = index
  lastWindowCenter = index
  expandDirection = null
  if (revertTimer) {
    clearTimeout(revertTimer)
    revertTimer = null
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
    updateWindow(index)
  }
}

// ---- 图片就绪监听 ----
const setupImageReadyListener = async () => {
  imageReadyListenerHandle = await JmcomicService.addImageReadyListener(
    chapterId.value,
    (sortOrder) => {
      imageMap.value.set(sortOrder, getImageUrl(chapterId.value, sortOrder, 'image'))
      loadedSortOrders.add(sortOrder)
      applyImageMap()
    },
  )
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
  if (revertTimer) {
    clearTimeout(revertTimer)
    revertTimer = null
  }

  // 应用阅读器设置
  applyReaderSettings()
  // 通知 native 进入阅读状态
  JmcomicService.setReaderState(true, isVertical.value).catch(() => {})

  if (isOffline.value) {
    JmcomicService.getDownloadedPhoto(albumId.value, chapterId.value)
      .then((pd) => {
        photoDetail = pd

        const pageParam = Number(route.query.page)
        currentIndex.value = (pageParam > 0 ? pageParam : 1) - 1

        totalCount.value = pd.images.length

        toolbarVisible.value = true

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
      })
      .catch((_e: any) => {
        showToast('离线章节加载失败', 'danger')
        totalCount.value = 0
        recordBrowseHistory()
      })
  } else {
    JmcomicService.getPhoto(chapterId.value)
      .then((pd) => {
        photoDetail = pd
        sortOrderToImage = new Map(pd.images.map((i) => [i.sortOrder, i]))

        const pageParam = Number(route.query.page)
        currentIndex.value = (pageParam > 0 ? pageParam : 1) - 1

        totalCount.value = pd.images.length

        toolbarVisible.value = true

        setupImageReadyListener().catch(() => {
        })

        const initOrders = calcWindow(currentIndex.value)
        const initImages = pd.images.filter((i) => initOrders.includes(i.sortOrder))
        for (const so of initOrders) {
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

        nextTick(() => {
          if (isVertical.value) {
            verticalViewRef.value?.scrollToIndex(currentIndex.value)
          }
        })
        recordBrowseHistory()
      })
      .catch(() => {
        showToast('章节加载失败', 'danger')
        totalCount.value = 0
        recordBrowseHistory()
      })
  }

  // 注册音量键监听
  setupVolumeKeyListener().catch(() => {})
})

onUnmounted(() => {
  imageReadyListenerHandle?.remove()
  volumeKeyListenerHandle?.remove()
  if (triggerRafId) cancelAnimationFrame(triggerRafId)
  if (revertTimer) clearTimeout(revertTimer)

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
