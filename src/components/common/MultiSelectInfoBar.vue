<template>
  <div class="multi-select-info" role="toolbar" aria-label="多选信息栏">
    <div class="multi-select-info-content desktop-page-content">
      <div class="selection-toolbar">
        <button type="button" @click="$emit('cancel')">
          <IonIcon :icon="closeOutline" aria-hidden="true" />
          取消
        </button>
        <div class="selection-actions">
          <button type="button" :disabled="preparing || allSelected" @click="$emit('select-all')">
            <IonSpinner
              v-if="preparingAction === 'select-all'"
              name="crescent"
              aria-hidden="true"
            />
            <IonIcon v-else :icon="checkmarkDoneOutline" aria-hidden="true" />
            全选
          </button>
          <button type="button" :disabled="preparing" @click="$emit('invert')">
            <IonSpinner v-if="preparingAction === 'invert'" name="crescent" aria-hidden="true" />
            <IonIcon v-else :icon="swapVerticalOutline" aria-hidden="true" />
            反选
          </button>
        </div>
      </div>
      <span v-if="preparing" class="selection-count" role="status">
        <span>已选择</span>
        <IonSpinner name="crescent" aria-label="正在加载" />
        <span>项</span>
      </span>
      <span v-else class="selection-count" aria-live="polite">已选择 {{ count }} 项</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { IonIcon, IonSpinner } from '@ionic/vue'
import { checkmarkDoneOutline, closeOutline, swapVerticalOutline } from 'ionicons/icons'

defineProps<{
  count: number
  preparing?: boolean
  preparingAction?: 'select-all' | 'invert' | null
  allSelected?: boolean
}>()
defineEmits<{ cancel: []; 'select-all': []; invert: [] }>()
</script>

<style scoped>
.multi-select-info {
  width: 100%;
  box-sizing: border-box;
  padding: calc(10px + var(--ion-safe-area-top, 0px)) 14px 10px;
  background: #fffaf6;
  box-shadow: 0 3px 14px rgb(76 42 24 / 0.16);
}
.multi-select-info-content {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: min(100%, 1000px);
  margin: 0 auto;
}
.selection-toolbar,
.selection-actions,
.multi-select-info button {
  display: flex;
  align-items: center;
}
.selection-toolbar {
  justify-content: space-between;
  gap: 8px;
}
.selection-actions {
  gap: 6px;
}
.multi-select-info button {
  justify-content: center;
  gap: 6px;
  white-space: nowrap;
  border: 0;
  border-radius: 8px;
  padding: 8px 10px;
  background: #fff0e7;
  color: #75472f;
  font: inherit;
}
.multi-select-info button:disabled {
  opacity: 0.55;
}
.multi-select-info button :deep(ion-icon) {
  font-size: 18px;
}
.multi-select-info button :deep(ion-spinner) {
  width: 18px;
  height: 18px;
}
.selection-count {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 5px;
  min-height: 18px;
  color: #75472f;
  font-size: 13px;
}
.selection-count :deep(ion-spinner) {
  width: 15px;
  height: 15px;
  color: #bf8350;
}
</style>
