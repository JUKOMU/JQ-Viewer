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
      <div class="preview-page-container">
        <div class="preview-container">
          <!-- 初始加载中：骨架占位 -->
          <div v-if="loading" ref="previewGridRef" class="preview-grid">
            <div v-for="i in skeletonCount" :key="'init-skel-' + i" class="preview-item">
              <div class="skeleton-thumb" />
              <span class="preview-page-num">{{ i }}</span>
            </div>
          </div>

          <!-- 逐批显示槽位 -->
          <template v-else-if="totalCount > 0">
            <div ref="previewGridRef" class="preview-grid">
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

            <div class="preview-footer" role="status" aria-live="polite" aria-atomic="true">
              <p v-if="loadingMore" class="footer-loading">
                <ion-spinner name="dots" aria-hidden="true" />
                <span>加载中...（{{ loadedCount }} / {{ totalCount }}）</span>
              </p>
              <p v-else-if="allVisible" class="footer-text">已显示所有图片</p>
              <p v-else-if="loadedCount < displayCount" class="footer-text">
                上滑重新加载缺失图片...（{{ loadedCount }} / {{ displayCount }}）
              </p>
              <p v-else class="footer-text">上滑加载更多...</p>
            </div>
          </template>

          <p v-else class="preview-empty">暂无图片</p>
        </div>
      </div>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({ name: 'PreviewAllPage' })

import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { ScrollCustomEvent } from '@ionic/vue'
import {
  IonButton,
  IonButtons,
  IonContent,
  IonHeader,
  IonIcon,
  IonPage,
  IonSpinner,
  IonTitle,
  IonToolbar,
} from '@ionic/vue'
import type { PluginListenerHandle } from '@capacitor/core'
import { arrowBack } from 'ionicons/icons'
import { getImageUrl, JmcomicService } from '@/services/JmcomicService'
import type { PhotoDetail, PreloadResult } from '@/services/JmcomicTypes'
import {
  PREVIEW_BATCH,
  type PreviewImageSlotSetter,
  usePreviewBatches,
} from '@/composables/usePreviewBatches'
import { getPreviewGridItemWidth } from '@/utils/previewGrid'
import { buildPdfDocumentParams, fetchPdfArrayBuffer } from '@/services/PdfReaderService'
import * as pdfjsLib from 'pdfjs-dist'

pdfjsLib.GlobalWorkerOptions.workerSrc = new URL(
  'pdfjs-dist/build/pdf.worker.min.mjs',
  import.meta.url,
).href

const NEAR_BOTTOM_THRESHOLD = 200

const route = useRoute()
const router = useRouter()

const albumId = computed(() => route.params.albumId as string)
const chapterId = computed(() => route.params.chapterId as string)
const chapterTitle = computed(() => (route.query.title as string) || chapterId.value)
const initialTotal = Number(route.query.total as string) || 0
const source = computed(() => (route.query.source as string) || 'network')
const isPdfSource = computed(() => source.value === 'pdf')
const isDownloadSource = computed(() => source.value === 'download')
const pdfPath = computed(() => (route.query.pdfPath as string) || '')

const totalCount = ref(initialTotal)
const loading = ref(true)
const skeletonCount = computed(() =>
  Math.min(totalCount.value || initialTotal || PREVIEW_BATCH, PREVIEW_BATCH),
)

let photoDetail: PhotoDetail | null = null
let pdfDoc: pdfjsLib.PDFDocumentProxy | null = null
let imageReadyListenerHandle: PluginListenerHandle | null = null
let disposed = false
const pdfObjectUrls = new Set<string>()

// ---- 滚动容器 ----
const contentRef = ref<InstanceType<typeof IonContent> | null>(null)
const scrollEl = ref<HTMLElement | null>(null)
const previewGridRef = ref<HTMLElement | null>(null)

const resolveScrollElement = async (): Promise<HTMLElement | null> => {
  if (scrollEl.value) return scrollEl.value
  const ce = contentRef.value?.$el
  if (ce && typeof (ce as any).getScrollElement === 'function') {
    scrollEl.value = await (ce as any).getScrollElement()
    return scrollEl.value
  }
  return null
}

const renderPdfPage = async (pageNum: number): Promise<string | null> => {
  if (!pdfDoc || disposed) return null
  let page: pdfjsLib.PDFPageProxy | null = null
  try {
    page = await pdfDoc.getPage(pageNum)
    if (disposed) return null
    const rawViewport = page.getViewport({ scale: 1 })
    const targetWidth = getPreviewGridItemWidth(previewGridRef.value)
    const scale = Math.max(
      0.4,
      (targetWidth * Math.min(window.devicePixelRatio || 1, 2)) / rawViewport.width,
    )
    const viewport = page.getViewport({ scale })

    const canvas = document.createElement('canvas')
    canvas.width = viewport.width
    canvas.height = viewport.height
    const task = page.render({ canvas, viewport })
    await task.promise
    if (disposed) return null

    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob((b) => resolve(b), 'image/jpeg', 0.82),
    )
    if (!blob || disposed) return null
    const url = URL.createObjectURL(blob)
    if (disposed) {
      URL.revokeObjectURL(url)
      return null
    }
    pdfObjectUrls.add(url)
    return url
  } catch {
    return null
  } finally {
    page?.cleanup()
  }
}

const renderPdfBatch = async (start: number, end: number, setImageSlot: PreviewImageSlotSetter) => {
  const missingPages = Array.from({ length: end - start }, (_, i) => start + i + 1).filter(
    (pageNum) => !slots.value[pageNum - 1],
  )
  const rendered = await Promise.all(
    missingPages.map((pageNum) => {
      return renderPdfPage(pageNum).then((url) => ({ pageNum, url }))
    }),
  )
  for (const item of rendered) {
    if (item.url) setImageSlot(item.pageNum, item.url)
  }
}

