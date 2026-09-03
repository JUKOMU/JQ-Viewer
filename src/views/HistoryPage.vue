<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <div class="toolbar-start">
          <MenuToggleButton />
        </div>
        <div class="tab-bar">
          <button
            type="button"
            class="tab-btn"
            :class="{ active: activeTab === 'browse' }"
            @click="switchTab('browse')"
          >
            浏览历史
          </button>
          <button
            type="button"
            class="tab-btn"
            :class="{ active: activeTab === 'parse' }"
            @click="switchTab('parse')"
          >
            解析历史
          </button>
        </div>
      </IonToolbar>
    </IonHeader>
    <IonContent ref="contentRef" :scroll-events="true" @ion-scroll="onScroll">
      <div class="page-shell">
        <div v-show="activeTab === 'browse'" class="tab-content">
          <div v-if="!browseOverviewLoaded && browseOverviewError" class="history-error">
            <IonIcon :icon="timeOutline" class="empty-icon" />
            <p>浏览历史加载失败</p>
            <button type="button" class="retry-btn" @click="retryBrowseOverview">重试</button>
          </div>
          <div v-else-if="!browseOverviewLoaded" class="empty-state">
            <IonSpinner name="dots" />
          </div>
          <div v-else-if="browseGroups.length === 0" class="empty-state">
            <IonIcon :icon="timeOutline" class="empty-icon" />
            <p>暂无浏览记录</p>
            <p class="empty-hint">开始浏览本子，记录将自动出现在这里</p>
          </div>
          <template v-else>
            <div class="section-header">
              <span class="section-title">共 {{ browseTotalCount }} 条记录</span>
              <button type="button" class="clear-btn" @click="confirmClearBrowse">清空</button>
            </div>
            <div class="card-list">
              <section v-for="group in browseGroups" :key="group.key" class="date-group">
                <h2 class="date-group-heading">
                  <button
                    :id="browseGroupToggleId(group.key)"
                    type="button"
                    class="date-group-toggle"
                    :aria-expanded="!isBrowseGroupCollapsed(group.key)"
                    :aria-controls="browseGroupContentId(group.key)"
                    :aria-label="`${isBrowseGroupCollapsed(group.key) ? '展开' : '收起'}${group.label}`"
                    @click="toggleBrowseGroup(group.key)"
                  >
                    <span>
                      {{ group.label }}
                      <span class="date-group-count">({{ group.totalCount }})</span>
                    </span>
                    <IonIcon
                      class="date-group-toggle-icon"
                      :class="{ collapsed: isBrowseGroupCollapsed(group.key) }"
                      :icon="chevronDownOutline"
                      aria-hidden="true"
                    />
                  </button>
                </h2>
                <div
                  v-show="!isBrowseGroupCollapsed(group.key)"
                  :id="browseGroupContentId(group.key)"
                  class="date-group-content"
                  role="region"
                  :aria-labelledby="browseGroupToggleId(group.key)"
                >
                  <div class="date-group-content-inner">
                    <div class="date-group-cards">
                      <div
                        v-for="item in group.items"
                        :key="item.id"
                        class="browse-card"
                        @click="openAlbum(item)"
                      >
                        <div class="card-cover-wrap">
                          <img :src="item.coverUrl" class="card-cover" alt="" loading="lazy" />
                        </div>
                        <div class="card-body">
                          <h3 class="card-title">{{ item.albumTitle }}</h3>
                          <div class="card-id">ID: {{ item.albumId }}</div>
                          <div class="card-meta">作者：{{ item.authors }}</div>
                          <div class="card-meta">{{ formatRelativeTime(item.timestamp) }}</div>
                          <div v-if="item.chapterTitle" class="card-chapter">
                            <IonIcon :icon="bookOutline" class="chapter-icon" />
                            <span>{{ item.chapterTitle }}</span>
                          </div>
                        </div>
                        <button
                          type="button"
                          class="card-more-btn"
                          :class="{ active: contextMenu?.item.id === item.id }"
                          aria-label="更多操作"
                          @click.stop="openContextMenu(item, $event)"
                        >
                          <IonIcon :icon="ellipsisVertical" />
                        </button>
                      </div>
                    </div>
                    <div
                      :ref="(element) => setBrowseGroupSentinel(group.key, element)"
                      class="browse-group-sentinel"
                      aria-hidden="true"
                    ></div>
                    <div v-if="group.loading" class="browse-group-loader">
                      <IonSpinner name="dots" />
                    </div>
                    <button
                      v-else-if="group.error"
                      type="button"
                      class="browse-group-retry"
                      @click="retryBrowseGroup(group.key)"
                    >
                      加载失败，点击重试
                    </button>
                  </div>
                </div>
              </section>
            </div>
          </template>
        </div>

        <div v-show="activeTab === 'parse'" class="tab-content">
          <div v-if="parseItems.length === 0" class="empty-state">
            <IonIcon :icon="documentTextOutline" class="empty-icon" />
            <p>暂无解析记录</p>
            <p class="empty-hint">解析搜索关键词后，记录会显示在这里</p>
          </div>
          <template v-else>
            <div class="section-header">
              <span class="section-title">共 {{ parseTotalCount }} 条记录</span>
              <button type="button" class="clear-btn" @click="confirmClearParse">清空</button>
            </div>
            <TransitionGroup name="parse-history-list" tag="div" class="card-list">
              <div
                v-for="item in parseItems"
                :key="item.id"
                class="parse-card"
                @click="openParseItem(item)"
              >
                <div class="parse-icon-wrap">
                  <IonIcon :icon="documentTextOutline" />
                </div>
                <div class="parse-body">
                  <div class="parse-text">{{ item.text }}</div>
                  <div class="parse-meta">
                    <span
                      class="parse-mode-badge"
                      :class="item.mode === 'batch-mode' ? 'mode-batch' : 'mode-single'"
                    >
                      {{ item.mode === 'batch-mode' ? '批量解析' : '单个解析' }}
                    </span>
                    <span class="parse-time">{{ formatRelativeTime(item.timestamp) }}</span>
                  </div>
                </div>
                <button
                  type="button"
                  class="card-more-btn"
                  :class="{ active: contextMenu?.item.id === item.id }"
                  aria-label="更多操作"
                  @click.stop="openContextMenu(item, $event)"
                >
                  <IonIcon :icon="ellipsisVertical" />
                </button>
              </div>
            </TransitionGroup>
          </template>
        </div>

        <div v-if="activeTab === 'parse' && loadingMoreByTab.parse" class="history-list-loader">
          <IonSpinner name="dots" />
        </div>
      </div>
    </IonContent>
    <CardContextMenu
      :visible="Boolean(contextMenu)"
      :anchor="contextMenu?.anchor ?? null"
      :actions="contextMenuActions"
      @close="closeContextMenu"
      @select="handleContextMenuAction"
    />
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({ name: 'HistoryPage' })

