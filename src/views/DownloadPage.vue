<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <div class="toolbar-start">
          <MenuToggleButton/>
        </div>
        <div class="toolbar-end">
          <div class="toolbar-action">
            <span class="toolbar-action-label">导入PDF</span>
            <button class="import-btn" @click="onImportPdf">
              <IonIcon :icon="cloudUploadOutline"/>
            </button>
          </div>
          <div v-if="hasTasks" class="toolbar-action">
            <span class="toolbar-action-label">{{ sortLabel }}</span>
            <button class="sort-btn" @click="toggleSort">
              <IonIcon :icon="sortIcon"/>
            </button>
          </div>
        </div>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <!-- 下拉刷新 -->
      <IonRefresher slot="fixed" @ion-refresh="onRefresh($event)">
        <IonRefresherContent/>
      </IonRefresher>

      <!-- 空状态 -->
      <div v-if="!hasTasks" class="empty-state">
        <IonIcon :icon="cloudDownloadOutline" class="empty-icon"/>
        <p>暂无下载内容</p>
      </div>

      <template v-else>
        <!-- 存储信息 -->
        <div v-if="hasStorageInfo" class="storage-bar">
          <IonIcon :icon="saveOutline" class="storage-icon"/>
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
          <div v-for="group in completedGroups" :key="group.albumId + '|' + group.chapters[0].source" class="task-card">
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
        <div class="bottom-spacer"/>
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
          v-if="selectedStatus === 'completed'"
          class="popover-btn"
          @click="popoverAction('read')"
        >
          <IonIcon :icon="bookOutline"/>
          {{ selectedGroup?.type === 'multi' ? '选择章节' : '阅读' }}
        </button>

        <button class="popover-btn" @click="popoverAction('detail')">
          <IonIcon :icon="informationCircleOutline"/>
          进入详情页
        </button>

        <button
          v-if="selectedStatus === 'completed' && !isSelectedPdf"
          class="popover-btn"
          @click="popoverAction('pdf')"
        >
          <IonIcon :icon="documentLockOutline"/>
          导出为PDF
        </button>

        <button
          v-if="selectedStatus === 'failed'"
          class="popover-btn"
          @click="popoverAction('retry')"
        >
          <IonIcon :icon="refreshOutline"/>
          {{ selectedError?.includes('张图片下载失败') ? '重新下载失败图片' : '重试' }}
        </button>

        <button
          v-if="selectedStatus === 'paused'"
          class="popover-btn"
          @click="popoverAction('resume')"
        >
          <IonIcon :icon="playOutline"/>
          继续
        </button>

        <button
          v-if="selectedStatus === 'downloading'"
          class="popover-btn"
          @click="popoverAction('pause')"
        >
          <IonIcon :icon="pauseOutline"/>
          暂停
        </button>

        <button
          v-if="cancelableStatuses.includes(selectedStatus ?? '')"
          class="popover-btn danger"
          @click="popoverAction('cancel')"
        >
          <IonIcon :icon="closeCircleOutline"/>
          取消
        </button>

        <button
          v-if="deletableStatuses.includes(selectedStatus ?? '') || isSelectedPdf"
          class="popover-btn danger"
          @click="popoverAction('delete')"
        >
          <IonIcon :icon="trashOutline"/>
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

    <!-- PDF导出底部面板 -->
    <PdfExportBottomSheet
      v-model="showPdfSheet"
      :chapters="chaptersForPdf"
      @confirm="onPdfExportConfirm"
    />

    <!-- 多章节删除底部面板 -->
    <DeleteChaptersBottomSheet
      v-model="showDeleteSheet"
      :album-title="deleteGroup?.albumTitle ?? ''"
      :chapters="deleteGroup?.chapters ?? []"
      @confirm="onDeleteSheetConfirm"
    />
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({name: 'DownloadPage'})

