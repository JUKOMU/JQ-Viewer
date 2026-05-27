<template>
  <section
    ref="rootRef"
    class="result-shell"
    @touchstart="handleTouchStart"
    @touchmove="handleTouchMove"
    @touchend="handleTouchEnd"
    @touchcancel="handleTouchEnd"
  >
    <div class="result-toolbar">
      <div class="toolbar-main">
        <div class="result-summary">
          {{ summaryText }}
        </div>
        <div class="toolbar-actions">
          <div class="mode-switch" aria-label="显示方式">
            <button
              type="button"
              class="mode-btn"
              :class="{ active: !isGridMode }"
              @click="emit('mode-change', 'list')"
            >
              列表
            </button>
            <button
              type="button"
              class="mode-btn"
              :class="{ active: isGridMode }"
              @click="emit('mode-change', 'grid')"
            >
              网格
            </button>
          </div>
        </div>
      </div>
      <Transition name="grid-columns-fade">
        <div v-if="isGridMode" class="grid-columns-switch" aria-label="网格列数">
          <button
            v-for="count in gridColumnOptions"
            :key="count"
            type="button"
            class="grid-columns-btn"
            :class="{ active: gridColumns === count }"
            @click="setGridColumns(count)"
          >
            {{ count }}x
          </button>
        </div>
      </Transition>
    </div>

    <div
      v-if="showPullIndicator"
      class="pull-indicator-row"
      :class="{ ready: pullReady, loading: loadingPrevious, active: pullIndicatorActive }"
    >
      <div class="pull-indicator" :style="pullIndicatorStyle">
        <IonSpinner v-if="loadingPrevious" name="dots" />
        <div v-else class="pull-hint">
          <span class="pull-line" />
          <span class="pull-arrow">↓</span>
          <span class="pull-line" />
        </div>
      </div>
    </div>

    <Transition name="result-fade" mode="out-in">
      <div v-if="showInitialLoadingState" key="loading" class="state-card">正在加载...</div>
      <div v-else-if="showErrorState" key="error" class="state-card error">
        <div>{{ errorMessage }}</div>
        <button type="button" class="retry-btn" @click="emit('retry')">重试</button>
      </div>
      <div v-else-if="showEmptyState" key="empty" class="state-card">
        {{ emptyText }}
      </div>
      <div
        v-else-if="showResultContent"
        :key="`result-${mode}`"
        class="result-content"
        :style="contentOffsetStyle"
      >
        <div :class="resultLayoutClass" :style="resultLayoutStyle">
          <article
            v-for="entry in items"
            :key="getEntryKey(entry)"
            :data-entry-key="getEntryKey(entry)"
            :class="itemCardClass"
            @click="emit('item-click', entry.item)"
          >
            <div class="cover-wrap">
              <img
                class="cover-img"
                :src="entry.item.coverUrl"
                :alt="entry.item.title"
                loading="lazy"
              />
            </div>
            <slot
              name="item-actions"
              :item="entry.item"
              :page="entry.page"
              :index-in-page="entry.indexInPage"
            />
            <div class="item-info">
              <h3 class="item-title">{{ entry.item.title }}</h3>
              <template v-if="!isGridMode">
                <div class="item-meta">ID: {{ entry.item.id }}</div>
                <div v-if="entry.item.authors.length" class="item-meta">
                  作者：{{ entry.item.authors.join(' / ') }}
                </div>
                <div v-if="entry.item.tags.length" class="item-tags">
                  <span v-for="tag in entry.item.tags.slice(0, 10)" :key="tag" class="tag-chip">{{
                    tag
                  }}</span>
                  <span v-if="entry.item.tags.length > 10" class="tag-chip tag-more">…</span>
                </div>
                <div class="item-serial">{{ getItemSerial(entry.page, entry.indexInPage) }}</div>
              </template>
            </div>
          </article>

          <div v-if="loadingNext" :class="resultLoaderClass">
            <IonSpinner name="dots" />
          </div>
        </div>
      </div>
    </Transition>
  </section>
</template>

<script setup lang="ts">
defineOptions({ name: 'SearchResultContainer' })