import { computed, nextTick, onActivated, onDeactivated, onMounted, reactive, ref } from 'vue'
import type { ComponentPublicInstance } from 'vue'
import { useRouter } from 'vue-router'
import { IonContent, IonIcon, IonPage, IonSpinner } from '@ionic/vue'
import { createAppAlert } from '@/services/AppAlertService'
import {
  bookOutline,
  chevronDownOutline,
  copyOutline,
  documentTextOutline,
  ellipsisVertical,
  informationCircleOutline,
  timeOutline,
  trashOutline,
} from 'ionicons/icons'
import { HistoryService } from '@/services/HistoryService'
import type {
  BrowseHistoryItem,
  HistoryPageResult,
  ParseHistoryItem,
} from '@/services/JmcomicTypes'
import { BROWSE_GROUP_DEFINITIONS, createBrowseHistorySnapshot } from '@/utils/historyDateGroups'
import type { BrowseGroupKey, BrowseHistoryGroupSnapshot } from '@/utils/historyDateGroups'
import MenuToggleButton from '@/components/common/MenuToggleButton.vue'
import CardContextMenu from '@/components/common/CardContextMenu.vue'

const PAGE_SIZE = 50
const router = useRouter()
const contentRef = ref<InstanceType<typeof IonContent>>()
const scrollEl = ref<HTMLElement | null>(null)
type HistoryTab = 'browse' | 'parse'

const activeTab = ref<HistoryTab>('browse')
const parseItems = ref<ParseHistoryItem[]>([])
const browseTotalCount = ref(0)
const parseTotalCount = ref(0)
const parseHasMore = ref(true)
const loadingMoreByTab = reactive<Record<HistoryTab, boolean>>({ browse: false, parse: false })
const tabLoaded = reactive<Record<HistoryTab, boolean>>({ browse: false, parse: false })
const tabHasSnapshotMetadata = reactive<Record<HistoryTab, boolean>>({
  browse: true,
  parse: false,
})
const tabLoadPromises: Record<HistoryTab, Promise<void> | null> = {
  browse: null,
  parse: null,
}
const tabRequestGeneration = reactive<Record<HistoryTab, number>>({ browse: 0, parse: 0 })
const tabScrollPositions = reactive<Record<HistoryTab, number>>({ browse: 0, parse: 0 })
const isTabTransitioning = ref(false)
let tabTransitionId = 0
let wasDeactivated = false
const collapsedBrowseGroups = ref<Set<BrowseGroupKey>>(new Set())

interface BrowseGroupState {
  key: BrowseGroupKey
  label: string
  startInclusive: number | null
  endExclusive: number | null
  items: BrowseHistoryItem[]
  totalCount: number
  offset: number
  hasMore: boolean
  loaded: boolean
  loading: boolean
  error: boolean
  generation: number
}

function createBrowseGroupState(key: BrowseGroupKey, label: string): BrowseGroupState {
  return {
    key,
    label,
    startInclusive: null,
    endExclusive: null,
    items: [],
    totalCount: 0,
    offset: 0,
    hasMore: false,
    loaded: false,
    loading: false,
    error: false,
    generation: 0,
  }
}

const browseGroupStates = reactive(
  Object.fromEntries(
    BROWSE_GROUP_DEFINITIONS.map(({ key, label }) => [key, createBrowseGroupState(key, label)]),
  ) as Record<BrowseGroupKey, BrowseGroupState>,
)
const browseSnapshot = ref<BrowseHistoryGroupSnapshot | null>(null)
const browseOverviewLoaded = ref(false)
const browseOverviewLoading = ref(false)
const browseOverviewError = ref(false)
const browseGroupPromises = new Map<BrowseGroupKey, Promise<void>>()
const browseGroupSentinels = new Map<BrowseGroupKey, HTMLElement>()