import {computed, onMounted, onUnmounted, ref} from 'vue'
import {useRouter} from 'vue-router'
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
  cloudUploadOutline,
  documentLockOutline,
  informationCircleOutline,
  pauseOutline,
  playOutline,
  refreshOutline,
  saveOutline,
  textOutline,
  timeOutline,
  trashOutline,
} from 'ionicons/icons'
import MenuToggleButton from '@/components/common/MenuToggleButton.vue'
import DownloadTaskCard from '@/components/download/DownloadTaskCard.vue'
import PdfExportBottomSheet from '@/components/download/PdfExportBottomSheet.vue'
import DeleteChaptersBottomSheet from '@/components/download/DeleteChaptersBottomSheet.vue'
import {JmcomicService, sanitizeError, showToast} from '@/services/JmcomicService'
import {alertController} from '@ionic/vue'
import {OfflineDownloadService} from '@/services/OfflineDownloadService'
import {PdfExportService} from '@/services/PdfExportService'
import type {AlbumDetail, CompletedEntry, CompletedGroup, DownloadTask, ImportedPdf, PdfExportTask} from '@/services/JmcomicTypes'
import {PdfImportService} from '@/services/PdfImportService'
import type {PlatformListenerHandle} from '@/services/platform/EventPort'
import {platformCapabilities} from '@/platform/activeCapabilities'

const router = useRouter()
const tasks = ref<DownloadTask[]>([])
const importedPdfs = ref<ImportedPdf[]>([])
const spaceUsedMb = ref(0)
const spaceAvailMb = ref(0)
const hasStorageInfo = ref(false)
let downloadProgressHandle: PlatformListenerHandle | null = null
let syncPromise: Promise<void> | null = null
let speedTimer: ReturnType<typeof setInterval> | null = null
let isUnmounted = false
let episodeTypeBackfillPromise: Promise<void> | null = null
const backfilledAlbumIds = new Set<string>()
const EPISODE_TYPE_BACKFILL_DELAY_MS = 500

interface SpeedSample {
  latestBytes: number
  sampledBytes: number
  sampledAt: number
  lastSpeed: number
}

const speedSamples = new Map<string, SpeedSample>()

const loadImportedPdfs = async () => {
  try {
    const result = await JmcomicService.getImportedPdfs()
    importedPdfs.value = result.pdfs
    void backfillMissingEpisodeTypes()
  } catch {
    // 离线时保留缓存数据
  }
}

// 排序
type SortMode = 'time' | 'title'
const sortMode = ref<SortMode>('time')
const sortIcon = computed(() => (sortMode.value === 'time' ? timeOutline : textOutline))
const sortLabel = computed(() => (sortMode.value === 'time' ? '时间排序' : '标题排序'))

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
const selectedTask = ref<DownloadTask | CompletedEntry | null>(null)
const selectedGroup = ref<CompletedGroup | null>(null)
const isPopoverOpen = ref(false)
const popoverEvent = ref<Event | null>(null)

const cancelableStatuses = ['queued', 'downloading', 'paused']
const deletableStatuses = ['completed', 'failed']

const isSelectedPdf = computed(() => {
  const t = selectedTask.value
  if (!t) return false
  return ('source' in t) && t.source === 'pdf-import'
})

const selectedStatus = computed(() => {
  const t = selectedTask.value
  if (!t) return null
  if ('source' in t) return 'completed'
  return t.status
})

const selectedError = computed(() => {
  const t = selectedTask.value
  if (!t || 'source' in t) return undefined
  return t.error
})

