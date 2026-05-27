<template>
  <IonPage>
    <IonContent ref="contentRef" :scroll-events="true" @ion-scroll="handleContentScroll">
      <IonRefresher
        slot="fixed"
        :disabled="canLoadPrevious && pageAtTop"
        @ion-refresh="handleRefresh"
      >
        <IonRefresherContent />
      </IonRefresher>

      <div class="favorite-page-top">
        <div class="favorite-page-toolbar" :class="{ pinned: pullHeaderPinned }">
          <MenuToggleButton />
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
                <IonIcon :icon="folderOpenOutline" />
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
      >
        <template #item-actions="{ item }">
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

      <QuickActionFab
        slot="fixed"
        @search="openRightMenu"
        @jump="scrollToTop"
        @top="scrollToTop"
        @back="goBack"
      />
    </IonContent>

    <!-- 操作进度遮罩（阻塞式，不可关闭） -->
    <div v-if="showProgressModal" class="progress-overlay">
      <div class="progress-dialog">
        <div class="progress-title">{{ progressTitle }}</div>

        <div class="progress-bar">
          <div class="progress-fill" :style="{ width: progressPercent + '%' }" />
        </div>
        <div class="progress-percent">{{ progressPercent }}%</div>

        <div class="progress-count">已完成：{{ progressCurrent }} / {{ progressTotal }} 个本子</div>

        <div v-if="progressCurrentItem" class="progress-file">
          {{ progressCurrentItem }}
        </div>

        <div class="progress-warning">请勿关闭应用</div>
      </div>
    </div>

    <FavoriteSideMenu
      ref="sideMenuRef"
      v-model="rightMenuOpen"
      :online-folders="onlineFolderEntries"
      :offline-folders="offlineFolderEntries"
      :selected-online-id="folderSource === 'online' ? currentFolderId : ''"
      :selected-offline-id="folderSource === 'offline' ? currentFolderId : ''"
      :online-folder-counts="onlineFolderCounts"
      @select-online-folder="onSelectOnlineFolder"
      @select-offline-folder="onSelectOfflineFolder"
      @add-folder="onAddFolder"
      @rename-folder="onRenameFolder"
      @delete-folder="onDeleteFolder"
      @move-folder="onMoveFolder"
      @copy-folder="onCopyFolder"
      @export-folder="onExportFolder"
    />

    <!-- 卡片操作上下文菜单 -->
    <div
      v-if="cardMenu"
      ref="cardMenuRef"
      class="card-context-menu"
      :style="cardMenuStyle"
      @mousedown.prevent.stop
      @touchstart.stop
    >
      <button type="button" class="card-menu-item" @click.stop="handleCardRead(cardMenu.item)">
        <IonIcon :icon="bookOutline" class="card-menu-icon" />
        <span>阅读</span>
      </button>
      <button type="button" class="card-menu-item" @click.stop="handleCardMove(cardMenu.item)">
        <IonIcon :icon="folderOpenOutline" class="card-menu-icon" />
        <span>移动</span>
      </button>
      <button type="button" class="card-menu-item" @click.stop="handleCardDownload(cardMenu.item)">
        <IonIcon :icon="downloadOutline" class="card-menu-icon" />
        <span>下载</span>
      </button>
      <button
        type="button"
        class="card-menu-item card-menu-item--danger"
        @click.stop="handleCardRemove(cardMenu.item)"
      >
        <IonIcon :icon="trashOutline" class="card-menu-icon" />
        <span>取消收藏</span>
      </button>
    </div>
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
import { useRouter } from 'vue-router'
import {
  IonContent,
  IonIcon,
  IonPage,
  IonRefresher,
  IonRefresherContent,
  menuController,
} from '@ionic/vue'
import { createGesture, type Gesture } from '@ionic/vue'
import {
  bookOutline,
  downloadOutline,
  ellipsisVertical,
  folderOpenOutline,
  trashOutline,
} from 'ionicons/icons'
import type { ScrollCustomEvent } from '@ionic/core'
import MenuToggleButton from '@/components/common/MenuToggleButton.vue'
import QuickActionFab from '@/components/common/QuickActionFab.vue'
import FavoriteSearchBar from '@/components/favorite/FavoriteSearchBar.vue'
import FavoriteSideMenu from '@/components/favorite/FavoriteSideMenu.vue'
import SearchResultContainer from '@/components/search/SearchResultContainer.vue'
import type {
  SearchResultContainerExposed,
  SearchResultDisplayItem,
} from '@/components/search/SearchResultContainer.vue'
import { JmcomicService, sanitizeError, showToast } from '@/services/JmcomicService'
import { OfflineDownloadService } from '@/services/OfflineDownloadService'
import {
  OfflineFavoriteService,
  offlineFolderCache,
  offlineTotalCount,
} from '@/services/OfflineFavoriteService'
import { ExportFormatService } from '@/services/ExportFormatService'
import { useAuth } from '@/composables/useAuth'
import type {
  FavoriteQuery,
  FavoriteResult,
  FolderEntry,
  SearchResult,
  SearchResultItem,
} from '@/services/JmcomicTypes'
import { OFFLINE_ALL_FOLDER_ID } from '@/services/JmcomicTypes'
import { useSideMenuState } from '@/composables/useSideMenuState'
import { alertController } from '@ionic/vue'
import { cachedState } from '@/composables/favoritePageCache'

