<template>
  <div class="manager">
    <div class="manager-actions">
      <button type="button" class="action-btn" aria-label="导入 PDF" @click="importPdf">
        <IonIcon :icon="cloudUploadOutline" />
        <span>导入 PDF</span>
      </button>
      <button
        type="button"
        class="icon-btn"
        aria-label="刷新 PDF 管理数据"
        title="刷新"
        @click="load"
      >
        <IonIcon :icon="refreshOutline" />
      </button>
    </div>

    <div class="subtabs" role="tablist" aria-label="PDF 管理视图">
      <button
        v-for="item in views"
        :key="item.key"
        type="button"
        role="tab"
        :aria-selected="activeView === item.key"
        :class="{ active: activeView === item.key }"
        @click="activeView = item.key"
      >
        {{ item.label }}<span v-if="item.count > 0" class="count">{{ item.count }}</span>
      </button>
    </div>

    <div v-if="managementState.databaseResetInfo?.pending" class="banner">
      <span>PDF 管理记录已重置，实际 PDF 文件未被删除，可重新导入。</span>
      <button type="button" @click="acknowledgeDatabaseReset">知道了</button>
    </div>

    <div v-if="activeView === 'files'" class="file-filters">
      <label class="search-field">
        <span>搜索 PDF</span>
        <input v-model="searchText" type="search" placeholder="标题、文件名或漫画 ID" />
      </label>
      <div class="filter-buttons" aria-label="PDF 来源筛选">
        <button
          v-for="item in fileFilters"
          :key="item.key"
          type="button"
          :class="{ selected: fileFilter === item.key }"
          @click="fileFilter = item.key"
        >
          {{ item.label }}
        </button>
      </div>
    </div>

    <div v-if="activeView === 'tasks'" class="filters task-filters">
      <span class="filter-label">任务状态</span>
      <div class="filter-buttons task-filter-buttons" aria-label="PDF 导出任务状态筛选">
        <button
          type="button"
          :class="{ selected: taskFilter === 'all' }"
          @click="taskFilter = 'all'"
        >
          全部状态
        </button>
        <button
          v-for="item in taskFilters"
          :key="item.key"
          type="button"
          :class="{ selected: taskFilter === item.key }"
          @click="taskFilter = item.key"
        >
          {{ item.label }}
        </button>
      </div>
    </div>

    <div v-if="loading" class="state">正在加载 PDF 数据...</div>
    <div v-else-if="errorMessage" class="state error-state">
      <p>{{ errorMessage }}</p>
      <button type="button" class="action-btn" @click="load">重试</button>
    </div>

    <div v-else-if="activeView === 'files'" class="list">
      <template v-if="files.length">
        <div v-for="file in files" :key="file.id" class="item-wrap">
          <PdfFileCard
            :file="file"
            :has-image-resource="hasImageResource(file)"
            :verifying="verifyingIds.has(file.id)"
            @open="readFile(file)"
            @more="openFileActions(file, $event)"
          />
        </div>
        <button
          v-if="fileCursor"
          type="button"
          class="load-more-btn"
          :disabled="loadingMore"
          @click="loadMoreFiles"
        >
          {{ loadingMore ? '正在加载...' : '继续加载' }}
        </button>
      </template>
      <div v-else class="state">暂无 PDF 文件</div>
    </div>

    <div v-else class="list">
      <template v-if="tasks.length">
        <div
          v-for="task in tasks"
          :id="`pdf-task-${task.exportId}`"
          :key="task.exportId"
          class="task-anchor"
          :class="{ highlighted: highlightedExportId === task.exportId }"
        >
          <PdfExportTaskCard
            :task="task"
            @cancel="cancelTask(task)"
            @retry="retryTask(task)"
            @delete="deleteTask(task)"
          />
        </div>
        <button
          v-if="taskCursor"
          type="button"
          class="load-more-btn"
          :disabled="loadingMore"
          @click="loadMoreTasks"
        >
          {{ loadingMore ? '正在加载...' : '继续加载' }}
        </button>
      </template>
      <div v-else class="state">暂无导出任务</div>
    </div>

    <IonPopover
      class="pdf-file-popover"
      :is-open="isFilePopoverOpen"
      :event="filePopoverEvent"
      @did-dismiss="closeFileActions"
    >
      <IonContent class="popover-content">
        <div class="popover-header">{{ selectedFile?.fileName }}</div>
        <button type="button" class="popover-btn" @click="fileAction('read')">
          <IonIcon :icon="bookOutline" />
          阅读
        </button>
        <button type="button" class="popover-btn" @click="fileAction('detail')">
          <IonIcon :icon="informationCircleOutline" />
          进入详情页
        </button>
        <button
          type="button"
          class="popover-btn"
          :disabled="isSelectedFileVerifying"
          @click="fileAction('verify')"
        >
          <IonSpinner v-if="isSelectedFileVerifying" name="crescent" />
          <IonIcon v-else :icon="checkmarkCircleOutline" />
          {{ isSelectedFileVerifying ? '校验中' : '校验' }}
        </button>
        <button type="button" class="popover-btn" @click="fileAction('copy-path')">
          <IonIcon :icon="copyOutline" />
          复制路径
        </button>
        <button type="button" class="popover-btn" @click="fileAction('open-folder')">
          <IonIcon :icon="folderOpenOutline" />
          打开文件夹
        </button>
        <button type="button" class="popover-btn" @click="fileAction('remove')">
          <IonIcon :icon="removeCircleOutline" />
          移除
        </button>
        <button type="button" class="popover-btn danger" @click="fileAction('delete')">
          <IonIcon :icon="trashOutline" />
          删除
        </button>
      </IonContent>
    </IonPopover>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { IonContent, IonIcon, IonPopover, IonSpinner } from '@ionic/vue'
