<template>
  <section class="result-shell">
    <div class="result-toolbar">
      <div class="toolbar-main">
        <div class="result-summary">
          <template v-if="result">
            共 {{ result.totalItems }} 条，当前第 {{ result.currentPage }}/{{ result.totalPages }} 页
          </template>
          <template v-else>
            {{ idleText }}
          </template>
        </div>
        <div class="toolbar-actions">
          <div class="mode-switch" aria-label="显示方式">
            <button
                type="button"
                class="mode-btn"
                :class="{ active: mode === 'list' }"
                @click="emit('mode-change', 'list')"
            >
              列表
            </button>
            <button
                type="button"
                class="mode-btn"
                :class="{ active: mode === 'grid' }"
                @click="emit('mode-change', 'grid')"
            >
              网格
            </button>
          </div>
        </div>
      </div>
      <Transition name="grid-columns-fade">
        <div v-if="mode === 'grid'" class="grid-columns-switch" aria-label="网格列数">
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

    <Transition name="result-fade" mode="out-in">
      <div v-if="loading" key="loading" class="state-card">正在搜索...</div>
      <div v-else-if="errorMessage" key="error" class="state-card error">
        <div>{{ errorMessage }}</div>
        <button type="button" class="retry-btn" @click="emit('retry')">重试</button>
      </div>
      <div v-else-if="result && !result.content.length" key="empty" class="state-card">
        {{ emptyText }}
      </div>
      <div
          v-else-if="result"
          :key="`result-${mode}`"
          :class="mode === 'grid' ? 'result-grid' : 'result-list'"
          :style="mode === 'grid' ? gridStyle : undefined"
      >
        <article
            v-for="(item, index) in result.content"
            :key="item.id"
            :class="mode === 'grid' ? 'grid-card' : 'list-card'"
            @click="emit('item-click', item)"
        >
          <div class="cover-wrap">
            <img class="cover-img" :src="item.coverUrl" :alt="item.title" loading="lazy">
          </div>
          <div class="item-info">
            <h3 class="item-title">{{ item.title }}</h3>
            <template v-if="mode === 'list'">
              <div class="item-meta">ID: {{ item.id }}</div>
              <div v-if="item.authors.length" class="item-meta">
                作者：{{ item.authors.join(' / ') }}
              </div>
              <div v-if="item.tags.length" class="item-tags">
                <span v-for="tag in item.tags.slice(0, 6)" :key="tag" class="tag-chip">{{ tag }}</span>
              </div>
              <div class="item-serial">{{ getItemSerial(result, index) }}</div>
            </template>
          </div>
        </article>
      </div>
    </Transition>
  </section>
</template>

<script setup lang="ts">
import {computed, ref} from 'vue'
import type {SearchResult, SearchResultItem} from '@/services/JmcomicTypes'

const GRID_COLUMNS_STORAGE_KEY = 'search-result-grid-columns'
const gridColumnOptions = [2, 3, 4, 5] as const

const readInitialGridColumns = () => {
  if (typeof window === 'undefined') {
    return 2
  }

  const savedValue = Number(window.localStorage.getItem(GRID_COLUMNS_STORAGE_KEY))
  return gridColumnOptions.includes(savedValue as typeof gridColumnOptions[number]) ? savedValue : 2
}

withDefaults(defineProps<{
  result: SearchResult | null
  loading: boolean
  errorMessage: string
  mode: 'list' | 'grid'
  idleText?: string
  emptyText?: string
}>(), {
  idleText: '搜索结果将在这里显示',
  emptyText: '没有搜索结果',
})

const gridColumns = ref<number>(readInitialGridColumns())

const gridStyle = computed(() => ({
  '--grid-columns': String(gridColumns.value),
}))

const emit = defineEmits<{
  'mode-change': [mode: 'list' | 'grid']
  'item-click': [item: SearchResultItem]
  retry: []
}>()

const getItemSerial = (result: SearchResult, index: number) => {
  const pageSize = 80
  return (result.currentPage - 1) * pageSize + index + 1
}

const setGridColumns = (count: typeof gridColumnOptions[number]) => {
  gridColumns.value = count
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(GRID_COLUMNS_STORAGE_KEY, String(count))
  }
}
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
  background: #fff3ea;
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
  transition: background-color 0.2s ease, color 0.2s ease, transform 0.16s ease;
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
  transition: background-color 0.2s ease, color 0.2s ease, border-color 0.2s ease, transform 0.16s ease;
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
  display: flex;
  align-items: stretch;
  gap: 10px;
  height: 108px;
  padding: 0 10px;
  border-radius: 12px;
  background: #fffaf6;
  box-shadow: 5px 12px 28px rgb(76 42 24 / 0.2);
}

.result-grid {
  display: grid;
  grid-template-columns: repeat(var(--grid-columns, 2), minmax(0, 1fr));
  gap: 4px;
}

.grid-card {
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
  height: 100%;
  flex: 0 0 auto;
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
  border-left: 1px solid rgb(85 85 85 / 0.1);
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
  -webkit-line-clamp: 2;
}

.grid-card .item-title {
  -webkit-line-clamp: 3;
}

.item-meta {
  color: #876653;
  font-size: 11px;
  line-height: 1.35;
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

.grid-columns-fade-enter-active,
.grid-columns-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.grid-columns-fade-enter-from,
.grid-columns-fade-leave-to {
  opacity: 0;
  transform: translateX(8px);
}

.result-fade-enter-active,
.result-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
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