const props = withDefaults(
  defineProps<{
    result: SearchResult | null
    items: SearchResultDisplayItem[]
    loading: boolean
    loadingPrevious?: boolean
    loadingNext?: boolean
    canLoadPrevious?: boolean
    pageAtTop?: boolean
    errorMessage: string
    mode: 'list' | 'grid'
    loadedPageStart?: number | null
    loadedPageEnd?: number | null
    idleText?: string
    emptyText?: string
  }>(),
  {
    items: () => [],
    loadingPrevious: false,
    loadingNext: false,
    canLoadPrevious: false,
    pageAtTop: false,
    loadedPageStart: null,
    loadedPageEnd: null,
    idleText: '搜索结果将在这里显示',
    emptyText: '没有搜索结果',
  },
)
const emit = defineEmits<{
  'mode-change': [mode: 'list' | 'grid']
  'item-click': [item: SearchResultItem]
  'load-previous': []
  'pull-state-change': [active: boolean]
  retry: []
}>()
import { computed, ref, watch } from 'vue'
import { IonSpinner } from '@ionic/vue'
import type { SearchResult, SearchResultItem } from '@/services/JmcomicTypes'

export interface SearchResultDisplayItem {
  item: SearchResultItem
  page: number
  indexInPage: number
}

export interface SearchResultContainerExposed {
  getRootElement: () => HTMLElement | null
  getEntryElement: (entryKey: string) => HTMLElement | null
}

type GridColumnCount = 2 | 3 | 4 | 5 | 6 | 7

// 网格模式下的列数会持久化到本地，避免用户每次切换后都要重新选择。
const GRID_COLUMNS_STORAGE_KEY = 'search-result-grid-columns'
const DEFAULT_GRID_COLUMNS: GridColumnCount = 2
const RESULT_PAGE_SIZE = 80
const gridColumnOptions: readonly GridColumnCount[] = [2, 3, 4, 5, 6, 7]

// 下拉加载上一页时，三个阈值分别控制“触发”、“最大拖动距离”和“触发后的停留距离”。
const PULL_TRIGGER_DISTANCE = 72
const PULL_MAX_DISTANCE = 108
const PULL_HOLD_DISTANCE = 52

const readInitialGridColumns = () => {
  if (typeof window === 'undefined') {
    return DEFAULT_GRID_COLUMNS
  }

  const savedValue = Number(window.localStorage.getItem(GRID_COLUMNS_STORAGE_KEY))
  return isGridColumnCount(savedValue) ? savedValue : DEFAULT_GRID_COLUMNS
}

const isGridColumnCount = (value: number): value is GridColumnCount => {
  return gridColumnOptions.includes(value as GridColumnCount)
}

const persistGridColumns = (count: GridColumnCount) => {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(GRID_COLUMNS_STORAGE_KEY, String(count))
  }
}

const rootRef = ref<HTMLElement | null>(null)
const gridColumns = ref<GridColumnCount>(readInitialGridColumns())
const pullDistance = ref(0)
const touchStartY = ref<number | null>(null)

const hasItems = computed(() => props.items.length > 0)
const isGridMode = computed(() => props.mode === 'grid')
const showInitialLoadingState = computed(() => props.loading && !hasItems.value)
const showErrorState = computed(() => Boolean(props.errorMessage) && !hasItems.value)
const showEmptyState = computed(() => Boolean(props.result) && !hasItems.value)
const showResultContent = computed(() => Boolean(props.result) && hasItems.value)
const pullIndicatorActive = computed(
  () => props.pageAtTop || props.loadingPrevious || pullDistance.value > 0,
)

const gridStyle = computed(() => ({
  '--grid-columns': String(gridColumns.value),
}))

const resultLayoutClass = computed(() => (isGridMode.value ? 'result-grid' : 'result-list'))
const resultLayoutStyle = computed(() => (isGridMode.value ? gridStyle.value : undefined))
const itemCardClass = computed(() => (isGridMode.value ? 'grid-card' : 'list-card'))
const resultLoaderClass = computed(() => (isGridMode.value ? 'grid-loader' : 'list-loader'))

const summaryText = computed(() => {
  if (!props.result) {
    return props.idleText
  }

  // 当前组件支持“向上补载旧页”，因此这里需要显示已加载页码范围而不是单页。
  if (props.loadedPageStart && props.loadedPageEnd) {
    if (props.loadedPageStart === props.loadedPageEnd) {
      return `共 ${props.result.totalItems} 条，当前第 ${props.loadedPageStart}/${props.result.totalPages} 页`
    }
    return `共 ${props.result.totalItems} 条，已加载第 ${props.loadedPageStart}-${props.loadedPageEnd}/${props.result.totalPages} 页`
  }

  return `共 ${props.result.totalItems} 条`
})