import { createAppAlert } from '@/services/AppAlertService'
import {
  bookOutline,
  checkmarkCircleOutline,
  cloudUploadOutline,
  copyOutline,
  folderOpenOutline,
  informationCircleOutline,
  refreshOutline,
  removeCircleOutline,
  trashOutline,
} from 'ionicons/icons'
import { useRouter } from 'vue-router'
import PdfFileCard from './PdfFileCard.vue'
import PdfExportTaskCard from './PdfExportTaskCard.vue'
import { JmcomicService, sanitizeError, showToast } from '@/services/JmcomicService'
import { PdfImportService } from '@/services/PdfImportService'
import {
  applyPdfProgressEvent,
  mergePdfFiles,
  mergePdfTasks,
  PdfManagementService,
} from '@/services/PdfManagementService'
import type { ImportedPdf, PdfExportTaskRecord, PdfManagementState } from '@/services/JmcomicTypes'

const props = defineProps<{
  initialView?: 'files' | 'tasks'
  initialExportId?: string
}>()
const router = useRouter()
const files = ref<ImportedPdf[]>([])
const tasks = ref<PdfExportTaskRecord[]>([])
const managementState = ref<PdfManagementState>({ recoveryState: 'ready' })
const loading = ref(false)
const loadingMore = ref(false)
const errorMessage = ref('')
const activeView = ref<'files' | 'tasks'>(props.initialView || 'files')
const fileFilter = ref<'all' | 'imported' | 'exported'>('all')
const taskFilter = ref<'all' | PdfExportTaskRecord['status']>('all')
const searchText = ref('')
const selectedFile = ref<ImportedPdf | null>(null)
const filePopoverEvent = ref<Event | null>(null)
const isFilePopoverOpen = ref(false)
const fileCursor = ref<string | null>(null)
const taskCursor = ref<string | null>(null)
const highlightedExportId = ref<string | null>(null)
const imageResourceKeys = ref<Set<string>>(new Set())
const verifyingIds = ref<Set<number>>(new Set())
let progressHandle: { remove: () => Promise<void> } | null = null
let fileRequestSequence = 0
let taskRequestSequence = 0
let isUnmounted = false

const fileFilters = [
  { key: 'all' as const, label: '全部' },
  { key: 'imported' as const, label: '导入' },
  { key: 'exported' as const, label: '导出' },
]
const taskFilters = [
  { key: 'queued' as const, label: '排队中' },
  { key: 'running' as const, label: '导出中' },
  { key: 'completed' as const, label: '已完成' },
  { key: 'partial' as const, label: '部分完成' },
  { key: 'failed' as const, label: '失败' },
  { key: 'interrupted' as const, label: '已中断' },
  { key: 'cancelled' as const, label: '已取消' },
]
const views = computed(() => [
  { key: 'files' as const, label: 'PDF 文件', count: files.value.length },
  { key: 'tasks' as const, label: '导出任务', count: tasks.value.length },
])
const isSelectedFileVerifying = computed(() =>
  selectedFile.value ? verifyingIds.value.has(selectedFile.value.id) : false,
)

