<template>
  <div class="manager">
    <div class="manager-actions">
      <button type="button" class="action-btn" aria-label="导入文件" @click="importFiles">
        <IonIcon :icon="cloudUploadOutline" />
        <span>导入文件</span>
      </button>
      <button
        type="button"
        class="icon-btn"
        aria-label="刷新导出管理数据"
        title="刷新"
        @click="load"
      >
        <IonIcon :icon="refreshOutline" />
      </button>
    </div>

    <div class="subtabs" role="tablist" aria-label="导出管理视图">
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
      <span>文件管理记录已重置，实际文件未被删除，可重新导入。</span>
      <button type="button" @click="acknowledgeDatabaseReset">知道了</button>
    </div>

    <div v-if="activeView === 'files'" class="file-filters">
      <label class="search-field">
        <span>搜索文件</span>
        <input v-model="searchText" type="search" placeholder="标题、文件名或漫画 ID" />
      </label>
      <div class="filter-buttons" aria-label="文件来源筛选">
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
      <label v-if="fileFilter !== 'zip'" class="select-field">
        <span>文件格式</span>
        <select v-model="fileFormat" aria-label="文件格式筛选">
          <option value="all">全部格式</option>
          <option value="pdf">PDF</option>
          <option value="cbz">CBZ</option>
        </select>
      </label>
    </div>

    <div v-if="activeView === 'tasks'" class="filters task-filters">
      <span class="filter-label">任务状态</span>
      <label class="select-field task-format-field">
        <span>任务格式</span>
        <select v-model="taskFormat" aria-label="导出任务格式筛选">
          <option value="all">全部格式</option>
          <option value="pdf">PDF</option>
          <option value="cbz">CBZ</option>
          <option value="zip">ZIP</option>
        </select>
      </label>
      <div class="filter-buttons task-filter-buttons" aria-label="导出任务状态筛选">
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

    <div v-if="loading" class="state">正在加载导出数据...</div>
    <div v-else-if="errorMessage" class="state error-state">
      <p>{{ errorMessage }}</p>
      <button type="button" class="action-btn" @click="load">重试</button>
    </div>

    <div v-else-if="activeView === 'files'" class="list">
      <template v-if="files.length">
        <div v-for="file in files" :key="file.id" class="item-wrap">
          <LocalFileCard
            :file="file"
            :has-image-resource="hasImageResource(file)"
            :readable="file.format !== 'zip'"
            :verifying="verifyingIds.has(file.id)"
            :menu-open="isFileActionMenuOpen && selectedFile === file"
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
      <div v-else class="state">暂无文件</div>
    </div>

    <div v-else class="list">
      <template v-if="tasks.length">
        <div
          v-for="task in tasks"
          :id="`export-task-${task.exportId}`"
          :key="task.exportId"
          class="task-anchor"
          :class="{ highlighted: highlightedExportId === task.exportId }"
        >
          <ExportTaskCard
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

    <CardContextMenu
      :visible="isFileActionMenuOpen"
      :anchor="fileActionMenuAnchor"
      :title="selectedFile?.fileName"
      :actions="fileActionMenuActions"
      @close="closeFileActions"
      @select="fileAction"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { IonIcon } from '@ionic/vue'
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
import LocalFileCard from './LocalFileCard.vue'
import ExportTaskCard from './ExportTaskCard.vue'
import CardContextMenu from '@/components/common/CardContextMenu.vue'
import { JmcomicService, sanitizeError, showToast } from '@/services/JmcomicService'
import { LocalFileImportService } from '@/services/LocalFileImportService'
import { normalizeRuntimeError } from '@/runtime/errors'
import {
  applyExportProgressEvent,
  mergeLocalFiles,
  mergeExportTasks,
  LocalFileManagementService,
} from '@/services/LocalFileManagementService'
import type {
  LocalFileRecord,
  ExportTaskRecord,
  LocalFileManagementState,
} from '@/services/JmcomicTypes'