const browseGroups = computed(() => {
  const snapshot = browseSnapshot.value
  if (!snapshot) return []
  return snapshot.ranges
    .map((range) => browseGroupStates[range.key])
    .filter((group) => group.totalCount > 0)
})

function browseGroupToggleId(key: BrowseGroupKey): string {
  return `history-group-toggle-${key}`
}

function browseGroupContentId(key: BrowseGroupKey): string {
  return `history-group-content-${key}`
}

function isBrowseGroupCollapsed(key: BrowseGroupKey): boolean {
  return collapsedBrowseGroups.value.has(key)
}

function isContextMenuInBrowseGroup(key: BrowseGroupKey): boolean {
  const menu = contextMenu.value
  if (!menu || menu.type !== 'browse') return false
  return browseGroupStates[key].items.some((item) => item.id === menu.item.id)
}

async function toggleBrowseGroup(key: BrowseGroupKey) {
  const next = new Set(collapsedBrowseGroups.value)
  if (next.has(key)) next.delete(key)
  else {
    if (isContextMenuInBrowseGroup(key)) closeContextMenu()
    next.add(key)
  }
  collapsedBrowseGroups.value = next
  if (!next.has(key)) {
    await nextTick()
    const group = browseGroupStates[key]
    if (group.totalCount > 0 && (!group.loaded || group.error)) {
      void loadBrowseGroup(key)
    }
    void checkBrowseGroupSentinels()
  }
}

type IonContentElement = HTMLElement & {
  getScrollElement?: () => Promise<HTMLElement | null>
}

async function resolveScrollElement(): Promise<HTMLElement | null> {
  if (scrollEl.value) return scrollEl.value
  const contentEl = contentRef.value?.$el as IonContentElement | undefined
  if (!contentEl?.getScrollElement) return null
  scrollEl.value = await contentEl.getScrollElement()
  return scrollEl.value
}

function setBrowseGroupSentinel(
  key: BrowseGroupKey,
  element: Element | ComponentPublicInstance | null,
) {
  if (element instanceof HTMLElement) browseGroupSentinels.set(key, element)
  else browseGroupSentinels.delete(key)
}

interface NormalizedHistoryPage<T> extends HistoryPageResult<T> {
  legacyArray: boolean
}

function normalizeHistoryPage<T>(
  result: HistoryPageResult<T> | T[] | null,
): NormalizedHistoryPage<T> | null {
  if (result === null) return null
  if (Array.isArray(result)) {
    return { items: result, totalCount: result.length, legacyArray: true }
  }
  const hasTotalCount = Number.isFinite(result.totalCount)
  return {
    items: result.items,
    totalCount: hasTotalCount ? Math.max(0, result.totalCount) : result.items.length,
    legacyArray: !hasTotalCount,
  }
}

function updateParseTotalCount(totalCount: number, hasMore = parseItems.value.length < totalCount) {
  parseTotalCount.value = Math.max(0, totalCount)
  parseHasMore.value = hasMore
}

function getBrowseGroupCount(
  groupCounts: Partial<Record<BrowseGroupKey, number>>,
  key: BrowseGroupKey,
): number {
  const count = groupCounts[key]
  return Number.isFinite(count) ? Math.max(0, count as number) : 0
}

function applyBrowseOverview(
  snapshot: BrowseHistoryGroupSnapshot,
  totalCount: number,
  groupCounts: Partial<Record<BrowseGroupKey, number>>,
) {
  browseSnapshot.value = snapshot
  browseTotalCount.value = Number.isFinite(totalCount) ? Math.max(0, totalCount) : 0

  for (const range of snapshot.ranges) {
    const group = browseGroupStates[range.key]
    group.label = BROWSE_GROUP_DEFINITIONS.find((definition) => definition.key === range.key)!.label
    group.startInclusive = range.startInclusive
    group.endExclusive = range.endExclusive
    group.totalCount = getBrowseGroupCount(groupCounts, range.key)
    if (group.totalCount === 0) {
      group.items.splice(0)
      group.offset = 0
      group.hasMore = false
      group.loaded = false
      group.loading = false
      group.error = false
    } else {
      group.hasMore = group.offset < group.totalCount
    }
  }
}

function isBrowseGroupLoadCurrent(
  key: BrowseGroupKey,
  groupGeneration: number,
  browseGeneration: number,
): boolean {
  return (
    browseGeneration === tabRequestGeneration.browse &&
    groupGeneration === browseGroupStates[key].generation
  )
}

