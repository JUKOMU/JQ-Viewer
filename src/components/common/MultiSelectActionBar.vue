<template>
  <div class="multi-select-actions" role="toolbar" aria-label="批量操作栏">
    <div class="multi-select-actions-content desktop-page-content">
      <button
        v-for="action in actions"
        :key="action.id"
        type="button"
        :class="{ 'multi-select-action--danger': action.id === 'delete' || action.id === 'remove' }"
        :disabled="disabled || action.disabled"
        @click="$emit('action', action.id)"
      >
        <IonIcon :icon="actionIcons[action.id]" aria-hidden="true" />
        {{ action.label }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { IonIcon } from '@ionic/vue'
import {
  closeCircleOutline,
  documentLockOutline,
  downloadOutline,
  heartOutline,
  pauseOutline,
  playOutline,
  refreshOutline,
  swapHorizontalOutline,
  trashOutline,
} from 'ionicons/icons'

const actionIcons: Record<string, string> = {
  favorite: heartOutline,
  download: downloadOutline,
  remove: trashOutline,
  move: swapHorizontalOutline,
  pause: pauseOutline,
  resume: playOutline,
  retry: refreshOutline,
  delete: trashOutline,
  export: documentLockOutline,
  cancel: closeCircleOutline,
}

defineProps<{ actions: { id: string; label: string; disabled?: boolean }[]; disabled?: boolean }>()
defineEmits<{ action: [id: string] }>()
</script>

<style scoped>
.multi-select-actions {
  width: 100%;
  box-sizing: border-box;
  padding: 10px 14px calc(10px + var(--ion-safe-area-bottom, 0px));
  background: #fffaf6;
  box-shadow: 0 -3px 14px rgb(76 42 24 / 0.16);
}
.multi-select-actions-content {
  display: flex;
  justify-content: center;
  flex-wrap: wrap;
  gap: 8px;
  width: min(100%, 1000px);
  margin: 0 auto;
}
.multi-select-actions button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-height: 38px;
  padding: 0 14px;
  border: 0;
  border-radius: 10px;
  background: #fff0e7;
  color: #75472f;
  font: inherit;
}
.multi-select-actions button.multi-select-action--danger {
  background: #ffe3df;
  color: #b53d36;
}
.multi-select-actions button:disabled {
  opacity: 0.5;
}
.multi-select-actions button :deep(ion-icon) {
  flex: 0 0 auto;
  font-size: 18px;
}
</style>
