<template>
  <IonPage>
    <IonContent ref="contentRef" :scroll-events="true" @ionScroll="handleContentScroll">
      <Transition name="search-overlay">
        <div v-if="searchOverlayVisible" class="search-overlay" @click.self="closeSearchOverlay">
          <div class="search-overlay-panel">
            <SearchHeaderBar
                ref="overlaySearchRef"
                :query="currentQuery"
                :loading="busy"
                @search="submitOverlaySearch"
            />
          </div>
        </div>
      </Transition>

      <div class="search-page-top">
        <div class="search-page-toolbar" :class="{ pinned: pullHeaderPinned }">
          <MenuToggleButton/>
          <div class="toolbar-search">
            <SearchHeaderBar
                ref="headerSearchRef"
                :query="currentQuery"
                :loading="busy"
                @search="submitSearch"
            />
          </div>
        </div>
      </div>

      <SearchResultContainer
          ref="resultContainerRef"
          :result="resultMeta"
          :items="displayItems"
          :loading="initialLoading"
          :loading-previous="loadingPrevious"
          :loading-next="loadingNext"
          :can-load-previous="canLoadPrevious"
          :page-at-top="pageAtTop"
          :error-message="errorMessage"
          :mode="displayMode"
          :loaded-page-start="loadedPageStart"
          :loaded-page-end="loadedPageEnd"
          idle-text="请输入关键词开始搜索"
          @mode-change="displayMode = $event"
          @item-click="handleItemClick"
          @load-previous="handleLoadPrevious"
          @pull-state-change="pullGestureActive = $event"
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
import {computed, nextTick, onActivated, onDeactivated, onMounted, ref, watch} from 'vue'

defineOptions({ name: 'SearchPage' })
import {useRoute, useRouter} from 'vue-router'
import {alertController, IonContent, IonPage} from '@ionic/vue'
import type {ScrollCustomEvent} from '@ionic/core'
import MenuToggleButton from '@/components/common/MenuToggleButton.vue'
import QuickActionFab from '@/components/common/QuickActionFab.vue'
import SearchHeaderBar from '@/components/search/SearchHeaderBar.vue'
import SearchResultContainer from '@/components/search/SearchResultContainer.vue'
import type {
  SearchResultContainerExposed,
  SearchResultDisplayItem,
} from '@/components/search/SearchResultContainer.vue'
import {JmcomicService} from '@/services/JmcomicService'
import type {SearchQuery, SearchResult, SearchResultItem} from '@/services/JmcomicTypes'

const NEXT_PAGE_THRESHOLD = 220

const route = useRoute()
const router = useRouter()

const resultMeta = ref<SearchResult | null>(null)
const initialLoading = ref(false)
const loadingPrevious = ref(false)
const loadingNext = ref(false)
const errorMessage = ref('')
const displayMode = ref<'list' | 'grid'>('list')
const contentRef = ref<InstanceType<typeof IonContent> | null>(null)
const scrollElementRef = ref<HTMLElement | null>(null)
const resultContainerRef = ref<SearchResultContainerExposed | null>(null)
const headerSearchRef = ref<{ focusInput: () => Promise<void> } | null>(null)
const overlaySearchRef = ref<{ focusInput: () => Promise<void> } | null>(null)
const searchOverlayVisible = ref(false)
const pageAtTop = ref(true)
const pullGestureActive = ref(false)

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

const pageCache = ref<Record<number, SearchResultItem[]>>({})

const loadedPages = computed(() => (
    Object.keys(pageCache.value)
        .map(Number)
        .filter((page) => Number.isInteger(page))
        .sort((a, b) => a - b)
))

const loadedPageStart = computed(() => loadedPages.value[0] ?? null)
const loadedPageEnd = computed(() => loadedPages.value.at(-1) ?? null)

const displayItems = computed<SearchResultDisplayItem[]>(() => (
    loadedPages.value.flatMap((page) => (
        (pageCache.value[page] ?? []).map((item, indexInPage) => ({
          item,
          page,
          indexInPage,
        }))
    ))
))