const pullReady = computed(() => pullDistance.value >= PULL_TRIGGER_DISTANCE)
const showPullIndicator = computed(() => props.canLoadPrevious)
const pullIndicatorStyle = computed(() => ({
  transform: `translateY(${Math.max(pullDistance.value - PULL_HOLD_DISTANCE, 0)}px)`,
  opacity: String(
    Math.min(
      (props.pageAtTop ? 0.72 : 0.52) +
        pullDistance.value / PULL_TRIGGER_DISTANCE +
        (props.loadingPrevious ? 0.28 : 0),
      1,
    ),
  ),
}))
const contentOffsetStyle = computed(() => ({
  transform: `translateY(${props.loadingPrevious ? Math.max(pullDistance.value, PULL_HOLD_DISTANCE) : pullDistance.value}px)`,
}))

const getItemSerial = (page: number, index: number) => {
  return (page - 1) * RESULT_PAGE_SIZE + index + 1
}

const getEntryKey = (entry: SearchResultDisplayItem) =>
  `${entry.page}-${entry.indexInPage}-${entry.item.id}`

const resetPullState = () => {
  pullDistance.value = 0
}

const getTouchClientY = (event: TouchEvent) => event.touches[0]?.clientY

const setGridColumns = (count: GridColumnCount) => {
  gridColumns.value = count
  persistGridColumns(count)
}

const handleTouchStart = (event: TouchEvent) => {
  touchStartY.value = getTouchClientY(event) ?? null
}

const handleTouchMove = (event: TouchEvent) => {
  // 只有列表滚动到顶部时，才接管原生触摸滚动并进入“下拉加载上一页”流程。
  if (
    !props.canLoadPrevious ||
    props.loadingPrevious ||
    !props.pageAtTop ||
    touchStartY.value === null
  ) {
    return
  }

  const currentY = getTouchClientY(event)
  if (typeof currentY !== 'number') {
    return
  }

  const deltaY = currentY - touchStartY.value
  if (deltaY <= 0) {
    resetPullState()
    return
  }

  // 这里做阻尼处理，避免拖动距离和视觉位移 1:1，手感会更柔和。
  pullDistance.value = Math.min(PULL_MAX_DISTANCE, deltaY * 0.45)
  if (event.cancelable) {
    event.preventDefault()
  }
}

const handleTouchEnd = () => {
  touchStartY.value = null

  if (!props.canLoadPrevious || props.loadingPrevious) {
    resetPullState()
    return
  }

  if (pullReady.value && props.pageAtTop) {
    pullDistance.value = PULL_HOLD_DISTANCE
    emit('load-previous')
    return
  }

  resetPullState()
}

watch(
  () => props.loadingPrevious,
  (loadingPrevious) => {
    if (!loadingPrevious) {
      resetPullState()
    }
  },
)

watch(
  () => props.pageAtTop,
  (pageAtTop) => {
    if (!pageAtTop && !props.loadingPrevious) {
      resetPullState()
    }
  },
)

watch(
  () => props.loadingPrevious || pullDistance.value > 0,
  (active) => {
    emit('pull-state-change', active)
  },
  { immediate: true },
)

defineExpose<SearchResultContainerExposed>({
  getRootElement: () => rootRef.value,
  getEntryElement: (entryKey: string) =>
    rootRef.value?.querySelector(`[data-entry-key="${entryKey}"]`) as HTMLElement | null,
})
</script>

<style scoped>
.result-shell {
  margin: 12px 14px 86px;
  color: #31251f;
}

.result-toolbar {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 10px;
  padding: 9px 12px;
  border-radius: 18px;
  background: #fffbf8;
  box-shadow: 0 10px 24px rgb(76 42 24 / 0.08);
}

.toolbar-main {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
}

.result-summary {
  min-width: 0;
  color: #785947;
  font-size: 12px;
  line-height: 1.45;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
}

.mode-switch {
  display: inline-flex;
  flex-shrink: 0;
  padding: 3px;
  border-radius: 999px;
  background: #fff;
  border: 1px solid rgb(250 156 105 / 0.42);
}

.grid-columns-switch {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 6px;
}

.mode-btn,
.grid-columns-btn,
.retry-btn {
  border: 0;
  font: inherit;
}

.mode-btn {
  height: 28px;
  padding: 0 12px;
  border-radius: 999px;
  background: transparent;
  color: #8a6048;
  font-size: 12px;
  transition:
    background-color 0.2s ease,
    color 0.2s ease,
    transform 0.16s ease;
}

.grid-columns-btn {
  height: 28px;
  min-width: 36px;
  padding: 0 10px;
  border-radius: 999px;
  background: #fff;
  color: #8a6048;
  font-size: 12px;
  border: 1px solid rgb(250 156 105 / 0.42);
  transition:
    background-color 0.2s ease,
    color 0.2s ease,
    border-color 0.2s ease,
    transform 0.16s ease;
}

