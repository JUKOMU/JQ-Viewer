<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <IonButtons slot="start">
          <IonButton text="" @click="goBack">
            <IonIcon :icon="arrowBack" />
          </IonButton>
        </IonButtons>
        <IonTitle class="toolbar-title">{{ albumTitle || '章节选择' }}</IonTitle>
        <IonButtons slot="end">
          <button class="mode-btn" :disabled="loadingAll" @click="toggleMode">
            {{ showMode === 'downloaded' ? '显示全部章节' : '显示已下载' }}
          </button>
        </IonButtons>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <div class="chapter-page-container">
        <div class="chapter-content desktop-page-content">
          <!-- 已下载模式 -->
          <div v-if="showMode === 'downloaded'" class="chapter-grid">
            <button
              v-for="ch in downloadedChapters"
              :key="ch.chapterId"
              type="button"
              class="chapter-card downloaded"
              @click="openLocalChapter(ch)"
            >
              <span class="chapter-num">{{ chapterNum(ch) }}</span>
              <span class="chapter-title">{{ ch.chapterId }}</span>
              <span v-if="ch.totalPages > 0" class="chapter-pages">{{ ch.totalPages }} 页</span>
              <span class="source-row">
                <span v-if="ch.downloadTask" class="source-chip image">图片</span>
                <span v-if="ch.sources.cbz" class="source-chip cbz">CBZ</span>
                <span v-if="ch.sources.pdf" class="source-chip pdf">PDF</span>
              </span>
              <img v-if="chapterCover(ch)" :src="chapterCover(ch)!" class="chapter-thumb" alt="" />
            </button>
          </div>

          <!-- 全部章节模式：加载中骨架屏 -->
          <div v-else-if="loadingAll" class="chapter-grid">
            <div v-for="n in skeletonCount" :key="n" class="skeleton-card">
              <div class="sk-line sk-line--short" />
              <div class="sk-line" />
            </div>
          </div>

          <!-- 全部章节模式：章节列表 -->
          <div v-else class="chapter-grid">
            <button
              v-for="meta in allChapters"
              :key="meta.id"
              type="button"
              class="chapter-card"
              :class="{ downloaded: downloadedIds.has(meta.id) }"
              @click="onOpenChapter(meta)"
            >
              <span class="chapter-num">第{{ meta.sortOrder }}话</span>
              <span class="chapter-title">{{ meta.title }}</span>
              <span v-if="downloadedIds.has(meta.id)" class="chapter-pages"
                >{{ getDownloadedPages(meta.id) }} 页</span
              >
              <span v-if="downloadedIds.has(meta.id)" class="source-row">
                <span v-if="downloadedMap.get(meta.id)?.downloadTask" class="source-chip image"
                  >图片</span
                >
                <span v-if="downloadedMap.get(meta.id)?.sources.cbz" class="source-chip cbz"
                  >CBZ</span
                >
                <span v-if="downloadedMap.get(meta.id)?.sources.pdf" class="source-chip pdf"
                  >PDF</span
                >
              </span>
              <img
                v-if="downloadedIds.has(meta.id) && getDownloadedCover(meta.id)"
                :src="getDownloadedCover(meta.id)!"
                class="chapter-thumb"
                alt=""
              />
            </button>
          </div>

          <div class="bottom-spacer" />
        </div>
      </div>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  IonButton,
  IonButtons,
  IonContent,
  IonHeader,
  IonIcon,
  IonPage,
  IonTitle,
  IonToolbar,
} from '@ionic/vue'
import { getImageUrl, JmcomicService } from '@/services/JmcomicService'
import { ChapterSourceService, type ChapterSourceSummary } from '@/services/ChapterSourceService'
import type { PhotoMeta } from '@/services/JmcomicTypes'
import { arrowBack } from 'ionicons/icons'

defineOptions({ name: 'ChapterSelectPage' })

const route = useRoute()
const router = useRouter()
const albumId = computed(() => route.params.albumId as string)
const albumTitle = ref('')
const showMode = ref<'downloaded' | 'all'>('downloaded')
const loadingAll = ref(false)
const allChapters = ref<PhotoMeta[]>([])
const skeletonCount = 6

