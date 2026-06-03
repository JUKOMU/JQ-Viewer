<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <IonButtons slot="start">
          <IonButton text="" @click="goBack">
            <IonIcon :icon="arrowBack"/>
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
            <span v-if="ch.pdfData" class="source-chip pdf">PDF</span>
          </span>
          <img
            v-if="chapterCover(ch)"
            :src="chapterCover(ch)!"
            class="chapter-thumb"
            alt=""
          />
        </button>
      </div>

      <!-- 全部章节模式：加载中骨架屏 -->
      <div v-else-if="loadingAll" class="chapter-grid">
        <div v-for="n in skeletonCount" :key="n" class="skeleton-card">
          <div class="sk-line sk-line--short"/>
          <div class="sk-line"/>
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
          @click="onOpenChapter(meta, downloadedIds.has(meta.id))"
        >
          <span class="chapter-num">第{{ meta.sortOrder }}话</span>
          <span class="chapter-title">{{ meta.title }}</span>
          <span v-if="downloadedIds.has(meta.id)" class="chapter-pages"
          >{{ getDownloadedPages(meta.id) }} 页</span
          >
          <span v-if="downloadedIds.has(meta.id)" class="source-row">
            <span v-if="downloadedMap.get(meta.id)?.downloadTask" class="source-chip image">图片</span>
            <span v-if="downloadedMap.get(meta.id)?.pdfData" class="source-chip pdf">PDF</span>
          </span>
          <img
            v-if="downloadedIds.has(meta.id) && getDownloadedCover(meta.id)"
            :src="getDownloadedCover(meta.id)!"
            class="chapter-thumb"
            alt=""
          />
        </button>
      </div>

      <div class="bottom-spacer"/>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
import {computed, onMounted, ref} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {IonButton, IonButtons, IonContent, IonHeader, IonIcon, IonPage, IonTitle, IonToolbar,} from '@ionic/vue'
import {getImageUrl, JmcomicService} from '@/services/JmcomicService'
import type {ImportedPdf, PhotoMeta} from '@/services/JmcomicTypes'
import {arrowBack} from 'ionicons/icons'

defineOptions({name: 'ChapterSelectPage'})

const route = useRoute()
const router = useRouter()

const albumId = computed(() => route.params.albumId as string)
const albumTitle = ref('')

type ShowMode = 'downloaded' | 'all'
const showMode = ref<ShowMode>('downloaded')
const loadingAll = ref(false)

interface LocalChapter {
  albumId: string
  albumTitle: string
  chapterId: string
  chapterTitle: string
  chapterSortOrder: number
  totalPages: number
  coverUrl: string
  downloadTask?: {
    firstImageSortOrder?: number
  }
  pdfData?: ImportedPdf
}

// 已下载/已导入章节
const downloadedChapters = ref<LocalChapter[]>([])
const downloadedIds = computed(() => new Set(downloadedChapters.value.map((ch) => ch.chapterId)))
const downloadedMap = computed(() => {
  const map = new Map<string, LocalChapter>()
  for (const ch of downloadedChapters.value) {
    map.set(ch.chapterId, ch)
  }
  return map
})

// 全部章节
const allChapters = ref<PhotoMeta[]>([])
const skeletonCount = 6

const chapterNum = (ch: LocalChapter) => {
  const so = ch.chapterSortOrder
  return so && so > 0 ? `第${so}话` : ch.chapterId
}

const getDownloadedPages = (chapterId: string): number => {
  return downloadedMap.value.get(chapterId)?.totalPages ?? 0
}

const getDownloadedCover = (chapterId: string): string | null => {
  const dt = downloadedMap.value.get(chapterId)
  return dt ? chapterCover(dt) : null
}

const chapterCover = (ch: LocalChapter): string | null => {
  if (ch.downloadTask?.firstImageSortOrder) {
    return getImageUrl(ch.chapterId, ch.downloadTask.firstImageSortOrder, 'thumb')
  }
  return ch.coverUrl || ch.pdfData?.coverUrl || null
}

const toggleMode = async () => {
  if (showMode.value === 'downloaded') {
    showMode.value = 'all'
    if (allChapters.value.length === 0) {
      loadingAll.value = true
      try {
        const album = await JmcomicService.getAlbum(albumId.value)
        albumTitle.value = album.title
        allChapters.value = album.photoMetas ?? []
      } catch {
        // 加载失败，保持在已下载列表可见
      } finally {
        loadingAll.value = false
      }
    }
  } else {
    showMode.value = 'downloaded'
  }
}

const openLocalChapter = (ch: LocalChapter) => {
  if (ch.downloadTask) {
    void router.push({
      path: `/album/${albumId.value}/read/${ch.chapterId}`,
      query: {
        title: ch.chapterId,
        total: String(ch.totalPages),
        source: 'download',
      },
    })
    return
  }

  if (ch.pdfData?.filePath) {
    void router.push({
      path: '/pdf-reader',
      query: {
        path: ch.pdfData.filePath,
        title: ch.pdfData.fileName,
        albumId: ch.albumId,
        albumTitle: ch.albumTitle,
        authors: ch.pdfData.authors,
        coverUrl: ch.pdfData.coverUrl || ch.coverUrl,
        chapterId: ch.chapterId,
        chapterTitle: ch.chapterId,
      },
    })
    return
  }
}

