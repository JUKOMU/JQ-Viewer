<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <div class="toolbar-start">
          <MenuToggleButton/>
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
        <div class="section" v-if="sortedActiveTasks.length">
          <div class="section-header">
            <span class="section-title">下载中 ({{ sortedActiveTasks.length }})</span>
          </div>
          <div v-for="task in sortedActiveTasks" :key="task.taskId" class="task-card">
            <DownloadTaskCard
              :task="task"
              :show-progress="true"
              @more="openActions(task)"
              @longpress="openActions(task)"
            />
          </div>
        </div>

        <!-- 已完成 -->
        <div class="section" v-if="sortedCompletedTasks.length">
          <div class="section-header">
            <span class="section-title">已完成 ({{ sortedCompletedTasks.length }})</span>
            <button class="clear-btn" @click="requestClear('completed')">清空</button>
          </div>
          <IonItemSliding v-for="task in sortedCompletedTasks" :key="task.taskId">
            <div class="task-card">
              <DownloadTaskCard
                :task="task"
                :show-progress="false"
                :disable-long-press="true"
                @click="onRead(task)"
                @more="openActions(task)"
                @longpress="openActions(task)"
              />
            </div>
            <IonItemOptions side="end">
              <IonItemOption color="danger" @click="onDelete(task)">删除</IonItemOption>
            </IonItemOptions>
          </IonItemSliding>
        </div>

        <!-- 下载失败 -->
        <div class="section" v-if="sortedFailedTasks.length">
          <div class="section-header">
            <span class="section-title">下载失败 ({{ sortedFailedTasks.length }})</span>
            <button class="clear-btn" @click="requestClear('failed')">清空</button>
          </div>
          <IonItemSliding v-for="task in sortedFailedTasks" :key="task.taskId">
            <div class="task-card">
              <DownloadTaskCard
                :task="task"
                :show-progress="false"
                :disable-long-press="true"
                @more="openActions(task)"
                @longpress="openActions(task)"
              />
            </div>
            <IonItemOptions side="end">
              <IonItemOption color="danger" @click="onDelete(task)">删除</IonItemOption>
            </IonItemOptions>
          </IonItemSliding>
        </div>
        <div class="bottom-spacer" />
      </template>
    </IonContent>

    <!-- 操作菜单 -->
    <IonActionSheet
      :is-open="isActionSheetOpen"
      :header="selectedTask?.chapterTitle || ''"
      :buttons="actionSheetButtons"
      @did-dismiss="isActionSheetOpen = false; selectedTask = null"
    />

    <!-- 清空确认对话框 -->
    <IonAlert
      :is-open="isClearAlertOpen"
      header="确认清空"
      :message="clearAlertMessage"
      :buttons="[
        { text: '取消', role: 'cancel' },
        { text: '确定清空', role: 'destructive', handler: executeClear }
      ]"
      @did-dismiss="isClearAlertOpen = false"
    />
  </IonPage>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  IonActionSheet,
  IonAlert,
  IonContent,
  IonHeader,
  IonIcon,
  IonItemOption,
  IonItemOptions,
  IonItemSliding,
  IonPage,
  IonRefresher,
  IonRefresherContent,
  IonToolbar,
  onIonViewWillEnter,
} from '@ionic/vue'
import { cloudDownloadOutline, saveOutline, textOutline, timeOutline } from 'ionicons/icons'
import type { PluginListenerHandle } from '@capacitor/core'
import MenuToggleButton from '@/components/common/MenuToggleButton.vue'
import DownloadTaskCard from '@/components/download/DownloadTaskCard.vue'
import { JmcomicService, showToast } from '@/services/JmcomicService'
import { OfflineDownloadService } from '@/services/OfflineDownloadService'
import type { DownloadTask } from '@/services/JmcomicTypes'

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
const sortIcon = computed(() => sortMode.value === 'time' ? timeOutline : textOutline)

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
const isActionSheetOpen = ref(false)

const openActions = (task: DownloadTask) => {
  selectedTask.value = task
  isActionSheetOpen.value = true
}

const actionSheetButtons = computed(() => {
  const t = selectedTask.value
  if (!t) return []

  const buttons: any[] = []

  if (t.status === 'completed') {
    buttons.push({
      text: '阅读',
      handler: () => onRead(t),
    })
  }

  buttons.push({
    text: '进入详情页',
    handler: () => onGoToAlbumDetail(t),
  })

  if (t.status === 'failed') {
    buttons.push({
      text: t.error?.includes('张图片下载失败') ? '重新下载失败图片' : '重试',
      handler: () => onRetry(t),
    })
  }

  if (t.status === 'paused') {
    buttons.push({
      text: '继续',
      handler: () => onResume(t),
    })
  }

  if (t.status === 'downloading') {
    buttons.push({
      text: '暂停',
      handler: () => onPause(t),
    })
  }

  if (t.status === 'queued' || t.status === 'downloading' || t.status === 'paused') {
    buttons.push({
      text: '取消',
      role: 'destructive',
      handler: () => onCancel(t),
    })
  }

  if (t.status === 'completed' || t.status === 'failed') {
    buttons.push({
      text: '删除',
      role: 'destructive',
      handler: () => onDelete(t),
    })
  }

  buttons.push({
    text: '取消',
    role: 'cancel',
  })

  return buttons
})