const currentFileFilters = () => ({
  sourceType: fileFilter.value === 'all' ? undefined : fileFilter.value,
  query: searchText.value,
})
const currentTaskFilters = () => ({
  status: taskFilter.value === 'all' ? undefined : taskFilter.value,
})

const setVerifying = (ids: number[], verifying: boolean) => {
  const next = new Set(verifyingIds.value)
  for (const id of ids) {
    if (verifying) next.add(id)
    else next.delete(id)
  }
  verifyingIds.value = next
}

const updateVisibleFiles = (updated: ImportedPdf[]) => {
  const byId = new Map(updated.map((file) => [file.id, file]))
  files.value = files.value.map((file) => byId.get(file.id) || file)
}

const refreshFilesInBackground = async (pageFiles: ImportedPdf[]) => {
  const ids = pageFiles.map((file) => file.id).filter((id) => !verifyingIds.value.has(id))
  if (!ids.length) return

  setVerifying(ids, true)
  try {
    updateVisibleFiles(await PdfManagementService.refreshFiles(ids))
  } catch (error) {
    await showToast(sanitizeError(error, 'PDF 状态刷新失败'), 'danger')
  } finally {
    setVerifying(ids, false)
  }
}

const loadFiles = async (reset: boolean) => {
  const requestSequence = ++fileRequestSequence
  const page = await PdfManagementService.getFiles(
    currentFileFilters(),
    reset ? undefined : fileCursor.value || undefined,
  )
  if (isUnmounted || requestSequence !== fileRequestSequence) return
  files.value = reset ? page.items : mergePdfFiles(files.value, page.items)
  fileCursor.value = page.nextCursor
  void refreshFilesInBackground(page.items)
}

const loadTasks = async (reset: boolean) => {
  const requestSequence = ++taskRequestSequence
  const page = await PdfManagementService.getTasks(
    currentTaskFilters(),
    reset ? undefined : taskCursor.value || undefined,
  )
  if (isUnmounted || requestSequence !== taskRequestSequence) return
  tasks.value = reset ? page.items : mergePdfTasks(tasks.value, page.items)
  taskCursor.value = page.nextCursor
}

const loadImageResourceKeys = async () => {
  try {
    const result = await JmcomicService.getDownloadTasks()
    imageResourceKeys.value = new Set(
      result.tasks
        .filter((task) => task.status === 'completed')
        .map((task) => `${task.albumId}|${task.chapterId}`),
    )
  } catch {
    imageResourceKeys.value = new Set()
  }
}

const hasImageResource = (file: ImportedPdf) =>
  Boolean(file.chapterId && imageResourceKeys.value.has(`${file.albumId}|${file.chapterId}`))

const focusTask = async (exportId: string) => {
  activeView.value = 'tasks'
  const task = await PdfManagementService.getTask(exportId)
  tasks.value = mergePdfTasks(tasks.value, [task])
  highlightedExportId.value = exportId
  await nextTick()
  document.getElementById(`pdf-task-${exportId}`)?.scrollIntoView?.({ block: 'center' })
}

const load = async () => {
  loading.value = true
  errorMessage.value = ''
  try {
    const stateResult = await PdfManagementService.getManagementState()
    managementState.value = stateResult
    await Promise.all([loadFiles(true), loadTasks(true), loadImageResourceKeys()])
    if (props.initialExportId) await focusTask(props.initialExportId)
  } catch (error) {
    errorMessage.value = sanitizeError(error, 'PDF 管理数据加载失败')
  } finally {
    loading.value = false
  }
}

const loadMoreFiles = async () => {
  if (loadingMore.value || !fileCursor.value) return
  loadingMore.value = true
  try {
    await loadFiles(false)
  } catch (error) {
    await showToast(sanitizeError(error, '继续加载 PDF 文件失败'), 'danger')
  } finally {
    loadingMore.value = false
  }
}
const loadMoreTasks = async () => {
  if (loadingMore.value || !taskCursor.value) return
  loadingMore.value = true
  try {
    await loadTasks(false)
  } catch (error) {
    await showToast(sanitizeError(error, '继续加载 PDF 任务失败'), 'danger')
  } finally {
    loadingMore.value = false
  }
}

