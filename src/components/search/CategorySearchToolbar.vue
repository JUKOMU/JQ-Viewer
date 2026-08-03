<template>
  <div class="toolbar-content">
    <section class="option-section">
      <div class="option-title">分类</div>
      <div class="option-group">
        <button
          v-for="option in CATEGORY_OPTIONS"
          :key="option.value"
          type="button"
          class="option-chip"
          :class="{ active: query.category === option.value }"
          @click="query.category = option.value"
        >
          {{ option.label }}
        </button>
      </div>
    </section>

    <section class="option-section">
      <div class="option-title">排序</div>
      <div class="option-group">
        <button
          v-for="option in ORDER_BY_OPTIONS"
          :key="option.value"
          type="button"
          class="option-chip"
          :class="{ active: query.orderBy === option.value }"
          @click="query.orderBy = option.value"
        >
          {{ option.label }}
        </button>
      </div>
    </section>

    <section class="option-section">
      <div class="option-title">时间范围</div>
      <div class="option-group">
        <button
          v-for="option in TIME_OPTIONS"
          :key="option.value"
          type="button"
          class="option-chip"
          :class="{ active: query.time === option.value }"
          @click="query.time = option.value"
        >
          {{ option.label }}
        </button>
      </div>
    </section>
    <div class="action-row">
      <button
        type="button"
        class="action-btn primary"
        aria-label="搜索"
        title="搜索"
        @click="emitSearch"
      >
        <IonIcon :icon="checkmarkOutline" />
      </button>
      <button type="button" class="action-btn" aria-label="重置" title="重置" @click="resetQuery">
        <IonIcon :icon="refreshOutline" />
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'CategorySearchToolbar' })

const emit = defineEmits<{
  search: [query: SearchQuery]
}>()
import { reactive } from 'vue'
import { IonIcon } from '@ionic/vue'
import { checkmarkOutline, refreshOutline } from 'ionicons/icons'
import { CATEGORY_OPTIONS, ORDER_BY_OPTIONS, TIME_OPTIONS } from '@/constants/searchOptions'
import type { SearchQuery } from '@/services/JmcomicTypes'
import { HistoryService } from '@/services/HistoryService'

const createDefaultQuery = (): SearchQuery => ({
  category: 'doujin',
  orderBy: 'mr',
  time: 'a',
  searchMainTag: 0,
})

const query = reactive<SearchQuery>(createDefaultQuery())

const emitSearch = () => {
  const catLabel = CATEGORY_OPTIONS.find((o) => o.value === query.category)?.label
  if (catLabel) HistoryService.addSearchHistory('category', catLabel)
  emit('search', { ...query })
}

const resetQuery = () => {
  Object.assign(query, createDefaultQuery())
}
</script>

<style scoped>
.toolbar-content {
  max-width: 1000px;
  margin: 0 auto;
  padding: 10px 16px;
  background: #fffbf8;
  border-radius: 20px;
  box-shadow: 0 4px 6px 0 rgb(62 39 26 / 0.2);
}

.option-section + .option-section {
  margin-top: 18px;
}

.option-title {
  margin-bottom: 10px;
  color: #555;
  font-size: 13px;
}

.option-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.option-chip,
.action-btn {
  border: 0;
}

.option-chip {
  height: 30px;
  padding: 0 12px;
  border-radius: 999px;
  background: #fff;
  color: #555;
  font-size: 12px;
  border: 1px solid rgb(250, 156, 105);
  transition:
    transform 0.15s ease-out,
    background-color 0.15s ease,
    box-shadow 0.15s ease;
}

.option-chip.active {
  transform: scale(0.97);
  background: rgb(250, 156, 105);
  color: #fff;
}

.action-row {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 22px;
}

.action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 40px;
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: #fff3ec;
  color: rgb(237 123 62);
  font-size: 20px;
}

.action-btn.primary {
  background: rgb(250, 156, 105);
  color: #fff;
}

.action-btn:active {
  transform: scale(0.94);
}
</style>
