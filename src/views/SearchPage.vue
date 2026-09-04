<template>
  <IonPage>
    <IonContent ref="contentRef" :scroll-events="true" @ion-scroll="handleContentScroll">
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
          <MenuToggleButton />
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
      >
        <template v-if="displayMode === 'list'" #item-actions="{ item }">
          <button
            type="button"
            class="card-more-btn"
            :class="{ active: cardMenu?.item.id === item.id }"
            aria-label="更多操作"
            @click.stop="openCardMenu(item, $event)"
          >
            <IonIcon :icon="ellipsisVertical" />
          </button>
        </template>
      </SearchResultContainer>

      <CardContextMenu
        :visible="Boolean(cardMenu)"
        :anchor="cardMenu?.anchor ?? null"
        :actions="cardMenuActions"
        @close="closeCardMenu"
        @select="handleCardMenuAction"
      />

      <!-- 收藏夹选择弹窗 -->
      <FavoriteFolderPicker
        v-model="showFolderPicker"
        :online-folders="pickerOnlineFolders"
        :offline-folders="pickerOfflineFolders"
        :online-folder-counts="pickerOnlineFolderCounts"
        :online-has-successful-data="onlineFolderHasSuccessfulData"
        :online-loading="onlineFolderLoading"
        :online-refreshing="onlineFolderRefreshing"
        :online-error-message="onlineFolderErrorMessage"
        :hide-online="!isLoggedIn"
        @select="onPickerSelect"
        @add-folder="onPickerAddFolder"
        @retry-online="refreshOnlineFolderData"
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
import {
  computed,
  nextTick,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  ref,
  watch,
} from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { IonContent, IonIcon, IonPage } from '@ionic/vue'
import { createAppAlert } from '@/services/AppAlertService'
import {
  bookOutline,
  downloadOutline,
  ellipsisVertical,
  heartOutline,
  informationCircleOutline,
} from 'ionicons/icons'
import type { ScrollCustomEvent } from '@ionic/core'
import MenuToggleButton from '@/components/common/MenuToggleButton.vue'
import QuickActionFab from '@/components/common/QuickActionFab.vue'
import CardContextMenu from '@/components/common/CardContextMenu.vue'
import SearchHeaderBar from '@/components/search/SearchHeaderBar.vue'
import FavoriteFolderPicker from '@/components/favorite/FavoriteFolderPicker.vue'
import type {
  SearchResultContainerExposed,
  SearchResultDisplayItem,
} from '@/components/search/SearchResultContainer.vue'
import SearchResultContainer from '@/components/search/SearchResultContainer.vue'
import { JmcomicService, sanitizeError, showToast } from '@/services/JmcomicService'
import { OfflineDownloadService } from '@/services/OfflineDownloadService'
import { OfflineFavoriteService } from '@/services/OfflineFavoriteService'
import { useAuth } from '@/composables/useAuth'
import { useFavoriteFolderStore } from '@/composables/favoriteFolderStore'
import { invalidateFavoritePageCache } from '@/composables/favoritePageCache'
import {
  allocateSearchPageContextGeneration,
  clearSearchPageContext,
  commitSearchPageWindow,
  getSearchPageContext,
  saveSearchPageContext,
  SEARCH_PAGE_WINDOW_SIZE,
  toSearchPageContextQuery,
  type SearchPageContextSnapshot,
} from '@/composables/searchPageContextCache'
import type {
  AlbumDetail,
  FolderEntry,
  SearchQuery,
  SearchResult,
  SearchResultItem,
} from '@/services/JmcomicTypes'

defineOptions({ name: 'SearchPage' })

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

const routeAnchorPage = ref(currentQuery.value.page ?? 1)

const pageCache = ref<Record<number, SearchResultItem[]>>({})
const savedScrollTop = ref(0)
let queryGeneration = allocateSearchPageContextGeneration()
let activeContextQuery = toSearchPageContextQuery({
  keyword: '',
  orderBy: 'mr',
  time: 'a',
  searchMainTag: 0,
})

const loadedPages = computed(() =>
  Object.keys(pageCache.value)
    .map(Number)
    .filter((page) => Number.isInteger(page))
    .sort((a, b) => a - b),
)

const loadedPageStart = computed(() => loadedPages.value[0] ?? null)
const loadedPageEnd = computed(() => loadedPages.value.at(-1) ?? null)

