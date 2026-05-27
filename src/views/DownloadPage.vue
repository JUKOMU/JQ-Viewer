<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <div class="toolbar-start">
          <MenuToggleButton />
        </div>
        <div class="toolbar-title">下载管理</div>
        <div class="toolbar-end">
          <button v-if="hasTasks" class="sort-btn" @click="toggleSort">
            <IonIcon :icon="sortIcon" />
          </button>
        </div>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <!-- 下拉刷新 -->
      <IonRefresher slot="fixed" @ion-refresh="onRefresh($event)">
        <IonRefresherContent />
      </IonRefresher>

      <!-- 空状态 -->
      <div v-if="!hasTasks" class="empty-state">
        <IonIcon :icon="cloudDownloadOutline" class="empty-icon" />
        <p>暂无下载内容</p>
      </div>

      <template v-else>
        <!-- 存储信息 -->
        <div v-if="hasStorageInfo" class="storage-bar">
          <IonIcon :icon="saveOutline" class="storage-icon" />
          <span>已用 {{ spaceUsedMb }}MB / 可用 {{ spaceAvailMb }}MB</span>
        </div>

        <!-- 下载中 -->
        <div v-if="sortedActiveTasks.length" class="section">
          <div class="section-header">
            <span class="section-title">下载中 ({{ sortedActiveTasks.length }})</span>
          </div>
          <div v-for="task in sortedActiveTasks" :key="task.taskId" class="task-card">
            <DownloadTaskCard
              :task="task"
              :show-progress="true"
              @more="openActions(task, $event)"
            />
          </div>
        </div>

        <!-- 已完成 -->
        <div v-if="completedGroups.length" class="section">
          <div class="section-header">
            <span class="section-title">已完成 ({{ completedGroups.length }})</span>
            <button class="clear-btn" @click="requestClear('completed')">清空</button>
          </div>
          <div v-for="group in completedGroups" :key="group.albumId" class="task-card">
            <DownloadTaskCard
              :task="group.chapters[0]"
              :show-progress="false"
              :total-size="group.totalSize"
              :downloaded-chapters="group.type === 'multi' ? group.chapters : undefined"
              @click="onReadGroup(group)"
              @more="openGroupActions(group, $event)"
            />
          </div>
        </div>

        <!-- 下载失败 -->
        <div v-if="sortedFailedTasks.length" class="section">
          <div class="section-header">
            <span class="section-title">下载失败 ({{ sortedFailedTasks.length }})</span>
            <button class="clear-btn" @click="requestClear('failed')">清空</button>
          </div>
          <div v-for="task in sortedFailedTasks" :key="task.taskId" class="task-card">
            <DownloadTaskCard
              :task="task"
              :show-progress="false"
              @more="openActions(task, $event)"
            />
          </div>
        </div>
        <div class="bottom-spacer" />
      </template>
    </IonContent>

    <!-- 操作菜单（IonPopover） -->
    <IonPopover
      :is-open="isPopoverOpen"
      :event="popoverEvent"
      @did-dismiss="
        (() => {
          isPopoverOpen = false
          if (!isDeleteAlertOpen) {
            selectedTask = null
            selectedGroup = null
            popoverEvent = null
          }
        })()
      "
    >
      <IonContent class="popover-content">
        <div class="popover-header">{{ popoverTitle }}</div>

        <button
          v-if="selectedTask?.status === 'completed'"
          class="popover-btn"
          @click="popoverAction('read')"
        >
          <IonIcon :icon="bookOutline" />
          {{ selectedGroup?.type === 'multi' ? '选择章节' : '阅读' }}
        </button>

        <button class="popover-btn" @click="popoverAction('detail')">
          <IonIcon :icon="informationCircleOutline" />
          进入详情页
        </button>

        <button
          v-if="selectedTask?.status === 'failed'"
          class="popover-btn"
          @click="popoverAction('retry')"
        >
          <IonIcon :icon="refreshOutline" />
          {{ selectedTask?.error?.includes('张图片下载失败') ? '重新下载失败图片' : '重试' }}
        </button>

        <button
          v-if="selectedTask?.status === 'paused'"
          class="popover-btn"
          @click="popoverAction('resume')"
        >
          <IonIcon :icon="playOutline" />
          继续
        </button>

        <button
          v-if="selectedTask?.status === 'downloading'"
          class="popover-btn"
          @click="popoverAction('pause')"
        >
          <IonIcon :icon="pauseOutline" />
          暂停
        </button>

        <button
          v-if="cancelableStatuses.includes(selectedTask?.status ?? '')"
          class="popover-btn danger"
          @click="popoverAction('cancel')"
        >
          <IonIcon :icon="closeCircleOutline" />
          取消
        </button>

        <button
          v-if="deletableStatuses.includes(selectedTask?.status ?? '')"
          class="popover-btn danger"
          @click="popoverAction('delete')"
        >
          <IonIcon :icon="trashOutline" />
          删除
        </button>
      </IonContent>
    </IonPopover>

    <!-- 删除确认对话框 -->
    <IonAlert
      :is-open="isDeleteAlertOpen"
      header="确认删除"
      :message="deleteAlertMessage"
      :buttons="[
        { text: '取消', role: 'cancel' },
        { text: '删除', role: 'destructive', handler: confirmDelete },
      ]"
      @did-dismiss="isDeleteAlertOpen = false"
    />

    <!-- 清空确认对话框 -->
    <IonAlert
      :is-open="isClearAlertOpen"
      header="确认清空"
      :message="clearAlertMessage"
      :buttons="[
        { text: '取消', role: 'cancel' },
        { text: '确定清空', role: 'destructive', handler: executeClear },
      ]"
      @did-dismiss="isClearAlertOpen = false"
    />
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({ name: 'DownloadPage' })

