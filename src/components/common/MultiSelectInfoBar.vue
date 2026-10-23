<template>
  <div class="multi-select-info" role="toolbar" aria-label="多选信息栏">
    <div class="multi-select-info-content">
      <button type="button" @click="preparing ? $emit('cancel-preparation') : $emit('cancel')">
        <IonIcon :icon="closeOutline" aria-hidden="true" />
        {{ preparing ? '取消准备' : '取消' }}
      </button>
      <span aria-live="polite">已选择 {{ count }} 项</span>
      <div class="selection-actions">
        <button type="button" :disabled="preparing" @click="$emit('select-all')">
          <IonIcon :icon="checkmarkDoneOutline" aria-hidden="true" />
          全选
        </button>
        <button type="button" :disabled="preparing" @click="$emit('invert')">
          <IonIcon :icon="swapVerticalOutline" aria-hidden="true" />
          反选
        </button>
      </div>
      <span v-if="preparing" role="status">正在准备…</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { IonIcon } from '@ionic/vue'
import { checkmarkDoneOutline, closeOutline, swapVerticalOutline } from 'ionicons/icons'

defineProps<{ count: number; preparing?: boolean }>()
defineEmits<{ cancel: []; 'cancel-preparation': []; 'select-all': []; invert: [] }>()
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
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: min(100%, 1000px);
  margin: 0 auto;
}
.multi-select-info button,
.selection-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}
.multi-select-info button {
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
</style>