const openFileActions = (file: ImportedPdf, event: Event) => {
  selectedFile.value = file
  filePopoverEvent.value = event
  isFilePopoverOpen.value = true
}
const closeFileActions = () => {
  isFilePopoverOpen.value = false
  selectedFile.value = null
  filePopoverEvent.value = null
}
const readFile = (file: ImportedPdf) => {
  void router.push({
    path: '/pdf-reader',
    query: {
      path: file.filePath,
      title: file.fileName,
      albumId: file.albumId,
      albumTitle: file.albumTitle,
      authors: file.authors,
      coverUrl: file.coverUrl,
      chapterId: file.chapterId || file.albumId,
      chapterTitle: file.chapterTitle,
    },
  })
}
const copyFilePath = async (file: ImportedPdf) => {
  try {
    await navigator.clipboard.writeText(file.filePath)
    await showToast('PDF 路径已复制', 'success')
  } catch (error) {
    await showToast(sanitizeError(error, '复制 PDF 路径失败'), 'danger')
  }
}
const openFileFolder = async (file: ImportedPdf) => {
  try {
    await PdfManagementService.openFolder(file.filePath)
  } catch (error) {
    await showToast(sanitizeError(error, '无法打开 PDF 所在文件夹'), 'danger')
  }
}
const verifyFile = async (file: ImportedPdf) => {
  if (verifyingIds.value.has(file.id)) return
  setVerifying([file.id], true)
  try {
    const verified = await PdfManagementService.verifyFile(file.id)
    updateVisibleFiles([verified])
    await showToast(
      `校验完成：${availabilityLabel(verified.availability)}`,
      verified.availability === 'available' ? 'success' : 'medium',
    )
  } catch (error) {
    await showToast(sanitizeError(error, 'PDF 校验失败'), 'danger')
  } finally {
    setVerifying([file.id], false)
  }
}

type FileAction = 'read' | 'detail' | 'verify' | 'copy-path' | 'open-folder' | 'remove' | 'delete'

const fileAction = (action: FileAction) => {
  const file = selectedFile.value
  isFilePopoverOpen.value = false
  if (!file) return

  if (action === 'read') readFile(file)
  else if (action === 'detail') {
    if (file.albumId) void router.push(`/album/${file.albumId}`)
    else void showToast('该 PDF 没有关联漫画，无法进入详情页', 'medium')
  } else if (action === 'verify') void verifyFile(file)
  else if (action === 'copy-path') void copyFilePath(file)
  else if (action === 'open-folder') void openFileFolder(file)
  else if (action === 'remove') void removeFromLibrary(file)
  else if (action === 'delete') void deleteFile(file)
}
const removeFromLibrary = async (file: ImportedPdf) => {
  try {
    await PdfManagementService.removeFile(file.id)
    files.value = files.value.filter((item) => item.id !== file.id)
    await showToast('已移出 PDF 文件库', 'success')
  } catch (error) {
    await showToast(sanitizeError(error, '移出失败'), 'danger')
  }
}

const formatBytes = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
const availabilityLabel = (value: ImportedPdf['availability']) =>
  ({
    unknown: '待校验',
    available: '可用',
    missing: '文件缺失',
    inaccessible: '无法读取',
    invalid: '文件损坏',
  })[value]

const deleteFile = async (file: ImportedPdf) => {
  try {
    const current = await PdfManagementService.inspectFileForDeletion(file.id)
    const alert = await createAppAlert({
      header: '确认删除实际 PDF 文件',
      message: [
        `文件名：${current.fileName}`,
        `完整定位符：${current.filePath}`,
        `大小：${formatBytes(current.fileSize)}`,
        `页数：${current.pageCount > 0 ? current.pageCount : '未知'}`,
        `状态：${availabilityLabel(current.availability)}`,
        '',
        '将删除该定位符当前指向的文件并移出文件库；若文件已被替换，替换后的当前文件也会被删除。此操作不可恢复。',
      ].join('\n'),
      buttons: [
        { text: '取消', role: 'cancel' },
        {
          text: '删除文件',
          role: 'destructive',
          handler: () => {
            void (async () => {
              try {
                const result = await PdfManagementService.deleteFile(file.id)
                files.value = files.value.filter((item) => item.id !== file.id)
                await showToast(
                  result.result === 'already_missing'
                    ? '文件已缺失，记录已移出'
                    : '已删除 PDF 文件',
                  'success',
                )
              } catch (error) {
                await showToast(sanitizeError(error, '删除失败，文件库记录已保留'), 'danger')
              }
            })()
          },
        },
      ],
    })
    await alert.present()
  } catch (error) {
    await showToast(sanitizeError(error, '无法读取当前 PDF 文件信息'), 'danger')
  }
}

