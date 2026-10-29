<template>
  <div class="batch-progress-overlay" role="status" aria-live="polite">
    <div class="progress-dialog">
      <div class="progress-title">{{ title }}</div>

      <div class="progress-bar">
        <div class="progress-fill" :style="{ width: progressPercent + '%' }" />
      </div>
      <div class="progress-percent">{{ progressPercent }}%</div>

      <div class="progress-count">已完成：{{ current }} / {{ total }} {{ unit }}</div>

      <div v-if="currentItem" class="progress-file">{{ currentItem }}</div>

      <div class="progress-warning">请勿关闭应用</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

// 仅展示批量操作进度，执行状态和显隐由调用页面维护。
const props = withDefaults(
  defineProps<{
    title: string
    current: number
    total: number
    currentItem?: string
    unit?: string
  }>(),
  { currentItem: '', unit: '项' },
)

const progressPercent = computed(() => {
  if (props.total <= 0) return 0
  return Math.round((props.current / props.total) * 100)
})
</script>

<style scoped>
.batch-progress-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;
}

.progress-dialog {
  background: #fff;
  border-radius: 16px;
  padding: 32px 28px 24px;
  width: 300px;
  text-align: center;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.18);
}

.progress-title {
  font-size: 16px;
  font-weight: 600;
  color: #4c2a18;
  margin-bottom: 20px;
}

.progress-bar {
  height: 8px;
  background: #f0e4db;
  border-radius: 4px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(135deg, #f0a060, #e8843c);
  border-radius: 4px;
  transition: width 0.25s ease;
}

.progress-percent {
  font-size: 28px;
  font-weight: 700;
  color: #e8843c;
  margin: 12px 0 8px;
}

.progress-count {
  font-size: 13px;
  color: #8c6b5a;
  margin-bottom: 4px;
}

.progress-file {
  font-size: 11px;
  color: #c4a494;
  margin-bottom: 16px;
  word-break: break-all;
  max-height: 32px;
  overflow: hidden;
}

.progress-warning {
  font-size: 12px;
  color: #d4a08a;
  margin-top: 8px;
}
</style>
