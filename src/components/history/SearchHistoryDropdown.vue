<template>
  <div
    v-if="visible && filteredItems.length > 0"
    class="history-dropdown"
    @mousedown.prevent
    @touchstart.stop
  >
    <div class="dropdown-header">
      <span class="dropdown-title">搜索历史</span>
      <button type="button" class="dropdown-clear" @click.stop="$emit('clear')">清除</button>
    </div>
    <div class="dropdown-list">
      <button
        v-for="(item, idx) in filteredItems"
        :key="item.timestamp"
        type="button"
        class="history-item"
        :class="{ 'last-item': idx === filteredItems.length - 1 }"
        @click.stop="$emit('select', item)"
      >
        <span class="item-keyword">{{ item.keyword }}</span>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { SearchHistoryItem } from '@/services/HistoryService'

const props = defineProps<{
  visible: boolean
  items: SearchHistoryItem[]
  filterText?: string
}>()

defineEmits<{
  select: [item: SearchHistoryItem]
  clear: []
}>()

const filteredItems = computed(() => {
  const ft = (props.filterText ?? '').trim().toLowerCase()
  if (!ft) return props.items
  return props.items.filter(item => item.keyword.toLowerCase().includes(ft))
})
</script>

<style scoped>
.history-dropdown {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  z-index: 100;
  background: #fffbf8;
  border: 1px solid rgb(250 156 105 / 0.3);
  border-radius: 14px;
  box-shadow: 0 8px 22px rgb(76 42 24 / 0.12);
  overflow: hidden;
}

.dropdown-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 14px;
  border-bottom: 1px solid rgb(245 210 188 / 0.5);
}

.dropdown-title {
  font-size: 11px;
  font-weight: 600;
  color: #8a6048;
}

.dropdown-clear {
  border: 0;
  background: transparent;
  color: rgb(237 123 62);
  font-size: 11px;
}

.dropdown-list {
  max-height: 240px;
  overflow-y: auto;
}

.history-item {
  display: flex;
  align-items: center;
  width: 100%;
  height: 40px;
  padding: 0 14px;
  border: 0;
  border-bottom: 1px solid rgb(245 210 188 / 0.4);
  background: transparent;
  font-size: 13px;
  color: #3a261d;
  cursor: pointer;
}

.history-item.last-item {
  border-bottom: 0;
}

.history-item:active {
  background: rgb(255 235 223 / 0.7);
}

.item-keyword {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