const canLoadPrevious = computed(() => (
    !!resultMeta.value && loadedPageStart.value !== null && loadedPageStart.value > 1
))

const canLoadNext = computed(() => (
    !!resultMeta.value && loadedPageEnd.value !== null && loadedPageEnd.value < resultMeta.value.totalPages
))

const busy = computed(() => (
    initialLoading.value || loadingPrevious.value || loadingNext.value
))

const pullHeaderPinned = computed(() => (
    pageAtTop.value && (pullGestureActive.value || loadingPrevious.value)
))

const resolveScrollElement = async () => {
  if (scrollElementRef.value) {
    return scrollElementRef.value
  }

  const contentEl = contentRef.value?.$el as HTMLIonContentElement | undefined
  if (!contentEl?.getScrollElement) {
    return null
  }

  scrollElementRef.value = await contentEl.getScrollElement()
  return scrollElementRef.value
}

const maybeLoadNextAfterRender = async () => {
  if (!canLoadNext.value || loadingNext.value || initialLoading.value) {
    return
  }

  await nextTick()

  const scrollElement = await resolveScrollElement()
  if (!scrollElement || loadedPageEnd.value === null) {
    return
  }

  const remain = scrollElement.scrollHeight - scrollElement.clientHeight - scrollElement.scrollTop
  if (remain <= NEXT_PAGE_THRESHOLD) {
    void appendPage(loadedPageEnd.value + 1)
  }
}

const fetchPage = async (query: SearchQuery, page: number) => {
  const nextQuery = {...query, page}
  return nextQuery.keyword?.trim()
      ? await JmcomicService.search(nextQuery)
      : await JmcomicService.categories(nextQuery)
}