import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  IonAlert,
  IonContent,
  IonHeader,
  IonIcon,
  IonPage,
  IonPopover,
  IonRefresher,
  IonRefresherContent,
  IonToolbar,
  onIonViewWillEnter,
} from '@ionic/vue'
import {
  bookOutline,
  closeCircleOutline,
  cloudDownloadOutline,
  informationCircleOutline,
  pauseOutline,
  playOutline,
  refreshOutline,
  saveOutline,
  textOutline,
  timeOutline,
  trashOutline,
} from 'ionicons/icons'
import type { PluginListenerHandle } from '@capacitor/core'
import MenuToggleButton from '@/components/common/MenuToggleButton.vue'
import DownloadTaskCard from '@/components/download/DownloadTaskCard.vue'
import { JmcomicService, sanitizeError, showToast } from '@/services/JmcomicService'
import { OfflineDownloadService } from '@/services/OfflineDownloadService'
import type { CompletedGroup, DownloadTask } from '@/services/JmcomicTypes'

const router = useRouter()
const tasks = ref<DownloadTask[]>([])
const spaceUsedMb = ref(0)
const spaceAvailMb = ref(0)
const hasStorageInfo = ref(false)
let downloadProgressHandle: PluginListenerHandle | null = null
let syncPromise: Promise<void> | null = null

// 排序
type SortMode = 'time' | 'title'
const sortMode = ref<SortMode>('time')
const sortIcon = computed(() => (sortMode.value === 'time' ? timeOutline : textOutline))

const toggleSort = () => {
  sortMode.value = sortMode.value === 'time' ? 'title' : 'time'
}

const sortTasks = (list: DownloadTask[]) => {
  if (sortMode.value === 'title') {
    return [...list].sort((a, b) => a.albumTitle.localeCompare(b.albumTitle, 'zh'))
  }
  return [...list].sort((a, b) => b.createdAt - a.createdAt)
}

// 操作菜单
const selectedTask = ref<DownloadTask | null>(null)
const selectedGroup = ref<CompletedGroup | null>(null)
const isPopoverOpen = ref(false)
const popoverEvent = ref<Event | null>(null)

const cancelableStatuses = ['queued', 'downloading', 'paused']
const deletableStatuses = ['completed', 'failed']

const popoverTitle = computed(() => {
  if (selectedGroup.value?.type === 'multi') {
    return selectedGroup.value.albumTitle
  }
  return selectedTask.value?.chapterTitle ?? ''
})

const openActions = (task: DownloadTask, event: Event) => {
  selectedTask.value = task
  selectedGroup.value = null
  popoverEvent.value = event
  isPopoverOpen.value = true
}

const openGroupActions = (group: CompletedGroup, event: Event) => {
  selectedTask.value = group.chapters[0]
  selectedGroup.value = group
  popoverEvent.value = event
  isPopoverOpen.value = true
}