const onOpenChapter = (ch: PhotoMeta, isDownloaded: boolean) => {
  const chapterId = ch.id
  const localChapter = downloadedMap.value.get(chapterId)
  if (isDownloaded && localChapter) {
    openLocalChapter(localChapter)
    return
  }

  const query: Record<string, string> = {
    title: ch.title ?? '',
    total: '0',
  }
  void router.push({
    path: `/album/${albumId.value}/read/${chapterId}`,
    query,
  })
}

const goBack = () => {
  router.back()
}

onMounted(async () => {
  const localChapters = new Map<string, LocalChapter>()
  let albumMetas: PhotoMeta[] = []

  const resolvePdfLocalKey = (pdf: ImportedPdf): string => {
    const exact = albumMetas.find((meta) => meta.id === pdf.chapterId)
    if (exact) return exact.id

    const byOrder = albumMetas.find((meta) => meta.sortOrder === pdf.chapterSortOrder)
    if (byOrder) return byOrder.id

    if (pdf.chapterSortOrder && pdf.chapterSortOrder > 0) {
      const localByOrder = [...localChapters.values()].find(
        (chapter) => chapter.chapterSortOrder === pdf.chapterSortOrder,
      )
      if (localByOrder) return localByOrder.chapterId
    }

    if (pdf.chapterId && localChapters.has(pdf.chapterId)) return pdf.chapterId

    if ((pdf.chapterId === pdf.albumId || !pdf.chapterId) && localChapters.size === 1) {
      return [...localChapters.keys()][0]
    }
    return pdf.chapterId || pdf.albumId
  }

  const findAlbumMeta = (chapterId: string, sortOrder?: number): PhotoMeta | undefined => {
    const exact = albumMetas.find((meta) => meta.id === chapterId)
    if (exact) return exact
    if (sortOrder && sortOrder > 0) {
      return albumMetas.find((meta) => meta.sortOrder === sortOrder)
    }
    return undefined
  }

  try {
    const album = await JmcomicService.getAlbum(albumId.value)
    albumTitle.value = album.title
    albumMetas = album.photoMetas ?? []
    allChapters.value = albumMetas
  } catch {
    // 网络失败时仍保留本地下载/PDF 列表可见
  }

  try {
    const downloadResult = await JmcomicService.getDownloadTasks()
    for (const t of downloadResult.tasks
      .filter((t) => t.status === 'completed' && t.albumId === albumId.value)
    ) {
      const meta = findAlbumMeta(t.chapterId, t.chapterSortOrder)
      localChapters.set(t.chapterId, {
        albumId: t.albumId,
        albumTitle: t.albumTitle,
        chapterId: t.chapterId,
        chapterTitle: meta?.title || t.chapterTitle,
        chapterSortOrder: meta?.sortOrder ?? t.chapterSortOrder ?? 0,
        totalPages: t.totalPages,
        coverUrl: t.coverUrl,
        downloadTask: {
          firstImageSortOrder: t.firstImageSortOrder,
        },
      })
    }
  } catch {
    // 离线或不支持时保留 PDF 导入列表加载机会
  }

  try {
    const pdfResult = await JmcomicService.getImportedPdfs()
    for (const p of pdfResult.pdfs
      .filter((p) => p.albumId === albumId.value)
    ) {
      const chapterId = resolvePdfLocalKey(p)
      const current = localChapters.get(chapterId)
      if (current) {
        const meta = findAlbumMeta(chapterId, p.chapterSortOrder)
        current.pdfData = p
        current.coverUrl ||= p.coverUrl
        current.chapterTitle = meta?.title || current.chapterTitle
        current.chapterSortOrder = meta?.sortOrder ?? current.chapterSortOrder
        if (!current.downloadTask) {
          current.albumTitle = p.albumTitle || p.fileName || p.albumId
          current.chapterTitle = meta?.title || p.chapterTitle || p.fileName
          current.chapterSortOrder = meta?.sortOrder ?? p.chapterSortOrder ?? 0
          current.totalPages = p.pageCount ?? 0
        }
      } else {
        const meta = findAlbumMeta(chapterId, p.chapterSortOrder)
        localChapters.set(chapterId, {
          albumId: p.albumId,
          albumTitle: p.albumTitle || p.fileName || p.albumId,
          chapterId,
          chapterTitle: meta?.title || p.chapterTitle || p.fileName,
          chapterSortOrder: meta?.sortOrder ?? p.chapterSortOrder ?? 0,
          totalPages: p.pageCount ?? 0,
          coverUrl: p.coverUrl,
          pdfData: p,
        })
      }
    }
  } catch {
    // 导入 PDF 列表读取失败不影响图片下载章节
  }

  downloadedChapters.value = [...localChapters.values()]
    .sort((a, b) => (a.chapterSortOrder ?? 0) - (b.chapterSortOrder ?? 0))
  if (downloadedChapters.value.length > 0) {
    albumTitle.value = downloadedChapters.value[0].albumTitle
  }
})
</script>

<style scoped>
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
  padding: 14px 10px;
  border: 1px solid rgb(245 210 188 / 0.7);
  border-radius: 12px;
  background: #fffaf6;
  color: #5a3d2e;
  text-align: center;
  transition: background-color 0.18s ease,
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
  font-size: 11px;
  line-height: 1.3;
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
    grid-template-columns: repeat(3, 1fr);
  }
}
</style>
