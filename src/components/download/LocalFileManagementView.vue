<template>
  <div ref="managerRef" class="manager">
    <div class="manager-actions" :inert="managementSelectionActive">
      <button
        type="button"
        class="action-btn"
        aria-label="导入文件"
        :disabled="managementSelectionActive"
        @click="importFiles"
      >
        <IonIcon :icon="cloudUploadOutline" />
        <span>导入文件</span>
      </button>
      <button
        type="button"
        class="icon-btn"
        aria-label="刷新导出管理数据"
        title="刷新"
        :disabled="managementSelectionActive"
        @click="load"
      >
        <IonIcon :icon="refreshOutline" />
      </button>
      <button type="button" class="action-btn" @click="enterManagementSelection()">多选</button>
    </div>

    <div
      class="subtabs"
      role="tablist"
      aria-label="导出管理视图"
      :inert="managementSelectionActive"
    >
      <button
        v-for="item in views"
        :key="item.key"
        type="button"
        role="tab"
        :aria-selected="activeView === item.key"
        :disabled="managementSelectionActive"
        :class="{ active: activeView === item.key }"
        @click="selectManagementView(item.key)"
      >
        {{ item.label }}<span v-if="item.count > 0" class="count">{{ item.count }}</span>
      </button>
    </div>

    <div v-if="managementState.databaseResetInfo?.pending" class="banner">
      <span>文件管理记录已重置，实际文件未被删除，可重新导入。</span>
      <button type="button" @click="acknowledgeDatabaseReset">知道了</button>
    </div>

    <div v-if="activeView === 'files'" class="file-filters" :inert="managementSelectionActive">
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
      <div v-if="fileFilter !== 'zip'" class="filter-group">
        <span class="filter-label">文件格式</span>
        <div class="filter-buttons task-filter-buttons" aria-label="文件格式筛选">
          <button
            v-for="item in fileFormatOptions"
            :key="item.value"
            type="button"
            :class="{ selected: fileFormat === item.value }"
            @click="fileFormat = item.value"
          >
            {{ item.label }}
          </button>
        </div>
      </div>
    </div>

    <div
      v-if="activeView === 'tasks'"
      class="filters task-filters"
      :inert="managementSelectionActive"
    >
      <div class="task-status-group">
        <span class="filter-label">任务状态</span>
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
      <div class="filter-group task-format-field">
        <span class="filter-label">任务格式</span>
        <div class="filter-buttons task-filter-buttons" aria-label="导出任务格式筛选">
          <button
            v-for="item in taskFormatOptions"
            :key="item.value"
            type="button"
            :class="{ selected: taskFormat === item.value }"
            @click="taskFormat = item.value"
          >
            {{ item.label }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="loading" class="state">正在加载导出数据...</div>
    <div v-else-if="errorMessage" class="state error-state">
      <p>{{ errorMessage }}</p>
      <button type="button" class="action-btn" @click="load">重试</button>
    </div>

    <div v-else-if="activeView === 'files'" class="list">
      <button
        v-if="canLoadPreviousFiles"
        type="button"
        class="load-more-btn"
        :disabled="fileLoadingMore"
        @click="loadMoreFiles('previous')"
      >
        加载上一页
      </button>
      <template v-if="files.length">
        <div v-for="file in files" :key="file.id" :data-file-id="file.id" class="item-wrap">
          <LocalFileCard
            :file="file"
            :has-image-resource="hasImageResource(file)"
            :readable="file.format !== 'zip'"
            :verifying="verifyingIds.has(file.id)"
            :selection-mode="managementSelectionActive && activeView === 'files'"
            :selected="managementSelection.selected.value.has(`f:${file.id}`)"
            :menu-open="isFileActionMenuOpen && selectedFile === file"
            @open="readFile(file)"
            @more="openFileActions(file, $event)"
            @select="selectFileFromCard(file.id)"
            @long-press="enterFileFromCard(file.id)"
          />
        </div>
        <div ref="filesSentinel" class="list-sentinel" aria-hidden="true"></div>
        <div v-if="fileLoadingMore" class="list-loader"><IonSpinner name="dots" /></div>
        <button
          v-else-if="fileLoadError"
          type="button"
          class="load-more-btn"
          @click="loadMoreFiles(fileLoadDirection)"
        >
          加载失败，点击重试
        </button>
      </template>
      <div v-else class="state">暂无文件</div>
    </div>

    <div v-else class="list">
      <button
        v-if="canLoadPreviousTasks"
        type="button"
        class="load-more-btn"
        :disabled="taskLoadingMore"
        @click="loadMoreTasks('previous')"
      >
        加载上一页
      </button>
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
            :selection-mode="managementSelectionActive && activeView === 'tasks'"
            :selected="managementSelection.selected.value.has(`t:${task.exportId}`)"
            @cancel="cancelTask(task)"
            @retry="retryTask(task)"
            @delete="deleteTask(task)"
            @select="selectTaskFromCard(task.exportId)"
            @long-press="enterTaskFromCard(task.exportId)"
          />
        </div>
        <div ref="tasksSentinel" class="list-sentinel" aria-hidden="true"></div>
        <div v-if="taskLoadingMore" class="list-loader"><IonSpinner name="dots" /></div>
        <button
          v-else-if="taskLoadError"
          type="button"
          class="load-more-btn"
          @click="loadMoreTasks(taskLoadDirection)"
        >
          加载失败，点击重试
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
    <!-- 工具栏和进度层使用宿主页的覆盖层，不进入内容滚动层。 -->
    <Teleport :to="overlayTarget">
      <div v-if="managementSelectionActive" class="multi-select-overlay">
        <MultiSelectInfoBar
          :count="managementSelection.count.value"
          :preparing="managementSelectionPreparing"
          :preparing-action="managementSelectionPreparingAction"
          :all-selected="managementSelection.allSelected.value"
          @cancel="exitManagementSelection"
          @select-all="prepareManagementSelection(false)"
          @invert="prepareManagementSelection(true)"
        />
        <MultiSelectActionBar
          :actions="[
            { id: 'delete', label: activeView === 'files' ? '删除实际文件' : '删除任务记录' },
          ]"
          :disabled="
            !managementSelection.count.value ||
            managementSelectionPreparing ||
            managementSelectionExecuting
          "
          @action="executeManagementDelete"
        />
      </div>
      <BatchProgressOverlay
        v-if="managementSelectionExecuting"
        title="正在删除"
        :current="managementProgressCurrent"
        :total="managementProgressTotal"
        :current-item="managementProgressTitle"
      />
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { IonIcon, IonSpinner, useBackButton } from '@ionic/vue'
import { createAppAlert } from '@/services/AppAlertService'
import {
  bookOutline,
  checkmarkCircleOutline,
  cloudUploadOutline,
  copyOutline,
  folderOpenOutline,
  informationCircleOutline,
  listOutline,
  refreshOutline,
  removeCircleOutline,
  trashOutline,
} from 'ionicons/icons'
import { useRouter } from 'vue-router'
import LocalFileCard from './LocalFileCard.vue'
import { ChapterSourceService } from '@/services/ChapterSourceService'
import ExportTaskCard from './ExportTaskCard.vue'
import CardContextMenu from '@/components/common/CardContextMenu.vue'
import MultiSelectInfoBar from '@/components/common/MultiSelectInfoBar.vue'
import MultiSelectActionBar from '@/components/common/MultiSelectActionBar.vue'
import BatchProgressOverlay from '@/components/common/BatchProgressOverlay.vue'
import { useMultiSelect } from '@/composables/useMultiSelect'
import { JmcomicService, sanitizeError, showToast } from '@/services/JmcomicService'
import { LocalFileImportService } from '@/services/LocalFileImportService'
import { normalizeRuntimeError } from '@/runtime/errors'
import { LocalFileManagementService } from '@/services/LocalFileManagementService'
import type {
  LocalFileRecord,
  ExportTaskRecord,
  LocalFileManagementState,
} from '@/services/JmcomicTypes'

