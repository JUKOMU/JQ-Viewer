<template>
  <div v-if="isActive && visibleCards.length" class="notification-center" aria-live="polite">
    <div
      v-for="card in visibleCards"
      :key="card.taskId"
      role="button"
      tabindex="0"
      class="notification-card"
      :class="card.status"
      @click="openDownloadPage"
      @keydown.enter="openDownloadPage"
      @keydown.space.prevent="openDownloadPage"
    >
      <div class="notification-main">
        <div class="notification-title">{{ cardTitle(card) }}</div>
        <div class="notification-meta">{{ cardMeta(card) }}</div>
        <div v-if="card.totalPages > 0" class="notification-progress">
          <span :style="{width: `${progressPercent(card)}%`}"></span>
        </div>
      </div>
      <button
        v-if="card.status !== 'downloading'"
        type="button"
        class="notification-close"
        aria-label="关闭通知"
        @click.stop="dismiss(card.taskId)"
      >
        ×
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import {computed, onMounted, onUnmounted, ref} from 'vue'
import {useRouter} from 'vue-router'
import {isDesktopRuntime} from '@/platform/runtime'
import {JmcomicService} from '@/services/JmcomicService'
import type {DownloadProgressEvent, DownloadStatus, DownloadTask} from '@/services/JmcomicTypes'
import type {PlatformListenerHandle} from '@/services/platform/EventPort'

defineOptions({name: 'InAppNotificationCenter'})

type Card = {
  taskId: string
  albumTitle: string
  chapterTitle: string
  downloadedPages: number
  totalPages: number
  status: DownloadStatus
  error?: string
  updatedAt: number
}

const router = useRouter()
const isActive = isDesktopRuntime
const cards = ref<Card[]>([])
let listenerHandle: PlatformListenerHandle | null = null
let refreshTimer: number | null = null
const dismissTimers = new Map<string, number>()

const visibleCards = computed(() =>
  [...cards.value]
    .sort((a, b) => b.updatedAt - a.updatedAt)
    .slice(0, 4),
)

onMounted(async () => {
  if (!isActive) return
  await refreshTasks()
  listenerHandle = await JmcomicService.addDownloadProgressListener((event) => {
    applyProgress(event)
    scheduleRefresh()
  }).catch(() => null)
})

onUnmounted(() => {
  listenerHandle?.remove()
  listenerHandle = null
  if (refreshTimer != null) {
    window.clearTimeout(refreshTimer)
    refreshTimer = null
  }
  for (const timer of dismissTimers.values()) {
    window.clearTimeout(timer)
  }
  dismissTimers.clear()
})

function applyProgress(event: DownloadProgressEvent) {
  const existing = cards.value.find((card) => card.taskId === event.taskId)
  const next: Card = {
    taskId: event.taskId,
    albumTitle: event.albumTitle ?? existing?.albumTitle ?? event.albumId,
    chapterTitle: event.chapterTitle ?? existing?.chapterTitle ?? event.chapterId,
    downloadedPages: event.downloadedPages,
    totalPages: event.totalPages,
    status: event.status,
    error: event.error,
    updatedAt: Date.now(),
  }
  cards.value = [next, ...cards.value.filter((card) => card.taskId !== event.taskId)]
  if (event.status === 'completed' || event.status === 'failed' || event.status === 'cancelled') {
    scheduleDismiss(event.taskId)
  }
}

async function refreshTasks() {
  try {
    const result = await JmcomicService.getDownloadTasks()
    const taskMap = new Map(result.tasks.map((task) => [task.taskId, task]))
    cards.value = cards.value
      .map((card) => taskMap.has(card.taskId) ? fromTask(taskMap.get(card.taskId)!) : card)
      .filter((card) => card.status !== 'cancelled')

    const activeTasks = result.tasks.filter((task) =>
      task.status === 'queued' || task.status === 'downloading' || task.status === 'paused',
    )
    for (const task of activeTasks) {
      if (!cards.value.some((card) => card.taskId === task.taskId)) {
        cards.value.push(fromTask(task))
      }
    }
  } catch {
    // 下载页仍会走自己的同步兜底，通知中心不阻塞应用。
  }
}

