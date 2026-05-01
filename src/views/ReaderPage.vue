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
        :image-map="imageMap"
        :total-count="totalCount"
        :initial-index="currentIndex"
        @update:current-index="onPageChange"
      />
      <HorizontalPageView
        v-else
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
import { JmcomicService } from '@/services/JmcomicService'
import type { ImageInfo, PhotoDetail } from '@/services/JmcomicTypes'
import ReaderTopToolbar from '@/components/reader/ReaderTopToolbar.vue'
import ReaderBottomToolbar from '@/components/reader/ReaderBottomToolbar.vue'
import VerticalScrollView from '@/components/reader/VerticalScrollView.vue'
import HorizontalPageView from '@/components/reader/HorizontalPageView.vue'

const N = 5        // 预加载窗口半径
const M = 30       // 最大缓存窗口
const DEBOUNCE_MS = 50
const AUTO_HIDE_MS = 3000

const route = useRoute()
const router = useRouter()

// ---- 路由参数 ----
const albumId = computed(() => route.params.albumId as string)
const chapterId = computed(() => route.params.chapterId as string)
const chapterTitle = computed(() => (route.query.title as string) || chapterId.value)

// ---- 核心状态 ----
const isVertical = ref(true)
const currentIndex = ref(0)
const totalCount = ref(0)
const imageMap = ref<Map<number, string>>(new Map())  // sortOrder -> dataUrl
const toolbarVisible = ref(true)
const isDragProgress = ref(false)

let photoDetail: PhotoDetail | null = null
let previewListenerHandle: PluginListenerHandle | null = null
let loadedSortOrders = new Set<number>()
let debounceTimer: ReturnType<typeof setTimeout> | null = null
let autoHideTimer: ReturnType<typeof setTimeout> | null = null

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
  const start = Math.max(0, center - N)
  const end = Math.min(totalCount.value, center + N + 1)
  for (let i = start; i < end; i++) {
    result.push(i + 1)  // sortOrder = index + 1
  }
  return result
}

const updateWindow = (center: number) => {
  if (!photoDetail) return
  const windowOrders = new Set(calcWindow(center))
  const allImages = photoDetail.images

  // 需要新加载的
  const toLoad: ImageInfo[] = []
  for (const so of windowOrders) {
    if (!loadedSortOrders.has(so)) {
      const img = allImages.find((i) => i.sortOrder === so)
      if (img) toLoad.push(img)
    }
  }

  // 提交加载
  if (toLoad.length > 0) {
    JmcomicService.decryptImageUrls(toLoad).catch(() => {})
  }

  // 卸载窗口外的旧图片（保留最大缓存 M 内的）
  const cacheMin = Math.max(0, center - Math.floor(M / 2))
  const cacheMax = Math.min(totalCount.value, center + Math.floor(M / 2) + 1)
  const cacheSet = new Set<number>()
  for (let i = cacheMin; i < cacheMax; i++) cacheSet.add(i + 1)

  for (const so of loadedSortOrders) {
    if (!cacheSet.has(so)) {
      const dataUrl = imageMap.value.get(so)
      if (dataUrl && dataUrl.startsWith('blob:')) {
        URL.revokeObjectURL(dataUrl)
      }
      imageMap.value.delete(so)
      loadedSortOrders.delete(so)
    }
  }
}

// ---- 页码变更（消抖） ----
const onPageChange = (index: number) => {
  if (index < 0 || index >= totalCount.value) return
  currentIndex.value = index
  resetAutoHide()

  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    updateWindow(index)
    debounceTimer = null
  }, DEBOUNCE_MS)
}

// 进度条拖动跳转（无消抖，直接响应）
const onProgressDrag = (page1Based: number) => {
  const index = page1Based - 1
  if (index < 0 || index >= totalCount.value) return
  currentIndex.value = index
  updateWindow(index)
}

// ---- 图片解密监听 ----
const setupPreviewListener = async () => {
  previewListenerHandle = await JmcomicService.addPreviewListener(chapterId.value, (img) => {
    imageMap.value.set(img.sortOrder, img.dataUrl)
    loadedSortOrders.add(img.sortOrder)
    // 触发响应式更新
    imageMap.value = new Map(imageMap.value)
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
  try {
    photoDetail = await JmcomicService.getPhoto(chapterId.value)
    totalCount.value = photoDetail.images.length

    // 初始定位
    const pageParam = Number(route.query.page)
    currentIndex.value = (pageParam > 0 ? pageParam : 1) - 1

    // 注册监听
    await setupPreviewListener()

    // 初始加载窗口
    const initOrders = calcWindow(currentIndex.value)
    const initImages = photoDetail.images.filter((i) => initOrders.includes(i.sortOrder))
    await JmcomicService.decryptImageUrls(initImages)
    for (const so of initOrders) {
      loadedSortOrders.add(so)
    }
  } catch {
    totalCount.value = 0
  }

  resetAutoHide()
})

onUnmounted(() => {
  previewListenerHandle?.remove()
  if (debounceTimer) clearTimeout(debounceTimer)
  if (autoHideTimer) clearTimeout(autoHideTimer)
  // 释放所有 blob URL
  for (const [, dataUrl] of imageMap.value) {
    if (dataUrl && dataUrl.startsWith('blob:')) {
      URL.revokeObjectURL(dataUrl)
    }
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
