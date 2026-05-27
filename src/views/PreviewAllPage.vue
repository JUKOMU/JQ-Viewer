<!-- 全量预览页：逐批显示骨架，图片逐张刷出 -->
<template>
  <IonPage>
    <IonHeader>
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-button @click="goBack">
            <ion-icon slot="icon-only" :icon="arrowBack" />
          </ion-button>
        </ion-buttons>
        <ion-title>预览 · {{ chapterTitle }}</ion-title>
      </ion-toolbar>
    </IonHeader>
    <IonContent ref="contentRef" :scroll-events="true" @ion-scroll="onIonScroll">
      <div class="preview-container">
        <!-- 初始加载中：骨架占位 -->
        <div v-if="loading" class="preview-grid">
          <div v-for="i in skeletonCount" :key="'init-skel-' + i" class="preview-item">
            <div class="skeleton-thumb" />
            <span class="preview-page-num">{{ i }}</span>
          </div>
        </div>

        <!-- 逐批显示槽位 -->
        <template v-else-if="totalCount > 0">
          <div class="preview-grid">
            <div v-for="i in displayCount" :key="'slot-' + i" class="preview-item">
              <template v-if="slots[i - 1]">
                <img
                  :src="slots[i - 1]!.dataUrl"
                  :alt="'第 ' + i + ' 页'"
                  class="preview-thumb"
                  @click="openReader(i)"
                />
              </template>
              <template v-else>
                <div class="skeleton-thumb" />
              </template>
              <span class="preview-page-num">{{ i }}</span>
            </div>
          </div>

          <div class="preview-footer">
            <p v-if="loadingMore" class="footer-loading">
              <ion-spinner name="dots" />
              <span>加载中...（{{ loadedCount }} / {{ totalCount }}）</span>
            </p>
            <p v-else-if="allVisible" class="footer-text">已显示所有图片</p>
            <p v-else class="footer-text">上滑加载更多...</p>
          </div>
        </template>

        <p v-else class="preview-empty">暂无图片</p>
      </div>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({ name: 'PreviewAllPage' })

import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { ScrollCustomEvent } from '@ionic/vue'
import type { PluginListenerHandle } from '@capacitor/core'
import {
  IonButtons,
  IonButton,
  IonContent,
  IonHeader,
  IonIcon,
  IonPage,
  IonSpinner,
  IonTitle,
  IonToolbar,
} from '@ionic/vue'
import { arrowBack } from 'ionicons/icons'
import { getImageUrl, JmcomicService } from '@/services/JmcomicService'
import type { PhotoDetail, PreloadResult } from '@/services/JmcomicTypes'

const BATCH = 20
const NEAR_BOTTOM_THRESHOLD = 200

const route = useRoute()
const router = useRouter()

const albumId = computed(() => route.params.albumId as string)
const chapterId = computed(() => route.params.chapterId as string)
const chapterTitle = computed(() => (route.query.title as string) || chapterId.value)
const initialTotal = Number(route.query.total as string) || 0

interface PreviewImage {
  sortOrder: number
  dataUrl: string
}
const slots = ref<(PreviewImage | null)[]>([])
const displayCount = ref(0)
const totalCount = ref(initialTotal)
const loading = ref(true)
const loadingMore = ref(false)
const cursor = ref(0) // 已提交给 Native 的图片数

const loadedCount = computed(() => slots.value.filter((s) => s !== null).length)
const allVisible = computed(() => displayCount.value >= totalCount.value)
const skeletonCount = computed(() => Math.min(totalCount.value || initialTotal || BATCH, BATCH))

let photoDetail: PhotoDetail | null = null
let imageReadyListenerHandle: PluginListenerHandle | null = null

// ---- 滚动容器 ----
const contentRef = ref<InstanceType<typeof IonContent> | null>(null)
const scrollEl = ref<HTMLElement | null>(null)

const resolveScrollElement = async (): Promise<HTMLElement | null> => {
  if (scrollEl.value) return scrollEl.value
  const ce = contentRef.value?.$el
  if (ce && typeof (ce as any).getScrollElement === 'function') {
    scrollEl.value = await (ce as any).getScrollElement()
    return scrollEl.value
  }
  return null
}