const cancelTask = async (task: PdfExportTaskRecord) => {
  try {
    const updated = await PdfManagementService.cancelTask(task.exportId)
    tasks.value = mergePdfTasks(tasks.value, [updated])
  } catch (error) {
    await showToast(sanitizeError(error, '取消导出失败'), 'danger')
  }
}
const retryTask = async (task: PdfExportTaskRecord) => {
  const alert = await createAppAlert({
    header: '确认重试整个任务',
    message:
      '将从第一卷开始重新导出并覆盖已有同名文件。成功写完的卷会立即替换；若后续卷失败，未处理的旧卷会保留，但不计入本次结果。',
    buttons: [
      { text: '取消', role: 'cancel' },
      {
        text: '重新导出',
        role: 'confirm',
        handler: () => {
          void (async () => {
            try {
              const updated = await PdfManagementService.retryTask(task.exportId, true)
              tasks.value = mergePdfTasks(tasks.value, [updated])
            } catch (error) {
              await showToast(sanitizeError(error, '重试导出失败'), 'danger')
            }
          })()
        },
      },
    ],
  })
  await alert.present()
}
const deleteTask = async (task: PdfExportTaskRecord) => {
  const alert = await createAppAlert({
    header: '确认删除任务记录',
    message: `只删除「${task.displayTitle}」的任务历史，最终 PDF 文件不会被删除。`,
    buttons: [
      { text: '取消', role: 'cancel' },
      {
        text: '删除记录',
        role: 'destructive',
        handler: () => {
          void (async () => {
            try {
              await PdfManagementService.deleteTaskRecord(task.exportId)
              tasks.value = tasks.value.filter((item) => item.exportId !== task.exportId)
            } catch (error) {
              await showToast(sanitizeError(error, '删除任务记录失败'), 'danger')
            }
          })()
        },
      },
    ],
  })
  await alert.present()
}

const acknowledgeDatabaseReset = async () => {
  await PdfManagementService.acknowledgeDatabaseReset()
  managementState.value = { ...managementState.value, databaseResetInfo: { pending: false } }
}
const importPdf = async () => {
  try {
    const result = await PdfManagementService.pickFolder()
    if (result.cancelled || (!result.path && !result.treeUri)) return
    await PdfImportService.scanAndParse(result.path || '', result.treeUri)
    await router.push('/import-review')
  } catch (error) {
    await showToast(sanitizeError(error, '无法打开文件夹选择器'), 'danger')
  }
}

const reloadFilesWithFeedback = async () => {
  try {
    await loadFiles(true)
  } catch (error) {
    await showToast(sanitizeError(error, 'PDF 文件加载失败'), 'danger')
  }
}

const reloadTasksWithFeedback = async () => {
  try {
    await loadTasks(true)
  } catch (error) {
    await showToast(sanitizeError(error, 'PDF 导出任务加载失败'), 'danger')
  }
}

let searchTimer: ReturnType<typeof setTimeout> | null = null
watch(fileFilter, () => void reloadFilesWithFeedback())
watch(searchText, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => void reloadFilesWithFeedback(), 300)
})
watch(taskFilter, () => void reloadTasksWithFeedback())
watch(
  () => props.initialView,
  (view) => {
    if (view) activeView.value = view
  },
)
watch(
  () => props.initialExportId,
  (exportId, previous) => {
    if (exportId && exportId !== previous) void focusTask(exportId)
  },
)

onMounted(async () => {
  await load()
  if (isUnmounted) return
  try {
    const handle = await PdfManagementService.addProgressListener((event) => {
      const merged = applyPdfProgressEvent(tasks.value, event)
      if (merged === null) void reloadTasksWithFeedback()
      else tasks.value = merged
      if (['completed', 'partial'].includes(event.status)) void reloadFilesWithFeedback()
    })
    if (isUnmounted) {
      void handle.remove()
      return
    }
    progressHandle = handle
  } catch (error) {
    if (!isUnmounted) {
      await showToast(sanitizeError(error, 'PDF 导出进度监听失败'), 'danger')
    }
  }
})
onUnmounted(() => {
  isUnmounted = true
  fileRequestSequence++
  taskRequestSequence++
  if (searchTimer) clearTimeout(searchTimer)
  void progressHandle?.remove()
})

