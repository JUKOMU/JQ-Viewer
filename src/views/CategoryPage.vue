<template>
  <IonPage>
    <IonContent ref="contentRef" :scroll-events="true" @ion-scroll="handleContentScroll">
      <Transition name="category-overlay">
        <div
          v-if="categoryOverlayVisible"
          class="category-overlay"
          @click.self="closeSearchOverlay"
        >
          <div class="category-overlay-panel">
            <CategorySearchToolbar @search="submitOverlayCategory"/>
          </div>
        </div>
      </Transition>

      <div class="category-page-top">
        <div class="category-page-toolbar" :class="{ pinned: pullHeaderPinned }">
          <MenuToggleButton/>
          <div class="toolbar-category">
            <CategorySearchToolbar @search="resetWithPage"/>
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
        idle-text="分类搜索结果将在这里显示"
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
            <IonIcon :icon="ellipsisVertical"/>
          </button>
        </template>
      </SearchResultContainer>

      <!-- 卡片操作上下文菜单 -->
      <div
        v-if="cardMenu"
        ref="cardMenuRef"
        class="card-context-menu"
        :style="cardMenuStyle"
        @mousedown.prevent.stop
        @touchstart.stop
      >
        <button type="button" class="card-menu-item" @click.stop="handleCardDetail(cardMenu.item)">
          <IonIcon :icon="informationCircleOutline" class="card-menu-icon"/>
          <span>详情</span>
        </button>
        <button type="button" class="card-menu-item" @click.stop="handleCardRead(cardMenu.item)">
          <IonIcon :icon="bookOutline" class="card-menu-icon"/>
          <span>阅读</span>
        </button>
        <button type="button" class="card-menu-item" @click.stop="handleCardDownload(cardMenu.item)">
          <IonIcon :icon="downloadOutline" class="card-menu-icon"/>
          <span>下载</span>
        </button>
        <button type="button" class="card-menu-item" @click.stop="handleCardFavorite(cardMenu.item)">
          <IonIcon :icon="heartOutline" class="card-menu-icon"/>
          <span>收藏</span>
        </button>
      </div>

      <!-- 收藏夹选择弹窗 -->
      <FavoriteFolderPicker
        v-model="showFolderPicker"
        :online-folders="pickerOnlineFolders"
        :offline-folders="pickerOfflineFolders"
        :online-folder-counts="pickerOnlineFolderCounts"
        @select="onPickerSelect"
        @add-folder="onPickerAddFolder"
      />

      <QuickActionFab
        v-if="resultMeta"
        slot="fixed"
        @search="openSearch"
        @jump="jumpToPage"
        @top="scrollToTop"
        @back="clearResult"
      />
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
import {computed, nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref} from 'vue'
import {useRouter} from 'vue-router'
import {alertController, IonContent, IonIcon, IonPage} from '@ionic/vue'
import {
  bookOutline,
  downloadOutline,
  ellipsisVertical,
  heartOutline,
  informationCircleOutline,
} from 'ionicons/icons'
import type {ScrollCustomEvent} from '@ionic/core'
import CategorySearchToolbar from '@/components/search/CategorySearchToolbar.vue'
import MenuToggleButton from '@/components/common/MenuToggleButton.vue'
import QuickActionFab from '@/components/common/QuickActionFab.vue'
import FavoriteFolderPicker from '@/components/favorite/FavoriteFolderPicker.vue'
import type {
  SearchResultContainerExposed,
  SearchResultDisplayItem,
} from '@/components/search/SearchResultContainer.vue'
import SearchResultContainer from '@/components/search/SearchResultContainer.vue'
import {JmcomicService, sanitizeError, showToast} from '@/services/JmcomicService'
import {OfflineDownloadService} from '@/services/OfflineDownloadService'
import {OfflineFavoriteService} from '@/services/OfflineFavoriteService'
import {useAuth} from '@/composables/useAuth'
import type {FavoriteResult, FolderEntry, SearchQuery, SearchResult, SearchResultItem} from '@/services/JmcomicTypes'

defineOptions({name: 'CategoryPage'})

const router = useRouter()

const NEXT_PAGE_THRESHOLD = 220

const resultMeta = ref<SearchResult | null>(null)
const initialLoading = ref(false)
const loadingPrevious = ref(false)
const loadingNext = ref(false)
const errorMessage = ref('')
const displayMode = ref<'list' | 'grid'>('grid')
const lastQuery = ref<SearchQuery | null>(null)
const contentRef = ref<InstanceType<typeof IonContent> | null>(null)
const scrollElementRef = ref<HTMLElement | null>(null)
const categoryOverlayVisible = ref(false)
const resultContainerRef = ref<SearchResultContainerExposed | null>(null)
const pageCache = ref<Record<number, SearchResultItem[]>>({})
const pageAtTop = ref(true)
const pullGestureActive = ref(false)

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
  return await JmcomicService.categories({...query, page})
}