const popoverAction = (action: string) => {
  isPopoverOpen.value = false
  const t = selectedTask.value
  if (!t) return

  switch (action) {
    case 'read':
      if (selectedGroup.value?.type === 'multi') {
        onOpenChapterSelect(selectedGroup.value)
      } else {
        onRead(t)
      }
      break
    case 'detail':
      onGoToAlbumDetail(t)
      break
    case 'retry':
      onRetry(t)
      break
    case 'resume':
      onResume(t)
      break
    case 'pause':
      onPause(t)
      break
    case 'cancel':
      onCancel(t)
      break
    case 'delete':
      requestDeleteConfirm()
      break
  }
}

// 删除确认
const isDeleteAlertOpen = ref(false)

const deleteAlertMessage = computed(() => {
  const g = selectedGroup.value
  if (g?.type === 'multi') {
    return `将删除「${g.albumTitle}」全部已下载章节（${g.chapters.length}个），此操作不可恢复。`
  }
  const t = selectedTask.value
  if (!t) return ''
  return `将删除「${t.chapterTitle}」的下载文件和记录，此操作不可恢复。`
})

const requestDeleteConfirm = () => {
  isDeleteAlertOpen.value = true
}

const confirmDelete = () => {
  const g = selectedGroup.value
  if (g?.type === 'multi') {
    onDeleteGroup(g)
  } else if (selectedTask.value) {
    onDelete(selectedTask.value)
  }
  selectedTask.value = null
  selectedGroup.value = null
  popoverEvent.value = null
}

const hasTasks = computed(() => tasks.value.length > 0)

const activeTasks = computed(() =>
  tasks.value.filter(
    (t) => t.status === 'queued' || t.status === 'downloading' || t.status === 'paused',
  ),
)

const completedTasks = computed(() => tasks.value.filter((t) => t.status === 'completed'))

const failedTasks = computed(() => tasks.value.filter((t) => t.status === 'failed'))

const sortedActiveTasks = computed(() => sortTasks(activeTasks.value))
const sortedFailedTasks = computed(() => sortTasks(failedTasks.value))

const completedGroups = computed<CompletedGroup[]>(() => {
  const byAlbum = new Map<string, DownloadTask[]>()
  for (const t of completedTasks.value) {
    const list = byAlbum.get(t.albumId)
    if (list) {
      list.push(t)
    } else {
      byAlbum.set(t.albumId, [t])
    }
  }
  const groups: CompletedGroup[] = []
  for (const [albumId, chapters] of byAlbum) {
    chapters.sort((a, b) => (a.chapterSortOrder ?? 0) - (b.chapterSortOrder ?? 0))
    groups.push({
      type: chapters.length > 1 ? 'multi' : 'single',
      albumId,
      albumTitle: chapters[0].albumTitle,
      coverUrl: chapters[0].coverUrl,
      chapters,
      totalSize: chapters.reduce((s, c) => s + (c.totalSize ?? 0), 0),
    })
  }
  return sortMode.value === 'title'
    ? groups.sort((a, b) => a.albumTitle.localeCompare(b.albumTitle, 'zh'))
    : groups.sort(
        (a, b) =>
          Math.max(...b.chapters.map((c) => c.createdAt)) -
          Math.max(...a.chapters.map((c) => c.createdAt)),
      )
})

const syncDownloadState = async () => {
  if (syncPromise) return syncPromise

  syncPromise = (async () => {
    try {
      const result = await JmcomicService.getDownloadTasks()
      tasks.value = result.tasks
      spaceUsedMb.value = Math.round(result.usedBytes / (1024 * 1024))
      spaceAvailMb.value = Math.round(result.availableBytes / (1024 * 1024))
      hasStorageInfo.value = true
      OfflineDownloadService.setAll(result.tasks)
    } catch {
      tasks.value = OfflineDownloadService.getAll()
      hasStorageInfo.value = false
    }
  })()

  try {
    await syncPromise
  } finally {
    syncPromise = null
  }
}

const onRefresh = async (event: CustomEvent) => {
  await syncDownloadState()
  ;(event.target as HTMLIonRefresherElement).complete()
}

