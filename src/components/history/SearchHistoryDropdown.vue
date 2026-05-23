<template>
  <div
    v-if="visible && items.length > 0"
    class="history-dropdown"
    @mousedown.prevent
    @touchstart.prevent
  >
    <div class="dropdown-header">
      <span class="dropdown-title">搜索历史</span>
      <button type="button" class="dropdown-clear" @click.stop="$emit('clear')">清除</button>
    </div>
    <div class="dropdown-list">
      <button
        v-for="item in items"
        :key="item.timestamp"
        type="button"
        class="history-item"
        @click.stop="$emit('select', item)"
      >
        <IonIcon :icon="timeOutline" class="item-icon" />
        <span class="item-keyword">{{ item.keyword }}</span>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { IonIcon } from '@ionic/vue'
import { timeOutline } from 'ionicons/icons'
import type { SearchHistoryItem } from '@/services/HistoryService'

defineProps<{
  visible: boolean
  items: SearchHistoryItem[]
}>()

defineEmits<{
  select: [item: SearchHistoryItem]
  clear: []
}>()
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
  gap: 10px;
  width: 100%;
  height: 40px;
  padding: 0 14px;
  border: 0;
  background: transparent;
  font-size: 13px;
  color: #3a261d;
  cursor: pointer;
}

.history-item:active {
  background: rgb(255 235 223 / 0.7);
}

.item-icon {
  font-size: 14px;
  color: #c96d3a;
  flex-shrink: 0;
}

.item-keyword {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