defineOptions({ name: 'FavoritePage' })

const { isDraggingRight, isSnappingClosed, leftMenuOpen, rightDragProgress, rightMenuOpen } =
  useSideMenuState()

const NEXT_PAGE_THRESHOLD = 220

const router = useRouter()

// --- 收藏夹源 ---
type FolderSource = 'online' | 'offline'
const folderSource = ref<FolderSource>('offline')
const currentFolderId = ref('default')
const onlineFolderMap = ref<Record<string, string>>({})
const onlineFolderCounts = ref<Record<string, number>>({})
const currentKeyword = ref('')

const loadOnlineFolderCounts = async () => {
  const ids = Object.keys(onlineFolderMap.value)
  if (ids.length === 0) return
  const counts: Record<string, number> = {}
  const promises = ids.map((id) =>
    JmcomicService.favorites({ folderId: id, page: 1 })
      .then((r) => {
        counts[id] = r.totalItems
      })
      .catch(() => {
        counts[id] = 0
      }),
  )
  await Promise.all(promises)
  onlineFolderCounts.value = counts
}

const onlineFolderEntries = computed<FolderEntry[]>(() =>
  Object.entries(onlineFolderMap.value).map(([id, name]) => ({ id, name, count: 0 })),
)

const offlineFolderEntries = computed<FolderEntry[]>(() => {
  const folders = offlineFolderCache.value
  const totalCount = offlineTotalCount.value
  const allEntry: FolderEntry = { id: OFFLINE_ALL_FOLDER_ID, name: '全部', count: totalCount }
  return [allEntry, ...folders]
})
computed(() => {
  if (folderSource.value === 'online') {
    return onlineFolderMap.value[currentFolderId.value] ?? ''
  }
  if (currentFolderId.value === 'default') return ''
  return offlineFolderEntries.value.find((f) => f.id === currentFolderId.value)?.name ?? ''
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

const saveToCache = () => {
  if (!resultMeta.value) return
  cachedState.value = {
    folderSource: folderSource.value,
    currentFolderId: currentFolderId.value,
    onlineFolderMap: { ...onlineFolderMap.value },
    onlineFolderCounts: { ...onlineFolderCounts.value },
    resultMeta: resultMeta.value,
    pageCache: { ...pageCache.value },
    displayMode: displayMode.value,
  }
}

const restoreFromCache = (): boolean => {
  const c = cachedState.value
  if (!c) return false
  folderSource.value = c.folderSource
  currentFolderId.value = c.currentFolderId
  onlineFolderMap.value = c.onlineFolderMap
  onlineFolderCounts.value = c.onlineFolderCounts
  resultMeta.value = c.resultMeta
  pageCache.value = c.pageCache
  displayMode.value = c.displayMode
  return true
}

const silentRefresh = async () => {
  try {
    const pageResult = await fetchPage(1)
    const cached = pageCache.value[1] ?? []
    const sameContent =
      cached.length === pageResult.content.length &&
      cached.every((item, i) => item.id === pageResult.content[i]?.id)
    const sameTotal = resultMeta.value?.totalItems === pageResult.totalItems
    if (!sameContent) {
      resultMeta.value = pageResult
      pageCache.value = { 1: pageResult.content }
    } else if (!sameTotal) {
      resultMeta.value = pageResult
    }
    saveToCache()
    if (!sameContent) {
      pageAtTop.value = true
    }
  } catch {
    // 静默失败，缓存数据保持显示
  }
}

const currentFavoriteQuery = computed<FavoriteQuery>(() => ({
  folderId: folderSource.value === 'online' ? currentFolderId.value : '0',
  page: 1,
  keyword: currentKeyword.value,
}))

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

const fetchOfflineFavorites = async (page: number): Promise<SearchResult> => {
  const pageSize = 20
  if (currentFolderId.value === OFFLINE_ALL_FOLDER_ID) {
    const items = await OfflineFavoriteService.getAllItemsMerged()
    const kw = currentKeyword.value || undefined
    const filtered = kw
      ? items.filter((i) => i.title.toLowerCase().includes(kw.toLowerCase()))
      : items
    const totalItems = filtered.length
    const totalPages = Math.max(1, Math.ceil(totalItems / pageSize))
    const currentPage = Math.min(Math.max(1, page), totalPages)
    const start = (currentPage - 1) * pageSize
    return {
      currentPage,
      totalItems,
      totalPages,
      content: filtered.slice(start, start + pageSize),
    }
  }
  const result = await OfflineFavoriteService.getItems(
    currentFolderId.value,
    currentKeyword.value || undefined,
    page,
    pageSize,
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
    saveToCache()
    await nextTick()
    void contentRef.value?.$el?.scrollToTop?.(0)
    pageAtTop.value = true
  } catch (error) {
    resultMeta.value = null
    pageCache.value = {}
    errorMessage.value = sanitizeError(error, '加载失败')
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

const handleRefresh = async (event: CustomEvent) => {
  const refresher = event.target as HTMLIonRefresherElement
  try {
    if (folderSource.value === 'online') {
      errorMessage.value = ''
      const result = await fetchOnlineFavorites(1)
      resultMeta.value = result
      pageCache.value = { 1: result.content }
    } else {
      const result = await fetchOfflineFavorites(1)
      resultMeta.value = result
      pageCache.value = { 1: result.content }
    }
  } catch {
    if (folderSource.value === 'online') {
      errorMessage.value = '刷新失败'
    }
  } finally {
    refresher.complete()
  }
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
    saveToCache()
    await maybeLoadNextAfterRender()
  } catch (error) {
    errorMessage.value = sanitizeError(error, '加载下一页失败')
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
    ? (resultContainerRef.value?.getEntryElement(anchorEntryKey)?.getBoundingClientRect().top ??
      null)
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
    saveToCache()
    await nextTick()
    if (anchorEntryKey && previousAnchorTop !== null) {
      const nextAnchorTop =
        resultContainerRef.value?.getEntryElement(anchorEntryKey)?.getBoundingClientRect().top ??
        null
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
    errorMessage.value = sanitizeError(error, '加载上一页失败')
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

  const scrollElement = scrollElementRef.value ?? (await resolveScrollElement())
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

// --- 收藏夹管理事件 ---
const busyManagement = ref(false)

// --- 操作进度弹窗 ---
const showProgressModal = ref(false)
const progressTitle = ref('')
const progressCurrent = ref(0)
const progressTotal = ref(0)
const progressCurrentItem = ref('')

const progressPercent = computed(() => {
  if (progressTotal.value <= 0) return 0
  return Math.round((progressCurrent.value / progressTotal.value) * 100)
})

// --- 卡片操作菜单 ---
interface CardMenuState {
  item: SearchResultItem
  x: number
  y: number
}

const cardMenu = ref<CardMenuState | null>(null)
const cardMenuRef = ref<HTMLElement | null>(null)

const cardMenuStyle = computed(() => {
  const m = cardMenu.value
  if (!m) return {}
  // 菜单估算高度 ~176px (4 × 44px)，超出视口底部则向上翻转
  const menuH = 180
  const top = m.y + menuH > window.innerHeight ? m.y - menuH - 8 : m.y
  return {
    position: 'fixed' as const,
    top: `${top}px`,
    left: `${Math.min(m.x, window.innerWidth - 160)}px`,
  }
})

function openCardMenu(item: SearchResultItem, event: MouseEvent) {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  cardMenu.value = { item, x: rect.left, y: rect.bottom + 4 }
  // 延迟注册 click-outside，避免同一事件触发立即关闭
  setTimeout(() => {
    document.addEventListener('mousedown', handleCardMenuClickOutside)
    document.addEventListener('touchstart', handleCardMenuClickOutside)
  }, 0)
}

function closeCardMenu() {
  cardMenu.value = null
  document.removeEventListener('mousedown', handleCardMenuClickOutside)
  document.removeEventListener('touchstart', handleCardMenuClickOutside)
}

function handleCardMenuClickOutside(e: Event) {
  // capture 阶段触发，此时需检查 target 是否在菜单内——菜单上的 .stop 只在冒泡阶段生效
  if (cardMenuRef.value?.contains(e.target as Node)) return
  closeCardMenu()
}

// 组件卸载时清理
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', handleCardMenuClickOutside)
  document.removeEventListener('touchstart', handleCardMenuClickOutside)
})

// --- 卡片操作：阅读 ---
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

// --- 卡片操作：移动 ---
async function handleCardMove(item: SearchResultItem) {
  closeCardMenu()
  if (currentFolderId.value === OFFLINE_ALL_FOLDER_ID) {
    await showToast('全部视图不支持移动，请进入具体文件夹操作', 'medium')
    return
  }
  const isOnline = folderSource.value === 'online'
  let folders: { id: string; name: string }[]

  if (isOnline) {
    folders = Object.entries(onlineFolderMap.value)
      .filter(([id]) => id !== currentFolderId.value)
      .map(([id, name]) => ({ id, name }))
  } else {
    folders = OfflineFavoriteService.getFolders().filter((f) => f.id !== currentFolderId.value)
  }

  if (folders.length === 0) {
    await showToast('没有其他收藏夹', 'medium')
    return
  }

  const alert = await alertController.create({
    header: '移动到',
    inputs: folders.map((f) => ({
      type: 'radio' as const,
      label: f.name,
      value: f.id,
    })),
    buttons: [
      { text: '取消', role: 'cancel' as const },
      {
        text: '确定',
        handler: async (data: string) => {
          if (!data) return
          if (isOnline) {
            try {
              if (data === '0') {
                await JmcomicService.toggleAlbumFavorite(item.id, currentFolderId.value)
                await JmcomicService.toggleAlbumFavorite(item.id, '0')
              } else {
                await JmcomicService.manageFavoriteFolder('move', data, '', item.id)
              }
              await showToast('已移动', 'success')
              void resetWithPage(1)
            } catch {
              await showToast('移动失败', 'danger')
            }
          } else {
            await OfflineFavoriteService.removeItem(currentFolderId.value, item.id)
            await OfflineFavoriteService.addItem(data, item)
            await showToast('已移动', 'success')
            void resetWithPage(1)
          }
        },
      },
    ],
  })
  await alert.present()
}

// --- 卡片操作：下载 ---
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

// --- 卡片操作：取消收藏 ---
async function handleCardRemove(item: SearchResultItem) {
  closeCardMenu()
  if (currentFolderId.value === OFFLINE_ALL_FOLDER_ID) {
    await showToast('全部视图不支持移除，请进入具体文件夹操作', 'medium')
    return
  }
  const isOnline = folderSource.value === 'online'
  const alert = await alertController.create({
    header: '取消收藏',
    message: `确定将「${item.title}」从收藏夹移除？`,
    buttons: [
      { text: '取消', role: 'cancel' as const },
      {
        text: '移除',
        role: 'destructive' as const,
        cssClass: 'danger-alert',
        handler: async () => {
          if (isOnline) {
            try {
              await JmcomicService.toggleAlbumFavorite(item.id, currentFolderId.value)
              await showToast('已取消收藏', 'success')
              void resetWithPage(1)
            } catch {
              await showToast('操作失败', 'danger')
            }
          } else {
            await OfflineFavoriteService.removeItem(currentFolderId.value, item.id)
            await showToast('已取消收藏', 'success')
            void resetWithPage(1)
          }
        },
      },
    ],
  })
  await alert.present()
}

const onAddFolder = async (source: 'online' | 'offline') => {
  const alert = await alertController.create({
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
            await alert.dismiss()
            try {
              const r = await JmcomicService.manageFavoriteFolder('add', '0', name, '')
              if (r.status === 'ok') {
                await refreshOnlineFolderList()
                await showToast('收藏夹已创建', 'success')
              } else {
                await showToast(r.msg || '创建失败', 'danger')
              }
            } catch {
              await showToast('创建失败', 'danger')
            }
          } else {
            await OfflineFavoriteService.createFolder(name)
            await showToast('收藏夹已创建', 'success')
          }
        },
      },
    ],
  })
  await alert.present()
}