.mode-btn.active {
  background: rgb(250 156 105);
  color: #fff;
}

.grid-columns-btn.active {
  background: rgb(250 156 105);
  color: #fff;
  border-color: rgb(250 156 105);
}

.mode-btn:active,
.grid-columns-btn:active {
  transform: scale(0.96);
}

.pull-indicator-row {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 32px;
  margin-bottom: 8px;
}

.pull-indicator {
  color: #9b6e57;
  pointer-events: none;
  transition:
    transform 0.18s ease,
    opacity 0.18s ease;
}

.pull-hint {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.pull-line {
  display: block;
  width: 36px;
  height: 1px;
  background: currentColor;
  opacity: 0.35;
}

.pull-arrow {
  font-size: 22px;
  line-height: 1;
  transition:
    transform 0.18s ease,
    color 0.18s ease;
}

.pull-indicator-row.ready .pull-arrow {
  transform: rotate(180deg);
  color: #fa9c69;
}

.pull-indicator-row.active {
  color: #8e644c;
}

.pull-indicator-row.loading,
.pull-indicator-row.ready {
  color: #fa9c69;
}

.result-content {
  transition: transform 0.18s ease;
}

.state-card {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 220px;
  padding: 24px;
  border-radius: 24px;
  background: linear-gradient(145deg, #fff8f2, #fff0e6);
  color: #8a6048;
  text-align: center;
}

.state-card.error {
  flex-direction: column;
  gap: 14px;
  color: #a43d2c;
}

.retry-btn {
  height: 34px;
  padding: 0 18px;
  border-radius: 999px;
  background: rgb(250 156 105);
  color: #fff;
}

.result-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.list-card {
  position: relative;
  display: flex;
  align-items: start;
  gap: 10px;
  min-height: 108px;
  padding: 0 10px 0 0;
  border-radius: 12px;
  background: #fffaf6;
  box-shadow: 5px 12px 28px rgb(76 42 24 / 0.2);
}

.result-grid {
  display: grid;
  grid-template-columns: repeat(var(--grid-columns, 3), minmax(0, 1fr));
  gap: 4px;
}

.list-loader,
.grid-loader {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 52px;
  color: #fa9c69;
}

.grid-loader {
  grid-column: 1 / -1;
}

.grid-card {
  position: relative;
  overflow: hidden;
  border-radius: 12px;
  background: #fffaf6;
  box-shadow: 5px 12px 28px rgb(76 42 24 / 0.2);
}

.cover-wrap {
  overflow: hidden;
  border-radius: 6px;
  background: linear-gradient(145deg, #f3ded0, #ffece0);
}

.list-card .cover-wrap {
  width: 80px;
  flex-shrink: 0;
  align-self: flex-start;
  aspect-ratio: 3 / 4;
}

.grid-card .cover-wrap {
  border-radius: 0;
  aspect-ratio: 3 / 4;
}

.cover-img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.item-info {
  min-width: 0;
}

.list-card .item-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: start;
  gap: 4px;
  overflow: hidden;
  padding: 0 2px 0 4px;
}

.grid-card .item-info {
  padding: 8px 9px 10px;
}

.item-title {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: #30201a;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.4;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  flex-shrink: 0;
}

.grid-card .item-title {
  -webkit-line-clamp: 3;
}

.item-meta {
  color: #876653;
  font-size: 11px;
  line-height: 1.35;
  flex-shrink: 0;
}

.item-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.item-serial {
  margin-top: auto;
  color: rgb(154 122 103 / 0.6);
  font-size: 9px;
  line-height: 1.3;
  text-align: right;
}

.tag-chip {
  max-width: 120px;
  overflow: hidden;
  padding: 3px 7px;
  border-radius: 999px;
  background: #fff0e7;
  color: #9b5a35;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag-more {
  background: transparent;
  color: #b89a84;
  font-weight: 600;
}

.grid-columns-fade-enter-active,
.grid-columns-fade-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
}

.grid-columns-fade-enter-from,
.grid-columns-fade-leave-to {
  opacity: 0;
  transform: translateX(8px);
}

.result-fade-enter-active,
.result-fade-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
}

.result-fade-enter-from,
.result-fade-leave-to {
  opacity: 0;
  transform: translateY(8px);
}

@media (min-width: 680px) {
  .result-shell {
    max-width: 1000px;
    margin-right: auto;
    margin-left: auto;
  }
}
</style>
