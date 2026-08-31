<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <IonButtons slot="start">
          <IonButton fill="clear" aria-label="返回浏览" @click="goBrowse">返回</IonButton>
        </IonButtons>
        <IonTitle>PicaComic 详情</IonTitle>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <main class="pica-page">
        <div v-if="loading" class="state-card" data-testid="picacomic-album-loading">
          正在加载详情…
        </div>
        <div
          v-else-if="errorMessage"
          class="state-card error-state"
          data-testid="picacomic-album-error"
        >
          <p>{{ errorMessage }}</p>
          <IonButton fill="outline" @click="loadAlbum">重试</IonButton>
        </div>
        <template v-else-if="album">
          <section class="album-hero pica-card">
            <div class="cover-wrap">
              <img v-if="coverUrl" :src="coverUrl" :alt="album.title" class="cover-image" />
              <span v-else class="cover-placeholder">封面</span>
            </div>
            <div class="hero-copy">
              <p class="eyebrow">PICA ALBUM · {{ album.ref.albumId }}</p>
              <h1>{{ album.title || '未命名作品' }}</h1>
              <p class="authors">{{ album.authors.join(' / ') || '作者未知' }}</p>
              <p class="description">{{ album.description || '暂无简介' }}</p>
              <div class="chips">
                <span v-for="tag in album.tags" :key="tag" class="chip">{{ tag }}</span>
                <span class="chip">{{ album.finished ? '完结' : '连载' }}</span>
              </div>
            </div>
          </section>

          <section class="pica-card chapter-card" aria-labelledby="chapters-title">
            <div class="section-heading">
              <div>
                <p class="eyebrow">READ-ONLY CHAPTERS</p>
                <h2 id="chapters-title">章节</h2>
              </div>
              <span class="chapter-count">{{ album.chapters.length }} 章</span>
            </div>
            <div v-if="album.chapters.length" class="chapter-list" data-testid="picacomic-chapters">
              <button
                v-for="chapter in album.chapters"
                :key="chapter.ref.chapterId"
                type="button"
                class="chapter-item"
                :data-testid="`picacomic-chapter-${chapter.ref.chapterId}`"
                @click="openReader(chapter.ref)"
              >
                <span class="chapter-order">第{{ chapter.ref.order }}话</span>
                <span class="chapter-title">{{ chapter.title || '未命名章节' }}</span>
                <span class="chapter-date">{{ chapter.updatedAt || '—' }}</span>
              </button>
            </div>
            <div v-else class="empty-chapters" data-testid="picacomic-no-chapters">暂无章节</div>
          </section>
        </template>
      </main>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({ name: 'PicacomicAlbumPage' })

import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
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
import type { PicacomicAlbumDetail, PicacomicChapterRef } from '../types'
import { routeForPicacomicError } from '../routeGate'

const route = useRoute()
const router = useRouter()
const album = ref<PicacomicAlbumDetail | null>(null)
const loading = ref(true)
const errorMessage = ref('')
const coverUrl = ref('')
let imageScope: PicacomicImageScope | null = null
let loadGeneration = 0

const albumId = computed(() => String(route.params.albumId ?? ''))

onMounted(() => {
  void loadAlbum()
})

onBeforeUnmount(() => {
  loadGeneration++
  void disposeImageScope()
})

async function loadAlbum() {
  const generation = ++loadGeneration
  loading.value = true
  errorMessage.value = ''
  try {
    const next = await picacomicService.getAlbum(albumId.value)
    if (generation !== loadGeneration) return
    album.value = next
    coverUrl.value = ''
    await loadCover(next, generation)
  } catch (error) {
    if (generation !== loadGeneration) return
    const redirect = routeForPicacomicError(error)
    if (redirect) {
      await router.replace(redirect)
      return
    }
    errorMessage.value = picacomicErrorMessage(error)
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}

async function loadCover(next: PicacomicAlbumDetail, generation: number) {
  await disposeImageScope()
  if (!next.cover) return
  const scope = picacomicService.createImageScope({
    onReady: (event) => {
      if (generation === loadGeneration && event.imageKey === next.cover?.imageKey) {
        coverUrl.value = next.cover.cacheUrl
      }
    },
    onFailed: () => {
      // Metadata and chapters remain available when the cover cannot load.
    },
  })
  imageScope = scope
  await scope.start()
  const result = await scope.request([next.cover.imageKey])
  if (generation === loadGeneration && result.cached.includes(next.cover.imageKey)) {
    coverUrl.value = next.cover.cacheUrl
  }
}

async function disposeImageScope() {
  const scope = imageScope
  imageScope = null
  if (scope) await scope.dispose()
}

function openReader(ref: PicacomicChapterRef) {
  void router.push({
    name: 'PicacomicReaderPage',
    params: { albumId: ref.albumId, chapterId: ref.chapterId },
  })
}

function goBrowse() {
  void router.replace({ name: 'PicacomicBrowsePage' })
}
</script>

<style scoped>
.pica-page {
  width: min(100%, 920px);
  margin: 0 auto;
  box-sizing: border-box;
  padding: 20px 16px 48px;
}

.pica-card,
.state-card {
  border: 1px solid rgb(245 210 188 / 0.7);
  border-radius: 16px;
  background: #fffaf6;
  box-shadow: 0 10px 28px rgb(76 42 24 / 0.06);
}

.album-hero {
  display: grid;
  grid-template-columns: minmax(130px, 190px) 1fr;
  gap: 20px;
  padding: 20px;
}

.cover-wrap {
  aspect-ratio: 3 / 4;
  overflow: hidden;
  border-radius: 12px;
  background: #f3e9e2;
}

.cover-image,
.cover-placeholder {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cover-placeholder {
  display: grid;
  place-items: center;
  color: #bd9a85;
}

.eyebrow {
  margin: 0 0 6px;
  color: #bd652a;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.1em;
}

h1,
h2 {
  margin: 0;
  color: #4c2a18;
}

h1 {
  font-size: 24px;
}

h2 {
  font-size: 19px;
}

.authors,
.description,
.chapter-count,
.chapter-date {
  color: #806451;
  font-size: 12px;
  line-height: 1.6;
}

.description {
  margin: 16px 0;
  white-space: pre-wrap;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chip {
  padding: 4px 8px;
  border-radius: 999px;
  background: #f6e1d4;
  color: #8a6048;
  font-size: 11px;
}

.chapter-card {
  margin-top: 16px;
  padding: 20px;
}

.section-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.chapter-list {
  display: grid;
  gap: 7px;
}

.chapter-item {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 12px;
  align-items: center;
  width: 100%;
  padding: 12px 14px;
  border: 1px solid rgb(245 210 188 / 0.56);
  border-radius: 10px;
  background: #fff;
  color: #4c2a18;
  text-align: start;
  cursor: pointer;
}

.chapter-item:active {
  background: #fff1e8;
}

.chapter-order {
  color: #bd652a;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

.chapter-title {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.empty-chapters,
.state-card {
  padding: 28px 20px;
  color: #806451;
  text-align: center;
}

.error-state {
  color: var(--ion-color-danger);
}

@media (max-width: 560px) {
  .album-hero {
    grid-template-columns: 112px 1fr;
    gap: 14px;
    padding: 14px;
  }

  .chapter-item {
    grid-template-columns: auto 1fr;
  }

  .chapter-date {
    grid-column: 2;
  }
}
</style>