function loadBrowseGroup(
  key: BrowseGroupKey,
  options: {
    reset?: boolean
    targetCount?: number
    browseGeneration?: number
  } = {},
): Promise<void> {
  const existingPromise = browseGroupPromises.get(key)
  if (existingPromise) return existingPromise

  const group = browseGroupStates[key]
  if (group.totalCount <= 0) return Promise.resolve()
  if (!options.reset && group.loaded && !group.hasMore && !group.error) {
    return Promise.resolve()
  }

  if (options.reset) {
    group.generation += 1
    group.items.splice(0)
    group.offset = 0
    group.loaded = false
    group.hasMore = group.totalCount > 0
    group.error = false
  }

  const groupGeneration = group.generation
  const browseGeneration = options.browseGeneration ?? tabRequestGeneration.browse
  const targetCount = Math.max(group.items.length + PAGE_SIZE, options.targetCount ?? 0)
  const task = (async () => {
    group.loading = true
    group.error = false
    try {
      while (
        isBrowseGroupLoadCurrent(key, groupGeneration, browseGeneration) &&
        group.hasMore &&
        group.items.length < targetCount
      ) {
        const page = normalizeHistoryPage(
          await HistoryService.getBrowseHistory(PAGE_SIZE, group.offset, {
            startInclusive: group.startInclusive,
            endExclusive: group.endExclusive,
          }),
        )
        if (!isBrowseGroupLoadCurrent(key, groupGeneration, browseGeneration)) return
        if (!page) {
          group.error = true
          return
        }

        if (!page.legacyArray) group.totalCount = Math.max(0, page.totalCount)
        const knownIds = new Set(group.items.map((item) => item.id))
        for (const item of page.items) {
          if (!knownIds.has(item.id)) {
            group.items.push(item)
            knownIds.add(item.id)
          }
        }
        group.offset += page.items.length
        group.loaded = true
        group.hasMore = page.legacyArray
          ? page.items.length === PAGE_SIZE
          : page.items.length > 0 && group.offset < group.totalCount
        if (page.items.length === 0) group.hasMore = false
      }
    } catch {
      if (isBrowseGroupLoadCurrent(key, groupGeneration, browseGeneration)) group.error = true
    } finally {
      if (isBrowseGroupLoadCurrent(key, groupGeneration, browseGeneration)) {
        group.loading = false
      }
    }

    if (isBrowseGroupLoadCurrent(key, groupGeneration, browseGeneration)) {
      await nextTick()
      const scrollElement = await resolveScrollElement()
      if (
        activeTab.value === 'browse' &&
        scrollElement &&
        (scrollElement.clientHeight > 0 || scrollElement.scrollHeight > 0)
      ) {
        void checkBrowseGroupSentinels()
      }
    }
  })()
  browseGroupPromises.set(key, task)
  void task.finally(() => {
    if (browseGroupPromises.get(key) === task) browseGroupPromises.delete(key)
  })
  return task
}

function reloadBrowseGroup(
  key: BrowseGroupKey,
  targetCount: number,
  browseGeneration: number,
): Promise<void> {
  return loadBrowseGroup(key, { reset: true, targetCount, browseGeneration })
}

function isSentinelNearViewport(sentinel: HTMLElement, scrollElement: HTMLElement): boolean {
  const containerRect = scrollElement.getBoundingClientRect()
  const sentinelRect = sentinel.getBoundingClientRect()
  const top = sentinelRect.top - containerRect.top
  const bottom = sentinelRect.bottom - containerRect.top
  return top < scrollElement.clientHeight + 200 && bottom > -200
}

async function checkBrowseGroupSentinels() {
  if (activeTab.value !== 'browse' || isTabTransitioning.value || !tabLoaded.browse) return
  const scrollElement = await resolveScrollElement()
  if (!scrollElement) return

  const groups = browseGroups.value.slice(0, BROWSE_GROUP_DEFINITIONS.length)
  const hasLayout = scrollElement.clientHeight > 0 || scrollElement.scrollHeight > 0
  if (!hasLayout) {
    const firstGroup = groups.find(
      (group) =>
        !isBrowseGroupCollapsed(group.key) &&
        !group.loaded &&
        !group.loading &&
        !group.error &&
        group.hasMore,
    )
    if (firstGroup) void loadBrowseGroup(firstGroup.key)
    return
  }

  for (const group of groups) {
    if (isBrowseGroupCollapsed(group.key) || group.loading || group.error || !group.hasMore) {
      continue
    }
    const sentinel = browseGroupSentinels.get(group.key)
    if (sentinel && isSentinelNearViewport(sentinel, scrollElement)) {
      void loadBrowseGroup(group.key)
    }
  }
}

async function loadParse(generation = tabRequestGeneration.parse): Promise<boolean> {
  const page = normalizeHistoryPage(await HistoryService.getParseHistory(PAGE_SIZE, 0))
  if (!page || generation !== tabRequestGeneration.parse) return false
  parseItems.value = page.items
  tabHasSnapshotMetadata.parse = !page.legacyArray
  updateParseTotalCount(
    page.totalCount,
    page.legacyArray ? page.items.length === PAGE_SIZE : page.items.length < page.totalCount,
  )
  return true
}

async function loadMoreParse() {
  if (!isTabReadyForPagination('parse') || loadingMoreByTab.parse || !parseHasMore.value) return
  loadingMoreByTab.parse = true
  const generation = tabRequestGeneration.parse
  try {
    const page = normalizeHistoryPage(
      await HistoryService.getParseHistory(PAGE_SIZE, parseItems.value.length),
    )
    if (!page || generation !== tabRequestGeneration.parse) return
    if (page.items.length > 0) parseItems.value.push(...page.items)
    updateParseTotalCount(
      page.totalCount,
      page.legacyArray
        ? page.items.length === PAGE_SIZE
        : parseItems.value.length < page.totalCount,
    )
  } finally {
    if (generation === tabRequestGeneration.parse) loadingMoreByTab.parse = false
  }
}