defineExpose({ refresh: load })
</script>

<style scoped>
.manager {
  padding: 0 14px 86px;
}

.manager-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-bottom: 10px;
}

.action-btn,
.icon-btn,
.load-more-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  min-height: 34px;
  border: 0;
  border-radius: 8px;
  padding: 0 10px;
  color: #704631;
  background: #fffbf8;
}

.action-btn:active,
.icon-btn:active,
.load-more-btn:active {
  background: #f5d2bc;
}

.icon-btn {
  width: 34px;
  padding: 0;
  font-size: 18px;
}

.subtabs {
  display: flex;
  gap: 2px;
  padding: 4px 14px;
  border-radius: 18px;
  background: #fffbf8;
}

.subtabs button {
  flex: 1;
  height: 34px;
  border: 0;
  border-radius: 14px;
  background: transparent;
  color: #8a6048;
  font-size: 12px;
  font-weight: 600;
}

.subtabs button.active {
  background: linear-gradient(145deg, #fa9c69, #f28752);
  color: #fff;
}

.count {
  margin-left: 4px;
  font-size: 11px;
}

.banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #fff3df;
  color: #8a6048;
  font-size: 12px;
}

.banner button {
  flex: 0 0 auto;
  border: 0;
  background: transparent;
  color: #704631;
  font-weight: 600;
}

.file-filters,
.filters {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 8px 0 10px;
}

.search-field,
.select-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.search-field > span,
.filter-label {
  padding: 0 4px;
  color: #8a6048;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.search-field {
  width: 100%;
}

.search-field input,
.select-field select {
  min-height: 36px;
  border: 1px solid #ead1c1;
  border-radius: 16px;
  padding: 0 10px;
  color: #4c2a18;
  background: #fffaf6;
  font-size: 13px;
}

.filter-buttons {
  display: flex;
  gap: 6px;
  width: 100%;
}

.filter-buttons button {
  flex: 1;
  min-height: 36px;
  border: 0;
  border-radius: 18px;
  padding: 0 14px;
  background: transparent;
  color: #8a6048;
  font-size: 13px;
}

.filter-buttons button.selected {
  background: #fff1e7;
  color: #c06f45;
  font-weight: 600;
}

.task-filters {
  align-items: flex-start;
}

.task-filter-buttons {
  flex-wrap: wrap;
}

.task-filter-buttons button {
  flex: 0 1 auto;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 10px;
}

.item-wrap {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.task-anchor {
  border-radius: 8px;
}

.task-anchor.highlighted {
  outline: 2px solid #c06f45;
  outline-offset: 2px;
}

.popover-content {
  --background: #fffaf6;
}

.popover-header {
  max-width: 220px;
  overflow: hidden;
  padding: 10px 14px 6px;
  color: #4c2a18;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.popover-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  border: 0;
  padding: 10px 14px;
  background: transparent;
  color: #4c2a18;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}

.popover-btn:active {
  background: #f5d2bc;
}

.popover-btn:disabled {
  cursor: default;
  opacity: 0.6;
}

.popover-btn.danger {
  color: #d9534f;
}

.popover-btn.danger:active {
  background: #ffeaea;
}

.popover-btn ion-icon {
  width: 20px;
  font-size: 16px;
  text-align: center;
}

.popover-btn ion-spinner {
  width: 16px;
  height: 16px;
  margin: 0 2px;
}

.popover-btn:focus-visible {
  outline: 2px solid #c06f45;
  outline-offset: 2px;
}

.load-more-btn {
  width: 100%;
  border: 1px solid #ead1c1;
}

.state {
  padding: 36vh 12px 0;
  color: #b89a84;
  text-align: center;
  font-size: 14px;
}

.error-state {
  padding-top: 40px;
  color: #b53d36;
}

button:focus-visible,
select:focus-visible {
  outline: 2px solid #c06f45;
  outline-offset: 2px;
}

.search-field input:focus-visible {
  outline: none;
}

@media (prefers-reduced-motion: reduce) {
  .list :deep(*) {
    transition: none !important;
  }
}
</style>