const hasTasks = computed(() => tasks.value.length > 0)

const activeTasks = computed(() =>
  tasks.value.filter(t => t.status === 'queued' || t.status === 'downloading' || t.status === 'paused')
)

const completedTasks = computed(() =>
  tasks.value.filter(t => t.status === 'completed')
)

const failedTasks = computed(() =>
  tasks.value.filter(t => t.status === 'failed')
)

const sortedActiveTasks = computed(() => sortTasks(activeTasks.value))
const sortedCompletedTasks = computed(() => sortTasks(completedTasks.value))
const sortedFailedTasks = computed(() => sortTasks(failedTasks.value))

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
    const task = tasks.value.find(t => t.taskId === data.taskId)
    if (!task) {
      void syncDownloadState()
      return
    }

    if (data.status === 'downloading') {
      task.downloadedPages = data.downloadedPages
      task.totalPages = data.totalPages
      task.status = 'downloading'
      task.speed = data.speed
      OfflineDownloadService.updateProgress(data.taskId, data.downloadedPages, data.totalPages)
    } else if (data.status === 'paused') {
      task.downloadedPages = data.downloadedPages
      task.status = 'paused'
      OfflineDownloadService.updateStatus(data.taskId, 'paused', data.downloadedPages, task.totalPages)
    } else if (data.status === 'completed') {
      task.status = 'completed'
      task.downloadedPages = data.downloadedPages
      task.totalPages = data.totalPages
      task.totalSize = data.totalSize
      task.completedAt = Date.now()
      OfflineDownloadService.updateStatus(data.taskId, 'completed', data.downloadedPages, data.totalPages)
      void syncDownloadState()
    } else if (data.status === 'failed') {
      task.status = 'failed'
      task.downloadedPages = data.downloadedPages
      task.totalPages = data.totalPages
      task.error = data.error
      task.totalSize = data.totalSize
      OfflineDownloadService.updateStatus(data.taskId, 'failed', data.downloadedPages, data.totalPages, data.error)
    }
  }).then((handle) => {
    downloadProgressHandle = handle
  }).catch(() => {})

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
    tasks.value = tasks.value.filter(t => t.taskId !== task.taskId)
    OfflineDownloadService.removeTask(task.taskId)
    void syncDownloadState()
  } catch (e: any) {
    await showToast(e?.message || '取消失败', 'danger')
  }
}

const onPause = async (task: DownloadTask) => {
  try {
    await JmcomicService.pauseDownload(task.taskId)
  } catch (e: any) {
    await showToast(e?.message || '暂停失败', 'danger')
  }
}

const onResume = async (task: DownloadTask) => {
  try {
    await JmcomicService.resumeDownload(task.taskId)
  } catch (e: any) {
    await showToast(e?.message || '继续失败', 'danger')
  }
}

const onRetry = async (task: DownloadTask) => {
  try {
    const { taskId } = await JmcomicService.downloadChapter(
      task.albumId, task.chapterId,
      task.albumTitle, task.chapterTitle, task.coverUrl,
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
    tasks.value = [newTask, ...tasks.value.filter(t => t.taskId !== task.taskId)]
    OfflineDownloadService.removeTask(task.taskId)
    OfflineDownloadService.addTask(newTask)
    void syncDownloadState()
  } catch (e: any) {
    await showToast(e?.message || '重试失败', 'danger')
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

const onGoToAlbumDetail = (task: DownloadTask) => {
  void router.push(`/album/${task.albumId}`)
}

const onDelete = async (task: DownloadTask) => {
  try {
    await JmcomicService.deleteDownloaded(task.albumId, task.chapterId)
    tasks.value = tasks.value.filter(t => t.taskId !== task.taskId)
    OfflineDownloadService.removeTask(task.taskId)
    void syncDownloadState()
  } catch (e: any) {
    await showToast(e?.message || '删除失败', 'danger')
  }
}

// 清空确认
const isClearAlertOpen = ref(false)
const clearTarget = ref<'completed' | 'failed'>('completed')

const clearAlertMessage = computed(() =>
  clearTarget.value === 'completed'
    ? `将删除所有已完成任务的文件和记录（${completedTasks.value.length} 个），此操作不可恢复。`
    : `将删除所有失败任务的文件和记录（${failedTasks.value.length} 个），此操作不可恢复。`
)

const requestClear = (target: 'completed' | 'failed') => {
  clearTarget.value = target
  isClearAlertOpen.value = true
}

const executeClear = () => {
  if (clearTarget.value === 'completed') clearCompleted()
  else clearFailed()
}

const clearCompleted = async () => {
  const list = completedTasks.value
  if (!list.length) return
  await Promise.all(list.map(task =>
    JmcomicService.deleteDownloaded(task.albumId, task.chapterId).catch(() => {})
  ))
  await syncDownloadState()  // 以 DB 为权威源，同时更新 tasks + OfflineDownloadService
}

const clearFailed = async () => {
  const list = failedTasks.value
  if (!list.length) return
  await Promise.all(list.map(task =>
    JmcomicService.deleteDownloaded(task.albumId, task.chapterId).catch(() => {})
  ))
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

:deep(ion-item-sliding) {
  --background: transparent;
  background: transparent;
  border-radius: 12px;
  margin-bottom: 8px;
}
</style>