interface LocalChapter {
  albumId: string
  albumTitle: string
  chapterId: string
  chapterTitle: string
  chapterSortOrder: number
  totalPages: number
  coverUrl: string
  sources: ChapterSourceSummary
  downloadTask?: ChapterSourceSummary['download']
}

const downloadedChapters = ref<LocalChapter[]>([])
const downloadedIds = computed(() => new Set(downloadedChapters.value.map((ch) => ch.chapterId)))
const downloadedMap = computed(
  () => new Map(downloadedChapters.value.map((chapter) => [chapter.chapterId, chapter])),
)

const chapterNum = (chapter: LocalChapter) =>
  chapter.chapterSortOrder > 0 ? `第${chapter.chapterSortOrder}话` : chapter.chapterId
const getDownloadedPages = (chapterId: string) =>
  downloadedMap.value.get(chapterId)?.totalPages ?? 0
const getDownloadedCover = (chapterId: string) => {
  const chapter = downloadedMap.value.get(chapterId)
  return chapter ? chapterCover(chapter) : null
}
const chapterCover = (chapter: LocalChapter): string | null => {
  if (chapter.downloadTask?.firstImageSortOrder) {
    return getImageUrl(chapter.chapterId, chapter.downloadTask.firstImageSortOrder, 'thumb')
  }
  return (
    chapter.coverUrl ||
    chapter.sources.cbz?.file.coverUrl ||
    chapter.sources.pdf?.file.coverUrl ||
    null
  )
}

const toggleMode = async () => {
  showMode.value = showMode.value === 'downloaded' ? 'all' : 'downloaded'
  if (showMode.value !== 'all' || allChapters.value.length > 0) return
  loadingAll.value = true
  try {
    const album = await JmcomicService.getAlbum(albumId.value)
    albumTitle.value = album.title
    allChapters.value = album.photoMetas ?? []
  } finally {
    loadingAll.value = false
  }
}

const openLocalChapter = async (chapter: LocalChapter) => {
  const source = await ChapterSourceService.resolve(albumId.value, chapter.chapterId)
  if (!source) return
  await router.push(
    ChapterSourceService.readerLocation(source, {
      albumId: chapter.albumId,
      albumTitle: chapter.albumTitle,
      chapterId: chapter.chapterId,
      chapterTitle: chapter.chapterTitle,
      authors: chapter.sources.cbz?.file.authors || chapter.sources.pdf?.file.authors || '',
      coverUrl: chapterCover(chapter) || '',
      totalPages: chapter.totalPages,
    }),
  )
}

const onOpenChapter = async (chapter: PhotoMeta) => {
  const local = downloadedMap.value.get(chapter.id)
  if (local) {
    await openLocalChapter(local)
    return
  }
  await router.push({
    path: `/album/${albumId.value}/read/${chapter.id}`,
    query: { title: chapter.title ?? '', total: '0' },
  })
}

const goBack = () => router.back()

onMounted(async () => {
  try {
    const album = await JmcomicService.getAlbum(albumId.value)
    albumTitle.value = album.title
    allChapters.value = album.photoMetas ?? []
  } catch {
    // 离线时仍显示本地来源。
  }

  const sources = await ChapterSourceService.getAlbumSources(albumId.value).catch(
    () => new Map<string, ChapterSourceSummary>(),
  )
  downloadedChapters.value = [...sources.entries()]
    .map(([chapterId, summary]) => {
      const meta = allChapters.value.find((candidate) => candidate.id === chapterId)
      const local = summary.cbz ?? summary.pdf
      return {
        albumId: albumId.value,
        albumTitle: summary.download?.albumTitle || local?.file.albumTitle || albumTitle.value,
        chapterId,
        chapterTitle:
          meta?.title || summary.download?.chapterTitle || local?.chapter.chapterTitle || chapterId,
        chapterSortOrder:
          meta?.sortOrder ?? summary.download?.chapterSortOrder ?? local?.chapter.sortOrder ?? 0,
        totalPages: summary.download?.totalPages ?? local?.chapter.pageCount ?? 0,
        coverUrl: summary.download?.coverUrl || local?.file.coverUrl || '',
        sources: summary,
        downloadTask: summary.download,
      }
    })
    .sort((left, right) => left.chapterSortOrder - right.chapterSortOrder)
  if (!albumTitle.value && downloadedChapters.value.length > 0) {
    albumTitle.value = downloadedChapters.value[0].albumTitle
  }
})
</script>

