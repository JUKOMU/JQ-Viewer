<template>
  <section class="result-shell">
    <div class="result-toolbar">
      <div class="result-summary">
        <template v-if="result">
          共 {{ result.totalItems }} 条，当前第 {{ result.currentPage }}/{{ result.totalPages }} 页
        </template>
        <template v-else>
          {{ idleText }}
        </template>
      </div>
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

    <div v-if="loading" class="state-card">正在搜索...</div>
    <div v-else-if="errorMessage" class="state-card error">
      <div>{{ errorMessage }}</div>
      <button type="button" class="retry-btn" @click="emit('retry')">重试</button>
    </div>
    <div v-else-if="result && !result.content.length" class="state-card">
      {{ emptyText }}
    </div>
    <div v-else-if="result" :class="mode === 'grid' ? 'result-grid' : 'result-list'">
      <article
          v-for="item in result.content"
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
          </template>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import type {SearchResult, SearchResultItem} from '@/services/JmcomicTypes'

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

const emit = defineEmits<{
  'mode-change': [mode: 'list' | 'grid']
  'item-click': [item: SearchResultItem]
  retry: []
}>()
</script>

<style scoped>
.result-shell {
  margin: 12px 14px 86px;
  color: #31251f;
}

.result-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  padding: 9px 12px;
  border-radius: 18px;
  background: rgb(255 249 244 / 0.94);
  box-shadow: 0 10px 24px rgb(76 42 24 / 0.08);
}

.result-summary {
  min-width: 0;
  color: #785947;
  font-size: 12px;
}

.mode-switch {
  display: inline-flex;
  flex-shrink: 0;
  padding: 3px;
  border-radius: 999px;
  background: #fff;
  border: 1px solid rgb(250 156 105 / 0.42);
}

.mode-btn,
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
}

.mode-btn.active {
  background: rgb(250 156 105);
  color: #fff;
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
  gap: 10px;
}

.list-card {
  display: grid;
  grid-template-columns: 82px minmax(0, 1fr);
  gap: 12px;
  padding: 10px;
  border-radius: 20px;
  background: #fffaf6;
  box-shadow: 0 12px 28px rgb(76 42 24 / 0.1);
}

.result-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.grid-card {
  overflow: hidden;
  border-radius: 18px;
  background: #fffaf6;
  box-shadow: 0 12px 28px rgb(76 42 24 / 0.1);
}

.cover-wrap {
  overflow: hidden;
  border-radius: 18px;
  background: linear-gradient(145deg, #f3ded0, #ffece0);
}

.list-card .cover-wrap {
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
  object-fit: cover;
}

.item-info {
  min-width: 0;
}

.list-card .item-info {
  padding: 3px 2px 0 0;
}

.grid-card .item-info {
  padding: 8px 9px 10px;
}

.item-title {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: #30201a;
  font-size: 13px;
  font-weight: 700;
  line-height: 1.4;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.item-meta {
  margin-top: 5px;
  color: #876653;
  font-size: 11px;
  line-height: 1.35;
}

.item-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 8px;
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

@media (min-width: 680px) {
  .result-shell {
    max-width: 1000px;
    margin-right: auto;
    margin-left: auto;
  }

  .result-grid {
    grid-template-columns: repeat(5, minmax(0, 1fr));
  }

  .list-card {
    grid-template-columns: 104px minmax(0, 1fr);
  }
}
</style>