const onScroll = (event: CustomEvent<{ scrollTop?: number }>) => {
  const tab = activeTab.value
  if (isTabTransitioning.value) return

  const eventScrollTop = event.detail?.scrollTop
  if (typeof eventScrollTop === 'number') tabScrollPositions[tab] = Math.max(0, eventScrollTop)

  if (!isTabReadyForPagination(tab)) return

  if (tab === 'browse') {
    void checkBrowseGroupSentinels()
    return
  }

  const el = scrollEl.value
  if (!el) return
  const threshold = 200
  if (el.scrollHeight - el.scrollTop - el.clientHeight < threshold) {
    void loadMoreParse().catch(() => undefined)
  }
}

function isTabReadyForPagination(tab: HistoryTab): boolean {
  return tabLoaded[tab] && tabLoadPromises[tab] === null
}

function saveActiveTabScrollPosition() {
  const el = scrollEl.value
  if (el) tabScrollPositions[activeTab.value] = Math.max(0, el.scrollTop)
}

async function restoreTabScrollPosition(tab: HistoryTab) {
  await nextTick()
  if (activeTab.value !== tab) return
  const el = await resolveScrollElement()
  if (!el || activeTab.value !== tab) return
  el.scrollTop = Math.max(0, tabScrollPositions[tab])
}

function beginTabTransition(): number {
  const id = ++tabTransitionId
  isTabTransitioning.value = true
  return id
}

function endTabTransition(id: number) {
  if (id === tabTransitionId) isTabTransitioning.value = false
}

async function loadBrowseOverview(
  generation: number,
  reloadGroupCounts?: ReadonlyMap<BrowseGroupKey, number>,
): Promise<boolean> {
  const nowMs = Date.now()
  const snapshot = createBrowseHistorySnapshot(nowMs)
  browseOverviewLoading.value = true
  try {
    const overview = await HistoryService.getBrowseHistoryOverview(snapshot.ranges)
    if (!overview) {
      if (generation === tabRequestGeneration.browse && !browseOverviewLoaded.value) {
        browseOverviewError.value = true
      }
      return false
    }
    if (generation !== tabRequestGeneration.browse) return false

    applyBrowseOverview(snapshot, overview.totalCount, overview.groupCounts ?? {})
    browseOverviewLoaded.value = true
    browseOverviewError.value = false

    if (reloadGroupCounts) {
      const reloads: Promise<void>[] = []
      for (const [key, targetCount] of reloadGroupCounts) {
        const group = browseGroupStates[key]
        if (targetCount > 0 && group.totalCount > 0) {
          reloads.push(reloadBrowseGroup(key, targetCount, generation))
        }
      }
      await Promise.all(reloads)
    }

    await nextTick()
    if (activeTab.value === 'browse') void checkBrowseGroupSentinels()
    return true
  } catch {
    if (generation === tabRequestGeneration.browse && !browseOverviewLoaded.value) {
      browseOverviewError.value = true
    }
    return false
  } finally {
    if (generation === tabRequestGeneration.browse) browseOverviewLoading.value = false
  }
}

function retryBrowseOverview() {
  browseOverviewError.value = false
  invalidateTab('browse')
  void ensureTabLoaded('browse')
}

function retryBrowseGroup(key: BrowseGroupKey) {
  void loadBrowseGroup(key)
}

interface EnsureTabOptions {
  browseGroupCounts?: ReadonlyMap<BrowseGroupKey, number>
}

function ensureTabLoaded(tab: HistoryTab, options: EnsureTabOptions = {}): Promise<void> {
  if (tabLoaded[tab]) return Promise.resolve()
  if (tabLoadPromises[tab]) return tabLoadPromises[tab]!

  const generation = tabRequestGeneration[tab]
  const promise = (async () => {
    try {
      const loaded =
        tab === 'browse'
          ? await loadBrowseOverview(generation, options.browseGroupCounts)
          : await loadParse(generation)
      if (loaded && generation === tabRequestGeneration[tab]) {
        tabLoaded[tab] = true
        if (tab === 'browse') {
          await nextTick()
          void checkBrowseGroupSentinels()
        }
      }
    } finally {
      if (generation === tabRequestGeneration[tab]) tabLoadPromises[tab] = null
    }
  })()
  tabLoadPromises[tab] = promise
  return promise
}

async function refreshTabOnActivation(tab: HistoryTab) {
  if (tab === 'browse') {
    if (!tabLoaded.browse) {
      if (activeTab.value === 'browse') await ensureTabLoaded('browse')
      return
    }

    const loadedGroupCounts = new Map<BrowseGroupKey, number>()
    for (const definition of BROWSE_GROUP_DEFINITIONS) {
      const group = browseGroupStates[definition.key]
      if (group.loaded && group.items.length > 0) {
        loadedGroupCounts.set(group.key, group.items.length)
      }
    }
    invalidateTab('browse')
    await ensureTabLoaded('browse', { browseGroupCounts: loadedGroupCounts })
    return
  }

  if (!tabLoaded[tab]) {
    if (activeTab.value === tab) await ensureTabLoaded(tab)
    return
  }
  if (!tabHasSnapshotMetadata[tab]) return
  invalidateTab(tab)
  await ensureTabLoaded(tab)
}

function invalidateTab(tab: HistoryTab) {
  tabRequestGeneration[tab] += 1
  tabLoaded[tab] = false
  tabLoadPromises[tab] = null
  loadingMoreByTab[tab] = false
  if (tab === 'browse') {
    browseGroupPromises.clear()
    for (const definition of BROWSE_GROUP_DEFINITIONS) {
      const group = browseGroupStates[definition.key]
      group.generation += 1
      group.loading = false
    }
  }
}

