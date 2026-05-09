<template>
  <div class="card">
    <div class="cover-wrap">
      <img
        :src="coverSrc"
        class="cover"
        referrerpolicy="no-referrer"
        @error="onCoverError"
        alt=""
      />
    </div>
    <div class="info">
      <div class="album-title">{{ task.albumTitle }}</div>
      <div class="chapter-title">{{ task.chapterTitle }}</div>
      <template v-if="showProgress && task.status === 'downloading'">
        <div class="progress-bar">
          <div class="progress-fill" :style="{ width: progressPct + '%' }" />
        </div>
        <div class="progress-text">
          {{ task.downloadedPages }}/{{ task.totalPages }}
          <span class="progress-pct">{{ progressPct }}%</span>
        </div>
        <div v-if="speedText" class="speed-text">{{ speedText }}</div>
      </template>
      <div v-else-if="task.status === 'queued'" class="status-tag queued">排队中</div>
      <div v-else-if="task.status === 'completed'" class="status-tag completed">共 {{ task.totalPages }} 页</div>
      <div v-else-if="task.status === 'failed'" class="status-tag failed">{{ task.error || '下载失败' }}</div>

      <div class="actions">
        <template v-if="task.status === 'queued' || task.status === 'downloading'">
          <button class="btn btn-cancel" @click.stop="$emit('cancel')">取消</button>
        </template>
        <template v-if="task.status === 'completed'">
          <button class="btn btn-read" @click.stop="$emit('read')">阅读</button>
          <button class="btn btn-delete" @click.stop="$emit('delete')">删除</button>
        </template>
        <template v-if="task.status === 'failed'">
          <button class="btn btn-retry" @click.stop="$emit('retry')">重试</button>
          <button class="btn btn-delete" @click.stop="$emit('delete')">删除</button>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { getImageUrl } from '@/services/JmcomicService'
import type { DownloadTask } from '@/services/JmcomicTypes'

const props = defineProps<{
  task: DownloadTask
  showProgress: boolean
}>()

defineEmits<{
  cancel: []
  retry: []
  read: []
  delete: []
}>()

const coverError = ref(false)
const coverSrc = computed(() => {
  if (coverError.value && props.task.firstImageSortOrder) {
    return getImageUrl(props.task.chapterId, props.task.firstImageSortOrder, 'image')
  }
  return props.task.coverUrl
})

const onCoverError = () => {
  coverError.value = true
}

const progressPct = computed(() => {
  if (props.task.totalPages <= 0) return 0
  return Math.round((props.task.downloadedPages / props.task.totalPages) * 100)
})

const speedText = computed(() => {
  const s = props.task.speed
  if (!s || s <= 0) return ''
  if (s >= 1024 * 1024) {
    return (s / (1024 * 1024)).toFixed(1) + ' MB/s'
  }
  if (s >= 1024) {
    return (s / 1024).toFixed(1) + ' KB/s'
  }
  return s + ' B/s'
})
</script>

<style scoped>
.card {
  display: flex;
  gap: 12px;
  padding: 2px 8px;
  background: #fffaf6;
  border-radius: 12px;
  border: 1px solid rgb(245 210 188 / 0.5);
}

.cover-wrap {
  width: 64px;
  height: 85px;
  flex-shrink: 0;
  border-radius: 6px;
  overflow: hidden;
  background: #ece1d8;
}

.cover {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.album-title {
  font-size: 14px;
  font-weight: 600;
  color: #4c2a18;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chapter-title {
  font-size: 12px;
  color: #8a6048;
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.progress-bar {
  height: 5px;
  background: #e8d5c4;
  border-radius: 3px;
  margin-top: 8px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #fa9c69, #f28752);
  border-radius: 3px;
  transition: width 0.3s ease;
}

.progress-text {
  font-size: 11px;
  color: #8a6048;
  margin-top: 3px;
}

.progress-pct {
  color: #f28752;
  margin-left: 6px;
}

.speed-text {
  font-size: 11px;
  color: #b89a84;
  margin-top: 2px;
}

.status-tag {
  font-size: 11px;
  margin-top: 6px;
  padding: 2px 8px;
  border-radius: 4px;
  align-self: flex-start;
}

.status-tag.queued {
  background: #fff3ea;
  color: #fa9c69;
}

.status-tag.completed {
  background: #eaf7ea;
  color: #52a86b;
}

.status-tag.failed {
  background: #ffeaea;
  color: #d9534f;
}

.actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.btn {
  font-size: 12px;
  font-weight: 600;
  border: 0;
  border-radius: 6px;
  padding: 4px 14px;
  cursor: pointer;
  transition: opacity 0.15s;
}

.btn:active {
  opacity: 0.7;
}

.btn-read {
  background: linear-gradient(145deg, #fa9c69, #f28752);
  color: #fff;
}

.btn-delete {
  background: #f5d2bc;
  color: #8a6048;
}

.btn-cancel {
  background: #e8d5c4;
  color: #8a6048;
}

.btn-retry {
  background: linear-gradient(145deg, #fa9c69, #f28752);
  color: #fff;
}
</style>
