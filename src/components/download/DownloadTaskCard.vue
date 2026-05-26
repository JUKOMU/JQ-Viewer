<template>
  <div
    class="card"
    :class="{ clickable: task.status === 'completed' }"
    @click="onCardClick"
  >
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
      <div class="title-row">
        <div class="titles">
          <div class="album-title">{{ task.albumTitle }}</div>
          <div class="chapter-title">{{ task.chapterId }}</div>
          <div v-if="hasMultiChapters" class="chapter-bubbles">
            <span v-for="ch in downloadedChapters" :key="ch.chapterId" class="bubble">
              {{ ch.chapterSortOrder ?? parseSortOrder(ch.chapterTitle) }}
            </span>
          </div>
        </div>
      </div>

      <!-- 下载中：进度条 + 速度 -->
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

      <!-- 排队中 -->
      <div v-else-if="task.status === 'queued'" class="status-tag queued">排队中</div>

      <!-- 已暂停 -->
      <div v-else-if="task.status === 'paused'" class="status-tag paused">已暂停</div>

      <!-- 已完成 -->
      <div v-else-if="task.status === 'completed'" class="status-row">
        <span class="tag completed">共 {{ displayTotalPages }} 页</span>
        <span v-if="sizeText" class="size-text">{{ sizeText }}</span>
      </div>

      <!-- 部分失败 -->
      <template v-else-if="task.status === 'failed' && task.downloadedPages > 0">
        <div class="progress-bar">
          <div class="progress-fill partial" :style="{ width: progressPct + '%' }" />
        </div>
        <div class="progress-text">
          已下载 {{ task.downloadedPages }}/{{ task.totalPages }}
          <span class="failed-count">失败 {{ failedCount }}</span>
        </div>
        <div v-if="sizeText" class="size-text size-standalone">{{ sizeText }}</div>
      </template>

      <!-- 完全失败 -->
      <div v-else-if="task.status === 'failed'" class="status-row">
        <span class="tag failed">{{ sanitizeError(task.error, '下载失败') }}</span>
      </div>
    </div>

    <button class="more-btn" @click.stop="$emit('more', $event)">
      <IonIcon :icon="ellipsisVertical" />
    </button>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { IonIcon } from '@ionic/vue'
import { ellipsisVertical } from 'ionicons/icons'
import { getImageUrl, sanitizeError } from '@/services/JmcomicService'
import type { DownloadTask } from '@/services/JmcomicTypes'

const props = defineProps<{
  task: DownloadTask
  showProgress: boolean
  downloadedChapters?: DownloadTask[]
  totalSize?: number
}>()

const emit = defineEmits<{
  more: [event: Event]
  click: []
}>()

const hasMultiChapters = computed(() =>
  (props.downloadedChapters?.length ?? 0) > 1
)

const displayTotalPages = computed(() => {
  if (hasMultiChapters.value && props.downloadedChapters) {
    return props.downloadedChapters.reduce((s, c) => s + c.totalPages, 0)
  }
  return props.task.totalPages
})

const parseSortOrder = (title: string): number => {
  const m = title.match(/^第(\d+)/)
  return m ? parseInt(m[1], 10) : 0
}

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

const failedCount = computed(() =>
  props.task.totalPages - props.task.downloadedPages
)

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

const sizeText = computed(() => {
  const s = props.totalSize ?? props.task.totalSize
  if (!s || s <= 0) return ''
  if (s >= 1024 * 1024 * 1024) {
    return (s / (1024 * 1024 * 1024)).toFixed(1) + ' GB'
  }
  if (s >= 1024 * 1024) {
    return (s / (1024 * 1024)).toFixed(1) + ' MB'
  }
  if (s >= 1024) {
    return (s / 1024).toFixed(1) + ' KB'
  }
  return s + ' B'
})

const onCardClick = () => {
  if (props.task.status === 'completed') {
    emit('click')
  }
}
</script>

<style scoped>
.card {
  display: flex;
  gap: 12px;
  padding: 0 36px 0 0;
  background: #fffaf6;
  border-radius: 12px;
  border: 1px solid rgb(245 210 188 / 0.5);
  position: relative;
  user-select: none;
}

.card.clickable {
  cursor: pointer;
}

.card.clickable:active {
  transform: scale(0.98);
}

.cover-wrap {
  width: 64px;
  height: 85px;
  flex-shrink: 0;
  border-radius: 6px;
  overflow: hidden;
  background: #ece1d8;
  box-shadow: 2px 0 2px rgba(0,0,0,0.2);
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

.title-row {
  display: flex;
  align-items: flex-start;
  gap: 4px;
}

.titles {
  flex: 1;
  min-width: 0;
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
  display: inline-block;
  font-size: 10px;
  color: #8a6048;
  background: #f0ede8;
  padding: 2px 8px;
  border-radius: 4px;
  margin-top: 4px;
  max-width: 100%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-family: monospace;
}

.chapter-bubbles {
  display: flex;
  gap: 4px;
  margin-top: 4px;
  max-width: 100%;
  overflow: hidden;
}

.bubble {
  display: inline-block;
  font-size: 10px;
  color: #8a6048;
  background: #f0ede8;
  padding: 2px 6px;
  border-radius: 4px;
  font-family: monospace;
  white-space: nowrap;
  flex-shrink: 0;
}

.more-btn {
  position: absolute;
  right: 2px;
  top: 50%;
  transform: translateY(-50%);
  width: 28px;
  height: 28px;
  border: 0;
  background: transparent;
  color: #8a6048;
  font-size: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  cursor: pointer;
}

.more-btn:active {
  background: #f5d2bc;
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

.progress-fill.partial {
  background: linear-gradient(90deg, #fa9c69, #e08860);
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

.failed-count {
  color: #d9534f;
  margin-left: 8px;
  font-weight: 600;
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
  background: #fffbf8;
  color: #fa9c69;
}

.status-tag.paused {
  background: #fff8e1;
  color: #f0a030;
}

.status-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
}

.tag {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
}

.tag.completed {
  background: #eaf7ea;
  color: #52a86b;
}

.tag.failed {
  background: #ffeaea;
  color: #d9534f;
}

.size-text {
  font-size: 11px;
  color: #b89a84;
}

.size-standalone {
  margin-top: 4px;
}
</style>
