<template>
  <IonPage>
    <IonContent ref="contentRef">
      <Transition name="search-overlay">
        <div v-if="searchOverlayVisible" class="search-overlay" @click.self="closeSearchOverlay">
          <div class="search-overlay-panel">
            <SearchHeaderBar
                ref="overlaySearchRef"
                :query="currentQuery"
                :loading="loading"
                @search="submitOverlaySearch"
            />
          </div>
        </div>
      </Transition>

      <div class="search-page-top">
        <div class="search-page-toolbar">
          <MenuToggleButton/>
          <div class="toolbar-search">
            <SearchHeaderBar
                ref="headerSearchRef"
                :query="currentQuery"
                :loading="loading"
                @search="submitSearch"
            />
          </div>
        </div>
      </div>
      <SearchResultContainer
          :result="result"
          :loading="loading"
          :error-message="errorMessage"
          :mode="displayMode"
          idle-text="请输入关键词开始搜索"
          @mode-change="displayMode = $event"
          @retry="retrySearch"
      />
      <QuickActionFab
          slot="fixed"
          @search="openSearch"
          @jump="jumpToPage"
          @top="scrollToTop"
          @back="goBack"
      />
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
import {computed, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {
  alertController,
  IonContent,
  IonPage,
} from '@ionic/vue'
import MenuToggleButton from '@/components/common/MenuToggleButton.vue'
import QuickActionFab from '@/components/common/QuickActionFab.vue'
import SearchHeaderBar from '@/components/search/SearchHeaderBar.vue'
import SearchResultContainer from '@/components/search/SearchResultContainer.vue'
import {JmcomicService} from '@/services/JmcomicService'
import type {SearchQuery, SearchResult} from '@/services/JmcomicTypes'

const route = useRoute()
const router = useRouter()

const result = ref<SearchResult | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const displayMode = ref<'list' | 'grid'>('list')
const contentRef = ref<InstanceType<typeof IonContent> | null>(null)
const headerSearchRef = ref<{ focusInput: () => Promise<void> } | null>(null)
const overlaySearchRef = ref<{ focusInput: () => Promise<void> } | null>(null)
const searchOverlayVisible = ref(false)

const readFirst = (value: unknown): string | undefined => {
  if (Array.isArray(value)) {
    return typeof value[0] === 'string' ? value[0] : undefined
  }
  return typeof value === 'string' ? value : undefined
}

const readNumber = (value: unknown, fallback: number): number => {
  const parsed = Number(readFirst(value))
  return Number.isFinite(parsed) ? parsed : fallback
}

const currentQuery = computed<SearchQuery>(() => ({
  keyword: readFirst(route.query.keyword) ?? '',
  orderBy: readFirst(route.query.orderBy) ?? 'mr',
  time: readFirst(route.query.time) ?? 'a',
  searchMainTag: readNumber(route.query.searchMainTag, 0),
  page: readNumber(route.query.page, 1),
}))

const runSearch = async (query: SearchQuery) => {
  loading.value = true
  errorMessage.value = ''
  try {
    result.value = query.keyword?.trim()
        ? await JmcomicService.search(query)
        : await JmcomicService.categories(query)
  } catch (error) {
    result.value = null
    errorMessage.value = error instanceof Error ? error.message : '搜索失败'
  } finally {
    loading.value = false
  }
}

const updateRouteQuery = (query: SearchQuery) => {
  void router.push({
    path: '/search',
    query: {
      keyword: query.keyword ?? '',
      orderBy: query.orderBy,
      time: query.time,
      searchMainTag: String(query.searchMainTag),
      page: String(query.page ?? 1),
    },
  })
}

const submitSearch = (query: SearchQuery) => {
  updateRouteQuery({...query, page: 1})
}

const submitOverlaySearch = (query: SearchQuery) => {
  closeSearchOverlay()
  updateRouteQuery({...query, page: 1})
}

const retrySearch = () => {
  void runSearch(currentQuery.value)
}

const openSearch = () => {
  searchOverlayVisible.value = true
  requestAnimationFrame(() => {
    void overlaySearchRef.value?.focusInput?.()
  })
}

const closeSearchOverlay = () => {
  searchOverlayVisible.value = false
}

const scrollToTop = () => {
  void contentRef.value?.$el?.scrollToTop?.(300)
}

const goBack = () => {
  if (window.history.length > 1) {
    router.back()
    return
  }
  void router.push('/home')
}

const jumpToPage = async () => {
  if (!result.value) {
    return
  }

  const alert = await alertController.create({
    header: '跳转页码',
    message: `请输入 1 - ${result.value.totalPages} 的页码`,
    inputs: [{
      name: 'page',
      type: 'number',
      min: 1,
      max: result.value.totalPages,
      value: String(result.value.currentPage),
      placeholder: '页码',
    }],
    buttons: [
      {text: '取消', role: 'cancel'},
      {
        text: '跳转',
        handler: (data: { page?: string }) => {
          const page = Number(data.page)
          if (!Number.isInteger(page) || page < 1 || page > result.value!.totalPages) {
            return false
          }
          updateRouteQuery({...currentQuery.value, page})
          return true
        },
      },
    ],
  })

  await alert.present()
}

watch(currentQuery, (query) => {
  void runSearch(query)
}, {immediate: true})
</script>

<style scoped>
.search-page-top {
  padding: calc(var(--ion-safe-area-top) + 2px) 14px 0;
}

.search-overlay {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: calc(10px + var(--ion-safe-area-top)) 14px 14px;
  background: rgb(16 12 10 / 0.16);
}

.search-overlay-panel {
  width: min(100%, 920px);
}

.search-page-toolbar {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  align-items: start;
  gap: 10px;
}

.toolbar-search {
  min-width: 0;
}

.search-overlay-enter-active,
.search-overlay-leave-active {
  transition: opacity 0.18s ease;
}

.search-overlay-enter-active .search-overlay-panel,
.search-overlay-leave-active .search-overlay-panel {
  transition: transform 0.22s ease, opacity 0.22s ease;
}

.search-overlay-enter-from,
.search-overlay-leave-to {
  opacity: 0;
}

.search-overlay-enter-from .search-overlay-panel,
.search-overlay-leave-to .search-overlay-panel {
  opacity: 0;
  transform: translateY(-14px);
}
</style>