const displayItems = computed<SearchResultDisplayItem[]>(() =>
  loadedPages.value.flatMap((page) =>
    (pageCache.value[page] ?? []).map((item, indexInPage) => ({
      item,
      page,
      indexInPage,
    })),
  ),
)

const canLoadPrevious = computed(
  () => !!resultMeta.value && loadedPageStart.value !== null && loadedPageStart.value > 1,
)

const canLoadNext = computed(
  () =>
    !!resultMeta.value &&
    loadedPageEnd.value !== null &&
    loadedPageEnd.value < resultMeta.value.totalPages,
)

const busy = computed(() => initialLoading.value || loadingPrevious.value || loadingNext.value)

const pullHeaderPinned = computed(
  () => pageAtTop.value && (pullGestureActive.value || loadingPrevious.value),
)

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

const contextQueryEqual = (left: typeof activeContextQuery, right: typeof activeContextQuery) =>
  left.keyword === right.keyword &&
  left.orderBy === right.orderBy &&
  left.time === right.time &&
  left.searchMainTag === right.searchMainTag

const isCurrentGeneration = (generation: number, query = activeContextQuery) =>
  generation === queryGeneration && contextQueryEqual(query, activeContextQuery)

const updateRoutePage = (page: number) => {
  routeAnchorPage.value = page
  const nextQuery = { ...currentQuery.value, page }
  lastSearchedQuery.value = nextQuery
  if (readNumber(route.query.page, 1) === page) return
  void router.replace({
    path: '/search',
    query: {
      keyword: nextQuery.keyword ?? '',
      orderBy: nextQuery.orderBy,
      time: nextQuery.time,
      searchMainTag: String(nextQuery.searchMainTag),
      page: String(page),
    },
  })
}

const commitPage = (
  page: number,
  content: SearchResultItem[],
  direction: 'reset' | 'append' | 'prepend',
) => {
  const { pageCache: nextCache, pages } = commitSearchPageWindow(
    pageCache.value,
    page,
    content,
    direction,
  )
  pageCache.value = nextCache
  const routePage = routeAnchorPage.value
  if (!pages.includes(routePage)) {
    const replacement = direction === 'prepend' ? (pages[0] ?? page) : (pages.at(-1) ?? page)
    updateRoutePage(replacement)
  }
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
  const nextQuery = { ...query, page }
  return nextQuery.keyword?.trim()
    ? await JmcomicService.search(nextQuery)
    : await JmcomicService.categories(nextQuery)
}

const captureSnapshot = (): SearchPageContextSnapshot | null => {
  if (!resultMeta.value || !Object.keys(pageCache.value).length) return null
  const firstItem = displayItems.value[0]
  const anchorEntryKey = firstItem
    ? `${firstItem.page}-${firstItem.indexInPage}-${firstItem.item.id}`
    : null
  const scrollTop = scrollElementRef.value?.scrollTop ?? 0
  const anchorOffset = anchorEntryKey
    ? (resultContainerRef.value?.getEntryElement?.(anchorEntryKey)?.getBoundingClientRect().top ??
      null)
    : null
  return {
    query: { ...activeContextQuery },
    pageCache: { ...pageCache.value },
    resultMeta: resultMeta.value,
    routePage: routeAnchorPage.value,
    anchorEntryKey,
    anchorOffset,
    scrollTop,
    displayMode: displayMode.value,
    generation: queryGeneration,
    savedAt: Date.now(),
  }
}

const saveContextSnapshot = () => {
  const snapshot = captureSnapshot()
  if (snapshot) saveSearchPageContext(snapshot)
}

const restoreContextSnapshot = async (snapshot: SearchPageContextSnapshot, generation: number) => {
  if (!isCurrentGeneration(generation, snapshot.query)) return false
  activeContextQuery = { ...snapshot.query }
  routeAnchorPage.value = snapshot.routePage
  savedScrollTop.value = snapshot.scrollTop
  resultMeta.value = snapshot.resultMeta
  pageCache.value = snapshot.pageCache
  displayMode.value = snapshot.displayMode
  errorMessage.value = ''
  await nextTick()
  if (!isCurrentGeneration(generation, snapshot.query)) return false
  const scrollEl = await resolveScrollElement()
  if (!isCurrentGeneration(generation, snapshot.query)) return false
  pageAtTop.value = snapshot.scrollTop <= 2
  if (!scrollEl) return true
  scrollEl.scrollTop = snapshot.scrollTop
  if (snapshot.anchorEntryKey && snapshot.anchorOffset !== null) {
    const restoredTop = resultContainerRef.value
      ?.getEntryElement?.(snapshot.anchorEntryKey)
      ?.getBoundingClientRect().top
    if (restoredTop !== undefined && restoredTop !== null) {
      scrollEl.scrollTop += restoredTop - snapshot.anchorOffset
    }
  }
  pageAtTop.value = snapshot.scrollTop <= 2
  return true
}