const onRenameFolder = async (payload: {
  folderId: string
  folderName: string
  isOnline: boolean
}) => {
  const alert = await alertController.create({
    header: '重命名收藏夹',
    inputs: [{ name: 'name', type: 'text', placeholder: '新名称', value: payload.folderName }],
    buttons: [
      { text: '取消', role: 'cancel' },
      {
        text: '确定',
        handler: async (data) => {
          const name = data?.name?.trim()
          if (!name || name === payload.folderName) return
          if (payload.isOnline) {
            await alert.dismiss()
            try {
              const r = await JmcomicService.manageFavoriteFolder(
                'edit',
                payload.folderId,
                name,
                '',
              )
              if (r.status === 'ok') {
                await refreshOnlineFolderList()
                await showToast('已重命名', 'success')
              } else {
                await showToast(r.msg || '重命名失败', 'danger')
              }
            } catch {
              await showToast('重命名失败', 'danger')
            }
          } else {
            await OfflineFavoriteService.renameFolder(payload.folderId, name)
            await showToast('已重命名', 'success')
          }
        },
      },
    ],
  })
  await alert.present()
}

const onDeleteFolder = async (payload: {
  folderId: string
  folderName: string
  isOnline: boolean
}) => {
  const alert = await alertController.create({
    header: '删除收藏夹',
    message: `确定删除「${payload.folderName}」吗？此操作不可撤销。`,
    buttons: [
      { text: '取消', role: 'cancel' },
      {
        text: '删除',
        role: 'destructive',
        cssClass: 'danger-alert',
        handler: async () => {
          if (payload.isOnline) {
            try {
              await alert.dismiss()
              const r = await JmcomicService.manageFavoriteFolder('del', payload.folderId, '', '')
              if (r.status === 'ok') {
                if (folderSource.value === 'online' && currentFolderId.value === payload.folderId) {
                  currentFolderId.value = '0'
                  void resetWithPage(1)
                }
                await refreshOnlineFolderList()
                await showToast('已删除', 'success')
              } else {
                await showToast(r.msg || '删除失败', 'danger')
              }
            } catch {
              await showToast('删除失败', 'danger')
            }
          } else {
            await OfflineFavoriteService.deleteFolder(payload.folderId)
            if (folderSource.value === 'offline' && currentFolderId.value === payload.folderId) {
              const folders = OfflineFavoriteService.getFolders()
              currentFolderId.value = folders[0]?.id ?? 'default'
            }
            void resetWithPage(1)
            await showToast('已删除', 'success')
          }
        },
      },
    ],
  })
  await alert.present()
}

