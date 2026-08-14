<template>
  <article class="card">
    <div class="icon-wrap"><IonIcon :icon="documentOutline" /></div>
    <div class="info">
      <div class="title">{{ task.displayTitle }}</div>
      <div class="subtitle">{{ task.mode === 'merged' ? '合并导出' : task.chapterId }}</div>
      <div v-if="showProgress" class="progress-bar">
        <div class="progress-fill" :style="{ width: progressPercent + '%' }" />
      </div>
      <div class="status-row">
        <span class="status" :class="task.status">{{ statusLabel }}</span>
        <span v-if="task.totalPages > 0" class="page-count">
          {{ task.currentPage }}/{{ task.totalPages }} 页
        </span>
        <span v-if="task.totalVolumes > 1" class="page-count">
          分卷 {{ task.currentVolume }}/{{ task.totalVolumes }}
        </span>
      </div>
      <div v-if="task.errorMessage" class="error">{{ task.errorMessage }}</div>
    </div>
    <div class="actions">
      <button
        v-if="cancelable"
        type="button"
        aria-label="取消 PDF 导出"
        title="取消"
        @click.stop="$emit('cancel')"
      >
        <IonIcon :icon="closeCircleOutline" />
      </button>
      <button
        v-if="retryable"
        type="button"
        aria-label="重试整个 PDF 导出任务"
        title="重试整个任务"
        @click.stop="$emit('retry')"
      >
        <IonIcon :icon="refreshOutline" />
      </button>
      <button
        v-if="deletable"
        type="button"
        aria-label="删除 PDF 导出任务记录"
        title="删除任务记录"
        @click.stop="$emit('delete')"
      >
        <IonIcon :icon="trashOutline" />
      </button>
    </div>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { IonIcon } from '@ionic/vue'
import { closeCircleOutline, documentOutline, refreshOutline, trashOutline } from 'ionicons/icons'
import type { PdfExportTaskRecord } from '@/services/JmcomicTypes'

const props = defineProps<{ task: PdfExportTaskRecord }>()
defineEmits<{
  cancel: []
  retry: []
  delete: []
}>()

const showProgress = computed(() => ['queued', 'running', 'cancelling'].includes(props.task.status))
const cancelable = computed(() => ['queued', 'running'].includes(props.task.status))
const retryable = computed(() =>
  ['failed', 'partial', 'interrupted', 'cancelled'].includes(props.task.status),
)
const deletable = computed(() => !['queued', 'running', 'cancelling'].includes(props.task.status))
const progressPercent = computed(() => {
  if (props.task.totalPages <= 0) return 0
  return Math.min(100, Math.round((props.task.currentPage / props.task.totalPages) * 100))
})
const statusLabel = computed(() => {
  const labels: Record<string, string> = {
    queued: '排队中',
    running: '导出中',
    cancelling: '取消中',
    cancelled: '已取消',
    completed: '已完成',
    partial: '部分完成',
    failed: '失败',
    interrupted: '已中断',
  }
  return labels[props.task.status] || props.task.status
})
</script>

<style scoped>
.card {
  display: flex;
  gap: 12px;
  min-height: 86px;
  padding: 12px 8px 12px 12px;
  background: #fffaf6;
  border: 1px solid rgb(245 210 188 / 0.5);
  border-radius: 8px;
}
.icon-wrap {
  display: grid;
  flex: 0 0 42px;
  place-items: center;
  height: 42px;
  border-radius: 8px;
  background: #f5e2d6;
  color: #9f5f3e;
  font-size: 24px;
}
.info {
  min-width: 0;
  flex: 1;
}
.title,
.subtitle,
.error {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.title {
  color: #4c2a18;
  font-size: 14px;
  font-weight: 600;
}
.subtitle {
  margin-top: 3px;
  color: #8a6048;
  font-size: 11px;
}
.progress-bar {
  height: 5px;
  margin-top: 9px;
  overflow: hidden;
  border-radius: 3px;
  background: #ead8cc;
}
.progress-fill {
  height: 100%;
  background: #c06f45;
  transition: width 0.2s ease;
}
.status-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-top: 8px;
  color: #8a6048;
  font-size: 11px;
}
.status {
  padding: 2px 6px;
  border-radius: 4px;
  background: #f5e2d6;
}
.status.failed,
.status.interrupted,
.status.partial {
  color: #b53d36;
  background: #ffe3df;
}
.status.completed {
  color: #3d7b4d;
  background: #e2f1e5;
}
.error {
  margin-top: 5px;
  color: #b53d36;
  font-size: 11px;
}
.actions {
  display: flex;
  flex: 0 0 auto;
  flex-direction: column;
  gap: 3px;
}
.actions button {
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #8a6048;
  font-size: 18px;
}
.actions button:first-child {
  color: #b53d36;
}
.actions button:focus-visible {
  outline: 2px solid #c06f45;
  outline-offset: 1px;
}
@media (prefers-reduced-motion: reduce) {
  .progress-fill {
    transition: none;
  }
}
</style>
