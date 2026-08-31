<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <IonButtons slot="start">
          <IonButton fill="clear" aria-label="返回首页" @click="goHome">返回</IonButton>
        </IonButtons>
        <IonTitle>PicaComic 浏览</IonTitle>
        <IonButtons slot="end">
          <IonButton fill="clear" data-testid="picacomic-logout" @click="logout">退出</IonButton>
        </IonButtons>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <main class="pica-page">
        <section class="pica-card search-card" aria-labelledby="browse-title">
          <div class="section-heading">
            <div>
              <p class="eyebrow">FAKE READ-ONLY CATALOG</p>
              <h1 id="browse-title">搜索或浏览分类</h1>
            </div>
            <span v-if="auth.user.value" class="session-chip">{{ auth.user.value.username }}</span>
          </div>
          <form class="search-row" data-testid="picacomic-search-form" @submit.prevent="runSearch">
            <IonInput
              v-model="keyword"
              label="关键词"
              label-placement="stacked"
              placeholder="输入关键词（fixture）"
              data-testid="picacomic-search-input"
              :disabled="loading"
            />
            <IonButton type="submit" :disabled="loading" data-testid="picacomic-search-submit">
              搜索
            </IonButton>
            <IonButton
              type="button"
              fill="outline"
              :disabled="loading"
              data-testid="picacomic-category-submit"
              @click="runCategory"
            >
              全部分类
            </IonButton>
          </form>
          <p class="hint">可用 fixture：普通关键词、empty、401、403、network、parse。</p>
        </section>

        <section class="result-section" aria-live="polite">
          <div v-if="loading && !result" class="state-card" data-testid="picacomic-loading">
            正在加载目录…
          </div>
          <div
            v-else-if="errorMessage"
            class="state-card error-state"
            data-testid="picacomic-error"
          >
            <p>{{ errorMessage }}</p>
            <IonButton
              fill="outline"
              :disabled="loading"
              data-testid="picacomic-retry"
              @click="retry"
            >
              重试
            </IonButton>
          </div>
          <div
            v-else-if="result && result.items.length === 0"
            class="state-card"
            data-testid="picacomic-empty"
          >
            没有找到结果
          </div>
          <template v-else-if="result">
            <div class="result-summary">
              <span
                >第 {{ result.currentPage }}/{{ result.totalPages }} 页 · 共
                {{ result.totalItems }} 条</span
              >
              <span v-if="loading">更新中…</span>
            </div>
            <div class="catalog-grid" data-testid="picacomic-results">
              <button
                v-for="item in result.items"
                :key="item.ref.albumId"
                type="button"
                class="catalog-card"
                :data-testid="`picacomic-album-${item.ref.albumId}`"
                @click="openAlbum(item.ref)"
              >
                <span class="cover-wrap">
                  <img
                    v-if="coverUrls.has(item.ref.albumId)"
                    :src="coverUrls.get(item.ref.albumId)"
                    :alt="item.title"
                    class="cover-image"
                  />
                  <span v-else class="cover-placeholder">封面</span>
                </span>
                <span class="catalog-copy">
                  <strong>{{ item.title || '未命名作品' }}</strong>
                  <small>{{ item.authors.join(' / ') || '作者未知' }}</small>
                  <small>{{ item.pagesCount }} 页 · {{ item.finished ? '完结' : '连载' }}</small>
                </span>
              </button>
            </div>
            <div class="pagination" data-testid="picacomic-pagination">
              <IonButton
                fill="outline"
                :disabled="loading || result.currentPage <= 1"
                data-testid="picacomic-page-previous"
                @click="loadPage(result.currentPage - 1)"
              >
                上一页
              </IonButton>
              <IonButton
                fill="outline"
                :disabled="loading || result.currentPage >= result.totalPages"
                data-testid="picacomic-page-next"
                @click="loadPage(result.currentPage + 1)"
              >
                下一页
              </IonButton>
            </div>
          </template>
          <div v-else class="state-card">输入关键词开始浏览。</div>
        </section>
      </main>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({ name: 'PicacomicBrowsePage' })

import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  IonButton,
  IonButtons,
  IonContent,
  IonHeader,
  IonInput,
  IonPage,
  IonTitle,
  IonToolbar,
} from '@ionic/vue'
import { usePicacomicAuth } from '../auth'
import {
  isPicacomicAuthError,
  picacomicErrorMessage,
  picacomicService,
  type PicacomicImageScope,
} from '../service'
import type { PicacomicAlbumRef, PicacomicCatalogPage } from '../types'
import { routeForPicacomicError } from '../routeGate'

const router = useRouter()
const auth = usePicacomicAuth()
const keyword = ref('')
const category = ref('all')
const page = ref(1)
const result = ref<PicacomicCatalogPage | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const coverUrls = ref(new Map<string, string>())
let imageScope: PicacomicImageScope | null = null
let loadGeneration = 0
let lastMode: 'search' | 'category' = 'search'