const resetWithPage = async (
  query: SearchQuery,
  { preferSnapshot = true }: { preferSnapshot?: boolean } = {},
) => {
  const targetPage = query.page ?? 1
  const trimmedKeyword = (query.keyword ?? '').trim()
  const context = toSearchPageContextQuery(query)
  const generation = allocateSearchPageContextGeneration()
  queryGeneration = generation
  activeContextQuery = context
  routeAnchorPage.value = targetPage
  initialLoading.value = true
  errorMessage.value = ''
  resultMeta.value = null
  pageCache.value = {}
  loadingPrevious.value = false
  loadingNext.value = false

  const cachedSnapshot = getSearchPageContext(query)
  const canRestoreSnapshot =
    !/^\d+$/.test(trimmedKeyword) && preferSnapshot && cachedSnapshot?.routePage === targetPage
  if (canRestoreSnapshot && (await restoreContextSnapshot(cachedSnapshot, generation))) {
    initialLoading.value = false
    return
  }
  if (!isCurrentGeneration(generation, context)) return
  clearSearchPageContext(query)

  if (/^\d+$/.test(trimmedKeyword)) {
    let album: AlbumDetail
    try {
      album = await JmcomicService.getAlbum(trimmedKeyword)
      if (!isCurrentGeneration(generation, context)) return
      if (!album || String(album.id ?? '').trim() !== trimmedKeyword || !album.title?.trim()) {
        throw new Error('invalid album detail')
      }
    } catch {
      if (!isCurrentGeneration(generation, context)) return
      await showToast('本子不存在', 'danger')
      initialLoading.value = false
      return
    }
    if (!isCurrentGeneration(generation, context)) return
    initialLoading.value = false
    try {
      await router.replace({
        path: `/album/${trimmedKeyword}`,
        query: {
          title: album.title,
          coverUrl: album.image,
          authors: album.authors?.join(',') ?? '',
        },
      })
    } catch (error) {
      await showToast(sanitizeError(error, '打开详情失败'), 'danger')
    }
    return
  }

  try {
    const pageResult = await fetchPage({ ...context, page: targetPage }, targetPage)
    if (!isCurrentGeneration(generation, context)) return
    resultMeta.value = pageResult
    commitPage(targetPage, pageResult.content, 'reset')
    await nextTick()
    const scrollEl = await resolveScrollElement()
    if (scrollEl) scrollEl.scrollTop = 0
    await contentRef.value?.$el?.scrollToTop?.(0)
    savedScrollTop.value = 0
    pageAtTop.value = true
    saveContextSnapshot()
  } catch (error) {
    if (isCurrentGeneration(generation, context)) {
      resultMeta.value = null
      pageCache.value = {}
      errorMessage.value = sanitizeError(error, '搜索失败')
    }
  } finally {
    if (isCurrentGeneration(generation, context)) initialLoading.value = false
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
  const newQuery = { ...query, page: 1 }
  lastSearchedQuery.value = { ...newQuery }
  void resetWithPage(newQuery, { preferSnapshot: false })
  if (!/^\d+$/.test((newQuery.keyword ?? '').trim())) updateRouteQuery(newQuery)
}

const submitOverlaySearch = (query: SearchQuery) => {
  closeSearchOverlay()
  submitSearch(query)
}

const retrySearch = () => {
  void resetWithPage(currentQuery.value, { preferSnapshot: false })
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
  if (loadingNext.value || initialLoading.value || !canLoadNext.value) return
  if (pageCache.value[page] || loadedPageEnd.value === null || page !== loadedPageEnd.value + 1)
    return

  const context = { ...activeContextQuery }
  const generation = queryGeneration
  const anchorPage =
    loadedPages.value.length >= SEARCH_PAGE_WINDOW_SIZE ? loadedPageStart.value : null
  const anchorEntry =
    displayItems.value.find((entry) => entry.page !== anchorPage) ?? displayItems.value[0]
  const anchorKey = anchorEntry
    ? `${anchorEntry.page}-${anchorEntry.indexInPage}-${anchorEntry.item.id}`
    : null
  const previousAnchorTop = anchorKey
    ? (resultContainerRef.value?.getEntryElement?.(anchorKey)?.getBoundingClientRect().top ?? null)
    : null

  loadingNext.value = true
  try {
    errorMessage.value = ''
    const pageResult = await fetchPage({ ...context, page: currentQuery.value.page }, page)
    if (!isCurrentGeneration(generation, context)) return
    resultMeta.value = pageResult
    commitPage(page, pageResult.content, 'append')
    await nextTick()
    if (anchorKey && previousAnchorTop !== null) {
      const nextTop = resultContainerRef.value
        ?.getEntryElement?.(anchorKey)
        ?.getBoundingClientRect().top
      const scrollEl = await resolveScrollElement()
      if (scrollEl && nextTop !== undefined && nextTop !== null) {
        scrollEl.scrollTop += nextTop - previousAnchorTop
      }
    }
    saveContextSnapshot()
    await maybeLoadNextAfterRender()
  } catch (error) {
    if (isCurrentGeneration(generation, context)) {
      errorMessage.value = sanitizeError(error, '加载下一页失败')
    }
  } finally {
    if (isCurrentGeneration(generation, context)) loadingNext.value = false
  }
}

const prependPage = async (page: number) => {
  if (loadingPrevious.value || initialLoading.value || !canLoadPrevious.value) return
  if (pageCache.value[page] || loadedPageStart.value === null || page !== loadedPageStart.value - 1)
    return

  const context = { ...activeContextQuery }
  const generation = queryGeneration
  const contentScrollElement = await resolveScrollElement()
  if (!isCurrentGeneration(generation, context)) return
  const anchorPage =
    loadedPages.value.length >= SEARCH_PAGE_WINDOW_SIZE ? loadedPageEnd.value : null
  const anchorEntry =
    displayItems.value.find((entry) => entry.page !== anchorPage) ?? displayItems.value[0]
  const anchorEntryKey = anchorEntry
    ? `${anchorEntry.page}-${anchorEntry.indexInPage}-${anchorEntry.item.id}`
    : null
  const previousAnchorTop = anchorEntryKey
    ? (resultContainerRef.value?.getEntryElement?.(anchorEntryKey)?.getBoundingClientRect().top ??
      null)
    : null
  const resultRoot = resultContainerRef.value?.getRootElement?.()
  const previousRootTop = resultRoot?.getBoundingClientRect().top ?? null

  if (!contentScrollElement || !resultRoot) return

  loadingPrevious.value = true
  try {
    errorMessage.value = ''
    const pageResult = await fetchPage({ ...context, page: currentQuery.value.page }, page)
    if (!isCurrentGeneration(generation, context)) return
    resultMeta.value = pageResult
    commitPage(page, pageResult.content, 'prepend')
    await nextTick()
    const nextTop = anchorEntryKey
      ? resultContainerRef.value?.getEntryElement?.(anchorEntryKey)?.getBoundingClientRect().top
      : null
    if (nextTop !== undefined && nextTop !== null && previousAnchorTop !== null) {
      contentScrollElement.scrollTop += nextTop - previousAnchorTop
    } else if (previousRootTop !== null) {
      const nextRootTop = resultRoot.getBoundingClientRect().top
      contentScrollElement.scrollTop += nextRootTop - previousRootTop
    }
    saveContextSnapshot()
  } catch (error) {
    if (isCurrentGeneration(generation, context)) {
      errorMessage.value = sanitizeError(error, '加载上一页失败')
    }
  } finally {
    if (isCurrentGeneration(generation, context)) loadingPrevious.value = false
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

  const scrollElement = scrollElementRef.value ?? (await resolveScrollElement())
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

// ========== 卡片操作菜单 ==========

const auth = useAuth()
const isLoggedIn = auth.isLoggedIn
const userInfo = auth.userInfo
const accountId = computed(
  () => userInfo?.value?.uid ?? (isLoggedIn.value ? 'authenticated' : null),
)
const {
  folders: pickerOnlineFolders,
  counts: pickerOnlineFolderCounts,
  hasSuccessfulData: onlineFolderHasSuccessfulData,
  isFetching: onlineFolderRefreshing,
  errorMessage: onlineFolderErrorMessage,
  refresh: refreshFavoriteFolders,
  invalidate: invalidateFavoriteFolders,
  clear: clearFavoriteFolders,
} = useFavoriteFolderStore()
const onlineFolderLoading = computed(
  () => onlineFolderRefreshing.value && !onlineFolderHasSuccessfulData.value,
)

interface CardMenuState {
  item: SearchResultItem
  anchor: HTMLElement
}

const cardMenu = ref<CardMenuState | null>(null)
const cardMenuActions = computed(() => [
  { id: 'detail', label: '详情', icon: informationCircleOutline },
  { id: 'read', label: '阅读', icon: bookOutline },
  { id: 'download', label: '下载', icon: downloadOutline },
  { id: 'favorite', label: '收藏', icon: heartOutline },
])

function openCardMenu(item: SearchResultItem, event: MouseEvent) {
  const anchor = event.currentTarget as HTMLElement
  if (cardMenu.value?.anchor === anchor) {
    closeCardMenu()
    return
  }
  cardMenu.value = { item, anchor }
}

function closeCardMenu() {
  cardMenu.value = null
}

function handleCardMenuAction(action: string) {
  const item = cardMenu.value?.item
  if (!item) return
  if (action === 'detail') handleCardDetail(item)
  else if (action === 'read') void handleCardRead(item)
  else if (action === 'download') void handleCardDownload(item)
  else if (action === 'favorite') void handleCardFavorite(item)
}

function handleCardDetail(item: SearchResultItem) {
  closeCardMenu()
  void router.push({
    path: `/album/${item.id}`,
    query: { title: item.title, coverUrl: item.coverUrl, authors: item.authors.join(',') },
  })
}

async function handleCardRead(item: SearchResultItem) {
  closeCardMenu()
  try {
    const photo = await JmcomicService.getPhoto(item.id)
    await router.push({
      path: `/album/${item.id}/read/${photo.id}`,
      query: { title: item.title, total: String(photo.images.length) },
    })
  } catch {
    await showToast('获取章节失败', 'danger')
  }
}

async function handleCardDownload(item: SearchResultItem) {
  closeCardMenu()
  try {
    const photo = await JmcomicService.getPhoto(item.id)
    const taskId = `${item.id}_${photo.id}`
    const existing = OfflineDownloadService.getAll().find(
      (t) => t.taskId === taskId && t.status !== 'failed',
    )
    if (existing) {
      await showToast('该章节已在下载队列中', 'medium')
      return
    }
    await JmcomicService.downloadChapter(item.id, photo.id, item.title, photo.title, item.coverUrl)
    OfflineDownloadService.addTask({
      taskId,
      albumId: item.id,
      chapterId: photo.id,
      albumTitle: item.title,
      chapterTitle: photo.title,
      coverUrl: item.coverUrl,
      isSingleEpisode: photo.isSingleEpisode,
      totalPages: 0,
      downloadedPages: 0,
      status: 'queued',
      createdAt: Date.now(),
    })
    await showToast('已加入下载队列', 'success')
  } catch {
    await showToast('获取章节失败', 'danger')
  }
}

function handleCardFavorite(item: SearchResultItem) {
  closeCardMenu()
  pickerTargetItem.value = item
  void openFolderPicker()
}

// ========== 收藏夹选择弹窗 ==========

const showFolderPicker = ref(false)
const pickerTargetItem = ref<SearchResultItem | null>(null)
const pickerOfflineFolders = ref<FolderEntry[]>([])

async function refreshOnlineFolderData() {
  if (!isLoggedIn.value) {
    clearFavoriteFolders()
    return
  }
  await refreshFavoriteFolders(accountId.value)
}

function openFolderPicker() {
  pickerOfflineFolders.value = OfflineFavoriteService.getFolders()
  showFolderPicker.value = true
  void (async () => {
    try {
      await OfflineFavoriteService.ensureInit()
      pickerOfflineFolders.value = OfflineFavoriteService.getFolders()
    } catch {
      // 离线收藏夹读取失败时保留当前缓存。
    }
  })()
  void refreshOnlineFolderData()
}

async function onPickerSelect(payload: { folderId: string; source: 'online' | 'offline' }) {
  showFolderPicker.value = false
  const item = pickerTargetItem.value
  pickerTargetItem.value = null
  if (!item) return
  void executeFavorite(item, payload)
}

async function executeFavorite(
  item: SearchResultItem,
  payload: { folderId: string; source: 'online' | 'offline' },
) {
  try {
    if (payload.source === 'online') {
      const album = await JmcomicService.getAlbum(item.id)
      if (album.isFavorite && payload.folderId === '0') {
        showToast('该本子已在收藏夹中', 'medium')
        return
      }
      await JmcomicService.favoriteToFolder(item.id, payload.folderId)
      invalidateFavoritePageCache()
      invalidateFavoriteFolders()
      void refreshOnlineFolderData()
      showToast('已收藏到在线收藏夹', 'success')
    } else {
      await OfflineFavoriteService.addItem(payload.folderId, item)
      invalidateFavoritePageCache()
      showToast('已收藏到离线收藏夹', 'success')
    }
  } catch (e: any) {
    showToast(sanitizeError(e, '收藏失败'), 'danger')
  }
}

async function onPickerAddFolder(source: 'online' | 'offline') {
  const alert = await createAppAlert({
    header: '新建收藏夹',
    inputs: [{ name: 'name', type: 'text', placeholder: '收藏夹名称' }],
    buttons: [
      { text: '取消', role: 'cancel' },
      {
        text: '确定',
        handler: async (data) => {
          const name = data?.name?.trim()
          if (!name) return

          if (source === 'online') {
            try {
              const r = await JmcomicService.manageFavoriteFolder('add', '0', name, '')
              if (r.status === 'ok') {
                await showToast('收藏夹已创建', 'success')
                invalidateFavoriteFolders()
                await refreshOnlineFolderData()
              } else {
                await showToast(r.msg || '创建失败', 'danger')
              }
            } catch {
              await showToast('创建失败', 'danger')
            }
          } else {
            await OfflineFavoriteService.createFolder(name)
            pickerOfflineFolders.value = OfflineFavoriteService.getFolders()
            await showToast('收藏夹已创建', 'success')
          }
        },
      },
    ],
  })
  await alert.present()
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

  const alert = await createAppAlert({
    header: '跳转页码',
    message: `请输入 1 - ${resultMeta.value.totalPages} 的页码`,
    inputs: [
      {
        name: 'page',
        type: 'number',
        min: 1,
        max: resultMeta.value.totalPages,
        value: String(currentQuery.value.page ?? 1),
        placeholder: '页码',
      },
    ],
    buttons: [
      { text: '取消', role: 'cancel' },
      {
        text: '跳转',
        handler: (data: { page?: string }) => {
          const page = Number(data.page)
          if (!Number.isInteger(page) || page < 1 || page > resultMeta.value!.totalPages) {
            return false
          }
          updateRouteQuery({ ...currentQuery.value, page })
          return true
        },
      },
    ],
  })

  await alert.present()
}

const lastSearchedQuery = ref<SearchQuery | null>(null)

function queryEqual(a: SearchQuery, b: SearchQuery): boolean {
  return (
    a.keyword === b.keyword &&
    a.orderBy === b.orderBy &&
    a.time === b.time &&
    a.searchMainTag === b.searchMainTag &&
    a.page === b.page
  )
}

watch(
  currentQuery,
  (query) => {
    if (route.name !== 'SearchPage') return
    if (lastSearchedQuery.value && queryEqual(query, lastSearchedQuery.value)) return
    lastSearchedQuery.value = { ...query }
    void resetWithPage(query)
  },
  { immediate: true },
)

onDeactivated(() => {
  savedScrollTop.value = scrollElementRef.value?.scrollTop ?? 0
  saveContextSnapshot()
})

onBeforeUnmount(() => saveContextSnapshot())

onActivated(async () => {
  await nextTick()
  const scrollEl = scrollElementRef.value ?? (await resolveScrollElement())
  if (scrollEl && savedScrollTop.value > 0) {
    scrollEl.scrollTop = savedScrollTop.value
  }
})

onMounted(async () => {
  const scrollEl = await resolveScrollElement()
  if (scrollEl && savedScrollTop.value > 0) {
    scrollEl.scrollTop = savedScrollTop.value
  }
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
  max-width: 1000px;
  margin-inline: auto;
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
  transition:
    transform 0.22s ease,
    opacity 0.22s ease;
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

/* ---- 卡片更多操作按钮 ---- */

:deep(.card-more-btn) {
  position: absolute;
  right: 2px;
  top: 50%;
  transform: translateY(-50%);
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #8a6048;
  font-size: 18px;
  cursor: pointer;
  z-index: 2;
}

:deep(.card-more-btn.active) {
  background: rgb(250 156 105 / 0.15);
  color: #c96d3a;
}
</style>
