<template>
  <IonPage>
    <IonContent ref="contentRef" :scroll-events="true" @ionScroll="handleContentScroll">
      <div class="favorite-page-top">
        <div class="favorite-page-toolbar" :class="{ pinned: pullHeaderPinned }">
          <MenuToggleButton/>
          <div class="toolbar-favorite">
            <div class="toolbar-row">
              <FavoriteSearchBar
                  ref="searchBarRef"
                  :query="currentFavoriteQuery"
                  :loading="busy"
                  @search="submitSearch"
              />
              <button
                  type="button"
                  class="folders-toggle-btn"
                  aria-label="收藏夹列表"
                  @click="openRightMenu"
              >
                <IonIcon :icon="folderOpenOutline"/>
              </button>
            </div>
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
          idle-text="请在右侧收藏夹菜单中选择文件夹"
          @mode-change="displayMode = $event"
          @item-click="handleItemClick"
          @load-previous="handleLoadPrevious"
          @pull-state-change="pullGestureActive = $event"
          @retry="retrySearch"
      />

      <QuickActionFab
          slot="fixed"
          @search="openRightMenu"
          @jump="scrollToTop"
          @top="scrollToTop"
          @back="goBack"
      />
    </IonContent>

    <FavoriteSideMenu
        ref="sideMenuRef"
        v-model="rightMenuOpen"
        :online-folders="onlineFolderEntries"
        :offline-folders="offlineFolderEntries"
        :selected-online-id="folderSource === 'online' ? currentFolderId : ''"
        :selected-offline-id="folderSource === 'offline' ? currentFolderId : ''"
        @select-online-folder="onSelectOnlineFolder"
        @select-offline-folder="onSelectOfflineFolder"
    />
  </IonPage>
</template>

<script setup lang="ts">
import {computed, nextTick, onActivated, onDeactivated, onMounted, ref, watch} from 'vue'

defineOptions({ name: 'FavoritePage' })
import {useRouter} from 'vue-router'
import {IonContent, IonIcon, IonPage, menuController} from '@ionic/vue'
import {createGesture, type Gesture} from '@ionic/vue'
import {folderOpenOutline} from 'ionicons/icons'
import type {ScrollCustomEvent} from '@ionic/core'
import MenuToggleButton from '@/components/common/MenuToggleButton.vue'
import QuickActionFab from '@/components/common/QuickActionFab.vue'
import FavoriteSearchBar from '@/components/favorite/FavoriteSearchBar.vue'
import FavoriteSideMenu from '@/components/favorite/FavoriteSideMenu.vue'
import SearchResultContainer from '@/components/search/SearchResultContainer.vue'
import type {
  SearchResultContainerExposed,
  SearchResultDisplayItem,
} from '@/components/search/SearchResultContainer.vue'
import {JmcomicService} from '@/services/JmcomicService'
import {OfflineFavoriteService} from '@/services/OfflineFavoriteService'
import {useAuth} from '@/composables/useAuth'
import type {FavoriteQuery, FavoriteResult, SearchResult, SearchResultItem} from '@/services/JmcomicTypes'
import {isDraggingRight, isSnappingClosed, leftMenuOpen, rightDragProgress, rightMenuOpen} from '@/composables/sideMenuState'

const NEXT_PAGE_THRESHOLD = 220

const router = useRouter()

// --- 收藏夹源 ---
type FolderSource = 'online' | 'offline'
const folderSource = ref<FolderSource>('offline')
const currentFolderId = ref('default')
const onlineFolderMap = ref<Record<string, string>>({})
const currentKeyword = ref('')

interface FolderEntry {
  id: string
  name: string
  count: number
}

const onlineFolderEntries = computed<FolderEntry[]>(() =>
    Object.entries(onlineFolderMap.value).map(([id, name]) => ({ id, name, count: 0 }))
)

const offlineFolderEntries = computed<FolderEntry[]>(() =>
    OfflineFavoriteService.getFolders()
)

const currentFolderName = computed(() => {
  if (folderSource.value === 'online') {
    return onlineFolderMap.value[currentFolderId.value] ?? ''
  }
  if (currentFolderId.value === 'default') return ''
  return offlineFolderEntries.value.find(f => f.id === currentFolderId.value)?.name ?? ''
})

// --- 搜索结果 ---
const resultMeta = ref<SearchResult | null>(null)
const initialLoading = ref(false)
const loadingPrevious = ref(false)
const loadingNext = ref(false)
const errorMessage = ref('')
const displayMode = ref<'list' | 'grid'>('list')
const pageAtTop = ref(true)
const pullGestureActive = ref(false)