onMounted(async () => {
  try {
    const state = await auth.refresh()
    if (state.state === 'signed_in') return
  } catch {
    // The route guard handles the redirect; this is defensive for direct mounts.
  }
  await router.replace({ name: 'PicacomicLoginPage' })
})

onBeforeUnmount(() => {
  loadGeneration++
  void disposeImageScope()
})

async function runSearch() {
  lastMode = 'search'
  page.value = 1
  await loadPage(1)
}

async function runCategory() {
  lastMode = 'category'
  page.value = 1
  await loadPage(1)
}

async function retry() {
  await loadPage(page.value)
}

async function loadPage(nextPage: number) {
  const generation = ++loadGeneration
  loading.value = true
  errorMessage.value = ''
  try {
    const next =
      lastMode === 'category'
        ? await picacomicService.categories(category.value, nextPage)
        : await picacomicService.search(keyword.value.trim(), nextPage)
    if (generation !== loadGeneration) return
    result.value = next
    page.value = next.currentPage
    await loadCovers(next, generation)
  } catch (error) {
    if (generation !== loadGeneration) return
    const redirect = routeForPicacomicError(error)
    if (redirect) {
      await router.replace(redirect)
      return
    }
    if (isPicacomicAuthError(error)) {
      await router.replace({ name: 'PicacomicLoginPage' })
      return
    }
    errorMessage.value = picacomicErrorMessage(error)
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}

async function loadCovers(next: PicacomicCatalogPage, generation: number) {
  await disposeImageScope()
  const scope = picacomicService.createImageScope({
    onReady: (event) => {
      if (generation !== loadGeneration) return
      const item = next.items.find((candidate) => candidate.cover?.imageKey === event.imageKey)
      if (item?.cover) coverUrls.value.set(item.ref.albumId, item.cover.cacheUrl)
    },
    onFailed: () => {
      // A failed cover does not block browsing the metadata.
    },
  })
  imageScope = scope
  const covers = next.items.flatMap((item) => (item.cover ? [item.cover] : []))
  await scope.start()
  const requested = await scope.request(covers.map((cover) => cover.imageKey))
  if (generation !== loadGeneration) return
  for (const imageKey of requested.cached) {
    const item = next.items.find((candidate) => candidate.cover?.imageKey === imageKey)
    if (item?.cover) coverUrls.value.set(item.ref.albumId, item.cover.cacheUrl)
  }
}

async function disposeImageScope() {
  const scope = imageScope
  imageScope = null
  if (scope) await scope.dispose()
}

function openAlbum(ref: PicacomicAlbumRef) {
  void router.push({ name: 'PicacomicAlbumPage', params: { albumId: ref.albumId } })
}

function goHome() {
  void router.replace('/home')
}

async function logout() {
  await auth.logout()
  await router.replace({ name: 'PicacomicLoginPage' })
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

.search-card {
  padding: 20px;
}

.section-heading,
.search-row,
.result-summary,
.pagination {
  display: flex;
  align-items: center;
  gap: 10px;
}

.section-heading,
.result-summary {
  justify-content: space-between;
}

.eyebrow {
  margin: 0 0 6px;
  color: #bd652a;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.12em;
}

h1 {
  margin: 0;
  color: #4c2a18;
  font-size: 22px;
}

.session-chip {
  padding: 6px 9px;
  border-radius: 999px;
  background: #f6e1d4;
  color: #8a6048;
  font-size: 12px;
}

.search-row {
  align-items: end;
  margin-top: 18px;
}

.search-row ion-input {
  flex: 1;
}

.hint,
.result-summary {
  color: #806451;
  font-size: 12px;
}

.hint {
  margin: 10px 0 0;
}

.result-section {
  margin-top: 16px;
}

.state-card {
  padding: 28px 20px;
  color: #806451;
  text-align: center;
}

.state-card p {
  margin: 0 0 12px;
}

.error-state {
  color: var(--ion-color-danger);
}

.result-summary {
  padding: 4px 2px 10px;
}

.catalog-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}

.catalog-card {
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
  border: 1px solid rgb(245 210 188 / 0.72);
  border-radius: 14px;
  background: #fffaf6;
  color: #4c2a18;
  text-align: start;
  cursor: pointer;
}

.catalog-card:active {
  transform: scale(0.985);
}

.cover-wrap {
  display: block;
  aspect-ratio: 3 / 4;
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
  font-size: 12px;
}

.catalog-copy {
  display: grid;
  gap: 4px;
  padding: 11px;
}

.catalog-copy strong {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  font-size: 14px;
}

.catalog-copy small {
  overflow: hidden;
  color: #806451;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pagination {
  justify-content: center;
  margin-top: 18px;
}
</style>