function browseGroupKeyForItem(
  itemId: number,
  preferredKey?: BrowseGroupKey,
): BrowseGroupKey | null {
  if (preferredKey && browseGroupStates[preferredKey].items.some((item) => item.id === itemId)) {
    return preferredKey
  }
  return (
    BROWSE_GROUP_DEFINITIONS.find((definition) =>
      browseGroupStates[definition.key].items.some((item) => item.id === itemId),
    )?.key ?? null
  )
}

async function reloadTabFromFirstPage(
  tab: HistoryTab,
  removedId: number,
  preferredBrowseGroupKey?: BrowseGroupKey,
) {
  if (tab === 'browse') {
    const groupKey = browseGroupKeyForItem(removedId, preferredBrowseGroupKey)
    const group = groupKey ? browseGroupStates[groupKey] : null
    const previousLength = group?.items.length ?? 0
    const removed = group ? group.items.some((item) => item.id === removedId) : false
    if (group && removed) {
      group.items = group.items.filter((item) => item.id !== removedId)
      group.offset = Math.max(0, group.offset - 1)
      group.totalCount = Math.max(0, group.totalCount - 1)
      group.hasMore = group.offset < group.totalCount
      browseTotalCount.value = Math.max(0, browseTotalCount.value - 1)
    }
    const reloadGroupCounts = new Map<BrowseGroupKey, number>()
    if (group?.loaded && previousLength > 0 && groupKey) {
      reloadGroupCounts.set(groupKey, previousLength)
    }
    invalidateTab('browse')
    await ensureTabLoaded('browse', { browseGroupCounts: reloadGroupCounts })
    return
  }

  if (!tabHasSnapshotMetadata[tab]) {
    const previousLength = parseItems.value.length
    parseItems.value = parseItems.value.filter((item) => item.id !== removedId)
    if (parseItems.value.length !== previousLength) {
      updateParseTotalCount(Math.max(0, parseTotalCount.value - 1))
    }
    return
  }

  const previousLength = parseItems.value.length
  parseItems.value = parseItems.value.filter((item) => item.id !== removedId)
  if (parseItems.value.length === previousLength) return
  updateParseTotalCount(Math.max(0, parseTotalCount.value - 1))
  if (parseHasMore.value) await loadMoreParse()
}

async function switchTab(tab: HistoryTab) {
  if (activeTab.value === tab) return
  const wasTransitioning = isTabTransitioning.value
  const transitionId = beginTabTransition()
  if (!wasTransitioning) saveActiveTabScrollPosition()
  activeTab.value = tab
  try {
    await ensureTabLoaded(tab)
    await restoreTabScrollPosition(tab)
    if (tab === 'browse') void checkBrowseGroupSentinels()
  } finally {
    endTabTransition(transitionId)
  }
}

onMounted(async () => {
  await resolveScrollElement()
  await ensureTabLoaded('browse')
})

onActivated(() => {
  const shouldRefreshTotals = wasDeactivated
  wasDeactivated = false
  const transitionId = beginTabTransition()
  void (async () => {
    try {
      if (shouldRefreshTotals) {
        await Promise.all([refreshTabOnActivation('browse'), refreshTabOnActivation('parse')])
      }
      await restoreTabScrollPosition(activeTab.value)
    } finally {
      endTabTransition(transitionId)
    }
  })()
})

onDeactivated(() => {
  wasDeactivated = true
  saveActiveTabScrollPosition()
})

function openAlbum(item: BrowseHistoryItem) {
  const authorsParam = item.authors.replace(/\s*\/\s*/g, ',')
  const chapterId = item.chapterId.trim()
  void router.push({
    path: `/album/${item.albumId}`,
    query: {
      title: item.albumTitle,
      coverUrl: item.coverUrl,
      authors: authorsParam,
      ...(chapterId ? { chapterId } : {}),
    },
  })
}

function openParseItem(item: ParseHistoryItem) {
  if (item.mode === 'batch-mode') {
    const key = `bp-${Date.now()}`
    sessionStorage.setItem(`batch-parse-text:${key}`, item.text)
    void router.push({ path: '/batch-parse', query: { key } })
  } else {
    const digits = item.text.replace(/\D/g, '')
    void router.push({ path: '/search', query: { keyword: digits } })
  }
}

// ---- 上下文菜单 ----

interface ContextMenuState {
  type: 'browse' | 'parse'
  item: BrowseHistoryItem | ParseHistoryItem
  anchor: HTMLElement
  browseGroupKey?: BrowseGroupKey
}

const contextMenu = ref<ContextMenuState | null>(null)
const contextMenuActions = computed(() => {
  if (contextMenu.value?.type === 'browse') {
    return [
      { id: 'detail', label: '进入详情页', icon: informationCircleOutline },
      { id: 'delete', label: '删除此记录', icon: trashOutline, danger: true },
    ]
  }
  return [
    { id: 'copy', label: '复制文本', icon: copyOutline },
    { id: 'delete', label: '删除此记录', icon: trashOutline, danger: true },
  ]
})

