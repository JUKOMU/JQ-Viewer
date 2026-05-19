<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <IonButtons slot="start">
          <IonButton @click="$emit('back')" text="">
            <IonIcon :icon="arrowBack"/>
          </IonButton>
        </IonButtons>
        <IonTitle class="toolbar-title">{{ albumTitle || '章节选择' }}</IonTitle>
        <IonButtons slot="end">
          <button class="mode-btn" @click="toggleMode" :disabled="loadingAll">
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
          @click="onOpenChapter(ch, true)"
        >
          <span class="chapter-num">{{ chapterNum(ch) }}</span>
          <span class="chapter-title">{{ ch.chapterTitle }}</span>
          <span v-if="ch.totalPages > 0" class="chapter-pages">{{ ch.totalPages }} 页</span>
          <img
            v-if="ch.firstImageSortOrder"
            :src="getImageUrl(ch.chapterId, ch.firstImageSortOrder, 'image')"
            class="chapter-thumb"
            alt=""
          />
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
          @click="onOpenChapter(meta, downloadedIds.has(meta.id))"
        >
          <span class="chapter-num">第{{ meta.sortOrder }}话</span>
          <span class="chapter-title">{{ meta.title }}</span>
          <span v-if="downloadedIds.has(meta.id)" class="chapter-pages">{{ getDownloadedPages(meta.id) }} 页</span>
          <img
            v-if="downloadedIds.has(meta.id) && getDownloadedImage(meta.id)"
            :src="getDownloadedImage(meta.id)!"
            class="chapter-thumb"
            alt=""
          />
        </button>
      </div>

      <div class="bottom-spacer" />
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  IonBackButton,
  IonIcon,
  IonButtons,
  IonContent,
  IonHeader,
  IonPage,
  IonTitle,
  IonToolbar,
} from '@ionic/vue'
import { getImageUrl, JmcomicService } from '@/services/JmcomicService'
import type { DownloadTask, PhotoMeta } from '@/services/JmcomicTypes'
import { arrowBack } from 'ionicons/icons'

const route = useRoute()
const router = useRouter()

const albumId = computed(() => route.params.albumId as string)
const albumTitle = ref('')

type ShowMode = 'downloaded' | 'all'
const showMode = ref<ShowMode>('downloaded')
const loadingAll = ref(false)

// 已下载章节
const downloadedChapters = ref<DownloadTask[]>([])
const downloadedIds = computed(() => new Set(downloadedChapters.value.map(ch => ch.chapterId)))
const downloadedMap = computed(() => {
  const map = new Map<string, DownloadTask>()
  for (const ch of downloadedChapters.value) {
    map.set(ch.chapterId, ch)
  }
  return map
})

// 全部章节
const allChapters = ref<PhotoMeta[]>([])
const skeletonCount = 6

const chapterNum = (ch: DownloadTask) => {
  const so = ch.chapterSortOrder
  return so && so > 0 ? `第${so}话` : ch.chapterTitle
}

const getDownloadedPages = (chapterId: string): number => {
  return downloadedMap.value.get(chapterId)?.totalPages ?? 0
}

const getDownloadedImage = (chapterId: string): string | null => {
  const dt = downloadedMap.value.get(chapterId)
  if (!dt?.firstImageSortOrder) return null
  return getImageUrl(dt.chapterId, dt.firstImageSortOrder, 'image')
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

const onOpenChapter = (ch: PhotoMeta | DownloadTask, isDownloaded: boolean) => {
  const chapterId = 'chapterId' in ch ? ch.chapterId : ch.id
  const title = 'chapterTitle' in ch ? ch.chapterTitle : (ch as PhotoMeta).title
  const total = 'totalPages' in ch ? ch.totalPages : 0
  const query: Record<string, string> = {
    title: title ?? '',
    total: String(total),
  }
  if (isDownloaded) {
    query.source = 'download'
  }
  void router.push({
    path: `/album/${albumId.value}/read/${chapterId}`,
    query,
  })
}

onMounted(async () => {
  try {
    const result = await JmcomicService.getDownloadTasks()
    downloadedChapters.value = result.tasks
      .filter(t => t.status === 'completed' && t.albumId === albumId.value)
      .sort((a, b) => (a.chapterSortOrder ?? 0) - (b.chapterSortOrder ?? 0))
    if (downloadedChapters.value.length > 0) {
      albumTitle.value = downloadedChapters.value[0].albumTitle
    }
  } catch {
    // 离线或不支持时静默
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
  transition: background-color 0.18s ease, border-color 0.18s ease;
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
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
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