const onCopyFolder = async (payload: {
  folderId: string
  folderName: string
  isOnline: boolean
}) => {
  if (busyManagement.value) {
    await showToast('操作进行中，请稍候', 'medium')
    return
  }

  if (payload.isOnline) {
    // 在线 → 离线：读取在线源夹全部项，写入本地离线文件夹
    const alert = await alertController.create({
      header: '复制到离线收藏夹',
      message: '将在线收藏夹内容复制到本地离线文件夹，确定吗？',
      inputs: [
        {
          name: 'name',
          type: 'text',
          placeholder: '新文件夹名称',
          value: `${payload.folderName} (副本)`,
        },
      ],
      buttons: [
        { text: '取消', role: 'cancel' },
        {
          text: '确定',
          handler: async (data) => {
            const name = data?.name?.trim()
            if (!name) return
            await alert.dismiss()
            busyManagement.value = true
            showProgressModal.value = true
            progressTitle.value = '正在复制到离线'
            progressCurrent.value = 0
            progressTotal.value = 0
            progressCurrentItem.value = ''
            try {
              const items: SearchResultItem[] = []
              let page = 1
              let totalPages = 1
              while (page <= totalPages) {
                const r = await JmcomicService.favorites({ folderId: payload.folderId, page })
                r.content.forEach((item) => items.push(item))
                if (progressTotal.value === 0) progressTotal.value = r.totalItems
                totalPages = r.totalPages
                page++
                progressCurrent.value = items.length
                if (items.length > 0) progressCurrentItem.value = items[items.length - 1].title
              }
              const newId = await OfflineFavoriteService.createFolder(name)
              if (items.length > 0) {
                await OfflineFavoriteService.addItems(newId, items)
              }
              await showToast(`已复制 ${items.length} 个本子到离线`, 'success')
            } catch {
              await showToast('复制失败', 'danger')
            }
            showProgressModal.value = false
            busyManagement.value = false
          },
        },
      ],
    })
    await alert.present()
  } else {
    // 离线子夹：离线副本 或 同步到在线
    const alert = await alertController.create({
      header: '复制收藏夹',
      inputs: [
        {
          name: 'name',
          type: 'text',
          placeholder: '新文件夹名称',
          value: `${payload.folderName} (副本)`,
        },
        {
          name: 'target',
          type: 'radio',
          label: '离线副本',
          value: 'offline',
          checked: true,
        },
        {
          name: 'target',
          type: 'radio',
          label: '同步到在线',
          value: 'online',
        },
      ],
      buttons: [
        { text: '取消', role: 'cancel' },
        {
          text: '确定',
          handler: async (data: string | Record<string, string>) => {
            const form = typeof data === 'string' ? { target: data } : data || {}
            const name = (form.name ?? '').trim()
            if (!name) return
            await alert.dismiss()
            busyManagement.value = true

            if (form.target === 'online') {
              // 离线 → 在线
              try {
                const items =
                  payload.folderId === OFFLINE_ALL_FOLDER_ID
                    ? await OfflineFavoriteService.getAllItemsMerged()
                    : await OfflineFavoriteService.getAllItems(payload.folderId)
                if (items.length === 0) {
                  await showToast('源文件夹为空', 'medium')
                  busyManagement.value = false
                  return
                }
                // 1. 创建在线文件夹
                const createResult = await JmcomicService.manageFavoriteFolder('add', '0', name, '')
                if (createResult.status !== 'ok') {
                  await showToast('创建在线文件夹失败', 'danger')
                  busyManagement.value = false
                  return
                }
                await refreshOnlineFolderList()
                const newFolderId = Object.entries(onlineFolderMap.value).find(
                  ([, fName]) => fName === name,
                )?.[0]
                if (!newFolderId) {
                  await showToast('创建成功但无法定位新文件夹', 'danger')
                  busyManagement.value = false
                  return
                }
                // 2. 获取在线已收藏 ID 集合（分页遍历"全部"）
                const favoritedIds = new Set<string>()
                let fp = 1
                let ftp = 1
                while (fp <= ftp) {
                  const r = await JmcomicService.favorites({ folderId: '0', page: fp })
                  r.content.forEach((item) => favoritedIds.add(item.id))
                  ftp = r.totalPages
                  fp++
                }
                // 3. 逐项处理（收集失败项）
                showProgressModal.value = true
                progressTitle.value = '正在同步到在线'
                progressTotal.value = items.length
                progressCurrent.value = 0
                progressCurrentItem.value = ''
                let ok = 0
                const failed: string[] = []
                for (const item of items) {
                  try {
                    if (!favoritedIds.has(item.id)) {
                      await JmcomicService.toggleAlbumFavorite(item.id, '0')
                    }
                    await JmcomicService.manageFavoriteFolder('move', newFolderId, '', item.id)
                    ok++
                  } catch {
                    failed.push(item.title || item.id)
                  }
                  progressCurrent.value = ok + failed.length
                  progressCurrentItem.value = item.title || item.id
                }
                showProgressModal.value = false
                if (failed.length > 0) {
                  await showToast(
                    `已同步 ${ok}/${items.length} 个，失败 ${failed.length} 项：${failed.slice(0, 3).join('、')}${failed.length > 3 ? ' 等' : ''}`,
                    'medium',
                  )
                } else {
                  await showToast(`已同步 ${ok} 个本子到在线`, 'success')
                }
              } catch {
                showProgressModal.value = false
                await showToast('同步失败', 'danger')
              }
            } else {
              // 离线 → 离线
              let newId: string
              if (payload.folderId === OFFLINE_ALL_FOLDER_ID) {
                const items = await OfflineFavoriteService.getAllItemsMerged()
                newId = await OfflineFavoriteService.createFolder(name)
                if (items.length > 0) {
                  await OfflineFavoriteService.addItems(newId, items)
                }
              } else {
                newId = await OfflineFavoriteService.copyFolder(payload.folderId, name)
              }
              await showToast('已复制', 'success')
              if (folderSource.value === 'offline' && currentFolderId.value === payload.folderId) {
                currentFolderId.value = newId
                void resetWithPage(1)
              }
            }
            busyManagement.value = false
          },
        },
      ],
    })
    await alert.present()
  }
}