const resetWithPage = async (query: SearchQuery) => {
  const targetPage = query.page ?? 1
  initialLoading.value = true
  errorMessage.value = ''
  resultMeta.value = null
  pageCache.value = {}
  try {
    const pageResult = await fetchPage(query, targetPage)
    resultMeta.value = pageResult
    pageCache.value = {
      [targetPage]: pageResult.content,
    }
    await nextTick()
    void contentRef.value?.$el?.scrollToTop?.(0)
    pageAtTop.value = true
  } catch (error) {
    resultMeta.value = null
    pageCache.value = {}
    errorMessage.value = error instanceof Error ? error.message : '搜索失败'
  } finally {
    initialLoading.value = false
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
  void resetWithPage(currentQuery.value)
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

const appendPage = async (page: number) => {
  if (loadingNext.value || initialLoading.value || !canLoadNext.value) {
    return
  }

  loadingNext.value = true
  try {
    errorMessage.value = ''
    const pageResult = await fetchPage(currentQuery.value, page)
    resultMeta.value = pageResult
    pageCache.value = {
      ...pageCache.value,
      [page]: pageResult.content,
    }
    await maybeLoadNextAfterRender()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '加载下一页失败'
  } finally {
    loadingNext.value = false
  }
}

const prependPage = async (page: number) => {
  if (loadingPrevious.value || initialLoading.value || !canLoadPrevious.value) {
    return
  }

  const contentScrollElement = await resolveScrollElement()
  const anchorEntry = displayItems.value[0]
  const anchorEntryKey = anchorEntry
      ? `${anchorEntry.page}-${anchorEntry.indexInPage}-${anchorEntry.item.id}`
      : null
  const previousAnchorTop = anchorEntryKey
      ? resultContainerRef.value?.getEntryElement(anchorEntryKey)?.getBoundingClientRect().top ?? null
      : null
  const resultRoot = resultContainerRef.value?.getRootElement()
  const previousRootTop = resultRoot?.getBoundingClientRect().top ?? null

  if (!contentScrollElement || !resultRoot) {
    return
  }

  loadingPrevious.value = true
  try {
    errorMessage.value = ''
    const pageResult = await fetchPage(currentQuery.value, page)
    resultMeta.value = pageResult
    pageCache.value = {
      [page]: pageResult.content,
      ...pageCache.value,
    }
    await nextTick()
    if (anchorEntryKey && previousAnchorTop !== null) {
      const nextAnchorTop = resultContainerRef.value?.getEntryElement(anchorEntryKey)?.getBoundingClientRect().top ?? null
      if (nextAnchorTop !== null) {
        contentScrollElement.scrollTop += nextAnchorTop - previousAnchorTop
        return
      }
    }

    const nextRootTop = resultRoot.getBoundingClientRect().top
    if (previousRootTop !== null) {
      contentScrollElement.scrollTop += nextRootTop - previousRootTop
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '加载上一页失败'
  } finally {
    loadingPrevious.value = false
  }
}

const handleLoadPrevious = () => {
  if (loadedPageStart.value !== null) {
    void prependPage(loadedPageStart.value - 1)
  }
}

const handleContentScroll = async (event: ScrollCustomEvent) => {
  pageAtTop.value = event.detail.scrollTop <= 2

  if (!canLoadNext.value || loadingNext.value || initialLoading.value) {
    return
  }

  const scrollElement = scrollElementRef.value ?? await resolveScrollElement()
  if (!scrollElement) {
    return
  }

  const remain = scrollElement.scrollHeight - scrollElement.clientHeight - event.detail.scrollTop
  if (remain <= NEXT_PAGE_THRESHOLD && loadedPageEnd.value !== null) {
    void appendPage(loadedPageEnd.value + 1)
  }
}

const handleItemClick = (item: SearchResultItem) => {
  void router.push({
    path: `/album/${item.id}`,
    query: {
      title: item.title,
      coverUrl: item.coverUrl,
      authors: item.authors.join(','),
    },
  })
}

const goBack = () => {
  if (window.history.length > 1) {
    router.back()
    return
  }
  void router.push('/home')
}

const jumpToPage = async () => {
  if (!resultMeta.value) {
    return
  }

  const alert = await alertController.create({
    header: '跳转页码',
    message: `请输入 1 - ${resultMeta.value.totalPages} 的页码`,
    inputs: [{
      name: 'page',
      type: 'number',
      min: 1,
      max: resultMeta.value.totalPages,
      value: String(currentQuery.value.page ?? 1),
      placeholder: '页码',
    }],
    buttons: [
      {text: '取消', role: 'cancel'},
      {
        text: '跳转',
        handler: (data: { page?: string }) => {
          const page = Number(data.page)
          if (!Number.isInteger(page) || page < 1 || page > resultMeta.value!.totalPages) {
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

const lastSearchedQuery = ref<SearchQuery | null>(null)

function queryEqual(a: SearchQuery, b: SearchQuery): boolean {
  return a.keyword === b.keyword && a.orderBy === b.orderBy &&
    a.time === b.time && a.searchMainTag === b.searchMainTag &&
    a.page === b.page
}

watch(currentQuery, (query) => {
  if (route.name !== 'SearchPage') return
  if (lastSearchedQuery.value && queryEqual(query, lastSearchedQuery.value)) return
  lastSearchedQuery.value = { ...query }
  void resetWithPage(query)
}, {immediate: true})

const savedScrollTop = ref(0)

onDeactivated(() => {
  savedScrollTop.value = scrollElementRef.value?.scrollTop ?? 0
})

onActivated(async () => {
  await nextTick()
  const scrollEl = scrollElementRef.value ?? await resolveScrollElement()
  if (scrollEl && savedScrollTop.value > 0) {
    scrollEl.scrollTop = savedScrollTop.value
  }
})

onMounted(() => {
  void resolveScrollElement()
})
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

.search-page-toolbar.pinned {
  position: sticky;
  top: 0;
  z-index: 8;
  padding-bottom: 10px;
  background: linear-gradient(180deg, #fff 0%, #fff 78%, rgb(255 255 255 / 0) 100%);
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