const props = defineProps<{
  initialView?: 'files' | 'tasks'
  initialExportId?: string
}>()
const router = useRouter()
const files = ref<LocalFileRecord[]>([])
const tasks = ref<ExportTaskRecord[]>([])
const managementState = ref<LocalFileManagementState>({ recoveryState: 'ready' })
const loading = ref(false)
const loadingMore = ref(false)
const errorMessage = ref('')
const activeView = ref<'files' | 'tasks'>(props.initialView || 'files')
const fileFilter = ref<'all' | 'imported' | 'exported' | 'zip'>('all')
const fileFormat = ref<'all' | 'pdf' | 'cbz'>('all')
const taskFilter = ref<'all' | ExportTaskRecord['status']>('all')
const taskFormat = ref<'all' | ExportTaskRecord['format']>('all')
const searchText = ref('')
const selectedFile = ref<LocalFileRecord | null>(null)
const fileActionMenuAnchor = ref<HTMLElement | null>(null)
const isFileActionMenuOpen = ref(false)
const fileCursor = ref<string | null>(null)
const taskCursor = ref<string | null>(null)
const highlightedExportId = ref<string | null>(null)
const imageResourceKeys = ref<Set<string>>(new Set())
const verifyingIds = ref<Set<number>>(new Set())
let progressHandle: { remove: () => Promise<void> } | null = null
let stateInvalidatedHandle: { remove: () => Promise<void> } | null = null
let fileRequestSequence = 0
let taskRequestSequence = 0
let imageResourceRequestSequence = 0
let stateChangeSequence = 0
let fileReloadPromise: Promise<void> | null = null
let taskReloadPromise: Promise<void> | null = null
let fileReloadRequested = false
let taskReloadRequested = false
let loadPromise: Promise<void> | null = null
let loadRequested = false
let isUnmounted = false

const fileFilters = [
  { key: 'all' as const, label: '全部' },
  { key: 'imported' as const, label: '导入' },
  { key: 'exported' as const, label: '导出' },
  { key: 'zip' as const, label: 'ZIP' },
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
  { key: 'files' as const, label: '文件', count: files.value.length },
  { key: 'tasks' as const, label: '导出任务', count: tasks.value.length },
])
const isSelectedFileVerifying = computed(() =>
  selectedFile.value ? verifyingIds.value.has(selectedFile.value.id) : false,
)
const fileActionMenuActions = computed(() => {
  const file = selectedFile.value
  const actions = []
  if (file?.format !== 'zip') actions.push({ id: 'read', label: '阅读', icon: bookOutline })
  actions.push({ id: 'detail', label: '进入详情页', icon: informationCircleOutline })
  if (file?.format === 'pdf') {
    actions.push({
      id: 'verify',
      label: isSelectedFileVerifying.value ? '校验中' : '校验',
      icon: checkmarkCircleOutline,
      disabled: isSelectedFileVerifying.value,
      loading: isSelectedFileVerifying.value,
    })
  }
  actions.push(
    { id: 'copy-path', label: '复制路径', icon: copyOutline },
    { id: 'open-folder', label: '打开文件夹', icon: folderOpenOutline },
    { id: 'remove', label: '移除', icon: removeCircleOutline },
    { id: 'delete', label: '删除', icon: trashOutline, danger: true },
  )
  return actions
})

const currentFileFilters = () => {
  const formats: LocalFileRecord['format'][] =
    fileFilter.value === 'zip'
      ? ['zip']
      : fileFormat.value === 'all'
        ? ['pdf', 'cbz']
        : [fileFormat.value]
  return {
    formats,
    sourceType:
      fileFilter.value === 'zip'
        ? ('exported' as const)
        : fileFilter.value === 'all'
          ? undefined
          : fileFilter.value,
    query: searchText.value,
  }
}
const currentTaskFilters = () => ({
  format: taskFormat.value === 'all' ? undefined : taskFormat.value,
  status: taskFilter.value === 'all' ? undefined : taskFilter.value,
})

const sameFormats = (left: readonly string[], right: readonly string[]) =>
  left.length === right.length && left.every((value, index) => value === right[index])

const setVerifying = (ids: number[], verifying: boolean) => {
  const next = new Set(verifyingIds.value)
  for (const id of ids) {
    if (verifying) next.add(id)
    else next.delete(id)
  }
  verifyingIds.value = next
}

const updateVisibleFiles = (updated: LocalFileRecord[]) => {
  const byId = new Map(updated.map((file) => [file.id, file]))
  files.value = files.value.map((file) => byId.get(file.id) || file)
}