const onMoveFolder = async (payload: {
  folderId: string
  folderName: string
  isOnline: boolean
}) => {
  if (busyManagement.value) {
    await showToast('操作进行中，请稍候', 'medium')
    return
  }

  if (payload.isOnline) {
    // 在线 → 在线：选择目标文件夹 → 备份 → 逐项移动 → 验证
    const targets = Object.entries(onlineFolderMap.value)
      .filter(([id]) => id !== payload.folderId)
      .map(([id, name]) => ({ id, name }))

    if (targets.length === 0) {
      await showToast('没有其他在线收藏夹', 'medium')
      return
    }

    const alert = await alertController.create({
      header: '移动到',
      message: `将「${payload.folderName}」的全部本子移动到：`,
      inputs: targets.map((t) => ({
        type: 'radio' as const,
        label: t.name,
        value: t.id,
      })),
      buttons: [
        { text: '取消', role: 'cancel' },
        {
          text: '确定',
          handler: async (targetId: string) => {
            if (!targetId) return
            await alert.dismiss()
            busyManagement.value = true

            try {
              // 1. 读取源夹全部项并本地备份
              const fullItems: SearchResultItem[] = []
              let page = 1
              let totalPages = 1
              while (page <= totalPages) {
                const r = await JmcomicService.favorites({ folderId: payload.folderId, page })
                r.content.forEach((item) => fullItems.push(item))
                totalPages = r.totalPages
                page++
              }

              const backupKey = `move_${payload.folderId}_${Date.now()}`
              await OfflineFavoriteService.saveBackup(backupKey, fullItems)

              // 2. 并发移动（concurrency=3, delay=200ms）
              showProgressModal.value = true
              progressTitle.value = '正在移动'
              progressTotal.value = fullItems.length
              progressCurrent.value = 0
              progressCurrentItem.value = ''

              const concurrency = 3
              const delayMs = 200
              let cursor = 0
              let moved = 0

              const moveToAll = targetId === '0'

              const worker = async () => {
                while (cursor < fullItems.length) {
                  const i = cursor++
                  try {
                    if (moveToAll) {
                      await JmcomicService.toggleAlbumFavorite(fullItems[i].id, payload.folderId)
                      await JmcomicService.toggleAlbumFavorite(fullItems[i].id, '0')
                    } else {
                      await JmcomicService.manageFavoriteFolder(
                        'move',
                        targetId,
                        '',
                        fullItems[i].id,
                      )
                    }
                    moved++
                    progressCurrent.value = moved
                    progressCurrentItem.value = fullItems[i].title || fullItems[i].id
                  } catch {
                    /* skip */
                    progressCurrent.value++
                  }
                  if (delayMs > 0 && cursor < fullItems.length) {
                    await new Promise((r) => setTimeout(r, delayMs))
                  }
                }
              }

              const workerCount = Math.min(concurrency, fullItems.length)
              await Promise.all(Array.from({ length: workerCount }, () => worker()))
              showProgressModal.value = false

              // 3. 验证
              if (moved === fullItems.length) {
                await OfflineFavoriteService.deleteBackup(backupKey)
                await refreshOnlineFolderList()
                await showToast(`已移动 ${moved} 个本子`, 'success')
                void resetWithPage(1)
              } else {
                await showToast(
                  `已移动 ${moved}/${fullItems.length} 个本子（备份已保留）`,
                  'medium',
                )
                await refreshOnlineFolderList()
                void resetWithPage(1)
              }
            } catch (e) {
              showProgressModal.value = false
              await showToast('移动失败，可通过备份恢复', 'danger')
            }
            busyManagement.value = false
          },
        },
      ],
    })
    await alert.present()
  } else {
    // 离线 → 离线：选择目标文件夹 → 移动全部项
    const targets = OfflineFavoriteService.getFolders().filter(
      (f) => f.id !== payload.folderId && f.id !== OFFLINE_ALL_FOLDER_ID,
    )

    if (targets.length === 0) {
      await showToast('没有其他离线收藏夹', 'medium')
      return
    }

    const alert = await alertController.create({
      header: '移动到',
      message: `将「${payload.folderName}」的全部本子移动到：`,
      inputs: targets.map((t) => ({
        type: 'radio' as const,
        label: t.name,
        value: t.id,
      })),
      buttons: [
        { text: '取消', role: 'cancel' },
        {
          text: '确定',
          handler: async (targetId: string) => {
            if (!targetId) return
            await alert.dismiss()
            busyManagement.value = true
            if (payload.folderId === OFFLINE_ALL_FOLDER_ID) {
              await OfflineFavoriteService.mergeAllToFolder(targetId)
            } else {
              await OfflineFavoriteService.moveAllItems(payload.folderId, targetId)
            }
            await showToast('已移动', 'success')
            if (folderSource.value === 'offline' && currentFolderId.value === payload.folderId) {
              currentFolderId.value = targetId
              void resetWithPage(1)
            }
            busyManagement.value = false
          },
        },
      ],
    })
    await alert.present()
  }
}