onMounted(async () => {
  JmcomicService.addDownloadProgressListener((data) => {
    const task = tasks.value.find((t) => t.taskId === data.taskId)
    if (!task) {
      void syncDownloadState()
      return
    }

    if (data.status === 'downloading') {
      task.downloadedPages = data.downloadedPages
      task.totalPages = data.totalPages
      task.status = 'downloading'
      if (data.speed > 0) {
        task.speed = data.speed
      }
      OfflineDownloadService.updateProgress(data.taskId, data.downloadedPages, data.totalPages)
    } else if (data.status === 'paused') {
      task.downloadedPages = data.downloadedPages
      task.status = 'paused'
      OfflineDownloadService.updateStatus(
        data.taskId,
        'paused',
        data.downloadedPages,
        task.totalPages,
      )
    } else if (data.status === 'completed') {
      task.status = 'completed'
      task.downloadedPages = data.downloadedPages
      task.totalPages = data.totalPages
      task.totalSize = data.totalSize
      task.completedAt = Date.now()
      OfflineDownloadService.updateStatus(
        data.taskId,
        'completed',
        data.downloadedPages,
        data.totalPages,
      )
      void syncDownloadState()
    } else if (data.status === 'failed') {
      task.status = 'failed'
      task.downloadedPages = data.downloadedPages
      task.totalPages = data.totalPages
      task.error = data.error
      task.totalSize = data.totalSize
      OfflineDownloadService.updateStatus(
        data.taskId,
        'failed',
        data.downloadedPages,
        data.totalPages,
        data.error,
      )
    }
  })
    .then((handle) => {
      downloadProgressHandle = handle
    })
    .catch(() => {})

  await syncDownloadState()
})

onIonViewWillEnter(() => {
  void syncDownloadState()
})

onUnmounted(() => {
  downloadProgressHandle?.remove()
})

const onCancel = async (task: DownloadTask) => {
  try {
    await JmcomicService.cancelDownload(task.taskId)
    tasks.value = tasks.value.filter((t) => t.taskId !== task.taskId)
    OfflineDownloadService.removeTask(task.taskId)
    void syncDownloadState()
  } catch (e: any) {
    await showToast(sanitizeError(e, '取消失败'), 'danger')
  }
}

const onPause = async (task: DownloadTask) => {
  try {
    await JmcomicService.pauseDownload(task.taskId)
  } catch (e: any) {
    await showToast(sanitizeError(e, '暂停失败'), 'danger')
  }
}

const onResume = async (task: DownloadTask) => {
  try {
    await JmcomicService.resumeDownload(task.taskId)
  } catch (e: any) {
    await showToast(sanitizeError(e, '继续失败'), 'danger')
  }
}

const onRetry = async (task: DownloadTask) => {
  try {
    const { taskId } = await JmcomicService.downloadChapter(
      task.albumId,
      task.chapterId,
      task.albumTitle,
      task.chapterTitle,
      task.coverUrl,
    )
    const newTask: DownloadTask = {
      ...task,
      taskId,
      status: 'queued',
      totalPages: 0,
      downloadedPages: 0,
      error: undefined,
      totalSize: undefined,
      createdAt: Date.now(),
      completedAt: undefined,
    }
    tasks.value = [newTask, ...tasks.value.filter((t) => t.taskId !== task.taskId)]
    OfflineDownloadService.removeTask(task.taskId)
    OfflineDownloadService.addTask(newTask)
    void syncDownloadState()
  } catch (e: any) {
    await showToast(sanitizeError(e, '重试失败'), 'danger')
  }
}

const onRead = (task: DownloadTask) => {
  void router.push({
    path: `/album/${task.albumId}/read/${task.chapterId}`,
    query: {
      title: task.chapterTitle,
      total: String(task.totalPages),
      source: 'download',
    },
  })
}

const onReadGroup = (group: CompletedGroup) => {
  if (group.type === 'multi') {
    onOpenChapterSelect(group)
  } else {
    onRead(group.chapters[0])
  }
}

const onOpenChapterSelect = (group: CompletedGroup) => {
  void router.push(`/album/${group.albumId}/download-chapters`)
}

const onGoToAlbumDetail = (task: DownloadTask) => {
  void router.push(`/album/${task.albumId}`)
}

const onDelete = async (task: DownloadTask) => {
  try {
    await JmcomicService.deleteDownloaded(task.albumId, task.chapterId)
    tasks.value = tasks.value.filter((t) => t.taskId !== task.taskId)
    OfflineDownloadService.removeTask(task.taskId)
    void syncDownloadState()
  } catch (e: any) {
    await showToast(sanitizeError(e, '删除失败'), 'danger')
  }
}