const contentRef = ref<InstanceType<typeof IonContent> | null>(null)
const scrollElementRef = ref<HTMLElement | null>(null)
const resultContainerRef = ref<SearchResultContainerExposed | null>(null)
const searchBarRef = ref<{ focusInput: () => Promise<void> } | null>(null)
const sideMenuRef = ref<InstanceType<typeof FavoriteSideMenu> | null>(null)

const pageCache = ref<Record<number, SearchResultItem[]>>({})

const currentFavoriteQuery = computed<FavoriteQuery>(() => ({
  folderId: folderSource.value === 'online' ? currentFolderId.value : '0',
  page: 1,
  keyword: currentKeyword.value,
}))

const loadedPages = computed(() =>
    Object.keys(pageCache.value)
        .map(Number)
        .filter((page) => Number.isInteger(page))
        .sort((a, b) => a - b)
)

const loadedPageStart = computed(() => loadedPages.value[0] ?? null)
const loadedPageEnd = computed(() => loadedPages.value.at(-1) ?? null)

const displayItems = computed<SearchResultDisplayItem[]>(() =>
    loadedPages.value.flatMap((page) =>
        (pageCache.value[page] ?? []).map((item, indexInPage) => ({
          item,
          page,
          indexInPage,
        }))
    )
)

const canLoadPrevious = computed(() =>
    !!resultMeta.value && loadedPageStart.value !== null && loadedPageStart.value > 1
)

const canLoadNext = computed(() =>
    !!resultMeta.value && loadedPageEnd.value !== null && loadedPageEnd.value < resultMeta.value.totalPages
)

const busy = computed(() =>
    initialLoading.value || loadingPrevious.value || loadingNext.value
)

const pullHeaderPinned = computed(() =>
    pageAtTop.value && (pullGestureActive.value || loadingPrevious.value)
)

const resolveScrollElement = async () => {
  if (scrollElementRef.value) return scrollElementRef.value
  const contentEl = contentRef.value?.$el as HTMLIonContentElement | undefined
  if (!contentEl?.getScrollElement) return null
  scrollElementRef.value = await contentEl.getScrollElement()
  return scrollElementRef.value
}

// --- 数据获取 ---
const fetchOnlineFavorites = async (page: number): Promise<SearchResult> => {
  const result: FavoriteResult = await JmcomicService.favorites({
    folderId: currentFolderId.value,
    page,
    keyword: currentKeyword.value,
  })
  if (result.folderList) {
    onlineFolderMap.value = result.folderList
  }
  return {
    currentPage: result.currentPage,
    totalItems: result.totalItems,
    totalPages: result.totalPages,
    content: result.content,
  }
}

const fetchOfflineFavorites = (page: number): SearchResult => {
  const result = OfflineFavoriteService.getItems(
      currentFolderId.value,
      currentKeyword.value || undefined,
      page,
      20,
  )
  return {
    currentPage: result.currentPage,
    totalItems: result.totalItems,
    totalPages: result.totalPages,
    content: result.content,
  }
}

const fetchPage = async (page: number): Promise<SearchResult> => {
  if (folderSource.value === 'online') {
    return await fetchOnlineFavorites(page)
  }
  return fetchOfflineFavorites(page)
}

const resetWithPage = async (page: number = 1) => {
  initialLoading.value = true
  errorMessage.value = ''
  resultMeta.value = null
  pageCache.value = {}
  try {
    const pageResult = await fetchPage(page)
    resultMeta.value = pageResult
    pageCache.value = { [page]: pageResult.content }
    await nextTick()
    void contentRef.value?.$el?.scrollToTop?.(0)
    pageAtTop.value = true
  } catch (error) {
    resultMeta.value = null
    pageCache.value = {}
    errorMessage.value = error instanceof Error ? error.message : '加载失败'
  } finally {
    initialLoading.value = false
  }
}

const submitSearch = (query: FavoriteQuery) => {
  currentKeyword.value = query.keyword ?? ''
  void resetWithPage(1)
}

const retrySearch = () => {
  void resetWithPage(1)
}

// --- 分页 ---
const appendPage = async (page: number) => {
  if (loadingNext.value || initialLoading.value || !canLoadNext.value) return
  loadingNext.value = true
  try {
    errorMessage.value = ''
    const pageResult = await fetchPage(page)
    resultMeta.value = pageResult
    pageCache.value = { ...pageCache.value, [page]: pageResult.content }
    await maybeLoadNextAfterRender()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '加载下一页失败'
  } finally {
    loadingNext.value = false
  }
}