const onExportFolder = async (payload: {
  folderId: string
  folderName: string
  isOnline: boolean
}) => {
  if (busyManagement.value) {
    await showToast('操作进行中，请稍候', 'medium')
    return
  }
  busyManagement.value = true
  const template = ExportFormatService.getExportFormat()

  try {
    let items: SearchResultItem[]
    if (payload.isOnline) {
      items = []
      let page = 1
      let totalPages = 1
      while (page <= totalPages) {
        const r = await JmcomicService.favorites({ folderId: payload.folderId, page })
        items.push(...r.content)
        totalPages = r.totalPages
        page++
      }
    } else if (payload.folderId === OFFLINE_ALL_FOLDER_ID) {
      items = await OfflineFavoriteService.getAllItemsMerged()
    } else {
      items = await OfflineFavoriteService.getAllItems(payload.folderId)
    }

    if (items.length === 0) {
      await showToast('该文件夹没有内容', 'medium')
      return
    }

    const text = items
      .map((item) => ExportFormatService.renderExportFormat(template, item))
      .join('\n')

    try {
      await navigator.clipboard.writeText(text)
      await showToast(`已复制 ${items.length} 条到剪贴板`, 'success')
    } catch {
      // clipboard 不可用，用 alert 展示
      const alert = await alertController.create({
        header: '导出结果',
        message: `共 ${items.length} 条，请手动复制：`,
        inputs: [{ name: 'text', type: 'textarea', value: text }],
        buttons: [{ text: '关闭', role: 'cancel' }],
      })
      await alert.present()
    }
  } catch (e) {
    await showToast('导出失败', 'danger')
  } finally {
    busyManagement.value = false
  }
}