<style scoped>
.chapter-page-container {
  width: 100%;
  container-type: inline-size;
}

.chapter-content {
  width: 100%;
  max-width: 1280px;
  margin-inline: auto;
}

.toolbar-title {
  font-size: 16px;
  font-weight: 600;
  color: #4c2a18;
  padding: 0;
}

.mode-btn {
  font-size: 12px;
  border: 1px solid rgb(245 210 188 / 0.7);
  background: #fffaf6;
  color: #8a6048;
  padding: 4px 10px;
  border-radius: 6px;
  cursor: pointer;
  white-space: nowrap;
}

.mode-btn:active {
  background: #f5d2bc;
}

.mode-btn:focus-visible,
.chapter-card:focus-visible {
  outline: 2px solid #e8843c;
  outline-offset: 2px;
}

/* 章节网格 */
.chapter-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
  padding: 12px 14px;
  align-items: start;
}

.chapter-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  min-width: 0;
  padding: 14px 10px;
  border: 1px solid rgb(245 210 188 / 0.7);
  border-radius: 12px;
  background: #fffaf6;
  color: #5a3d2e;
  text-align: center;
  transition:
    background-color 0.18s ease,
    border-color 0.18s ease;
  cursor: pointer;
}

.chapter-card.downloaded {
  background: #fff0e7;
  border-color: #fa9c69;
}

.chapter-card:active {
  background: #f5d2bc;
}

.chapter-num {
  font-size: 11px;
  font-weight: 700;
  color: #a07858;
}

.chapter-card.downloaded .chapter-num {
  color: #e07030;
}

.chapter-title {
  max-width: 100%;
  font-size: 11px;
  line-height: 1.3;
  overflow-wrap: anywhere;
}

.chapter-pages {
  font-size: 10px;
  color: #b89a84;
}

.source-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}

.source-chip {
  font-size: 10px;
  line-height: 1;
  padding: 3px 7px;
  border-radius: 999px;
}

.source-chip.image {
  background: #eaf7ea;
  color: #52a86b;
}

.source-chip.pdf {
  background: #ffeaea;
  color: #d9534f;
}

.source-chip.cbz {
  background: #edf8f1;
  color: #397d54;
}

.chapter-thumb {
  width: 100%;
  height: auto;
  max-height: 140px;
  object-fit: cover;
  border-radius: 6px;
  margin-top: 6px;
}

/* 骨架卡片 */
.skeleton-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 14px 10px;
  border: 1px solid rgb(245 210 188 / 0.3);
  border-radius: 12px;
  background: #fffaf6;
}

.skeleton-card .sk-line {
  height: 12px;
  width: 70%;
  border-radius: 6px;
  background: linear-gradient(90deg, #f3ded0 25%, #ffece0 50%, #f3ded0 75%);
  background-size: 200% 100%;
  animation: shimmer 1.4s ease infinite;
}

.skeleton-card .sk-line--short {
  width: 45%;
  height: 11px;
}

@keyframes shimmer {
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}

.bottom-spacer {
  height: 60px;
}

@media (min-width: 680px) {
  .chapter-grid {
    width: 100%;
    max-width: 1140px;
    box-sizing: border-box;
    grid-template-columns: repeat(auto-fill, minmax(196px, 1fr));
    gap: 12px;
    padding: 16px 24px;
    margin-inline: auto;
  }
}

@media (hover: hover) and (pointer: fine) {
  .mode-btn:hover:not(:disabled) {
    background: #fff0e7;
    border-color: #fa9c69;
  }

  .chapter-card:hover {
    background: #fff4ed;
    border-color: #f2b58f;
  }

  .chapter-card.downloaded:hover {
    background: #ffe5d6;
    border-color: #e8843c;
  }
}

@media (prefers-reduced-motion: reduce) {
  .chapter-card {
    transition: none;
  }

  .skeleton-card .sk-line {
    animation: none;
  }
}

@container (min-width: 960px) {
  .chapter-grid {
    grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
    gap: 12px;
    align-items: stretch;
  }

  .chapter-card {
    width: 100%;
    min-width: 0;
    height: 100%;
  }
}
</style>