const refreshFilesInBackground = async (pageFiles: LocalFileRecord[]) => {
  const ids = pageFiles
    .filter((file) => file.format === 'pdf')
    .map((file) => file.id)
    .filter((id) => !verifyingIds.value.has(id))
  if (!ids.length) return

  setVerifying(ids, true)
  try {
    updateVisibleFiles(await LocalFileManagementService.refreshFiles(ids))
  } catch (error) {
    await showToast(sanitizeError(error, '文件状态刷新失败'), 'danger')
  } finally {
    setVerifying(ids, false)
  }
}

const loadFiles = async (reset: boolean) => {
  const requestSequence = ++fileRequestSequence
  const changeSequence = stateChangeSequence
  const filters = currentFileFilters()
  let page: Awaited<ReturnType<typeof LocalFileManagementService.getFiles>>
  try {
    page = await LocalFileManagementService.getFiles(
      filters,
      reset ? undefined : fileCursor.value || undefined,
    )
  } catch (error) {
    const currentFilters = currentFileFilters()
    if (
      changeSequence !== stateChangeSequence ||
      !sameFormats(filters.formats, currentFilters.formats) ||
      filters.sourceType !== currentFilters.sourceType ||
      filters.query !== currentFilters.query
    ) {
      fileReloadRequested = true
      return
    }
    throw error
  }
  if (isUnmounted || requestSequence !== fileRequestSequence) return
  const currentFilters = currentFileFilters()
  if (
    changeSequence !== stateChangeSequence ||
    !sameFormats(filters.formats, currentFilters.formats) ||
    filters.sourceType !== currentFilters.sourceType ||
    filters.query !== currentFilters.query
  ) {
    fileReloadRequested = true
    if (!fileReloadPromise) void requestFilesReload()
    return
  }
  files.value = reset ? page.items : mergeLocalFiles(files.value, page.items)
  fileCursor.value = page.nextCursor
  void refreshFilesInBackground(page.items)
}

const loadTasks = async (reset: boolean) => {
  const requestSequence = ++taskRequestSequence
  const changeSequence = stateChangeSequence
  const filters = currentTaskFilters()
  let page: Awaited<ReturnType<typeof LocalFileManagementService.getTasks>>
  try {
    page = await LocalFileManagementService.getTasks(
      filters,
      reset ? undefined : taskCursor.value || undefined,
    )
  } catch (error) {
    const currentFilters = currentTaskFilters()
    if (
      changeSequence !== stateChangeSequence ||
      filters.format !== currentFilters.format ||
      filters.status !== currentFilters.status
    ) {
      taskReloadRequested = true
      return
    }
    throw error
  }
  if (isUnmounted || requestSequence !== taskRequestSequence) return
  const currentFilters = currentTaskFilters()
  if (
    changeSequence !== stateChangeSequence ||
    filters.format !== currentFilters.format ||
    filters.status !== currentFilters.status
  ) {
    taskReloadRequested = true
    if (!taskReloadPromise) void requestTasksReload()
    return
  }
  tasks.value = reset
    ? page.items.map((task) => {
        const existing = tasks.value.find((item) => item.exportId === task.exportId)
        return existing && existing.snapshotRevision > task.snapshotRevision ? existing : task
      })
    : mergeExportTasks(tasks.value, page.items)
  taskCursor.value = page.nextCursor
}

const requestFilesReload = async (): Promise<void> => {
  fileReloadRequested = true
  if (fileReloadPromise) return fileReloadPromise

  fileReloadPromise = (async () => {
    while (fileReloadRequested && !isUnmounted) {
      fileReloadRequested = false
      await loadFiles(true)
    }
  })()

  try {
    await fileReloadPromise
  } finally {
    fileReloadPromise = null
    if (fileReloadRequested && !isUnmounted) void requestFilesReload()
  }
}

const requestTasksReload = async (): Promise<void> => {
  taskReloadRequested = true
  if (taskReloadPromise) return taskReloadPromise

  taskReloadPromise = (async () => {
    while (taskReloadRequested && !isUnmounted) {
      taskReloadRequested = false
      await loadTasks(true)
    }
  })()

  try {
    await taskReloadPromise
  } finally {
    taskReloadPromise = null
    if (taskReloadRequested && !isUnmounted) void requestTasksReload()
  }
}