function openContextMenu(item: BrowseHistoryItem | ParseHistoryItem, event: MouseEvent) {
  const anchor = event.currentTarget as HTMLElement
  const type = 'albumId' in item ? 'browse' : 'parse'
  if (contextMenu.value?.anchor === anchor) {
    closeContextMenu()
    return
  }
  const browseGroupKey =
    type === 'browse' ? (browseGroupKeyForItem(item.id) ?? undefined) : undefined
  contextMenu.value = { type, item, anchor, browseGroupKey }
}

function closeContextMenu() {
  contextMenu.value = null
}

function handleContextMenuAction(action: string) {
  if (action === 'detail') handleMenuDetail()
  else if (action === 'copy') void handleMenuCopy()
  else if (action === 'delete') void handleMenuDelete()
}

function handleMenuDetail() {
  const item = contextMenu.value?.item as BrowseHistoryItem
  closeContextMenu()
  if (item) openAlbum(item)
}

async function handleMenuCopy() {
  const item = contextMenu.value?.item as ParseHistoryItem
  const text = item?.text
  closeContextMenu()
  if (text) {
    try {
      await navigator.clipboard.writeText(text)
    } catch {
      /* ignore */
    }
  }
}

async function handleMenuDelete() {
  const m = contextMenu.value
  if (!m) return
  closeContextMenu()

  const isBrowse = m.type === 'browse'
  const alert = await createAppAlert({
    header: '确认删除',
    message: isBrowse ? '确定要删除这条浏览记录吗？' : '确定要删除这条解析记录吗？',
    buttons: [
      { text: '取消', role: 'cancel' },
      {
        text: '删除',
        role: 'destructive',
        handler: async () => {
          if (isBrowse) {
            await HistoryService.deleteBrowseItem(m.item.id)
            await reloadTabFromFirstPage('browse', m.item.id, m.browseGroupKey)
          } else {
            await HistoryService.deleteParseItem(m.item.id)
            await reloadTabFromFirstPage('parse', m.item.id)
          }
        },
      },
    ],
  })
  await alert.present()
}

async function confirmClearBrowse() {
  const alert = await createAppAlert({
    header: '确认清空',
    message: '确定要清空所有浏览记录吗？此操作不可撤销。',
    buttons: [
      { text: '取消', role: 'cancel' },
      {
        text: '清空',
        role: 'destructive',
        handler: async () => {
          invalidateTab('browse')
          await HistoryService.clearBrowseHistory()
          closeContextMenu()
          browseOverviewLoaded.value = true
          browseOverviewLoading.value = false
          browseOverviewError.value = false
          browseSnapshot.value = null
          browseTotalCount.value = 0
          for (const definition of BROWSE_GROUP_DEFINITIONS) {
            const group = browseGroupStates[definition.key]
            group.items.splice(0)
            group.totalCount = 0
            group.offset = 0
            group.hasMore = false
            group.loaded = false
            group.loading = false
            group.error = false
          }
          tabLoaded.browse = false
          tabHasSnapshotMetadata.browse = true
          collapsedBrowseGroups.value = new Set()
        },
      },
    ],
  })
  await alert.present()
}

async function confirmClearParse() {
  const alert = await createAppAlert({
    header: '确认清空',
    message: '确定要清空所有解析记录吗？此操作不可撤销。',
    buttons: [
      { text: '取消', role: 'cancel' },
      {
        text: '清空',
        role: 'destructive',
        handler: async () => {
          invalidateTab('parse')
          await HistoryService.clearParseHistory()
          parseItems.value = []
          updateParseTotalCount(0)
          parseHasMore.value = true
        },
      },
    ],
  })
  await alert.present()
}

