<template>
  <div class="multi-select-actions" role="toolbar" aria-label="批量操作栏">
    <button
      v-for="action in actions"
      :key="action.id"
      type="button"
      :disabled="disabled || action.disabled"
      @click="$emit('action', action.id)"
    >
      {{ action.label }}
    </button>
  </div>
</template>

<script setup lang="ts">
defineProps<{ actions: { id: string; label: string; disabled?: boolean }[]; disabled?: boolean }>()
defineEmits<{ action: [id: string] }>()
</script>

<style scoped>
.multi-select-actions {
  display: flex;
  justify-content: center;
  flex-wrap: wrap;
  gap: 8px;
  width: min(100%, 1000px);
  box-sizing: border-box;
  padding: 10px 14px calc(10px + var(--ion-safe-area-bottom, 0px));
  background: #fffaf6;
  box-shadow: 0 -3px 14px rgb(76 42 24 / 0.16);
}
.multi-select-actions button {
  min-height: 38px;
  padding: 0 14px;
  border: 0;
  border-radius: 10px;
  background: #fa9c69;
  color: white;
  font: inherit;
}
.multi-select-actions button:disabled {
  opacity: 0.5;
}
</style>