const resetWithPage = async (query: SearchQuery) => {
  const targetQuery = {...query, page: query.page ?? 1}
  lastQuery.value = targetQuery
  initialLoading.value = true
  errorMessage.value = ''
  resultMeta.value = null
  pageCache.value = {}
  try {
    const pageResult = await fetchPage(targetQuery, targetQuery.page!)
    resultMeta.value = pageResult
    pageCache.value = {
      [targetQuery.page!]: pageResult.content,
    }
    await nextTick()
    void contentRef.value?.$el?.scrollToTop?.(0)
    pageAtTop.value = true
  } catch (error) {
    resultMeta.value = null
    pageCache.value = {}
    errorMessage.value = sanitizeError(error, '搜索失败')
  } finally {
    initialLoading.value = false
  }
}

const retrySearch = () => {
  if (lastQuery.value) {
    void resetWithPage(lastQuery.value)
  }
}

const openSearch = () => {
  categoryOverlayVisible.value = true
}

const closeSearchOverlay = () => {
  categoryOverlayVisible.value = false
}

const submitOverlayCategory = (query: SearchQuery) => {
  closeSearchOverlay()
  void resetWithPage(query)
}

const scrollToTop = () => {
  void contentRef.value?.$el?.scrollToTop?.(300)
}

const appendPage = async (page: number) => {
  if (!lastQuery.value || loadingNext.value || initialLoading.value || !canLoadNext.value) {
    return
  }

  loadingNext.value = true
  try {
    errorMessage.value = ''
    const pageResult = await fetchPage(lastQuery.value, page)
    resultMeta.value = pageResult
    pageCache.value = {
      ...pageCache.value,
      [page]: pageResult.content,
    }
    await maybeLoadNextAfterRender()
  } catch (error) {
    errorMessage.value = sanitizeError(error, '加载下一页失败')
  } finally {
    loadingNext.value = false
  }
}

