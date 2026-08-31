<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <IonButtons slot="start">
          <IonButton fill="clear" aria-label="返回详情" @click="goAlbum">返回</IonButton>
        </IonButtons>
        <IonTitle>{{ chapter?.title || 'PicaComic 阅读' }}</IonTitle>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <main class="reader-page">
        <div v-if="loading" class="reader-state" data-testid="picacomic-reader-loading">
          正在准备图片…
        </div>
        <div
          v-else-if="errorMessage"
          class="reader-state error-state"
          data-testid="picacomic-reader-error"
        >
          <p>{{ errorMessage }}</p>
          <IonButton fill="outline" @click="loadChapter">重试章节</IonButton>
        </div>
        <template v-else-if="chapter">
          <section class="reader-toolbar" aria-label="章节导航">
            <IonButton
              fill="outline"
              :disabled="!previousChapter"
              data-testid="picacomic-previous-chapter"
              @click="switchChapter(previousChapter)"
            >
              上一章
            </IonButton>
            <select
              :value="chapter.ref.chapterId"
              aria-label="选择章节"
              data-testid="picacomic-chapter-select"
              @change="selectChapter"
            >
              <option
                v-for="item in chapters"
                :key="item.ref.chapterId"
                :value="item.ref.chapterId"
              >
                第{{ item.ref.order }}话 {{ item.title }}
              </option>
            </select>
            <IonButton
              fill="outline"
              :disabled="!nextChapter"
              data-testid="picacomic-next-chapter"
              @click="switchChapter(nextChapter)"
            >
              下一章
            </IonButton>
          </section>

          <p class="reader-summary">
            第 {{ chapter.ref.order }} 话 · {{ chapter.images.length }} 页
          </p>
          <section class="image-list" data-testid="picacomic-reader-images">
            <article v-for="image in chapter.images" :key="image.imageKey" class="image-card">
              <img
                v-if="imageUrls.has(image.imageKey)"
                :src="imageUrls.get(image.imageKey)"
                :alt="`第 ${image.pageIndex} 页`"
                class="reader-image"
                @error="markFailed(image.imageKey)"
              />
              <div
                v-else-if="failedImages.has(image.imageKey)"
                class="image-failed"
                data-testid="picacomic-image-failed"
              >
                <span>第 {{ image.pageIndex }} 页加载失败</span>
                <IonButton
                  fill="outline"
                  size="small"
                  :disabled="retryingImages.has(image.imageKey)"
                  :data-testid="`picacomic-retry-${image.pageIndex}`"
                  @click="retryImage(image.imageKey)"
                >
                  {{ retryingImages.has(image.imageKey) ? '重试中…' : '重试' }}
                </IonButton>
              </div>
              <div v-else class="image-skeleton" :aria-label="`第 ${image.pageIndex} 页加载中`">
                第 {{ image.pageIndex }} 页加载中…
              </div>
            </article>
          </section>
        </template>
      </main>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({ name: 'PicacomicReaderPage' })

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  IonButton,
  IonButtons,
  IonContent,
  IonHeader,
  IonPage,
  IonTitle,
  IonToolbar,
} from '@ionic/vue'
import { picacomicErrorMessage, picacomicService, type PicacomicImageScope } from '../service'
import type { PicacomicAlbumDetail, PicacomicChapterDetail, PicacomicChapterRef } from '../types'
import { routeForPicacomicError } from '../routeGate'

const route = useRoute()
const router = useRouter()
const album = ref<PicacomicAlbumDetail | null>(null)
const chapter = ref<PicacomicChapterDetail | null>(null)
const chapters = computed(() => album.value?.chapters ?? [])
const loading = ref(true)
const errorMessage = ref('')
const imageUrls = ref(new Map<string, string>())
const failedImages = ref(new Set<string>())
const retryingImages = ref(new Set<string>())
let imageScope: PicacomicImageScope | null = null
let loadGeneration = 0

const albumId = computed(() => String(route.params.albumId ?? ''))
const chapterId = computed(() => String(route.params.chapterId ?? ''))
const previousChapter = computed(() => adjacentChapter(-1))
const nextChapter = computed(() => adjacentChapter(1))

onMounted(() => {
  void loadChapter()
})

watch([albumId, chapterId], () => {
  void loadChapter()
})

onBeforeUnmount(() => {
  loadGeneration++
  void disposeImageScope()
})