const props = defineProps<{
  // 页面级挂载点，必须位于 IonContent 外。
  overlayTarget: HTMLElement
  initialView?: 'files' | 'tasks'
  initialExportId?: string
}>()
const emit = defineEmits<{ 'selection-change': [active: boolean] }>()
const router = useRouter()
const managementSelection = useMultiSelect<string>()
const { active: managementSelectionActive } = managementSelection
const managementSelectionPreparing = ref(false)
const managementSelectionPreparingAction = ref<'select-all' | 'invert' | null>(null)
const managementSelectionExecuting = ref(false)
watch(
  [managementSelectionActive, managementSelectionExecuting],
  ([active, executing]) => emit('selection-change', active || executing),
  { immediate: true },
)
const managementProgressCurrent = ref(0)
const managementProgressTotal = ref(0)
const managementProgressTitle = ref('')
let managementSelectionToken = 0
const managerRef = ref<HTMLElement | null>(null)
const files = ref<LocalFileRecord[]>([])
const tasks = ref<ExportTaskRecord[]>([])
interface WindowPage<T> {
  items: T[]
  cursor: string | undefined
  nextCursor: string | null
  index: number
}

// 只保留三页记录；历史仅保存不透明请求游标，以便重新读取窗口前的页。
const filePages = ref<WindowPage<LocalFileRecord>[]>([])
const taskPages = ref<WindowPage<ExportTaskRecord>[]>([])
let filePageCursors: (string | undefined)[] = []
let taskPageCursors: (string | undefined)[] = []
const canLoadPreviousFiles = computed(() => (filePages.value[0]?.index ?? 0) > 0)
const canLoadPreviousTasks = computed(
  () => (taskPages.value.find((page) => page.index >= 0)?.index ?? 0) > 0,
)
const managementState = ref<LocalFileManagementState>({ recoveryState: 'ready' })
const loading = ref(false)
const fileLoadingMore = ref(false)
const taskLoadingMore = ref(false)
const fileLoadDirection = ref<'next' | 'previous'>('next')
const taskLoadDirection = ref<'next' | 'previous'>('next')
const fileLoadError = ref(false)
const taskLoadError = ref(false)
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
const filesSentinel = ref<HTMLElement | null>(null)
const tasksSentinel = ref<HTMLElement | null>(null)
let listObserver: IntersectionObserver | null = null