// PDF导出
const showPdfSheet = ref(false)
const chaptersForPdf = ref<DownloadTask[]>([])

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
      } else if (isSelectedPdf.value) {
        onRead(t)
      } else {
        onRead(t)
      }
      break
    case 'detail':
      onGoToAlbumDetail(t)
      break
    case 'retry':
      onRetry(t as DownloadTask)
      break
    case 'resume':
      onResume(t as DownloadTask)
      break
    case 'pause':
      onPause(t as DownloadTask)
      break
    case 'cancel':
      onCancel(t as DownloadTask)
      break
    case 'pdf':
      onOpenPdfSheet()
      break
    case 'delete':
      if (selectedGroup.value?.type === 'multi') {
        requestDeleteGroupConfirm(selectedGroup.value)
      } else if (isSelectedPdf.value) {
        requestDeletePdfConfirm()
      } else {
        requestDeleteConfirm()
      }
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
  if ('source' in t && t.source === 'pdf-import') {
    return `将删除「${t.chapterTitle}」的导入记录（不会删除原 PDF 文件），此操作不可恢复。`
  }
  return `将删除「${t.chapterTitle}」的下载文件和记录，此操作不可恢复。`
})

const requestDeleteConfirm = () => {
  isDeleteAlertOpen.value = true
}

const requestDeletePdfConfirm = () => {
  isDeleteAlertOpen.value = true
}

const showDeleteSheet = ref(false)
const deleteGroup = ref<CompletedGroup | null>(null)

const requestDeleteGroupConfirm = (group: CompletedGroup) => {
  deleteGroup.value = group
  showDeleteSheet.value = true
}

const confirmDelete = () => {
  if (isSelectedPdf.value) {
    void confirmDeletePdf()
    return
  }
  const g = selectedGroup.value
  if (g?.type === 'multi') {
    void deleteChapters(g.chapters)
  } else if (selectedTask.value) {
    onDelete(selectedTask.value as DownloadTask)
  }
  selectedTask.value = null
  selectedGroup.value = null
  popoverEvent.value = null
}

const confirmDeletePdf = async () => {
  const t = selectedTask.value
  if (!t || !('source' in t) || !t.pdfData) return
  try {
    await JmcomicService.deleteImportedPdf(t.pdfData.id)
    importedPdfs.value = importedPdfs.value.filter(
      (p) => p.id !== t.pdfData!.id,
    )
    selectedTask.value = null
    selectedGroup.value = null
    popoverEvent.value = null
    await showToast('已删除导入记录', 'success')
  } catch (e: any) {
    await showToast(sanitizeError(e, '删除失败'), 'danger')
  }
}

const hasTasks = computed(() => tasks.value.length > 0 || importedPdfs.value.length > 0)

const activeTasks = computed(() =>
  tasks.value.filter(
    (t) => t.status === 'queued' || t.status === 'downloading' || t.status === 'paused',
  ),
)

const isFullyDownloadedFailure = (task: DownloadTask) =>
  task.status === 'failed' &&
  task.totalPages > 0 &&
  task.downloadedPages >= task.totalPages

const completedTasks = computed(() =>
  tasks.value.filter((t) => t.status === 'completed' || isFullyDownloadedFailure(t)),
)

const failedTasks = computed(() =>
  tasks.value.filter((t) => t.status === 'failed' && !isFullyDownloadedFailure(t)),
)

const sortedActiveTasks = computed(() => sortTasks(activeTasks.value))
const sortedFailedTasks = computed(() => sortTasks(failedTasks.value))

// 将下载任务和导入 PDF 映射为统一的 CompletedEntry
const mapDownloadTaskToEntry = (t: DownloadTask): CompletedEntry => ({
  albumId: t.albumId,
  albumTitle: t.albumTitle,
  coverUrl: t.coverUrl,
  chapterId: t.chapterId,
  chapterTitle: t.chapterTitle,
  chapterSortOrder: t.chapterSortOrder ?? 0,
  isSingleEpisode: t.isSingleEpisode,
  authors: '',
  createdAt: t.createdAt,
  completedAt: t.completedAt ?? t.createdAt,
  totalSize: t.totalSize ?? 0,
  source: 'download',
  downloadTask: t,
})