const loadImageResourceKeys = async () => {
  const requestSequence = ++imageResourceRequestSequence
  const changeSequence = stateChangeSequence
  try {
    const result = await JmcomicService.getDownloadTasks()
    if (
      isUnmounted ||
      requestSequence !== imageResourceRequestSequence ||
      changeSequence !== stateChangeSequence
    ) {
      return
    }
    imageResourceKeys.value = new Set(
      result.tasks
        .filter((task) => task.status === 'completed')
        .map((task) => `${task.albumId}|${task.chapterId}`),
    )
  } catch {
    if (
      isUnmounted ||
      requestSequence !== imageResourceRequestSequence ||
      changeSequence !== stateChangeSequence
    ) {
      return
    }
    imageResourceKeys.value = new Set()
  }
}

const hasImageResource = (file: LocalFileRecord) =>
  Boolean(file.chapterId && imageResourceKeys.value.has(`${file.albumId}|${file.chapterId}`))

const focusTask = async (exportId: string) => {
  activeView.value = 'tasks'
  const changeSequence = stateChangeSequence
  try {
    const task = await LocalFileManagementService.getTask(exportId)
    if (isUnmounted) return
    if (changeSequence !== stateChangeSequence) {
      await requestTasksReload()
      return
    }
    tasks.value = mergeExportTasks(tasks.value, [task])
    highlightedExportId.value = exportId
    await nextTick()
    document.getElementById(`export-task-${exportId}`)?.scrollIntoView?.({ block: 'center' })
  } catch (error) {
    const runtimeError = normalizeRuntimeError(error)
    if (runtimeError.code !== 'not-found') throw error
    highlightedExportId.value = null
    await requestTasksReload()
  }
}

const load = async (): Promise<void> => {
  loadRequested = true
  if (loadPromise) return loadPromise

  loadPromise = (async () => {
    loading.value = true
    errorMessage.value = ''
    try {
      while (loadRequested && !isUnmounted) {
        loadRequested = false
        const changeSequence = stateChangeSequence
        const stateResult = await LocalFileManagementService.getManagementState()
        if (isUnmounted) return
        if (changeSequence !== stateChangeSequence) {
          loadRequested = true
          continue
        }
        managementState.value = stateResult
        await Promise.all([requestFilesReload(), requestTasksReload(), loadImageResourceKeys()])
        if (props.initialExportId) await focusTask(props.initialExportId)
      }
    } catch (error) {
      errorMessage.value = sanitizeError(error, '导出管理数据加载失败')
    } finally {
      loading.value = false
    }
  })()

  try {
    await loadPromise
  } finally {
    loadPromise = null
    if (loadRequested && !isUnmounted) void load()
  }
}

const loadMoreFiles = async () => {
  if (loadingMore.value || !fileCursor.value) return
  loadingMore.value = true
  try {
    await loadFiles(false)
  } catch (error) {
    await showToast(sanitizeError(error, '继续加载文件失败'), 'danger')
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
    await showToast(sanitizeError(error, '继续加载导出任务失败'), 'danger')
  } finally {
    loadingMore.value = false
  }
}