const observeListSentinels = async () => {
  await nextTick()
  if (!listObserver) return
  if (filesSentinel.value) listObserver.observe(filesSentinel.value)
  if (tasksSentinel.value) listObserver.observe(tasksSentinel.value)
}

const rebuildFileWindow = () => {
  files.value = filePages.value.flatMap((page) => page.items)
  fileCursor.value = filePages.value[filePages.value.length - 1]?.nextCursor ?? null
}
const rebuildTaskWindow = () => {
  tasks.value = taskPages.value.flatMap((page) => page.items)
  taskCursor.value = taskPages.value[taskPages.value.length - 1]?.nextCursor ?? null
}
const updateTaskWindow = (update: (task: ExportTaskRecord) => ExportTaskRecord | null) => {
  taskPages.value = taskPages.value.map((page) => ({
    ...page,
    items: page.items.map(update).filter((task): task is ExportTaskRecord => task !== null),
  }))
  rebuildTaskWindow()
}

const updateFileWindow = (update: (file: LocalFileRecord) => LocalFileRecord | null) => {
  filePages.value = filePages.value.map((page) => ({
    ...page,
    items: page.items.map(update).filter((file): file is LocalFileRecord => file !== null),
  }))
  rebuildFileWindow()
}

const captureWindowAnchor = async (view: 'files' | 'tasks') => {
  const content = managerRef.value?.closest<HTMLIonContentElement>('ion-content')
  const scroll = await content?.getScrollElement()
  const top = scroll?.getBoundingClientRect().top
  const selector = view === 'files' ? '[data-file-id]' : '.task-anchor'
  const anchor =
    top === undefined
      ? undefined
      : Array.from(managerRef.value?.querySelectorAll<HTMLElement>(selector) ?? []).find(
          (element) => element.getBoundingClientRect().bottom > top,
        )
  const anchorTop = anchor?.getBoundingClientRect().top
  return async () => {
    await nextTick()
    if (scroll && anchor?.isConnected && anchorTop !== undefined) {
      scroll.scrollTop += anchor.getBoundingClientRect().top - anchorTop
    }
  }
}