async function refreshOnlineFolderList() {
  try {
    const result: FavoriteResult = await JmcomicService.favorites({ folderId: '0', page: 1 })
    if (result.folderList) {
      onlineFolderMap.value = result.folderList
    }
    await loadOnlineFolderCounts()
  } catch {
    /* ignore */
  }
}

// --- 初始化 ---
const { isLoggedIn } = useAuth()

const initOnlineFolders = async () => {
  await OfflineFavoriteService.ensureInit()
  if (!isLoggedIn.value) {
    folderSource.value = 'offline'
    const offlineFolders = OfflineFavoriteService.getFolders()
    if (offlineFolders.length > 0) {
      currentFolderId.value = offlineFolders[0].id
      void resetWithPage(1)
    } else {
      currentFolderId.value = OFFLINE_ALL_FOLDER_ID
    }
    return
  }

  const hasCached = restoreFromCache()
  if (!hasCached) {
    initialLoading.value = true
  }

  try {
    const result: FavoriteResult = await JmcomicService.favorites({ folderId: '0', page: 1 })
    if (result.folderList) {
      onlineFolderMap.value = result.folderList
    }
    void loadOnlineFolderCounts()
    folderSource.value = 'online'
    currentFolderId.value = '0'
    if (hasCached) {
      void silentRefresh()
    } else {
      void resetWithPage(1)
    }
  } catch {
    if (!hasCached) {
      folderSource.value = 'offline'
      const offlineFolders = OfflineFavoriteService.getFolders()
      if (offlineFolders.length > 0) {
        currentFolderId.value = offlineFolders[0].id
        void resetWithPage(1)
      } else {
        currentFolderId.value = OFFLINE_ALL_FOLDER_ID
        initialLoading.value = false
      }
    }
  }
}