const openFileActions = (file: LocalFileRecord, event: Event) => {
  const anchor = event.currentTarget as HTMLElement
  if (fileActionMenuAnchor.value === anchor) {
    closeFileActions()
    return
  }
  selectedFile.value = file
  fileActionMenuAnchor.value = anchor
  isFileActionMenuOpen.value = true
}
const closeFileActions = () => {
  isFileActionMenuOpen.value = false
  selectedFile.value = null
  fileActionMenuAnchor.value = null
}
const readFile = (file: LocalFileRecord) => {
  if (file.format === 'zip') return
  void router.push({
    path: file.format === 'cbz' ? '/cbz-reader' : '/pdf-reader',
    query: {
      fileRef: String(file.fileRef),
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
const copyFilePath = async (file: LocalFileRecord) => {
  try {
    await navigator.clipboard.writeText(file.displayPath)
    await showToast('文件路径已复制', 'success')
  } catch (error) {
    await showToast(sanitizeError(error, '复制文件路径失败'), 'danger')
  }
}
const openFileFolder = async (file: LocalFileRecord) => {
  try {
    await LocalFileManagementService.openFolder(file.fileRef)
  } catch (error) {
    await showToast(sanitizeError(error, '无法打开文件所在文件夹'), 'danger')
  }
}
const verifyFile = async (file: LocalFileRecord) => {
  if (verifyingIds.value.has(file.id)) return
  setVerifying([file.id], true)
  try {
    const verified = await LocalFileManagementService.verifyFile(file.id)
    updateVisibleFiles([verified])
    await showToast(
      `校验完成：${availabilityLabel(verified.availability)}`,
      verified.availability === 'available' ? 'success' : 'medium',
    )
  } catch (error) {
    await showToast(sanitizeError(error, '文件校验失败'), 'danger')
  } finally {
    setVerifying([file.id], false)
  }
}

const fileAction = (action: string) => {
  const file = selectedFile.value
  closeFileActions()
  if (!file) return

  if (action === 'read' && file.format !== 'zip') readFile(file)
  else if (action === 'detail') {
    if (file.albumId) void router.push(`/album/${file.albumId}`)
    else void showToast('该文件没有关联漫画，无法进入详情页', 'medium')
  } else if (action === 'verify') void verifyFile(file)
  else if (action === 'copy-path') void copyFilePath(file)
  else if (action === 'open-folder') void openFileFolder(file)
  else if (action === 'remove') void removeFromLibrary(file)
  else if (action === 'delete') void deleteFile(file)
}
const removeFromLibrary = async (file: LocalFileRecord) => {
  try {
    await LocalFileManagementService.removeFile(file.id)
    files.value = files.value.filter((item) => item.id !== file.id)
    await showToast('已移出文件库', 'success')
  } catch (error) {
    await showToast(sanitizeError(error, '移出失败'), 'danger')
  }
}

const formatBytes = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
const availabilityLabel = (value: LocalFileRecord['availability']) =>
  ({
    unknown: '待校验',
    available: '可用',
    missing: '文件缺失',
    inaccessible: '无法读取',
    invalid: '文件损坏',
  })[value]

const deleteFile = async (file: LocalFileRecord) => {
  try {
    const current = await LocalFileManagementService.inspectFileForDeletion(file.id)
    const alert = await createAppAlert({
      header: `确认删除实际 ${current.format.toUpperCase()} 文件`,
      message: [
        `文件名：${current.fileName}`,
        `完整定位符：${current.displayPath}`,
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
                const result = await LocalFileManagementService.deleteFile(file.id)
                files.value = files.value.filter((item) => item.id !== file.id)
                await showToast(
                  result.result === 'already_missing'
                    ? '文件已缺失，记录已移出'
                    : '已删除文件',
                  'success',
                )
              } catch (error) {
                const runtimeError = normalizeRuntimeError(error)
                if (runtimeError.code === 'not-found') {
                  await requestFilesReload()
                  return
                }
                if (runtimeError.code === 'permission-denied') {
                  await refreshFilesInBackground([file])
                }
                await showToast(sanitizeError(error, '删除失败，文件库记录已保留'), 'danger')
              }
            })()
          },
        },
      ],
    })
    await alert.present()
  } catch (error) {
    const runtimeError = normalizeRuntimeError(error)
    if (runtimeError.code === 'not-found') {
      await requestFilesReload()
      return
    }
    await showToast(sanitizeError(error, '无法读取当前文件信息'), 'danger')
  }
}