function formatRelativeTime(timestamp: number): string {
  const diff = Date.now() - timestamp
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}小时前`
  if (diff < 604_800_000) return `${Math.floor(diff / 86_400_000)}天前`
  const d = new Date(timestamp)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
</script>

<style scoped>
.toolbar-start {
  width: 100%;
  max-width: 720px;
  box-sizing: border-box;
  margin-inline: auto;
  padding: 0 0 8px 14px;
}

/* 页面容器 */
.page-shell {
  width: calc(100% - 28px);
  max-width: 720px;
  margin: 0 auto 86px;
}

/* Tab 栏 */
.tab-bar {
  display: flex;
  width: 100%;
  max-width: 720px;
  box-sizing: border-box;
  gap: 2px;
  margin-inline: auto;
  margin-bottom: 10px;
  padding: 4px 14px;
  border-radius: 18px;
  background: #fffbf8;
}

.tab-btn {
  flex: 1;
  height: 34px;
  border: 0;
  border-radius: 14px;
  background: transparent;
  color: #8a6048;
  font-size: 12px;
  font-weight: 600;
  transition:
    background-color 0.18s ease,
    color 0.18s ease;
}

.tab-btn.active {
  background: linear-gradient(145deg, #fa9c69, #f28752);
  color: #fff;
}

/* Empty state */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding-top: 36vh;
  color: #b89a84;
}

.empty-icon {
  font-size: 56px;
  margin-bottom: 12px;
}

.empty-state p {
  margin: 0;
  font-size: 14px;
}

.empty-hint {
  margin-top: 6px !important;
  font-size: 12px !important;
  color: #c4a494;
}

.history-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding-top: 30vh;
  color: #b85d52;
}

.history-error p {
  margin: 0 0 12px;
  font-size: 14px;
}

.retry-btn,
.browse-group-retry {
  border: 0;
  border-radius: 6px;
  background: #fff0e8;
  color: #c96d3a;
  cursor: pointer;
}

.retry-btn {
  padding: 7px 18px;
  font-size: 12px;
}

/* Section header */
.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 4px 10px;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: #8a6048;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.clear-btn {
  font-size: 12px;
  border: 0;
  background: transparent;
  color: #d9534f;
  cursor: pointer;
  padding: 2px 8px;
  border-radius: 4px;
}

.clear-btn:active {
  background: #ffeaea;
}

/* Card list */
.card-list {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.history-list-loader {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 52px;
  color: #fa9c69;
}

.date-group + .date-group {
  margin-top: 4px;
}

.date-group-heading {
  margin: 0;
}

.date-group-toggle {
  display: flex;
  width: 100%;
  min-height: 44px;
  align-items: center;
  gap: 8px;
  padding: 12px 4px 8px;
  border: 0;
  background: transparent;
  color: #8a6048;
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

.date-group-count {
  color: #b89078;
  font-size: 13px;
  font-weight: 500;
}

.date-group-toggle:focus-visible {
  outline: 2px solid #e8843c;
  outline-offset: 1px;
  border-radius: 6px;
}

.date-group-toggle-icon {
  flex-shrink: 0;
  margin-left: auto;
  font-size: 16px;
  transition: transform 0.24s ease;
}

.date-group-toggle-icon.collapsed {
  transform: rotate(-90deg);
}

.date-group-content {
  overflow: visible;
}

.date-group-content-inner {
  min-height: 0;
}

.date-group-cards {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.browse-group-sentinel {
  height: 1px;
  pointer-events: none;
}

.browse-group-loader,
.browse-group-retry {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  color: #fa9c69;
}

.browse-group-retry {
  width: 100%;
  padding: 10px;
  font-size: 12px;
}

/* Browse card */
.browse-card {
  position: relative;
  display: flex;
  align-items: stretch;
  gap: 0;
  height: 108px;
  padding: 0 10px 0 0;
  background: #fffaf6;
  border-radius: 12px;
  box-shadow: 5px 12px 28px rgb(76 42 24 / 0.2);
  cursor: pointer;
  transition: transform 0.16s ease;
}

.browse-card:active {
  transform: scale(0.98);
}

.card-cover-wrap {
  flex-shrink: 0;
  aspect-ratio: 3 / 4;
  height: 100%;
  overflow: hidden;
  border-radius: 6px;
  background: linear-gradient(145deg, #f3ded0, #ffece0);
}

.card-cover {
  width: 100%;
  height: 100%;
  object-fit: contain;
  display: block;
}

.card-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: start;
  gap: 4px;
  padding: 0 2px 0 10px;
  overflow: hidden;
}

.card-title {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: #30201a;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.4;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.card-id {
  color: #876653;
  font-size: 11px;
  line-height: 1.35;
}

.card-meta {
  color: #876653;
  font-size: 11px;
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-chapter {
  display: flex;
  align-items: center;
  gap: 3px;
  padding: 2px 6px;
  background: #fff3ea;
  border-radius: 4px;
  font-size: 10px;
  color: #c96d3a;
  align-self: flex-start;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chapter-icon {
  font-size: 11px;
  flex-shrink: 0;
}

/* Parse card */
.parse-card {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 12px;
  background: #fffaf6;
  border-radius: 12px;
  box-shadow: 5px 12px 28px rgb(76 42 24 / 0.2);
  cursor: pointer;
  transition: transform 0.16s ease;
  margin-bottom: 6px;
}

.parse-card:active {
  transform: scale(0.98);
}

.parse-icon-wrap {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: #fff3ea;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #c96d3a;
  font-size: 18px;
}

.parse-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.parse-text {
  font-size: 13px;
  color: #30201a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.parse-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}

.parse-mode-badge {
  font-size: 9px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 4px;
  line-height: 1.6;
}

.mode-single {
  background: #e8f0fe;
  color: #4a7fbd;
}

.mode-batch {
  background: #fef3e0;
  color: #c9822e;
}

.parse-time {
  font-size: 10px;
  color: #b8a090;
}

/* More button */
.card-more-btn {
  position: absolute;
  right: 2px;
  top: 50%;
  transform: translateY(-50%);
  width: 28px;
  height: 28px;
  border: 0;
  background: transparent;
  color: #8a6048;
  font-size: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  cursor: pointer;
  flex-shrink: 0;
}

.card-more-btn:active,
.card-more-btn.active {
  background: rgb(250 156 105 / 0.15);
  color: #c96d3a;
}

/* Parse history TransitionGroup */
.parse-history-list-enter-active,
.parse-history-list-leave-active {
  transition:
    opacity 0.28s ease,
    transform 0.28s ease;
}

.parse-history-list-enter-from {
  opacity: 0;
  transform: translateY(12px) scale(0.97);
}

.parse-history-list-leave-to {
  opacity: 0;
  transform: translateX(-20px);
}

.parse-history-list-move {
  transition: transform 0.28s ease;
}

@media (prefers-reduced-motion: reduce) {
  .date-group-toggle-icon,
  .parse-history-list-enter-active,
  .parse-history-list-leave-active,
  .parse-history-list-move {
    transition-duration: 0.01ms;
  }
}
</style>