const mapImportedPdfToEntry = (pdf: ImportedPdf): CompletedEntry => ({
  albumId: pdf.albumId,
  albumTitle: pdf.albumTitle || pdf.fileName || pdf.albumId,
  coverUrl: pdf.coverUrl,
  chapterId: pdf.chapterId || `import_pdf_${pdf.id}`,
  displayId: pdf.chapterId || pdf.albumId,
  chapterTitle: pdf.chapterTitle || pdf.fileName,
  chapterSortOrder: pdf.chapterSortOrder,
  isSingleEpisode: pdf.isSingleEpisode,
  authors: pdf.authors,
  createdAt: pdf.createdAt,
  completedAt: pdf.createdAt,
  totalSize: pdf.fileSize ?? 0,
  source: 'pdf-import',
  pdfData: pdf,
})

const completedEntries = computed<CompletedEntry[]>(() => [
  ...completedTasks.value.map(mapDownloadTaskToEntry),
  ...importedPdfs.value.map(mapImportedPdfToEntry),
])

const completedGroups = computed<CompletedGroup[]>(() => {
  const byAlbum = new Map<string, CompletedEntry[]>()
  for (const t of completedEntries.value) {
    const key = t.albumId + '|' + t.source
    const list = byAlbum.get(key)
    if (list) {
      list.push(t)
    } else {
      byAlbum.set(key, [t])
    }
  }
  const groups: CompletedGroup[] = []
  for (const chapters of byAlbum.values()) {
    chapters.sort((a, b) => (a.chapterSortOrder ?? 0) - (b.chapterSortOrder ?? 0))
    groups.push({
      type: chapters.length > 1 ? 'multi' : 'single',
      albumId: chapters[0].albumId,
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

const sleep = (ms: number) => new Promise((resolve) => window.setTimeout(resolve, ms))

const resolveAlbumSingleEpisode = (album: AlbumDetail): boolean =>
  album.isSingleEpisode ?? (album.seriesId === '0' || (album.photoMetas?.length ?? 0) <= 1)

const collectEpisodeTypeBackfillAlbumIds = () => {
  const ids = new Set<string>()
  for (const task of tasks.value) {
    if (task.albumId && task.isSingleEpisode === undefined && !backfilledAlbumIds.has(task.albumId)) {
      ids.add(task.albumId)
    }
  }
  for (const pdf of importedPdfs.value) {
    if (pdf.albumId && pdf.isSingleEpisode === undefined && !backfilledAlbumIds.has(pdf.albumId)) {
      ids.add(pdf.albumId)
    }
  }
  return [...ids]
}

const applyEpisodeTypeToLocalState = (albumId: string, isSingleEpisode: boolean) => {
  tasks.value = tasks.value.map((task) =>
    task.albumId === albumId ? {...task, isSingleEpisode} : task,
  )
  importedPdfs.value = importedPdfs.value.map((pdf) =>
    pdf.albumId === albumId ? {...pdf, isSingleEpisode} : pdf,
  )
}

const backfillMissingEpisodeTypes = async () => {
  if (episodeTypeBackfillPromise) return episodeTypeBackfillPromise
  episodeTypeBackfillPromise = (async () => {
    while (!isUnmounted) {
      const albumIds = collectEpisodeTypeBackfillAlbumIds()
      if (albumIds.length === 0) return
      for (const albumId of albumIds) {
        if (isUnmounted) return
        try {
          const album = await JmcomicService.getAlbum(albumId)
          const isSingleEpisode = resolveAlbumSingleEpisode(album)
          await JmcomicService.updateLocalEpisodeType(albumId, isSingleEpisode)
          backfilledAlbumIds.add(albumId)
          applyEpisodeTypeToLocalState(albumId, isSingleEpisode)
        } catch {
          // 回填失败不影响下载页显示，后续进入页面会再尝试。
        }
        if (!isUnmounted) {
          await sleep(EPISODE_TYPE_BACKFILL_DELAY_MS)
        }
      }
    }
  })()
  try {
    await episodeTypeBackfillPromise
  } finally {
    episodeTypeBackfillPromise = null
  }
}

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
      void backfillMissingEpisodeTypes()
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

const stopSpeedTimer = () => {
  if (speedTimer) {
    clearInterval(speedTimer)
    speedTimer = null
  }
}

const sampleDownloadSpeeds = () => {
  const now = Date.now()
  const activeTaskIds = new Set<string>()

  for (const task of tasks.value) {
    if (task.status !== 'downloading') {
      if (task.speed) task.speed = 0
      speedSamples.delete(task.taskId)
      continue
    }

    activeTaskIds.add(task.taskId)
    const sample = speedSamples.get(task.taskId)
    if (!sample) {
      continue
    }

    if (sample.latestBytes > sample.sampledBytes && now > sample.sampledAt) {
      sample.lastSpeed = Math.max(
        0,
        Math.round((sample.latestBytes - sample.sampledBytes) * 1000 / (now - sample.sampledAt)),
      )
      task.speed = sample.lastSpeed
      sample.sampledBytes = sample.latestBytes
      sample.sampledAt = now
    } else if (sample.lastSpeed > 0) {
      sample.lastSpeed = Math.floor(sample.lastSpeed / 2)
      task.speed = sample.lastSpeed
    }
  }

  for (const taskId of speedSamples.keys()) {
    if (!activeTaskIds.has(taskId)) speedSamples.delete(taskId)
  }

  if (!tasks.value.some((task) => task.status === 'downloading')) {
    stopSpeedTimer()
  }
}

const startSpeedTimer = () => {
  if (speedTimer) return
  speedTimer = setInterval(sampleDownloadSpeeds, 1000)
}

const updateSpeedSample = (task: DownloadTask, downloadedBytes?: number, fallbackSpeed?: number) => {
  if (downloadedBytes !== undefined && downloadedBytes >= 0) {
    const now = Date.now()
    task.downloadedBytes = downloadedBytes

    const sample = speedSamples.get(task.taskId)
    if (sample) {
      sample.latestBytes = downloadedBytes
    } else {
      speedSamples.set(task.taskId, {
        latestBytes: downloadedBytes,
        sampledBytes: downloadedBytes,
        sampledAt: now,
        lastSpeed: task.speed ?? 0,
      })
    }
    startSpeedTimer()
  } else if (fallbackSpeed && fallbackSpeed > 0 && !speedSamples.has(task.taskId)) {
    task.speed = fallbackSpeed
    speedSamples.set(task.taskId, {
      latestBytes: task.downloadedBytes ?? 0,
      sampledBytes: task.downloadedBytes ?? 0,
      sampledAt: Date.now(),
      lastSpeed: fallbackSpeed,
    })
    startSpeedTimer()
  }
}

const onRefresh = async (event: CustomEvent) => {
  await syncDownloadState()
  await loadImportedPdfs()
  ;(event.target as HTMLIonRefresherElement).complete()
}

onMounted(async () => {
  isUnmounted = false
  void loadImportedPdfs()

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
      updateSpeedSample(task, data.downloadedBytes, data.speed)
      OfflineDownloadService.updateProgress(data.taskId, data.downloadedPages, data.totalPages)
    } else if (data.status === 'paused') {
      task.downloadedPages = data.downloadedPages
      task.totalPages = data.totalPages || task.totalPages
      task.status = 'paused'
      task.speed = 0
      speedSamples.delete(data.taskId)
      OfflineDownloadService.updateStatus(
        data.taskId,
        'paused',
        data.downloadedPages,
        task.totalPages,
      )
      void syncDownloadState()
    } else if (data.status === 'cancelled') {
      tasks.value = tasks.value.filter((t) => t.taskId !== data.taskId)
      speedSamples.delete(data.taskId)
      OfflineDownloadService.removeTask(data.taskId)
      void syncDownloadState()
    } else if (data.status === 'completed') {
      task.status = 'completed'
      task.downloadedPages = data.downloadedPages
      task.totalPages = data.totalPages
      task.totalSize = data.totalSize
      task.completedAt = Date.now()
      task.speed = 0
      speedSamples.delete(data.taskId)
      OfflineDownloadService.updateStatus(
        data.taskId,
        'completed',
        data.downloadedPages,
        data.totalPages,
      )
      void syncDownloadState()
    } else if (data.status === 'failed') {
      task.downloadedPages = data.downloadedPages
      task.totalPages = data.totalPages
      task.totalSize = data.totalSize
      task.speed = 0
      speedSamples.delete(data.taskId)
      if (isFullyDownloadedFailure({...task, status: 'failed'})) {
        task.status = 'completed'
        task.error = undefined
        task.completedAt = Date.now()
        OfflineDownloadService.updateStatus(
          data.taskId,
          'completed',
          data.downloadedPages,
          data.totalPages,
        )
        void syncDownloadState()
        return
      }
      task.status = 'failed'
      task.error = data.error
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
      if (isUnmounted) {
        void handle.remove()
        return
      }
      downloadProgressHandle = handle
    })
    .catch(() => {
    })

  await syncDownloadState()
})

onIonViewWillEnter(() => {
  void syncDownloadState()
  void loadImportedPdfs()
})

onUnmounted(() => {
  isUnmounted = true
  downloadProgressHandle?.remove()
  downloadProgressHandle = null
  stopSpeedTimer()
  speedSamples.clear()
})

// ---- PDF 导出 ----
const onOpenPdfSheet = () => {
  const g = selectedGroup.value
  if (g?.type === 'multi') {
    chaptersForPdf.value = g.chapters
      .filter((c) => c.source === 'download')
      .map((c) => c.downloadTask!)
  } else if (selectedTask.value) {
    const t = selectedTask.value
    if (!('source' in t)) {
      chaptersForPdf.value = [t as DownloadTask]
    } else if (t.source === 'download' && t.downloadTask) {
      chaptersForPdf.value = [t.downloadTask]
    }
  }
  showPdfSheet.value = true
}

const onPdfExportConfirm = async (payload: {
  selectedChapters: DownloadTask[]
  useOriginal: boolean
  compressionRatio: number
  editedPath: string
  splitPages: number
}) => {
  showPdfSheet.value = false

  // 多章节时获取本子详情以支持 author/authors/tag 模板变量
  let albumDetail: AlbumDetail | null = null
  if (payload.selectedChapters.length !== 1) {
    try {
      albumDetail = await JmcomicService.getAlbum(payload.selectedChapters[0].albumId)
    } catch { /* 获取失败则变量渲染为空 */ }
  }

  function resolveChapterName(ch: DownloadTask, album: AlbumDetail | null): string {
    if (album?.seriesId === '0') return album.title || ch.albumTitle
    const order = ch.chapterSortOrder
    if (order && order > 0) return `第${order}话`
    return ch.chapterTitle || ''
  }

  const tasks: PdfExportTask[] = payload.selectedChapters.map((ch) => {
    const savePath =
      payload.selectedChapters.length === 1
        ? payload.editedPath
        : PdfExportService.buildFullPath({
            id: ch.albumId,
            title: ch.albumTitle,
            chapterId: ch.chapterId,
            chapterName: resolveChapterName(ch, albumDetail),
            chapterTitle: ch.chapterTitle || '',
            pageCount: ch.totalPages,
            author: albumDetail?.authors?.[0] ?? '',
            authors: albumDetail?.authors?.join('、') ?? '',
            tags: albumDetail?.tags ?? [],
          })

    return {
      albumId: ch.albumId,
      chapterId: ch.chapterId,
      chapterTitle: ch.chapterTitle,
      savePath,
      useOriginal: payload.useOriginal,
      compressionRatio: payload.compressionRatio,
      splitPages: payload.splitPages,
    }
  })

  // 检查文件是否已存在
  try {
    const paths = tasks.map(t => t.savePath)
    const result = await JmcomicService.checkFilesExist(paths)
    if (result.existing.length > 0) {
      const confirmed = await confirmOverwrite(result.existing)
      if (!confirmed) return
    }
  } catch {
    /* 检查失败时直接放行 */
  }

  await ensureNotificationPermission()

  try {
    await JmcomicService.exportPdfBatch(tasks)
    await showToast(
      platformCapabilities.support.notificationPermissionPrompt
        ? 'PDF导出已开始，请查看通知'
        : 'PDF导出已开始，请查看网页内通知',
      'success',
    )
  } catch (e: any) {
    await showToast(sanitizeError(e, '导出启动失败'), 'danger')
  }
}

async function confirmOverwrite(existingFiles: string[]): Promise<boolean> {
  return new Promise((resolve) => {
    const fileList = existingFiles.slice(0, 3).join('\n')
      + (existingFiles.length > 3 ? `\n... 等 ${existingFiles.length} 个文件` : '')
    alertController.create({
      header: '文件已存在',
      message: `以下文件已存在，是否覆盖？\n${fileList}`,
      buttons: [
        { text: '取消', role: 'cancel', handler: () => resolve(false) },
        { text: '覆盖', role: 'destructive', handler: () => resolve(true) },
      ],
    }).then(alert => alert.present())
  })
}

async function ensureNotificationPermission(): Promise<boolean> {
  if (!platformCapabilities.support.notificationPermissionPrompt) {
    return true
  }
  try {
    const check = await JmcomicService.checkNotificationPermission()
    if (check.granted) return true

    const alert = await alertController.create({
      header: '需要通知权限',
      message: 'PDF导出将在后台进行，需要通过通知查看进度。拒绝后仍会继续导出，但不会显示系统通知。',
      buttons: [
        { text: '暂不授权', role: 'cancel' },
        {
          text: '允许通知',
          role: 'confirm',
        },
      ],
    })
    await alert.present()
    const dismissed = await alert.onDidDismiss()
    if (dismissed.role === 'confirm') {
      await JmcomicService.requestNotificationPermission()
    }
    const result = await JmcomicService.checkNotificationPermission()
    return result.granted
  } catch {
    return true
  }
}

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
    const {taskId} = await JmcomicService.downloadChapter(
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

const onRead = (entry: CompletedEntry | DownloadTask) => {
  if ('source' in entry && entry.source === 'pdf-import') {
    const ce = entry as CompletedEntry
    const pdf = ce.pdfData
    if (pdf?.filePath) {
      void router.push({
        path: '/pdf-reader',
        query: {
          path: pdf.filePath,
          title: pdf.fileName,
          albumId: ce.albumId,
          albumTitle: ce.albumTitle,
          authors: ce.authors,
          coverUrl: ce.coverUrl,
          chapterId: ce.chapterId || ce.displayId || ce.albumId,
          chapterTitle: ce.chapterTitle,
        },
      })
    }
    return
  }
  const dt = 'downloadTask' in entry && entry.downloadTask
    ? entry.downloadTask
    : entry as DownloadTask
  void router.push({
    path: `/album/${dt.albumId}/read/${dt.chapterId}`,
    query: {
      title: dt.chapterTitle,
      total: String(dt.totalPages),
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

const onGoToAlbumDetail = (entry: CompletedEntry | DownloadTask) => {
  void router.push(`/album/${entry.albumId}`)
}

// ========== PDF 导入入口 ==========

const onImportPdf = async () => {
  try {
    const result = await JmcomicService.pickFolder()
    if (result.cancelled || (!result.path && !result.treeUri)) return
    await PdfImportService.scanAndParse(result.path || '', result.treeUri)
    router.push('/import-review')
  } catch (e: any) {
    await showToast(sanitizeError(e, '无法打开文件夹选择器'), 'danger')
  }
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

const onDeleteSheetConfirm = async (chapters: CompletedEntry[]) => {
  showDeleteSheet.value = false
  await deleteChapters(chapters)
  deleteGroup.value = null
  selectedTask.value = null
  selectedGroup.value = null
  popoverEvent.value = null
}

const deleteChapters = async (chapters: CompletedEntry[]) => {
  try {
    const downloadChapters = chapters.filter((ch) => ch.source === 'download')
    const pdfChapters = chapters.filter((ch) => ch.source === 'pdf-import')

    const downloadResults = await Promise.all(
      downloadChapters.map(async (ch) => {
        try {
          const result = await JmcomicService.deleteDownloaded(ch.albumId, ch.chapterId)
          return { ch, ok: result.success !== false }
        } catch {
          return { ch, ok: false }
        }
      }),
    )
    const deletedDownloadChapters = downloadResults
      .filter((result) => result.ok)
      .map((result) => result.ch)
    const taskIds = new Set(
      deletedDownloadChapters
        .map((ch) => ch.downloadTask?.taskId)
        .filter((taskId): taskId is string => !!taskId),
    )
    tasks.value = tasks.value.filter((t) => !taskIds.has(t.taskId))
    for (const taskId of taskIds) {
      OfflineDownloadService.removeTask(taskId)
    }

    const pdfResults = await Promise.all(
      pdfChapters.map(async (ch) => {
        if (!ch.pdfData) return { ch, ok: false }
        try {
          const result = await JmcomicService.deleteImportedPdf(ch.pdfData.id)
          return { ch, ok: result.success !== false }
        } catch {
          return { ch, ok: false }
        }
      }),
    )
    const deletedPdfIds = new Set(
      pdfResults
        .filter((result) => result.ok && result.ch.pdfData)
        .map((result) => result.ch.pdfData!.id),
    )
    importedPdfs.value = importedPdfs.value.filter(
      (p) => !deletedPdfIds.has(p.id),
    )

    void syncDownloadState()
    const deletedCount = deletedDownloadChapters.length + deletedPdfIds.size
    const failedCount = chapters.length - deletedCount
    if (failedCount > 0 && deletedCount > 0) {
      await showToast(`已删除 ${deletedCount} 个，${failedCount} 个删除失败`, 'medium')
    } else if (failedCount > 0) {
      await showToast('删除失败', 'danger')
    } else {
      await showToast('已删除选中章节', 'success')
    }
  } catch (e: any) {
    await showToast(sanitizeError(e, '删除失败'), 'danger')
  }
}

// 清空确认
const isClearAlertOpen = ref(false)
const clearTarget = ref<'completed' | 'failed'>('completed')

const clearAlertMessage = computed(() => {
  if (clearTarget.value === 'completed') {
    const totalCount = completedTasks.value.length + importedPdfs.value.length
    return `将删除所有已完成任务的文件(导入的除外)和记录（${totalCount} 个），此操作不可恢复。`
  }
  return `将删除所有失败任务的文件和记录（${failedTasks.value.length} 个），此操作不可恢复。`
})

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
  if (!list.length && !importedPdfs.value.length) return
  await Promise.all([
    ...list.map((task) =>
      JmcomicService.deleteDownloaded(task.albumId, task.chapterId).catch(() => {}),
    ),
    ...importedPdfs.value.map((pdf) =>
      JmcomicService.deleteImportedPdf(pdf.id).catch(() => {}),
    ),
  ])
  tasks.value = tasks.value.filter((t) => t.status !== 'completed')
  importedPdfs.value = []
  OfflineDownloadService.setAll(tasks.value)
  await syncDownloadState()
}

const clearFailed = async () => {
  const list = failedTasks.value
  if (!list.length) return
  await Promise.all(
    list.map((task) =>
      JmcomicService.deleteDownloaded(task.albumId, task.chapterId).catch(() => {
      }),
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
  display: flex;
  align-items: center;
  gap: 8px;
}

.toolbar-action {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.toolbar-action-label {
  color: #8a6048;
  font-size: 11px;
  line-height: 1;
  white-space: nowrap;
}

.import-btn {
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
  margin-right: 2px;
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