// ---- 生命周期 ----
onMounted(async () => {
  try {
    photoDetail = await JmcomicService.getPhoto(chapterId.value)
    totalCount.value = photoDetail.images.length
  } catch {
    totalCount.value = 0
    loading.value = false
    return
  }

  imageReadyListenerHandle = await JmcomicService.addImageReadyListener(
    chapterId.value,
    (sortOrder) => {
      const idx = sortOrder - 1
      if (idx >= 0 && idx < slots.value.length) {
        slots.value[idx] = {
          sortOrder,
          dataUrl: getImageUrl(chapterId.value, sortOrder, 'thumb'),
        }
      }
    },
  )

  await resolveScrollElement()

  // 展开首批槽位
  const firstCount = Math.min(BATCH, totalCount.value)
  slots.value = new Array(firstCount).fill(null)
  displayCount.value = firstCount
  loading.value = false

  // 提交预加载首批缩略图
  loadingMore.value = true
  const batch = photoDetail.images.slice(0, firstCount)
  const result: PreloadResult = await JmcomicService.preloadImages(chapterId.value, batch, 'thumb')
  // 已缓存的图片直接填入槽位
  for (const so of result.cached) {
    const idx = so - 1
    if (idx >= 0 && idx < slots.value.length) {
      slots.value[idx] = {
        sortOrder: so,
        dataUrl: getImageUrl(chapterId.value, so, 'thumb'),
      }
    }
  }
  cursor.value = firstCount

  await new Promise((r) => setTimeout(r, 300))
  loadingMore.value = false

  await maybeLoadMoreAfterRender()
})

onUnmounted(() => {
  imageReadyListenerHandle?.remove()
})

// ---- 滚动 ----
const onIonScroll = async (ev: ScrollCustomEvent) => {
  if (loadingMore.value || allVisible.value) return
  const se = scrollEl.value ?? (await resolveScrollElement())
  if (!se) return
  const remain = se.scrollHeight - se.clientHeight - ev.detail.scrollTop
  if (remain <= NEAR_BOTTOM_THRESHOLD) {
    await expandBatch()
  }
}

const maybeLoadMoreAfterRender = async () => {
  if (allVisible.value || loadingMore.value) return
  await nextTick()
  const se = scrollEl.value ?? (await resolveScrollElement())
  if (!se) return
  const remain = se.scrollHeight - se.clientHeight - se.scrollTop
  if (remain <= NEAR_BOTTOM_THRESHOLD) {
    await expandBatch()
    await maybeLoadMoreAfterRender()
  }
}

// ---- 展开一批：加载动画 → 0.3s → 扩槽位 → 提交下载 ----
const expandBatch = async () => {
  if (!photoDetail || allVisible.value) return
  loadingMore.value = true

  // 先等一下，让加载动画可见
  await new Promise((r) => setTimeout(r, 400))

  // 扩槽位
  const newDisplayCount = Math.min(displayCount.value + BATCH, totalCount.value)
  while (slots.value.length < newDisplayCount) {
    slots.value.push(null)
  }
  displayCount.value = newDisplayCount

  // 提交预加载（扩槽后才提交，保证事件能正确填充）
  if (cursor.value < totalCount.value) {
    const nextCursor = Math.min(cursor.value + BATCH, totalCount.value)
    const batch = photoDetail.images.slice(cursor.value, nextCursor)
    JmcomicService.preloadImages(chapterId.value, batch, 'thumb')
      .then((result: PreloadResult) => {
        for (const so of result.cached) {
          const idx = so - 1
          if (idx >= 0 && idx < slots.value.length) {
            slots.value[idx] = {
              sortOrder: so,
              dataUrl: getImageUrl(chapterId.value, so, 'thumb'),
            }
          }
        }
      })
      .catch(() => {})
    cursor.value = nextCursor
  }

  loadingMore.value = false
}

const openReader = (page: number) => {
  void router.push({
    path: `/album/${albumId.value}/read/${chapterId.value}`,
    query: { page: String(page), title: chapterTitle.value, total: String(totalCount.value) },
  })
}

const goBack = () => router.back()
</script>

<style scoped>
.preview-container {
  padding: 10px 12px;
}

.preview-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
}

.preview-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.preview-thumb,
.skeleton-thumb {
  width: 100%;
  aspect-ratio: 3 / 4;
  border-radius: 6px;
  overflow: hidden;
}

.preview-thumb {
  object-fit: cover;
  display: block;
  background: linear-gradient(145deg, #f3ded0, #ffece0);
}

.skeleton-thumb {
  background: linear-gradient(145deg, #e8d5c5, #f3e0d2);
  animation: skeleton-pulse 1.5s ease-in-out infinite;
}

@keyframes skeleton-pulse {
  0%,
  100% {
    opacity: 0.4;
  }
  50% {
    opacity: 0.8;
  }
}

.preview-page-num {
  text-align: center;
  color: #8a6048;
  font-size: 10px;
  line-height: 1.4;
}

.preview-footer {
  padding: 12px 0 40px;
  text-align: center;
}

.footer-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin: 0;
  color: #8a6048;
  font-size: 12px;
}

.footer-text {
  margin: 0;
  color: #8a6048;
  font-size: 12px;
}

.preview-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 180px;
  color: #8a6048;
  font-size: 13px;
}

@media (min-width: 680px) {
  .preview-grid {
    grid-template-columns: repeat(4, 1fr);
  }
}
</style>
