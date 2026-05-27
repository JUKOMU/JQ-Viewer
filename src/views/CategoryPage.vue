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
import {computed, nextTick, onActivated, onDeactivated, onMounted, ref} from 'vue'
import {useRouter} from 'vue-router'
import {alertController, IonContent, IonPage} from '@ionic/vue'
import type {ScrollCustomEvent} from '@ionic/core'
import CategorySearchToolbar from '@/components/search/CategorySearchToolbar.vue'
import MenuToggleButton from '@/components/common/MenuToggleButton.vue'
import QuickActionFab from '@/components/common/QuickActionFab.vue'
import type {
  SearchResultContainerExposed,
  SearchResultDisplayItem,
} from '@/components/search/SearchResultContainer.vue'
import SearchResultContainer from '@/components/search/SearchResultContainer.vue'
import {JmcomicService, sanitizeError} from '@/services/JmcomicService'
import type {SearchQuery, SearchResult, SearchResultItem} from '@/services/JmcomicTypes'

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
</style>