const updateVisibleFiles = (updated: LocalFileRecord[]) => {
  const byId = new Map(updated.map((file) => [file.id, file]))
  updateFileWindow((file) => {
    const updatedFile = byId.get(file.id)
    return updatedFile && updatedFile.updatedAt >= file.updatedAt ? updatedFile : file
  })
}

const removeFileAndFill = async (id: number) => {
  fileRequestSequence++
  updateFileWindow((file) => (file.id === id ? null : file))
  void loadImageResourceKeys()
  if (fileCursor.value) await loadFiles(false, 'refresh')
}

const removeTaskAndFill = async (exportId: string) => {
  taskRequestSequence++
  updateTaskWindow((task) => (task.exportId === exportId ? null : task))
  if (taskCursor.value) await loadTasks(false, 'refresh')
}

const mergeTaskIntoWindow = (task: ExportTaskRecord) => {
  let found = false
  updateTaskWindow((current) => {
    if (current.exportId !== task.exportId) return current
    found = true
    return task.snapshotRevision >= current.snapshotRevision ? task : current
  })
  if (!found) return false
  return true
}
const highlightedExportId = ref<string | null>(null)
const imageResourceKeys = ref<Set<string>>(new Set())
const verifyingIds = ref<Set<number>>(new Set())
let progressHandle: { remove: () => Promise<void> } | null = null
let stateInvalidatedHandle: { remove: () => Promise<void> } | null = null
let fileRequestSequence = 0
let taskRequestSequence = 0
let imageResourceRequestSequence = 0
let focusTaskRequestSequence = 0
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
const fileFormatOptions: readonly { value: typeof fileFormat.value; label: string }[] = [
  { value: 'all', label: '全部格式' },
  { value: 'pdf', label: 'PDF' },
  { value: 'cbz', label: 'CBZ' },
]
const taskFormatOptions: readonly { value: typeof taskFormat.value; label: string }[] = [
  { value: 'all', label: '全部格式' },
  { value: 'pdf', label: 'PDF' },
  { value: 'cbz', label: 'CBZ' },
  { value: 'zip', label: 'ZIP' },
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
  if (file) {
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
    { id: 'multi-select', label: '多选', icon: listOutline },
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

const refreshFilesInBackground = async (pageFiles: LocalFileRecord[]) => {
  const ids = pageFiles
    .filter((file) => file.format === 'pdf')
    .map((file) => file.id)
    .filter((id) => !verifyingIds.value.has(id))
  if (!ids.length) return

  setVerifying(ids, true)
  const changeSequence = stateChangeSequence
  try {
    const updated = await LocalFileManagementService.refreshFiles(ids)
    if (!isUnmounted && changeSequence === stateChangeSequence) updateVisibleFiles(updated)
  } catch (error) {
    await showToast(sanitizeError(error, '文件状态刷新失败'), 'danger')
  } finally {
    setVerifying(ids, false)
  }
}

const loadFiles = async (reset: boolean, direction: 'next' | 'previous' | 'refresh' = 'next') => {
  const requestSequence = ++fileRequestSequence
  const changeSequence = stateChangeSequence
  const filters = currentFileFilters()
  const first = filePages.value[0]
  const last = filePages.value[filePages.value.length - 1]
  const index = reset
    ? 0
    : direction === 'previous'
      ? first.index - 1
      : direction === 'refresh'
        ? first.index
        : (last?.index ?? -1) + 1
  let cursor = reset
    ? undefined
    : direction === 'next'
      ? fileCursor.value || undefined
      : direction === 'refresh'
        ? first.cursor
        : filePageCursors[index]
  const incoming: WindowPage<LocalFileRecord>[] = []
  try {
    const count = direction === 'refresh' ? Math.max(1, filePages.value.length) : 1
    for (let offset = 0; offset < count; offset++) {
      const page = await LocalFileManagementService.getFiles(filters, cursor)
      incoming.push({ ...page, cursor, index: index + offset })
      if (!page.nextCursor) break
      cursor = page.nextCursor
    }
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
  const restoreAnchor = await captureWindowAnchor('files')
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
  if (reset) filePageCursors = []
  for (const page of incoming) {
    filePageCursors[page.index] = page.cursor
    if (page.nextCursor) filePageCursors[page.index + 1] = page.nextCursor
  }
  const ids = new Set(incoming.flatMap((page) => page.items.map((file) => file.id)))
  const kept = filePages.value.map((page) => ({
    ...page,
    items: page.items.filter((file) => !ids.has(file.id)),
  }))
  filePages.value =
    reset || direction === 'refresh'
      ? incoming
      : direction === 'previous'
        ? [...incoming, ...kept].slice(0, 3)
        : [...kept, ...incoming].slice(-3)
  rebuildFileWindow()
  fileLoadError.value = false
  void loadImageResourceKeys()
  await restoreAnchor()
  void observeListSentinels()
  for (const page of incoming) void refreshFilesInBackground(page.items)
}

const loadTasks = async (reset: boolean, direction: 'next' | 'previous' | 'refresh' = 'next') => {
  const requestSequence = ++taskRequestSequence
  const changeSequence = stateChangeSequence
  const filters = currentTaskFilters()
  const first = taskPages.value.find((page) => page.index >= 0)
  const last = taskPages.value[taskPages.value.length - 1]
  const index = reset
    ? 0
    : direction === 'previous'
      ? (first?.index ?? 0) - 1
      : direction === 'refresh'
        ? (first?.index ?? 0)
        : (last?.index ?? -1) + 1
  let cursor = reset
    ? undefined
    : direction === 'next'
      ? taskCursor.value || undefined
      : direction === 'refresh'
        ? first?.cursor
        : taskPageCursors[index]
  const incoming: WindowPage<ExportTaskRecord>[] = []
  try {
    const count =
      direction === 'refresh'
        ? Math.max(1, taskPages.value.filter((page) => page.index >= 0).length)
        : 1
    for (let offset = 0; offset < count; offset++) {
      const page = await LocalFileManagementService.getTasks(filters, cursor)
      incoming.push({ ...page, cursor, index: index + offset })
      if (!page.nextCursor) break
      cursor = page.nextCursor
    }
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
  const restoreAnchor = await captureWindowAnchor('tasks')
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
  if (reset) taskPageCursors = []
  for (const page of incoming) {
    taskPageCursors[page.index] = page.cursor
    if (page.nextCursor) taskPageCursors[page.index + 1] = page.nextCursor
  }
  const ids = new Set(incoming.flatMap((page) => page.items.map((task) => task.exportId)))
  const kept = taskPages.value.map((page) => ({
    ...page,
    items: page.items.filter((task) => !ids.has(task.exportId)),
  }))
  taskPages.value =
    reset || direction === 'refresh'
      ? incoming
      : direction === 'previous'
        ? [...incoming, ...kept].slice(0, 3)
        : [...kept, ...incoming].slice(-3)
  rebuildTaskWindow()
  taskLoadError.value = false
  await restoreAnchor()
  void observeListSentinels()
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
  const fileSequence = fileRequestSequence
  const items = files.value
    .filter((file) => file.albumId && file.chapterId)
    .map((file) => ({ albumId: file.albumId!, chapterId: file.chapterId! }))
  if (!items.length) {
    imageResourceKeys.value = new Set()
    return
  }
  try {
    const result = await JmcomicService.getDownloadedResourceKeys(items)
    if (
      isUnmounted ||
      requestSequence !== imageResourceRequestSequence ||
      fileSequence !== fileRequestSequence
    )
      return
    imageResourceKeys.value = new Set(result.keys)
  } catch (error) {
    if (
      isUnmounted ||
      requestSequence !== imageResourceRequestSequence ||
      fileSequence !== fileRequestSequence
    )
      return
    imageResourceKeys.value = new Set()
    console.warn('读取文件图片资源标记失败', error)
  }
}

const hasImageResource = (file: LocalFileRecord) =>
  Boolean(file.chapterId && imageResourceKeys.value.has(`${file.albumId}|${file.chapterId}`))

const focusTask = async (exportId: string) => {
  activeView.value = 'tasks'
  const requestSequence = ++focusTaskRequestSequence
  const changeSequence = stateChangeSequence
  try {
    const task = await LocalFileManagementService.getTask(exportId)
    if (isUnmounted || requestSequence !== focusTaskRequestSequence) return
    if (changeSequence !== stateChangeSequence) {
      await requestTasksReload()
      return
    }
    if (!mergeTaskIntoWindow(task)) {
      // 定位项独占临时页；被移出的末页保留在尾游标之后，可继续加载。
      taskRequestSequence++
      const kept = taskPages.value.filter((page) => page.index >= 0).slice(0, 2)
      taskPages.value = [
        { items: [task], index: -1, cursor: undefined, nextCursor: kept[0]?.cursor ?? null },
        ...kept,
      ]
      rebuildTaskWindow()
    }
    highlightedExportId.value = exportId
    await nextTick()
    document.getElementById(`export-task-${exportId}`)?.scrollIntoView?.({ block: 'center' })
  } catch (error) {
    if (isUnmounted || requestSequence !== focusTaskRequestSequence) return
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
        await Promise.all([requestFilesReload(), requestTasksReload()])
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

const loadMoreFiles = async (direction: 'next' | 'previous' = 'next') => {
  if (
    fileLoadingMore.value ||
    (direction === 'next' ? !fileCursor.value : !canLoadPreviousFiles.value)
  )
    return
  fileLoadingMore.value = true
  fileLoadDirection.value = direction
  try {
    await loadFiles(false, direction)
    fileLoadError.value = false
  } catch (error) {
    fileLoadError.value = true
    await showToast(sanitizeError(error, '加载文件失败'), 'danger')
  } finally {
    fileLoadingMore.value = false
  }
}
const loadMoreTasks = async (direction: 'next' | 'previous' = 'next') => {
  if (
    taskLoadingMore.value ||
    (direction === 'next' ? !taskCursor.value : !canLoadPreviousTasks.value)
  )
    return
  taskLoadingMore.value = true
  taskLoadDirection.value = direction
  try {
    await loadTasks(false, direction)
    taskLoadError.value = false
  } catch (error) {
    taskLoadError.value = true
    await showToast(sanitizeError(error, '加载导出任务失败'), 'danger')
  } finally {
    taskLoadingMore.value = false
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
  void router.push(ChapterSourceService.fileReaderLocation(file))
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

  if (action === 'multi-select') enterManagementSelection(`f:${file.id}`)
  else if (action === 'read' && file.format !== 'zip') readFile(file)
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
    await removeFileAndFill(file.id)
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
                await removeFileAndFill(file.id)
                await showToast(
                  result.result === 'already_missing' ? '文件已缺失，记录已移出' : '已删除文件',
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
    mergeTaskIntoWindow(updated)
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
              mergeTaskIntoWindow(updated)
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
              const result = await LocalFileManagementService.deleteTaskRecord(task.exportId)
              if (!result.success) throw new Error('删除任务记录失败')
              await removeTaskAndFill(task.exportId)
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

const managementRecords = ref(new Map<string, LocalFileRecord | ExportTaskRecord>())

function selectManagementView(view: 'files' | 'tasks') {
  if (managementSelectionActive.value) return
  activeView.value = view
}

function enterManagementSelection(id?: string) {
  if (id?.startsWith('t:')) {
    const task = tasks.value.find((item) => `t:${item.exportId}` === id)
    if (task && ['queued', 'running', 'cancelling'].includes(task.status)) return
  }
  managementSelection.enter(id)
}

function exitManagementSelection() {
  managementSelectionToken++
  managementSelection.exit()
  managementSelectionPreparing.value = false
  managementSelectionPreparingAction.value = null
  managementRecords.value = new Map()
}

useBackButton(5, (processNextHandler) => {
  if (managementSelectionExecuting.value) return
  if (managementSelectionActive.value) exitManagementSelection()
  else processNextHandler()
})

const selectFileFromCard = (id: number) => {
  managementSelection.toggle(`f:${id}`)
}
const enterFileFromCard = (id: number) => {
  enterManagementSelection(`f:${id}`)
}
const selectTaskFromCard = (id: string) => {
  managementSelection.toggle(`t:${id}`)
}
const enterTaskFromCard = (id: string) => {
  enterManagementSelection(`t:${id}`)
}

async function prepareManagementSelection(invert: boolean) {
  if (managementSelectionPreparing.value || managementSelectionExecuting.value) return
  const token = ++managementSelectionToken
  const view = activeView.value
  const records = new Map<string, LocalFileRecord | ExportTaskRecord>()
  managementSelectionPreparing.value = true
  managementSelectionPreparingAction.value = invert ? 'invert' : 'select-all'
  try {
    let cursor: string | undefined
    if (view === 'files') {
      do {
        const page = await LocalFileManagementService.getFiles(currentFileFilters(), cursor)
        if (token !== managementSelectionToken) return
        for (const file of page.items) records.set(`f:${file.id}`, file)
        cursor = page.nextCursor ?? undefined
      } while (cursor)
    } else {
      do {
        const page = await LocalFileManagementService.getTasks(currentTaskFilters(), cursor)
        if (token !== managementSelectionToken) return
        for (const task of page.items)
          if (!['queued', 'running', 'cancelling'].includes(task.status))
            records.set(`t:${task.exportId}`, task)
        cursor = page.nextCursor ?? undefined
      } while (cursor)
    }
    if (token !== managementSelectionToken || view !== activeView.value) return
    managementRecords.value = records
    const ids = [...records.keys()]
    if (invert) managementSelection.invert(ids)
    else managementSelection.selectAll(ids)
  } catch {
    if (token === managementSelectionToken)
      await showToast('读取完整管理列表失败，本次选择未生效', 'danger')
  } finally {
    if (token === managementSelectionToken) {
      managementSelectionPreparing.value = false
      managementSelectionPreparingAction.value = null
    }
  }
}

async function executeManagementDelete() {
  if (managementSelectionExecuting.value || managementSelectionPreparing.value) return
  const records = [...managementSelection.selected.value]
    .map(
      (id) =>
        managementRecords.value.get(id) ??
        (id.startsWith('f:')
          ? files.value.find((file) => `f:${file.id}` === id)
          : tasks.value.find((task) => `t:${task.exportId}` === id)),
    )
    .filter((record): record is LocalFileRecord | ExportTaskRecord => !!record)
  if (!records.length) return
  const isFiles = activeView.value === 'files'
  const details = isFiles
    ? (records as LocalFileRecord[])
        .map((file) => `${file.fileName}\n${file.displayPath}`)
        .join('\n\n')
    : `只删除 ${records.length} 条已结束的导出任务记录，最终文件不会被删除。`
  const alert = await createAppAlert({
    header: isFiles ? '确认删除实际文件' : '确认删除任务记录',
    message: isFiles
      ? `${details}\n\n将删除定位符当前指向的实际文件并移出文件库；若文件已被替换，替换后的当前文件也会被删除。此操作不可恢复。`
      : details,
    buttons: [
      { text: '取消', role: 'cancel' },
      {
        text: isFiles ? '删除文件' : '删除记录',
        role: 'destructive',
        handler: () => {
          void (async () => {
            managementSelectionExecuting.value = true
            managementProgressCurrent.value = 0
            managementProgressTotal.value = records.length
            let succeeded = 0
            let failed = 0
            try {
              for (const record of records) {
                managementProgressTitle.value = isFiles
                  ? (record as LocalFileRecord).fileName
                  : (record as ExportTaskRecord).displayTitle
                try {
                  if (isFiles) {
                    const file = record as LocalFileRecord
                    const current = await LocalFileManagementService.inspectFileForDeletion(file.id)
                    if (!current) throw new Error('文件不可定位')
                    await LocalFileManagementService.deleteFile(file.id)
                    await removeFileAndFill(file.id)
                  } else {
                    const task = record as ExportTaskRecord
                    const result = await LocalFileManagementService.deleteTaskRecord(task.exportId)
                    if (!result.success) throw new Error('删除任务记录失败')
                    await removeTaskAndFill(task.exportId)
                  }
                  succeeded++
                } catch {
                  failed++
                }
                managementProgressCurrent.value++
              }
            } finally {
              managementSelectionExecuting.value = false
              exitManagementSelection()
              await showToast(
                `删除完成：成功 ${succeeded} 项，失败 ${failed} 项`,
                failed ? 'medium' : 'success',
              )
            }
          })()
        },
      },
    ],
  })
  await alert.present()
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
  listObserver = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue
        if (entry.target === filesSentinel.value && activeView.value === 'files')
          void loadMoreFiles()
        if (entry.target === tasksSentinel.value && activeView.value === 'tasks')
          void loadMoreTasks()
      }
    },
    { rootMargin: '240px' },
  )
  if (filesSentinel.value) listObserver.observe(filesSentinel.value)
  if (tasksSentinel.value) listObserver.observe(tasksSentinel.value)
  const progressRegistration = (async () => {
    try {
      const handle = await LocalFileManagementService.addProgressListener((event) => {
        stateChangeSequence++
        if (
          (taskFilter.value !== 'all' && taskFilter.value !== event.status) ||
          (taskFormat.value !== 'all' && taskFormat.value !== event.format)
        ) {
          updateTaskWindow((task) => (task.exportId === event.exportId ? null : task))
        } else {
          const current = tasks.value.find((task) => task.exportId === event.exportId)
          if (!current) void reloadTasksWithFeedback()
          else if (event.snapshotRevision >= current.snapshotRevision) {
            updateTaskWindow((task) =>
              task.exportId === event.exportId ? { ...task, ...event } : task,
            )
          }
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

watch([fileFilter, fileFormat, searchText, taskFilter, taskFormat, activeView], () => {
  if (managementSelectionActive.value) exitManagementSelection()
})
onUnmounted(() => {
  isUnmounted = true
  listObserver?.disconnect()
  listObserver = null
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
  width: 100%;
  max-width: 1000px;
  box-sizing: border-box;
  margin-inline: auto;
  padding: 8px 16px 86px;
}

.manager-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-bottom: 12px;
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
  background: #fff;
  box-shadow: 0 2px 10px rgba(115, 67, 38, 0.06);
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
  margin-bottom: 14px;
  padding: 4px 14px;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 2px 10px rgba(115, 67, 38, 0.06);
}

.subtabs button {
  flex: 1;
  height: 34px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #8a6048;
  font-size: 12px;
  font-weight: 600;
}

.subtabs button.active {
  background: #fff0e7;
  color: #e8843c;
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
  margin: 0 0 14px;
  padding: 12px;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 2px 10px rgba(115, 67, 38, 0.06);
}

.search-field {
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

.search-field input {
  min-height: 36px;
  border: 1px solid #ead1c1;
  border-radius: 8px;
  padding: 0 10px;
  color: #4c2a18;
  background: #fff;
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
  border-radius: 8px;
  padding: 0 14px;
  background: transparent;
  color: #8a6048;
  font-size: 13px;
}

.filter-buttons button.selected {
  background: #fff1e7;
  color: #e8843c;
  font-weight: 600;
}

.task-filters {
  align-items: flex-start;
}

.task-status-group {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.filter-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
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
  margin-top: 0;
}

.item-wrap {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.task-anchor {
  border-radius: 12px;
}

.task-anchor.highlighted {
  outline: 2px solid #c06f45;
  outline-offset: 2px;
}

.load-more-btn {
  width: 100%;
  border: 1px solid #ead1c1;
}

.list-sentinel {
  min-height: 1px;
  pointer-events: none;
}

.list-loader {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 52px;
  color: #fa9c69;
}

.list .load-more-btn {
  min-height: 44px;
  border: 0;
  border-radius: 8px;
  background: #fff0e8;
  color: #c96d3a;
  font-size: 12px;
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
.multi-select-overlay {
  --desktop-page-max-width: 1000px;
  position: fixed;
  inset: 0;
  z-index: 30;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: space-between;
  pointer-events: none;
}
.multi-select-overlay > * {
  pointer-events: auto;
}
</style>