async function loadChapter() {
  const generation = ++loadGeneration
  loading.value = true
  errorMessage.value = ''
  imageUrls.value = new Map()
  failedImages.value = new Set()
  retryingImages.value = new Set()
  await disposeImageScope()
  try {
    const nextAlbum = await picacomicService.getAlbum(albumId.value)
    if (generation !== loadGeneration) return
    const target = nextAlbum.chapters.find((item) => item.ref.chapterId === chapterId.value)
    if (!target) {
      const stale = new Error('stale chapter') as Error & { code: string }
      stale.code = 'PICACOMIC_STALE_RESOURCE'
      throw stale
    }
    const nextChapter = await picacomicService.getPhoto(target.ref)
    if (generation !== loadGeneration) return
    album.value = nextAlbum
    chapter.value = nextChapter
    await loadImages(nextChapter, generation)
  } catch (error) {
    if (generation !== loadGeneration) return
    const redirect = routeForPicacomicError(error)
    if (redirect) {
      await router.replace(redirect)
      return
    }
    errorMessage.value = picacomicErrorMessage(error, '章节加载失败')
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}

async function loadImages(nextChapter: PicacomicChapterDetail, generation: number) {
  const scope = picacomicService.createImageScope({
    onReady: (event) => {
      if (generation !== loadGeneration) return
      const image = nextChapter.images.find((candidate) => candidate.imageKey === event.imageKey)
      if (!image) return
      const next = new Map(imageUrls.value)
      next.set(image.imageKey, image.cacheUrl)
      imageUrls.value = next
      const failed = new Set(failedImages.value)
      failed.delete(image.imageKey)
      failedImages.value = failed
    },
    onFailed: (event) => {
      if (generation !== loadGeneration) return
      const failed = new Set(failedImages.value)
      failed.add(event.imageKey)
      failedImages.value = failed
    },
  })
  imageScope = scope
  await scope.start()
  const result = await scope.request(nextChapter.images.map((image) => image.imageKey))
  if (generation !== loadGeneration) return
  const nextUrls = new Map(imageUrls.value)
  for (const image of nextChapter.images) {
    if (result.cached.includes(image.imageKey)) nextUrls.set(image.imageKey, image.cacheUrl)
  }
  imageUrls.value = nextUrls
}

async function retryImage(imageKey: string) {
  const scope = imageScope
  const currentChapter = chapter.value
  if (!scope || !currentChapter) return
  const retrying = new Set(retryingImages.value)
  retrying.add(imageKey)
  retryingImages.value = retrying
  const failed = new Set(failedImages.value)
  failed.delete(imageKey)
  failedImages.value = failed
  try {
    const result = await scope.retry(imageKey)
    const image = currentChapter.images.find((candidate) => candidate.imageKey === imageKey)
    if (image && result.cached.includes(imageKey)) {
      const next = new Map(imageUrls.value)
      next.set(imageKey, image.cacheUrl)
      imageUrls.value = next
    }
  } catch (error) {
    if (error && typeof error === 'object' && 'code' in error) {
      const nextFailed = new Set(failedImages.value)
      nextFailed.add(imageKey)
      failedImages.value = nextFailed
    }
  } finally {
    const nextRetrying = new Set(retryingImages.value)
    nextRetrying.delete(imageKey)
    retryingImages.value = nextRetrying
  }
}

function markFailed(imageKey: string) {
  const next = new Set(failedImages.value)
  next.add(imageKey)
  failedImages.value = next
  const urls = new Map(imageUrls.value)
  urls.delete(imageKey)
  imageUrls.value = urls
}

function adjacentChapter(delta: number): PicacomicChapterRef | null {
  const index = chapters.value.findIndex((item) => item.ref.chapterId === chapterId.value)
  const target = chapters.value[index + delta]
  return target?.ref ?? null
}

function switchChapter(ref: PicacomicChapterRef | null) {
  if (!ref) return
  void router.replace({
    name: 'PicacomicReaderPage',
    params: { albumId: ref.albumId, chapterId: ref.chapterId },
  })
}

function selectChapter(event: Event) {
  const target = event.target as HTMLSelectElement
  const selected = chapters.value.find((item) => item.ref.chapterId === target.value)
  switchChapter(selected?.ref ?? null)
}

async function disposeImageScope() {
  const scope = imageScope
  imageScope = null
  if (scope) await scope.dispose()
}

function goAlbum() {
  void router.replace({ name: 'PicacomicAlbumPage', params: { albumId: albumId.value } })
}
</script>

<style scoped>
.reader-page {
  width: min(100%, 920px);
  margin: 0 auto;
  box-sizing: border-box;
  padding: 16px 12px 42px;
}

.reader-state {
  padding: 36px 20px;
  color: #806451;
  text-align: center;
}

.reader-state p {
  margin: 0 0 14px;
}

.error-state {
  color: var(--ion-color-danger);
}

.reader-toolbar {
  position: sticky;
  top: 0;
  z-index: 2;
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 8px;
  align-items: center;
  padding: 6px;
  border: 1px solid rgb(245 210 188 / 0.7);
  border-radius: 12px;
  background: rgb(255 250 246 / 0.94);
  backdrop-filter: blur(8px);
}

.reader-toolbar select {
  min-width: 0;
  height: 36px;
  padding: 0 8px;
  border: 1px solid rgb(245 210 188 / 0.7);
  border-radius: 8px;
  background: #fff;
  color: #4c2a18;
  font-size: 12px;
}

.reader-summary {
  margin: 14px 4px 10px;
  color: #806451;
  font-size: 12px;
}

.image-list {
  display: grid;
  gap: 10px;
}

.image-card {
  min-height: 220px;
  overflow: hidden;
  border-radius: 10px;
  background: #f3e9e2;
}

.reader-image {
  display: block;
  width: 100%;
  min-height: 220px;
  object-fit: contain;
  background: #f3e9e2;
}

.image-skeleton,
.image-failed {
  display: grid;
  place-items: center;
  min-height: 220px;
  gap: 10px;
  color: #9a7660;
  font-size: 13px;
}

.image-failed {
  color: var(--ion-color-danger);
}

@media (max-width: 520px) {
  .reader-toolbar {
    grid-template-columns: 1fr 1fr;
  }

  .reader-toolbar select {
    grid-column: 1 / -1;
    grid-row: 1;
    width: 100%;
  }
}
</style>