const prependPage = async (page: number) => {
  if (loadingPrevious.value || initialLoading.value || !canLoadPrevious.value) return

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

  if (!contentScrollElement || !resultRoot) return

  loadingPrevious.value = true
  try {
    errorMessage.value = ''
    const pageResult = await fetchPage(page)
    resultMeta.value = pageResult
    pageCache.value = { [page]: pageResult.content, ...pageCache.value }
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

const maybeLoadNextAfterRender = async () => {
  if (!canLoadNext.value || loadingNext.value || initialLoading.value) return
  await nextTick()
  const scrollElement = await resolveScrollElement()
  if (!scrollElement || loadedPageEnd.value === null) return
  const remain = scrollElement.scrollHeight - scrollElement.clientHeight - scrollElement.scrollTop
  if (remain <= NEXT_PAGE_THRESHOLD) {
    void appendPage(loadedPageEnd.value + 1)
  }
}

const handleLoadPrevious = () => {
  if (loadedPageStart.value !== null) {
    void prependPage(loadedPageStart.value - 1)
  }
}

const handleContentScroll = async (event: ScrollCustomEvent) => {
  pageAtTop.value = event.detail.scrollTop <= 2
  if (!canLoadNext.value || loadingNext.value || initialLoading.value) return

  const scrollElement = scrollElementRef.value ?? await resolveScrollElement()
  if (!scrollElement) return
  const remain = scrollElement.scrollHeight - scrollElement.clientHeight - event.detail.scrollTop
  if (remain <= NEXT_PAGE_THRESHOLD && loadedPageEnd.value !== null) {
    void appendPage(loadedPageEnd.value + 1)
  }
}

// --- 事件处理 ---
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

const getPanelWidth = () => Math.min(window.innerWidth * 0.78, 320)

const openRightMenu = async () => {
  if (leftMenuOpen.value) {
    void menuController.close()
  }
  // 两阶段入场动画：先渲染在关闭位置，再 transition 到打开位置
  rightDragProgress.value = 0
  isDraggingRight.value = true
  rightMenuOpen.value = true
  await nextTick()
  const panelEl = sideMenuRef.value?.panelRef
  if (panelEl) void panelEl.offsetHeight // 强制 reflow，确认初始位置已渲染
  rightDragProgress.value = 1
  isDraggingRight.value = false
}

const onSelectOnlineFolder = (folderId: string) => {
  folderSource.value = 'online'
  currentFolderId.value = folderId
  currentKeyword.value = ''
  void resetWithPage(1)
}

const onSelectOfflineFolder = (folderId: string) => {
  folderSource.value = 'offline'
  currentFolderId.value = folderId
  currentKeyword.value = ''
  void resetWithPage(1)
}

// --- 初始化 ---
const { isLoggedIn } = useAuth()

const initOnlineFolders = async () => {
  if (!isLoggedIn.value) {
    folderSource.value = 'offline'
    const offlineFolders = OfflineFavoriteService.getFolders()
    if (offlineFolders.length > 0) {
      currentFolderId.value = offlineFolders[0].id
      void resetWithPage(1)
    }
    return
  }
  try {
    const result: FavoriteResult = await JmcomicService.favorites({ folderId: '0', page: 1 })
    if (result.folderList) {
      onlineFolderMap.value = result.folderList
    }
    folderSource.value = 'online'
    currentFolderId.value = '0'
    void resetWithPage(1)
  } catch {
    folderSource.value = 'offline'
    const offlineFolders = OfflineFavoriteService.getFolders()
    if (offlineFolders.length > 0) {
      currentFolderId.value = offlineFolders[0].id
      void resetWithPage(1)
    }
  }
}

// --- 手势 ---
let menuOpenGesture: Gesture | undefined
let menuCloseGesture: Gesture | undefined

const setupOpenGesture = () => {
  const contentEl = document.getElementById('main-content')
  if (!contentEl) return

  menuOpenGesture?.destroy()
  menuOpenGesture = createGesture({
    el: contentEl,
    gestureName: 'fav-menu-open',
    gesturePriority: 29,
    threshold: 0,
    canStart: (detail) => {
      if (leftMenuOpen.value) return false
      if (rightMenuOpen.value) return false
      if (detail.startX < window.innerWidth * 0.5) return false
      if (detail.deltaX > 6) return false
      return true
    },
    onStart: () => {
      isDraggingRight.value = true
      rightDragProgress.value = 0
      rightMenuOpen.value = true
      if (leftMenuOpen.value) void menuController.close()
    },
    onMove: (detail) => {
      const pw = getPanelWidth()
      const progress = Math.max(0, Math.min(1, -detail.deltaX / pw))
      rightDragProgress.value = progress
    },
    onEnd: (detail) => {
      isDraggingRight.value = false
      const velocity = detail.velocityX
      if (rightDragProgress.value >= 0.25 || velocity < -0.3) {
        rightDragProgress.value = 1
      } else {
        isSnappingClosed.value = true
        rightDragProgress.value = 0
        setTimeout(() => {
          rightMenuOpen.value = false
          isSnappingClosed.value = false
        }, 260)
      }
    },
  })
  menuOpenGesture.enable(true)
}

let closeStartProgress = 0

const setupCloseGesture = () => {
  menuCloseGesture?.destroy()
  menuCloseGesture = undefined

  const panelEl = sideMenuRef.value?.panelRef as HTMLElement | undefined
  if (!panelEl) return

  menuCloseGesture = createGesture({
    el: panelEl,
    gestureName: 'fav-menu-close',
    gesturePriority: 31,
    threshold: 0,
    canStart: () => rightMenuOpen.value && !isDraggingRight.value,
    onStart: () => {
      isDraggingRight.value = true
      closeStartProgress = rightDragProgress.value || 1
    },
    onMove: (detail) => {
      const pw = getPanelWidth()
      const progress = Math.max(0, Math.min(1, closeStartProgress - detail.deltaX / pw))
      rightDragProgress.value = progress
    },
    onEnd: (detail) => {
      isDraggingRight.value = false
      const velocity = detail.velocityX
      let snapOpen: boolean
      if (velocity > 0.3) {
        snapOpen = false // 向右快速划动 → 关闭
      } else if (velocity < -0.3) {
        snapOpen = true // 向左快速划动 → 保持打开
      } else {
        snapOpen = rightDragProgress.value >= 0.75 // 拖动超过 25% 则关闭
      }
      if (snapOpen) {
        rightDragProgress.value = 1
      } else {
        isSnappingClosed.value = true
        rightDragProgress.value = 0
        setTimeout(() => {
          rightMenuOpen.value = false
          isSnappingClosed.value = false
        }, 260)
      }
    },
  })
  menuCloseGesture.enable(true)
}

// 右侧菜单打开时建立关闭手势，关闭时销毁
watch(rightMenuOpen, (open) => {
  if (open) {
    nextTick(() => setupCloseGesture())
  } else {
    menuCloseGesture?.destroy()
    menuCloseGesture = undefined
  }
})

// 登录态从未登录变为已登录时自动切换到在线收藏夹
watch(isLoggedIn, (loggedIn, wasLoggedIn) => {
  if (loggedIn && !wasLoggedIn) {
    folderSource.value = 'online'
    currentFolderId.value = '0'
    void resetWithPage(1)
  }
})

onMounted(() => {
  void initOnlineFolders()
  nextTick(() => {
    void resolveScrollElement()
    setupOpenGesture()
  })
})

// --- keepAlive 状态保存 ---
const savedScrollTop = ref(0)

onDeactivated(() => {
  savedScrollTop.value = scrollElementRef.value?.scrollTop ?? 0
  menuOpenGesture?.destroy()
  menuOpenGesture = undefined
  menuCloseGesture?.destroy()
  menuCloseGesture = undefined
  rightMenuOpen.value = false
  isDraggingRight.value = false
  isSnappingClosed.value = false
  rightDragProgress.value = 0
})

onActivated(async () => {
  await nextTick()
  const scrollEl = scrollElementRef.value ?? await resolveScrollElement()
  if (scrollEl && savedScrollTop.value > 0) {
    scrollEl.scrollTop = savedScrollTop.value
  }
  nextTick(() => {
    setupOpenGesture()
  })
})
</script>

<style scoped>
.favorite-page-top {
  padding: calc(var(--ion-safe-area-top) + 2px) 14px 0;
}

.favorite-page-toolbar {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  align-items: start;
  gap: 10px;
}

.favorite-page-toolbar.pinned {
  position: sticky;
  top: 0;
  z-index: 8;
  padding-bottom: 10px;
  background: linear-gradient(180deg, #fff 0%, #fff 78%, rgb(255 255 255 / 0) 100%);
}

.toolbar-favorite {
  min-width: 0;
}

.toolbar-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.toolbar-row > :first-child {
  flex: 1;
  min-width: 0;
}

.folders-toggle-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border: 0;
  border-radius: 16px;
  background: #fff3ea;
  color: #7f553b;
  font-size: 20px;
  box-shadow: -1px 5px 5px 0 rgb(76 42 24 / 0.08);
  cursor: pointer;
}

.folders-toggle-btn:active {
  transform: scale(0.94);
}

.current-folder-label {
  margin-top: 6px;
  padding: 2px 10px;
  font-size: 11px;
  color: #8a6048;
  background: #fff0e7;
  border-radius: 999px;
  display: inline-block;
}
</style>