function fromTask(task: DownloadTask): Card {
  return {
    taskId: task.taskId,
    albumTitle: task.albumTitle,
    chapterTitle: task.chapterTitle,
    downloadedPages: task.downloadedPages,
    totalPages: task.totalPages,
    status: task.status,
    error: task.error,
    updatedAt: Date.now(),
  }
}

function scheduleRefresh() {
  if (refreshTimer != null) return
  refreshTimer = window.setTimeout(() => {
    refreshTimer = null
    void refreshTasks()
  }, 250)
}

function scheduleDismiss(taskId: string) {
  const oldTimer = dismissTimers.get(taskId)
  if (oldTimer != null) {
    window.clearTimeout(oldTimer)
  }
  dismissTimers.set(taskId, window.setTimeout(() => dismiss(taskId), 6000))
}

function dismiss(taskId: string) {
  const timer = dismissTimers.get(taskId)
  if (timer != null) {
    window.clearTimeout(timer)
    dismissTimers.delete(taskId)
  }
  cards.value = cards.value.filter((card) => card.taskId !== taskId)
}

function openDownloadPage() {
  void router.push('/download')
}

function progressPercent(card: Card) {
  if (card.totalPages <= 0) return 0
  return Math.max(0, Math.min(100, Math.round(card.downloadedPages * 100 / card.totalPages)))
}

function cardTitle(card: Card) {
  return card.chapterTitle || card.albumTitle || card.taskId
}

function cardMeta(card: Card) {
  const count = card.totalPages > 0 ? `${card.downloadedPages}/${card.totalPages}` : '准备中'
  switch (card.status) {
    case 'queued':
      return `排队中 · ${count}`
    case 'downloading':
      return `正在下载 · ${count}`
    case 'paused':
      return `已暂停 · ${count}`
    case 'completed':
      return `已完成 · ${count}`
    case 'failed':
      return card.error ? `失败 · ${card.error}` : `失败 · ${count}`
    case 'cancelled':
      return '已取消'
    default:
      return count
  }
}
</script>

<style scoped>
.notification-center {
  position: fixed;
  right: 18px;
  bottom: 18px;
  z-index: 2147483000;
  display: grid;
  gap: 10px;
  width: min(360px, calc(100vw - 28px));
  pointer-events: none;
}

.notification-card {
  width: 100%;
  min-height: 78px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 28px;
  gap: 8px;
  align-items: start;
  padding: 12px 10px 10px 14px;
  border: 1px solid rgb(245 210 188 / 0.72);
  border-radius: 8px;
  background: #fffaf6;
  color: #4c2a18;
  box-shadow: 0 10px 28px rgb(74 46 28 / 0.18);
  text-align: left;
  cursor: pointer;
  pointer-events: auto;
}

.notification-card.downloading {
  border-color: rgb(250 156 105 / 0.9);
}

.notification-card.failed {
  border-color: rgb(217 83 79 / 0.65);
}

.notification-main {
  min-width: 0;
}

.notification-title {
  overflow: hidden;
  color: #3d2113;
  font-size: 13px;
  font-weight: 650;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.notification-meta {
  overflow: hidden;
  margin-top: 5px;
  color: #8a6048;
  font-size: 12px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.notification-progress {
  height: 5px;
  overflow: hidden;
  margin-top: 10px;
  border-radius: 999px;
  background: rgb(245 210 188 / 0.75);
}

.notification-progress span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: #fa9c69;
  transition: width 0.18s ease;
}

.notification-close {
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #8a6048;
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
}

.notification-close:active {
  background: #f5d2bc;
}

@media (max-width: 640px) {
  .notification-center {
    right: 14px;
    bottom: 14px;
  }
}
</style>