const preloadImageBatch = async (
  start: number,
  end: number,
  setImageSlot: PreviewImageSlotSetter,
) => {
  if (disposed) return
  if (isPdfSource.value) {
    await renderPdfBatch(start, end, setImageSlot)
    return
  }
  if (!photoDetail) return
  if (isDownloadSource.value) {
    for (const image of photoDetail.images.slice(start, end)) {
      setImageSlot(image.sortOrder, getImageUrl(photoDetail.id, image.sortOrder, 'thumb'))
    }
    return
  }

  const batch = photoDetail.images.slice(start, end)
  const result: PreloadResult = await JmcomicService.preloadImages(chapterId.value, batch, 'thumb')
  if (disposed) return
  for (const so of result.cached) {
    setImageSlot(so, getImageUrl(chapterId.value, so, 'thumb'))
  }
}

const previewBatches = usePreviewBatches(totalCount, preloadImageBatch)
const { slots, displayCount, loadingMore, loadedCount, allVisible } = previewBatches

// ---- 生命周期 ----
onMounted(async () => {
  try {
    if (isPdfSource.value) {
      if (!pdfPath.value) throw new Error('缺少 PDF 路径')
      const arrayBuffer = await fetchPdfArrayBuffer(pdfPath.value)
      const loadedPdfDoc = await pdfjsLib.getDocument(buildPdfDocumentParams(arrayBuffer)).promise
      if (disposed) {
        void loadedPdfDoc.destroy()
        return
      }
      pdfDoc = loadedPdfDoc
      totalCount.value = loadedPdfDoc.numPages
    } else if (isDownloadSource.value) {
      photoDetail = await JmcomicService.getDownloadedPhoto(albumId.value, chapterId.value)
      totalCount.value = photoDetail.images.length
    } else {
      photoDetail = await JmcomicService.getPhoto(chapterId.value)
      totalCount.value = photoDetail.images.length
    }
  } catch {
    if (disposed) return
    totalCount.value = 0
    loading.value = false
    return
  }

  if (disposed) return

  if (!isPdfSource.value && !isDownloadSource.value) {
    const setImageSlot = previewBatches.createImageSlotSetter()
    const listenerHandle = await JmcomicService.addImageReadyListener(
      chapterId.value,
      (sortOrder) => {
        setImageSlot(sortOrder, getImageUrl(chapterId.value, sortOrder, 'thumb'))
      },
    )
    if (disposed) {
      void listenerHandle.remove()
      return
    }
    imageReadyListenerHandle = listenerHandle
  }

  await resolveScrollElement()
  if (disposed) return

  await previewBatches.initialize()
  if (disposed) return
  loading.value = false
  await maybeLoadMoreAfterRender()
})

onUnmounted(() => {
  disposed = true
  previewBatches.reset()
  imageReadyListenerHandle?.remove()
  pdfDoc?.destroy()
  pdfDoc = null
  for (const url of pdfObjectUrls) {
    URL.revokeObjectURL(url)
  }
  pdfObjectUrls.clear()
})

// ---- 滚动 ----
const onIonScroll = async (ev: ScrollCustomEvent) => {
  if (disposed || loadingMore.value || allVisible.value) return
  const se = scrollEl.value ?? (await resolveScrollElement())
  if (!se) return
  const remain = se.scrollHeight - se.clientHeight - ev.detail.scrollTop
  if (remain <= NEAR_BOTTOM_THRESHOLD) {
    await previewBatches.expandBatch()
  }
}

const maybeLoadMoreAfterRender = async () => {
  if (disposed || allVisible.value || loadingMore.value) return
  await nextTick()
  const se = scrollEl.value ?? (await resolveScrollElement())
  if (!se) return
  const remain = se.scrollHeight - se.clientHeight - se.scrollTop
  if (remain <= NEAR_BOTTOM_THRESHOLD) {
    const batchFilled = await previewBatches.expandBatch()
    if (batchFilled) {
      await maybeLoadMoreAfterRender()
    }
  }
}

watch(loadedCount, (count) => {
  if (disposed || loading.value || loadingMore.value || allVisible.value) return
  if (count === displayCount.value) void maybeLoadMoreAfterRender()
})

const openReader = (page: number) => {
  if (isPdfSource.value && pdfPath.value) {
    void router.push({
      path: '/pdf-reader',
      query: {
        path: pdfPath.value,
        title: (route.query.pdfTitle as string) || chapterTitle.value,
        albumId: albumId.value,
        albumTitle: (route.query.albumTitle as string) || chapterTitle.value,
        authors: (route.query.authors as string) || '',
        coverUrl: (route.query.coverUrl as string) || '',
        chapterId: chapterId.value,
        chapterTitle: chapterTitle.value,
        page: String(page),
      },
    })
    return
  }

  void router.push({
    path: `/album/${albumId.value}/read/${chapterId.value}`,
    query: {
      page: String(page),
      title: chapterTitle.value,
      total: String(totalCount.value),
      ...(isDownloadSource.value ? { source: 'download' } : {}),
    },
  })
}

const goBack = () => router.back()
</script>

<style scoped>
.preview-page-container {
  width: 100%;
  container-type: inline-size;
}

.preview-container {
  padding: 10px 12px;
  box-sizing: border-box;
  width: 100%;
  max-width: 1280px;
  margin-inline: auto;
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

@container (min-width: 960px) {
  .preview-grid {
    grid-template-columns: repeat(auto-fill, minmax(116px, 1fr));
    gap: 8px;
  }
}
</style>