const onDeleteGroup = async (group: CompletedGroup) => {
  try {
    await Promise.all(
      group.chapters.map((ch) =>
        JmcomicService.deleteDownloaded(ch.albumId, ch.chapterId).catch(() => {}),
      ),
    )
    const taskIds = new Set(group.chapters.map((ch) => ch.taskId))
    tasks.value = tasks.value.filter((t) => !taskIds.has(t.taskId))
    for (const ch of group.chapters) {
      OfflineDownloadService.removeTask(ch.taskId)
    }
    void syncDownloadState()
  } catch (e: any) {
    await showToast(sanitizeError(e, '删除失败'), 'danger')
  }
}

// 清空确认
const isClearAlertOpen = ref(false)
const clearTarget = ref<'completed' | 'failed'>('completed')

const clearAlertMessage = computed(() =>
  clearTarget.value === 'completed'
    ? `将删除所有已完成任务的文件和记录（${completedTasks.value.length} 个），此操作不可恢复。`
    : `将删除所有失败任务的文件和记录（${failedTasks.value.length} 个），此操作不可恢复。`,
)

const requestClear = (target: 'completed' | 'failed') => {
  clearTarget.value = target
  isClearAlertOpen.value = true
}

const executeClear = async () => {
  if (clearTarget.value === 'completed') await clearCompleted()
  else await clearFailed()
}

const clearCompleted = async () => {
  const list = completedTasks.value
  if (!list.length) return
  await Promise.all(
    list.map((task) =>
      JmcomicService.deleteDownloaded(task.albumId, task.chapterId).catch(() => {}),
    ),
  )
  tasks.value = tasks.value.filter((t) => t.status !== 'completed')
  OfflineDownloadService.setAll(tasks.value)
  await syncDownloadState()
}

const clearFailed = async () => {
  const list = failedTasks.value
  if (!list.length) return
  await Promise.all(
    list.map((task) =>
      JmcomicService.deleteDownloaded(task.albumId, task.chapterId).catch(() => {}),
    ),
  )
  tasks.value = tasks.value.filter((t) => t.status !== 'failed')
  OfflineDownloadService.setAll(tasks.value)
  await syncDownloadState()
}
</script>

<style scoped>
.toolbar-start {
  padding: 0 0 8px 14px;
}

.toolbar-title {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  font-size: 16px;
  font-weight: 600;
  color: #4c2a18;
}

.toolbar-end {
  position: absolute;
  right: 12px;
  bottom: 6px;
}

.sort-btn {
  width: 32px;
  height: 32px;
  border: 0;
  background: transparent;
  color: #8a6048;
  font-size: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  cursor: pointer;
}

.sort-btn:active {
  background: #f5d2bc;
}

:deep(ion-header),
:deep(ion-toolbar) {
  overflow: visible;
}

:deep(ion-toolbar) {
  --min-height: auto;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding-top: 40vh;
  color: #b89a84;
}

.empty-icon {
  font-size: 56px;
  margin-bottom: 12px;
}

.empty-state p {
  margin: 0;
  font-size: 14px;
}

.storage-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  margin: 8px 14px;
  background: #fffaf6;
  border-radius: 10px;
  font-size: 13px;
  color: #8a6048;
  border: 1px solid rgb(245 210 188 / 0.5);
}

.storage-icon {
  font-size: 18px;
  color: #fa9c69;
}

.section {
  padding: 4px 14px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 4px 6px;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: #8a6048;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.clear-btn {
  font-size: 12px;
  border: 0;
  background: transparent;
  color: #d9534f;
  cursor: pointer;
  padding: 2px 8px;
  border-radius: 4px;
}

.clear-btn:active {
  background: #ffeaea;
}

.task-card {
  margin-bottom: 8px;
}

.bottom-spacer {
  height: 80px;
}

/* Popover 内容样式 */
.popover-content {
  --background: #fffaf6;
}

.popover-header {
  font-size: 13px;
  font-weight: 600;
  color: #4c2a18;
  padding: 10px 14px 6px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 220px;
}

.popover-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  border: 0;
  background: transparent;
  color: #4c2a18;
  font-size: 13px;
  padding: 10px 14px;
  cursor: pointer;
  text-align: left;
}

.popover-btn:active {
  background: #f5d2bc;
}

.popover-btn.danger {
  color: #d9534f;
}

.popover-btn.danger:active {
  background: #ffeaea;
}

.popover-btn ion-icon {
  font-size: 16px;
  width: 20px;
  text-align: center;
}
</style>