const cancelTask = async (task: ExportTaskRecord) => {
  try {
    const updated = await LocalFileManagementService.cancelTask(task.exportId)
    tasks.value = mergeExportTasks(tasks.value, [updated])
  } catch (error) {
    const runtimeError = normalizeRuntimeError(error)
    if (runtimeError.code === 'not-found') {
      await requestTasksReload()
      return
    }
    await showToast(sanitizeError(error, '取消导出失败'), 'danger')
  }
}
const retryTask = async (task: ExportTaskRecord) => {
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
              const updated = await LocalFileManagementService.retryTask(task.exportId, true)
              tasks.value = mergeExportTasks(tasks.value, [updated])
            } catch (error) {
              const runtimeError = normalizeRuntimeError(error)
              if (runtimeError.code === 'not-found' || runtimeError.code === 'conflict') {
                await requestTasksReload()
                await showToast(sanitizeError(error, '导出任务状态已变化，请刷新后重试'), 'medium')
                return
              }
              await showToast(sanitizeError(error, '重试导出失败'), 'danger')
            }
          })()
        },
      },
    ],
  })
  await alert.present()
}
const deleteTask = async (task: ExportTaskRecord) => {
  const alert = await createAppAlert({
    header: '确认删除任务记录',
    message: `只删除「${task.displayTitle}」的任务历史，最终 ${task.format.toUpperCase()} 文件不会被删除。`,
    buttons: [
      { text: '取消', role: 'cancel' },
      {
        text: '删除记录',
        role: 'destructive',
        handler: () => {
          void (async () => {
            try {
              await LocalFileManagementService.deleteTaskRecord(task.exportId)
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
  await LocalFileManagementService.acknowledgeDatabaseReset()
  managementState.value = { ...managementState.value, databaseResetInfo: { pending: false } }
}
const importFiles = async () => {
  try {
    const result = await LocalFileManagementService.pickFolder()
    if (!result) return
    try {
      await LocalFileImportService.scanAndParse(result.ref)
    } catch (error) {
      const runtimeError = normalizeRuntimeError(error)
      if (runtimeError.code !== 'not-found' && runtimeError.code !== 'permission-denied') {
        throw error
      }
      const replacement = await LocalFileManagementService.pickFolder()
      if (!replacement) return
      await LocalFileImportService.scanAndParse(replacement.ref)
    }
    await router.push('/import-review')
  } catch (error) {
    await showToast(sanitizeError(error, '无法打开文件夹选择器'), 'danger')
  }
}

const reloadFilesWithFeedback = async () => {
  try {
    await requestFilesReload()
  } catch (error) {
    await showToast(sanitizeError(error, '文件加载失败'), 'danger')
  }
}

const reloadTasksWithFeedback = async () => {
  try {
    await requestTasksReload()
  } catch (error) {
    await showToast(sanitizeError(error, '导出任务加载失败'), 'danger')
  }
}

let searchTimer: ReturnType<typeof setTimeout> | null = null
watch(fileFilter, () => void reloadFilesWithFeedback())
watch(fileFormat, () => void reloadFilesWithFeedback())
watch(searchText, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => void reloadFilesWithFeedback(), 300)
})
watch(taskFilter, () => void reloadTasksWithFeedback())
watch(taskFormat, () => void reloadTasksWithFeedback())
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
  isUnmounted = false
  const progressRegistration = (async () => {
    try {
      const handle = await LocalFileManagementService.addProgressListener((event) => {
        stateChangeSequence++
        if (
          (taskFilter.value !== 'all' && taskFilter.value !== event.status) ||
          (taskFormat.value !== 'all' && taskFormat.value !== event.format)
        ) {
          tasks.value = tasks.value.filter((task) => task.exportId !== event.exportId)
        } else {
          const merged = applyExportProgressEvent(tasks.value, event)
          if (merged === null) void reloadTasksWithFeedback()
          else tasks.value = merged
        }
        if (['completed', 'partial'].includes(event.status)) void reloadFilesWithFeedback()
      })
      if (isUnmounted) {
        void handle.remove()
        return
      }
      progressHandle = handle
    } catch (error) {
      if (!isUnmounted) {
        await showToast(sanitizeError(error, '导出进度监听失败'), 'danger')
      }
    }
  })()
  const invalidationRegistration = (async () => {
    try {
      const handle = await JmcomicService.addStateInvalidatedListener?.(() => {
        stateChangeSequence++
        void load()
      })
      if (!handle) return
      if (isUnmounted) {
        void handle.remove()
        return
      }
      stateInvalidatedHandle = handle
    } catch {
      // Android 不需要处理 SSE 重连，保持原有加载行为。
    }
  })()

  await Promise.all([progressRegistration, invalidationRegistration])
  if (isUnmounted) return
  await load()
})
onUnmounted(() => {
  isUnmounted = true
  fileRequestSequence++
  taskRequestSequence++
  imageResourceRequestSequence++
  fileReloadRequested = false
  taskReloadRequested = false
  loadRequested = false
  if (searchTimer) clearTimeout(searchTimer)
  void progressHandle?.remove()
  void stateInvalidatedHandle?.remove()
  progressHandle = null
  stateInvalidatedHandle = null
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

.task-format-field {
  width: min(220px, 100%);
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
