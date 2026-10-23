<template>
  <div class="multi-select-info" role="toolbar" aria-label="多选信息栏">
    <button type="button" @click="preparing ? $emit('cancel-preparation') : $emit('cancel')">
      {{ preparing ? '取消准备' : '取消' }}
    </button>
    <span aria-live="polite">已选择 {{ count }} 项</span>
    <button type="button" :disabled="preparing" @click="$emit('select-all')">全选</button>
    <button type="button" :disabled="preparing" @click="$emit('invert')">反选</button>
    <span v-if="preparing" role="status">正在准备…</span>
  </div>
</template>

<script setup lang="ts">
defineProps<{ count: number; preparing?: boolean }>()
defineEmits<{ cancel: []; 'cancel-preparation': []; 'select-all': []; invert: [] }>()
</script>

<style scoped>
.multi-select-info {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: min(100%, 1000px);
  box-sizing: border-box;
  padding: 10px 14px;
  background: #fffaf6;
  box-shadow: 0 3px 14px rgb(76 42 24 / 0.16);
}
.multi-select-info button {
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
</style>