// --- 手势（仅关闭） ---
let menuCloseGesture: Gesture | undefined

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

// 右侧菜单打开时建立关闭手势，关闭时销毁；同时刷新在线文件夹数量
watch(rightMenuOpen, (open) => {
  if (open) {
    nextTick(() => setupCloseGesture())
    if (isLoggedIn.value) {
      void loadOnlineFolderCounts()
    }
  } else {
    menuCloseGesture?.destroy()
    menuCloseGesture = undefined
  }
})

// 登录态从未登录变为已登录时自动切换到在线收藏夹
watch(isLoggedIn, (loggedIn, wasLoggedIn) => {
  if (loggedIn && !wasLoggedIn) {
    initialLoading.value = true
    folderSource.value = 'online'
    currentFolderId.value = '0'
    void resetWithPage(1)
  }
})

onMounted(() => {
  void initOnlineFolders()
  nextTick(() => {
    void resolveScrollElement()
  })
})

// --- keepAlive 状态保存 ---
const savedScrollTop = ref(0)

onDeactivated(() => {
  savedScrollTop.value = scrollElementRef.value?.scrollTop ?? 0
  menuCloseGesture?.destroy()
  menuCloseGesture = undefined
  rightMenuOpen.value = false
  isDraggingRight.value = false
  isSnappingClosed.value = false
  rightDragProgress.value = 0
})

onActivated(async () => {
  await nextTick()
  const scrollEl = scrollElementRef.value ?? (await resolveScrollElement())
  if (scrollEl && savedScrollTop.value > 0) {
    scrollEl.scrollTop = savedScrollTop.value
  }
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

/* 卡片更多操作按钮 */
.card-more-btn {
  position: absolute;
  right: 2px;
  top: 50%;
  transform: translateY(-50%);
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #8a6048;
  font-size: 18px;
  cursor: pointer;
}

.card-more-btn:active {
  background: #f5d2bc;
}

/* 卡片上下文菜单 */
.card-context-menu {
  position: fixed;
  z-index: 200;
  min-width: 150px;
  background: #fffbf8;
  border: 1px solid rgb(250 156 105 / 0.3);
  border-radius: 14px;
  box-shadow: 0 8px 22px rgb(76 42 24 / 0.16);
  overflow: hidden;
}

.card-menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 44px;
  padding: 10px 16px;
  border: 0;
  border-bottom: 1px solid rgb(245 210 188 / 0.4);
  background: transparent;
  color: #3a261d;
  font: inherit;
  font-size: 14px;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.14s ease;
}

.card-menu-item:last-child {
  border-bottom: 0;
}

.card-menu-item:hover,
.card-menu-item:active {
  background: #fff0e7;
}

.card-menu-item--danger {
  color: #d4533e;
}

.card-menu-icon {
  font-size: 16px;
  flex-shrink: 0;
}

/* --- 操作进度弹窗 --- */
.progress-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;
}

.progress-dialog {
  background: #fff;
  border-radius: 16px;
  padding: 32px 28px 24px;
  width: 300px;
  text-align: center;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.18);
}

.progress-title {
  font-size: 16px;
  font-weight: 600;
  color: #4c2a18;
  margin-bottom: 20px;
}

.progress-bar {
  height: 8px;
  background: #f0e4db;
  border-radius: 4px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(135deg, #f0a060, #e8843c);
  border-radius: 4px;
  transition: width 0.25s ease;
}

.progress-percent {
  font-size: 28px;
  font-weight: 700;
  color: #e8843c;
  margin: 12px 0 8px;
}

.progress-count {
  font-size: 13px;
  color: #8c6b5a;
  margin-bottom: 4px;
}

.progress-file {
  font-size: 11px;
  color: #c4a494;
  margin-bottom: 16px;
  word-break: break-all;
  max-height: 32px;
  overflow: hidden;
}

.progress-warning {
  font-size: 12px;
  color: #d4a08a;
  margin-top: 8px;
}
</style>
