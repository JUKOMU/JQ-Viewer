<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <div class="toolbar-start">
          <MenuToggleButton/>
        </div>
        <div class="toolbar-title">下载管理</div>
      </IonToolbar>
    </IonHeader>
    <IonContent>
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
        <div class="section" v-if="activeTasks.length">
          <div class="section-title">下载中 ({{ activeTasks.length }})</div>
          <div v-for="task in activeTasks" :key="task.taskId" class="task-card">
            <DownloadTaskCard
              :task="task"
              :show-progress="true"
              @cancel="onCancel(task)"
              @pause="onPause(task)"
              @resume="onResume(task)"
            />
          </div>
        </div>

        <!-- 已完成 -->
        <div class="section" v-if="completedTasks.length">
          <div class="section-title">已完成 ({{ completedTasks.length }})</div>
          <div v-for="task in completedTasks" :key="task.taskId" class="task-card">
            <DownloadTaskCard
              :task="task"
              :show-progress="false"
              @read="onRead(task)"
              @delete="onDelete(task)"
              @retry="onRetry(task)"
            />
          </div>
        </div>

        <!-- 下载失败 -->
        <div class="section" v-if="failedTasks.length">
          <div class="section-title">下载失败 ({{ failedTasks.length }})</div>
          <div v-for="task in failedTasks" :key="task.taskId" class="task-card">
            <DownloadTaskCard
              :task="task"
              :show-progress="false"
              @delete="onDelete(task)"
              @retry="onRetry(task)"
            />
          </div>
        </div>
        <div class="bottom-spacer" />
      </template>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { IonContent, IonHeader, IonIcon, IonPage, IonToolbar, onIonViewWillEnter } from '@ionic/vue'
import { cloudDownloadOutline, saveOutline } from 'ionicons/icons'
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

const syncDownloadState = async () => {
  if (syncPromise) return syncPromise

  syncPromise = (async () => {
    // 全量同步
    try {
      const result = await JmcomicService.getDownloadTasks()
      tasks.value = result.tasks
      spaceUsedMb.value = Math.round(result.usedBytes / (1024 * 1024))
      spaceAvailMb.value = Math.round(result.availableBytes / (1024 * 1024))
      hasStorageInfo.value = true
      OfflineDownloadService.setAll(result.tasks)
    } catch {
      // 离线回退：使用 localStorage 缓存
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

onMounted(async () => {
  // 注册下载进度监听（先于 sync 注册，避免丢失事件）
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
      task.completedAt = Date.now()
      OfflineDownloadService.updateStatus(data.taskId, 'completed', data.downloadedPages, data.totalPages)
      void syncDownloadState()
    } else if (data.status === 'failed') {
      task.status = 'failed'
      task.downloadedPages = data.downloadedPages
      task.totalPages = data.totalPages
      task.error = data.error
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
    // 不删除已有文件——库的 ImageDownloadTask 会自动跳过已存在的文件，仅下载缺失图片
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

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: #8a6048;
  padding: 8px 4px 6px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.task-card {
  margin-bottom: 8px;
}

.bottom-spacer {
  height: 80px;
}
</style>