const prependPage = async (page: number) => {
  if (!lastQuery.value || loadingPrevious.value || initialLoading.value || !canLoadPrevious.value) {
    return
  }

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

  if (!contentScrollElement || !resultRoot) {
    return
  }

  loadingPrevious.value = true
  try {
    errorMessage.value = ''
    const pageResult = await fetchPage(lastQuery.value, page)
    resultMeta.value = pageResult
    pageCache.value = {
      [page]: pageResult.content,
      ...pageCache.value,
    }
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

const {isLoggedIn} = useAuth()

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
  cardMenu.value = {item, x: rect.left, y: rect.bottom + 4}
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
  if (cardMenuRef.value?.contains(e.target as Node)) return
  closeCardMenu()
}

function handleCardDetail(item: SearchResultItem) {
  closeCardMenu()
  void router.push({
    path: `/album/${item.id}`,
    query: {title: item.title, coverUrl: item.coverUrl, authors: item.authors.join(',')},
  })
}

async function handleCardRead(item: SearchResultItem) {
  closeCardMenu()
  try {
    const photo = await JmcomicService.getPhoto(item.id)
    await router.push({
      path: `/album/${item.id}/read/${photo.id}`,
      query: {title: item.title, total: String(photo.images.length)},
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
const pickerOnlineFolders = ref<FolderEntry[]>([])
const pickerOfflineFolders = ref<FolderEntry[]>([])
const pickerOnlineFolderCounts = ref<Record<string, number>>({})

async function loadOnlineFolderData() {
  if (!isLoggedIn.value) {
    pickerOnlineFolders.value = []
    return
  }
  try {
    const result: FavoriteResult = await JmcomicService.favorites({folderId: '0', page: 1})
    if (result.folderList) {
      const entries: FolderEntry[] = []
      const countPromises: Promise<void>[] = []
      const counts: Record<string, number> = {}
      for (const [id, name] of Object.entries(result.folderList)) {
        entries.push({id, name, count: 0})
        countPromises.push(
          JmcomicService.favorites({folderId: id, page: 1})
            .then((r) => {
              counts[id] = r.totalItems
            })
            .catch(() => {
              counts[id] = 0
            }),
        )
      }
      pickerOnlineFolders.value = entries
      await Promise.all(countPromises)
      pickerOnlineFolderCounts.value = counts
    }
  } catch {
    pickerOnlineFolders.value = []
  }
}

async function openFolderPicker() {
  await OfflineFavoriteService.ensureInit()
  pickerOfflineFolders.value = OfflineFavoriteService.getFolders()
  void loadOnlineFolderData()
  showFolderPicker.value = true
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
      if (album.isFavorite) {
        showToast('该本子已在收藏夹中', 'medium')
        return
      }
      await JmcomicService.toggleAlbumFavorite(item.id, payload.folderId)
      showToast('已收藏到在线收藏夹', 'success')
    } else {
      await OfflineFavoriteService.addItem(payload.folderId, item)
      showToast('已收藏到离线收藏夹', 'success')
    }
  } catch (e: any) {
    showToast(sanitizeError(e, '收藏失败'), 'danger')
  }
}

async function onPickerAddFolder() {
  const alert = await alertController.create({
    header: '新建收藏夹',
    inputs: [{name: 'name', type: 'text', placeholder: '收藏夹名称'}],
    buttons: [
      {text: '取消', role: 'cancel'},
      {
        text: '确定',
        handler: async (data) => {
          const name = data?.name?.trim()
          if (!name) return

          if (isLoggedIn.value) {
            try {
              const r = await JmcomicService.manageFavoriteFolder('add', '0', name, '')
              if (r.status === 'ok') {
                await showToast('收藏夹已创建', 'success')
                await loadOnlineFolderData()
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

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', handleCardMenuClickOutside)
  document.removeEventListener('touchstart', handleCardMenuClickOutside)
})

const clearResult = () => {
  resultMeta.value = null
  pageCache.value = {}
  errorMessage.value = ''
  lastQuery.value = null
}

const jumpToPage = async () => {
  if (!resultMeta.value || !lastQuery.value) {
    return
  }

  const alert = await alertController.create({
    header: '跳转页码',
    message: `请输入 1 - ${resultMeta.value.totalPages} 的页码`,
    inputs: [
      {
        name: 'page',
        type: 'number',
        min: 1,
        max: resultMeta.value.totalPages,
        value: String(lastQuery.value.page ?? 1),
        placeholder: '页码',
      },
    ],
    buttons: [
      {text: '取消', role: 'cancel'},
      {
        text: '跳转',
        handler: (data: { page?: string }) => {
          const page = Number(data.page)
          if (!Number.isInteger(page) || page < 1 || page > resultMeta.value!.totalPages) {
            return false
          }
          void resetWithPage({...lastQuery.value!, page})
          return true
        },
      },
    ],
  })

  await alert.present()
}

const savedScrollTop = ref(0)

onDeactivated(() => {
  savedScrollTop.value = scrollElementRef.value?.scrollTop ?? 0
})

onActivated(async () => {
  await nextTick()
  const scrollEl = scrollElementRef.value ?? (await resolveScrollElement())
  if (scrollEl && savedScrollTop.value > 0) {
    scrollEl.scrollTop = savedScrollTop.value
  }
})

onMounted(() => {
  void resolveScrollElement()
})
</script>

<style scoped>
.toolbar-start {
  padding: 0 0 8px 14px;
}

:deep(ion-header),
:deep(ion-toolbar) {
  overflow: visible;
}

:deep(ion-toolbar) {
  --min-height: auto;
}

.category-page-top {
  padding: calc(var(--ion-safe-area-top) + 2px) 14px 0;
}

.category-overlay {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: calc(10px + var(--ion-safe-area-top)) 14px 14px;
  background: rgb(16 12 10 / 0.16);
}

.category-overlay-panel {
  width: min(100%, 920px);
}

.category-page-toolbar {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  align-items: start;
  gap: 5px;
}

.category-page-toolbar.pinned {
  position: sticky;
  top: 0;
  z-index: 8;
  padding-bottom: 10px;
  background: linear-gradient(180deg, #fff 0%, #fff 78%, rgb(255 255 255 / 0) 100%);
}

.toolbar-category {
  min-width: 0;
}

.category-overlay-enter-active,
.category-overlay-leave-active {
  transition: opacity 0.18s ease;
}

.category-overlay-enter-active .category-overlay-panel,
.category-overlay-leave-active .category-overlay-panel {
  transition: transform 0.22s ease,
  opacity 0.22s ease;
}

.category-overlay-enter-from,
.category-overlay-leave-to {
  opacity: 0;
}

.category-overlay-enter-from .category-overlay-panel,
.category-overlay-leave-to .category-overlay-panel {
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

/* ---- 上下文菜单 ---- */

.card-context-menu {
  display: flex;
  flex-direction: column;
  min-width: 140px;
  padding: 6px;
  border-radius: 14px;
  background: #fffaf6;
  box-shadow: 0 12px 36px rgb(76 42 24 / 0.22);
  z-index: 50;
  overflow: hidden;
}

.card-menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 42px;
  padding: 10px 14px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: #3a261d;
  font: inherit;
  font-size: 14px;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.12s ease;
}

.card-menu-item:active {
  background: #fff0e7;
}

.card-menu-icon {
  flex-shrink: 0;
  font-size: 17px;
  color: #8a6048;
}
</style>
