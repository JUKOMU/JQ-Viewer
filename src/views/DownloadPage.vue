<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <div class="download-page-shell desktop-page-content">
          <div class="toolbar-start">
            <MenuToggleButton />
          </div>
          <div class="view-tabs" role="tablist" aria-label="下载页面视图">
            <button
              type="button"
              role="tab"
              class="tab-btn"
              :aria-selected="activeMainView === 'downloads'"
              :disabled="downloadSelectionActive"
              :class="{ active: activeMainView === 'downloads' }"
              @click="selectMainView('downloads')"
            >
              下载
            </button>
            <button
              type="button"
              role="tab"
              class="tab-btn"
              :aria-selected="activeMainView === 'export'"
              :disabled="downloadSelectionActive"
              :class="{ active: activeMainView === 'export' }"
              @click="selectMainView('export')"
            >
              导出
            </button>
          </div>
        </div>
      </IonToolbar>
    </IonHeader>
    <IonContent
      v-show="activeMainView === 'downloads'"
      ref="downloadContentRef"
      @ionScroll="onDownloadScroll"
    >
      <!-- 下拉刷新 -->
      <IonRefresher
        slot="fixed"
        :disabled="downloadSelectionActive"
        @ion-refresh="onRefresh($event)"
      >
        <IonRefresherContent />
      </IonRefresher>

      <div class="download-page-shell desktop-page-content">
        <div v-if="initialLoading" class="download-list-loader">
          <IonSpinner name="dots" />
        </div>
        <div v-else-if="!hasTasks" class="empty-state">
          <IonIcon :icon="cloudDownloadOutline" class="empty-icon" />
          <p>暂无下载内容</p>
        </div>

        <template v-else>
          <!-- 存储信息 -->
          <div v-if="hasStorageInfo" class="storage-bar">
            <IonIcon :icon="saveOutline" class="storage-icon" />
            <span>已用 {{ spaceUsedMb }}MB / 可用 {{ spaceAvailMb }}MB</span>
          </div>
          <div v-if="hasTasks" class="sort-label-row">
            <button
              type="button"
              class="sort-btn sort-direction-btn"
              :disabled="downloadSelectionActive"
              :aria-label="sortDirection === 'asc' ? '升序' : '降序'"
              :title="sortDirection === 'asc' ? '升序' : '降序'"
              @click="toggleSortDirection"
            >
              <IonIcon :icon="sortDirection === 'asc' ? arrowUpOutline : arrowDownOutline" />
            </button>
            <button
              type="button"
              class="sort-btn"
              :aria-label="sortLabel"
              :disabled="downloadSelectionActive"
              @click="toggleSort"
            >
              <IonIcon :icon="sortIcon" />
            </button>
            <span class="toolbar-action-label">{{ sortLabel }}</span>
          </div>

          <section
            v-for="group in downloadGroups"
            :key="group.key"
            v-memo="[
              group.items,
              group.loading,
              group.error,
              group.collapsed,
              group.totalCount,
              group.previousCursor,
              group.nextCursor,
              isActionMenuOpen,
              selectedTask,
              downloadSelectionActive,
              downloadSelectedIds,
            ]"
            class="download-group section"
          >
            <h2 class="section-header">
              <button
                type="button"
                class="section-toggle"
                :aria-expanded="!group.collapsed"
                :disabled="downloadSelectionActive"
                :aria-controls="`download-group-${group.key}`"
                :aria-label="`${group.collapsed ? '展开' : '收起'}${group.label}`"
                @click="toggleGroup(group.key)"
              >
                <span class="section-title">{{ group.label }} ({{ group.totalCount }})</span>
                <IonIcon
                  :icon="chevronDownOutline"
                  class="section-toggle-icon"
                  :class="{ collapsed: group.collapsed }"
                  aria-hidden="true"
                />
              </button>
              <button
                v-if="group.clearable"
                type="button"
                class="clear-btn"
                :disabled="downloadSelectionActive"
                @click="requestClear(group.status as 'completed' | 'failed')"
              >
                清空
              </button>
              <button
                v-if="group.clearable"
                type="button"
                class="clear-btn"
                :disabled="downloadSelectionActive"
                @click="enterGroupDownloadSelection(group.key)"
              >
                多选
              </button>
            </h2>
            <div
              v-if="!group.collapsed"
              :id="`download-group-${group.key}`"
              class="section-content"
            >
              <button
                v-if="group.previousCursor"
                type="button"
                class="group-previous"
                @click="loadGroup(group.key, 'previous')"
              >
                加载上一页
              </button>
              <div
                v-for="item in group.items"
                :key="itemKey(item)"
                :data-download-key="itemKey(item)"
                class="task-card"
              >
                <DownloadTaskCard
                  v-if="group.status !== 'completed'"
                  :task="item as DownloadTask"
                  :show-progress="group.status === 'active'"
                  :menu-open="isTaskMenuOpen(item as DownloadTask)"
                  :selection-mode="downloadSelectionActive"
                  :selection-eligible="(item as DownloadTask).status !== 'verifying'"
                  :selected="downloadSelectedIds.has((item as DownloadTask).taskId)"
                  @more="openActions(item as DownloadTask, $event)"
                  @select="toggleDownloadSelection((item as DownloadTask).taskId)"
                  @long-press="enterEligibleDownloadSelection(item as DownloadTask)"
                />
                <DownloadTaskCard
                  v-else
                  :task="groupEntry(item as CompletedDownloadGroup)"
                  :show-progress="false"
                  :total-size="(item as CompletedDownloadGroup).totalSize"
                  :downloaded-chapters="
                    (item as CompletedDownloadGroup).type === 'multi'
                      ? (item as CompletedDownloadGroup).chapters.map((chapter) =>
                          toCompletedEntry(chapter, item as CompletedDownloadGroup),
                        )
                      : undefined
                  "
                  :menu-open="isTaskMenuOpen(groupEntry(item as CompletedDownloadGroup))"
                  :selection-mode="downloadSelectionActive"
                  :selection-eligible="(item as CompletedDownloadGroup).type === 'single'"
                  :selected="
                    downloadSelectedIds.has(
                      (item as CompletedDownloadGroup).chapters[0]?.taskId ?? '',
                    )
                  "
                  @click="onReadGroup(item as CompletedDownloadGroup)"
                  @more="openGroupActions(item as CompletedDownloadGroup, $event)"
                  @select="
                    toggleDownloadSelection(
                      (item as CompletedDownloadGroup).chapters[0]?.taskId ?? '',
                    )
                  "
                  @long-press="enterCompletedDownloadSelection(item as CompletedDownloadGroup)"
                />
              </div>
              <div v-if="group.loading" class="browse-group-loader"><IonSpinner name="dots" /></div>
              <button
                v-else-if="group.error"
                type="button"
                class="browse-group-retry"
                @click="loadGroup(group.key, group.loadDirection)"
              >
                加载失败，点击重试
              </button>
              <div
                v-if="group.nextCursor"
                class="group-sentinel"
                :data-download-sentinel="group.key"
              ></div>
            </div>
          </section>
          <div class="bottom-spacer" />
        </template>
      </div>
    </IonContent>
    <div
      v-if="activeMainView === 'downloads' && downloadSelectionActive"
      class="multi-select-overlay"
    >
      <MultiSelectInfoBar
        :count="downloadSelectionCount"
        :preparing="downloadSelectionPreparing"
        @cancel="cancelDownloadSelection"
        @cancel-preparation="cancelDownloadPreparation"
        @select-all="prepareDownloadSelection(false)"
        @invert="prepareDownloadSelection(true)"
      />
      <MultiSelectActionBar
        :actions="downloadSelectionActions"
        :disabled="
          !downloadSelectionCount || downloadSelectionPreparing || downloadSelectionExecuting
        "
        @action="executeDownloadSelection"
      />
    </div>
    <div v-if="downloadBatchRunning" class="download-batch-progress" role="status">
      {{ downloadBatchActionLabel }}：{{ downloadBatchCurrent }} / {{ downloadBatchTotal }}
      <div>{{ downloadBatchCurrentTitle }}</div>
    </div>
    <IonContent v-show="activeMainView === 'export'">
      <div class="download-page-shell desktop-page-content">
        <LocalFileManagementView
          ref="exportManagementRef"
          :initial-view="exportInitialView"
          :initial-export-id="exportInitialExportId"
        />
      </div>
    </IonContent>

    <CardContextMenu
      :visible="isActionMenuOpen"
      :anchor="actionMenuAnchor"
      :title="popoverTitle"
      :actions="actionMenuActions"
      @close="closeActions"
      @select="popoverAction"
    />

    <!-- 导出底部面板 -->
    <ExportBottomSheet
      v-model="showExportSheet"
      :chapters="chaptersForExport"
      :allow-merge="!downloadBatchExport"
      @confirm="onExportConfirm"
    />

    <!-- 多章节删除底部面板 -->
    <DeleteChaptersBottomSheet
      v-model="showDeleteSheet"
      :album-title="deleteGroup?.albumTitle ?? ''"
      :chapters="deleteGroupChapters"
      @confirm="onDeleteSheetConfirm"
    />
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({ name: 'DownloadPage' })

import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  IonContent,
  IonHeader,
  IonIcon,
  IonPage,
  IonRefresher,
  IonRefresherContent,
  IonSpinner,
  IonToolbar,
  onIonViewWillEnter,
  useBackButton,
} from '@ionic/vue'
import {
  bookOutline,
  arrowDownOutline,
  arrowUpOutline,
  chevronDownOutline,
  closeCircleOutline,
  cloudDownloadOutline,
  documentLockOutline,
  informationCircleOutline,
  listOutline,
  pauseOutline,
  playOutline,
  refreshOutline,
  saveOutline,
  textOutline,
  timeOutline,
  trashOutline,
} from 'ionicons/icons'
import type { ListenerHandle } from '@/runtime/BackendEvents'
import MenuToggleButton from '@/components/common/MenuToggleButton.vue'
import DownloadTaskCard from '@/components/download/DownloadTaskCard.vue'
import ExportBottomSheet from '@/components/download/ExportBottomSheet.vue'
import DeleteChaptersBottomSheet from '@/components/download/DeleteChaptersBottomSheet.vue'
import LocalFileManagementView from '@/components/download/LocalFileManagementView.vue'
import CardContextMenu from '@/components/common/CardContextMenu.vue'
import MultiSelectInfoBar from '@/components/common/MultiSelectInfoBar.vue'
import MultiSelectActionBar from '@/components/common/MultiSelectActionBar.vue'
import { useMultiSelect } from '@/composables/useMultiSelect'
import { createAppAlert } from '@/services/AppAlertService'
import { JmcomicService, sanitizeError, showToast } from '@/services/JmcomicService'
import { OfflineDownloadService } from '@/services/OfflineDownloadService'
import { ExportService, type ExportPlan } from '@/services/ExportService'
import {
  downloadRefreshGroupForStatus,
  shouldLoadDownloadNextPage,
  shouldLoadDownloadPreviousPage,
} from '@/utils/downloadPagination'
import type {
  CompletedEntry,
  CompletedDownloadGroup,
  DownloadTask,
  DownloadTaskPageStatus,
  DownloadTaskSort,
  DownloadTaskSortDirection,
  ExportMode,
  ExportFormat,
} from '@/services/JmcomicTypes'

const router = useRouter()
const route = useRoute()
const activeMainView = ref<'downloads' | 'export'>(
  route.query.view === 'pdf' || route.query.view === 'export' ? 'export' : 'downloads',
)
const exportInitialView = computed<'files' | 'tasks'>(() =>
  route.query.tab === 'tasks' ? 'tasks' : 'files',
)
const exportInitialExportId = computed(() =>
  typeof route.query.exportId === 'string' ? route.query.exportId : undefined,
)
const exportManagementRef = ref<InstanceType<typeof LocalFileManagementView> | null>(null)
const downloadSelection = useMultiSelect<string>()
const {
  active: downloadSelectionActive,
  selected: downloadSelectedIds,
  count: downloadSelectionCount,
  enter: enterDownloadSelection,
  toggle: toggleDownloadSelection,
  select: selectDownloadItems,
  invert: invertDownloadItems,
  exit: exitDownloadSelection,
} = downloadSelection
const downloadSelectionPreparing = ref(false)
const downloadSelectionExecuting = ref(false)
const downloadBatchRunning = ref(false)
const downloadBatchActionLabel = ref('正在处理')
const downloadBatchCurrent = ref(0)
const downloadBatchTotal = ref(0)
const downloadBatchCurrentTitle = ref('')
let downloadSelectionToken = 0
useBackButton(5, (processNextHandler) => {
  if (downloadSelectionExecuting.value || downloadBatchRunning.value) return
  if (downloadSelectionActive.value) cancelDownloadSelection()
  else processNextHandler()
})
let ionEnterCount = 0

const selectMainView = (view: 'downloads' | 'export') => {
  if (downloadSelectionActive.value) return
  activeMainView.value = view
}

watch(
  () => route.query.view,
  (view) => {
    activeMainView.value = view === 'pdf' || view === 'export' ? 'export' : 'downloads'
  },
)

type DownloadGroupKey = 'active' | 'completed' | 'failed'
type DownloadPageItem = DownloadTask | CompletedDownloadGroup

interface DownloadGroupPage {
  items: DownloadPageItem[]
  nextCursor: string | null
  previousCursor: string | null
}

interface DownloadGroupState {
  key: DownloadGroupKey
  status: DownloadTaskPageStatus
  label: string
  clearable: boolean
  items: DownloadPageItem[]
  pages: DownloadGroupPage[]
  nextCursor: string | null
  previousCursor: string | null
  totalCount: number
  loading: boolean
  error: boolean
  collapsed: boolean
  initialized: boolean
  requestId: number
  loadDirection: 'next' | 'previous'
}

const createDownloadGroup = (
  key: DownloadGroupKey,
  label: string,
  clearable = false,
): DownloadGroupState => ({
  key,
  status: key,
  label,
  clearable,
  items: [],
  pages: [],
  nextCursor: null,
  previousCursor: null,
  totalCount: 0,
  loading: false,
  error: false,
  collapsed: false,
  initialized: false,
  requestId: 0,
  loadDirection: 'next',
})

const downloadGroupStates = reactive<Record<DownloadGroupKey, DownloadGroupState>>({
  active: createDownloadGroup('active', '下载中'),
  completed: createDownloadGroup('completed', '已完成', true),
  failed: createDownloadGroup('failed', '下载失败', true),
})
const downloadGroups = computed(() => Object.values(downloadGroupStates))
const downloadContentRef = ref<any>(null)
const tasks = ref<DownloadTask[]>([])
const initialLoading = computed(
  () =>
    !hasTasks.value && downloadGroups.value.some((group) => group.loading && !group.initialized),
)
const hasTasks = computed(() =>
  downloadGroups.value.some((group) => group.totalCount > 0 || group.items.length > 0),
)
const spaceUsedMb = ref(0)
const spaceAvailMb = ref(0)
const hasStorageInfo = ref(false)
let downloadProgressHandle: ListenerHandle | null = null
let stateInvalidatedHandle: ListenerHandle | null = null
let syncPromise: Promise<void> | null = null
let syncRequested = false
let syncCollapsedRequested = false
let downloadStateSequence = 0
let speedTimer: ReturnType<typeof setInterval> | null = null
let isUnmounted = false

interface SpeedSample {
  latestBytes: number
  sampledBytes: number
  sampledAt: number
  lastSpeed: number
}

const speedSamples = new Map<string, SpeedSample>()
const unseenActiveTaskIds = new Set<string>()

// 排序
type SortMode = DownloadTaskSort
const sortMode = ref<SortMode>('time')
const sortDirection = ref<DownloadTaskSortDirection>('desc')
const sortIcon = computed(() => {
  if (sortMode.value === 'time') return timeOutline
  if (sortMode.value === 'author') return textOutline
  if (sortMode.value === 'id') return textOutline
  return textOutline
})
const sortLabel = computed(
  () => ({ time: '时间排序', title: '标题排序', id: 'ID排序', author: '作者排序' })[sortMode.value],
)
const defaultDirection = (mode: SortMode): DownloadTaskSortDirection =>
  mode === 'time' ? 'desc' : 'asc'

const toggleSort = () => {
  if (downloadSelectionActive.value) return
  const modes: SortMode[] = ['time', 'title', 'id', 'author']
  sortMode.value = modes[(modes.indexOf(sortMode.value) + 1) % modes.length]
  sortDirection.value = defaultDirection(sortMode.value)
  void downloadContentRef.value?.$el?.scrollToTop?.(0)
  void resetDownloadGroups(true, false)
}
const toggleSortDirection = () => {
  if (downloadSelectionActive.value) return
  sortDirection.value = sortDirection.value === 'asc' ? 'desc' : 'asc'
  void downloadContentRef.value?.$el?.scrollToTop?.(0)
  void resetDownloadGroups(true, false)
}

// 操作菜单
const selectedTask = ref<DownloadTask | CompletedEntry | null>(null)
const selectedGroup = ref<CompletedDownloadGroup | null>(null)
const isActionMenuOpen = ref(false)
const actionMenuAnchor = ref<HTMLElement | null>(null)

const cancelableStatuses = ['queued', 'downloading', 'paused']
const deletableStatuses = ['completed', 'failed']

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

const isTaskMenuOpen = (task: DownloadTask | CompletedEntry) =>
  isActionMenuOpen.value && selectedTask.value === task

// 文件导出
const showExportSheet = ref(false)
const chaptersForExport = ref<DownloadTask[]>([])
const downloadBatchExport = ref(false)

const popoverTitle = computed(() => {
  if (selectedGroup.value?.type === 'multi') {
    return selectedGroup.value.albumTitle
  }
  return selectedTask.value?.chapterTitle ?? ''
})

const actionMenuActions = computed(() => {
  const actions = []
  if (selectedStatus.value === 'completed') {
    actions.push({
      id: 'read',
      label: selectedGroup.value?.type === 'multi' ? '选择章节' : '阅读',
      icon: bookOutline,
    })
  }
  actions.push({ id: 'detail', label: '进入详情页', icon: informationCircleOutline })
  if (selectedStatus.value === 'completed') {
    actions.push({ id: 'export', label: '导出', icon: documentLockOutline })
  }
  if (selectedStatus.value === 'failed') {
    actions.push({
      id: 'retry',
      label: selectedError.value?.includes('张图片下载失败') ? '重新下载失败图片' : '重试',
      icon: refreshOutline,
    })
  }
  if (selectedStatus.value === 'paused') {
    actions.push({ id: 'resume', label: '继续', icon: playOutline })
  }
  if (selectedStatus.value === 'downloading') {
    actions.push({ id: 'pause', label: '暂停', icon: pauseOutline })
  }
  if (cancelableStatuses.includes(selectedStatus.value ?? '')) {
    actions.push({ id: 'cancel', label: '取消', icon: closeCircleOutline, danger: true })
  }
  if (deletableStatuses.includes(selectedStatus.value ?? '')) {
    actions.push({ id: 'delete', label: '删除', icon: trashOutline, danger: true })
  }
  if (selectedStatus.value !== 'verifying' && selectedGroup.value?.type !== 'multi')
    actions.push({ id: 'multi-select', label: '多选', icon: listOutline })
  return actions
})

const openActions = (task: DownloadTask, event: Event) => {
  const anchor = event.currentTarget as HTMLElement
  if (actionMenuAnchor.value === anchor) {
    closeActions()
    return
  }
  selectedTask.value = task
  selectedGroup.value = null
  actionMenuAnchor.value = anchor
  isActionMenuOpen.value = true
}

const openGroupActions = (group: CompletedDownloadGroup, event: Event) => {
  const anchor = event.currentTarget as HTMLElement
  if (actionMenuAnchor.value === anchor) {
    closeActions()
    return
  }
  selectedTask.value = group.chapters[0]
  selectedGroup.value = group
  actionMenuAnchor.value = anchor
  isActionMenuOpen.value = true
}

const popoverAction = (action: string) => {
  isActionMenuOpen.value = false
  const t = selectedTask.value
  if (!t) {
    closeActions()
    return
  }

  switch (action) {
    case 'multi-select':
      if (selectedGroup.value?.type === 'single')
        enterCompletedDownloadSelection(selectedGroup.value)
      else if (selectedGroup.value?.type !== 'multi' && !('source' in t)) {
        completedSelectionDomain.value = t.status === 'completed'
        enterDownloadSelection(t.taskId)
      }
      closeActions()
      break
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
    case 'export':
      onOpenExportSheet()
      break
    case 'delete':
      if (selectedGroup.value?.type === 'multi') {
        requestDeleteGroupConfirm(selectedGroup.value)
      } else {
        requestDeleteConfirm()
      }
      break
  }

  if (action !== 'delete') closeActions()
}

const closeActions = () => {
  isActionMenuOpen.value = false
  if (!isDeleteAlertOpen.value && !showDeleteSheet.value) {
    selectedTask.value = null
    selectedGroup.value = null
    actionMenuAnchor.value = null
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

const requestDeleteConfirm = async () => {
  isDeleteAlertOpen.value = true
  const alert = await createAppAlert({
    header: '确认删除',
    message: deleteAlertMessage.value,
    buttons: [
      { text: '取消', role: 'cancel' },
      { text: '删除', role: 'destructive', handler: confirmDelete },
    ],
  })
  await alert.present()
  await alert.onDidDismiss()
  isDeleteAlertOpen.value = false
}

const showDeleteSheet = ref(false)
const deleteGroup = ref<CompletedDownloadGroup | null>(null)

const requestDeleteGroupConfirm = (group: CompletedDownloadGroup) => {
  deleteGroup.value = group
  showDeleteSheet.value = true
}

const deleteGroupChapters = computed<CompletedEntry[]>(
  () =>
    deleteGroup.value?.chapters.map((chapter) => toCompletedEntry(chapter, deleteGroup.value!)) ??
    [],
)

const confirmDelete = () => {
  const g = selectedGroup.value
  if (g?.type === 'multi') {
    void deleteChapters(g.chapters.map((chapter) => toCompletedEntry(chapter, g)))
  } else if (selectedTask.value) {
    onDelete(selectedTask.value as DownloadTask)
  }
  selectedTask.value = null
  selectedGroup.value = null
  actionMenuAnchor.value = null
}

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

const toCompletedEntry = (task: DownloadTask, group?: CompletedDownloadGroup): CompletedEntry => {
  return mapDownloadTaskToEntry({
    ...task,
    ...(group
      ? {
          albumTitle: group.albumTitle,
          coverUrl: group.coverUrl,
          author: group.author,
          isSingleEpisode: group.isSingleEpisode,
          totalSize: group.totalSize,
          completedAt: group.completedAt,
        }
      : {}),
  })
}

const groupEntry = (group: CompletedDownloadGroup) => toCompletedEntry(group.chapters[0], group)

const itemKey = (item: DownloadPageItem) =>
  'chapters' in item ? `album-${item.albumId}` : `task-${item.taskId}`

const completedSelectionDomain = ref(false)
const downloadSelectionRecords = ref(new Map<string, DownloadTask>())
const downloadSelectionActions = computed(() => {
  const selected = [...downloadSelectedIds.value]
  const tasks = [
    ...downloadGroupStates.active.items,
    ...downloadGroupStates.failed.items,
    ...downloadGroupStates.completed.items,
  ]
    .flatMap((item) => ('chapters' in item ? item.chapters : [item]))
    .filter((task) => selected.includes(task.taskId))
  for (const task of downloadSelectionRecords.value.values())
    if (selected.includes(task.taskId) && !tasks.some((item) => item.taskId === task.taskId))
      tasks.push(task)
  const actions: { id: string; label: string }[] = []
  if (tasks.some((task) => task.status === 'queued' || task.status === 'downloading'))
    actions.push({ id: 'pause', label: '暂停' })
  if (tasks.some((task) => task.status === 'paused')) actions.push({ id: 'resume', label: '继续' })
  if (tasks.some((task) => task.status === 'failed')) actions.push({ id: 'retry', label: '重试' })
  if (tasks.some((task) => task.status === 'completed' || task.status === 'failed'))
    actions.push({ id: 'delete', label: '删除' })
  if (tasks.some((task) => task.status === 'completed'))
    actions.push({ id: 'export', label: '导出' })
  return actions
})

const enterCompletedDownloadSelection = (group: CompletedDownloadGroup) => {
  if (group.type !== 'single') return
  completedSelectionDomain.value = true
  enterDownloadSelection(group.chapters[0]?.taskId)
}

const enterEligibleDownloadSelection = (task: DownloadTask) => {
  if (task.status === 'verifying') return
  completedSelectionDomain.value = false
  enterDownloadSelection(task.taskId)
}

function enterGroupDownloadSelection(group: DownloadGroupKey) {
  completedSelectionDomain.value = group === 'completed'
  enterDownloadSelection()
}

function cancelDownloadSelection() {
  downloadSelectionToken++
  exitDownloadSelection()
  completedSelectionDomain.value = false
  downloadSelectionRecords.value = new Map()
  downloadSelectionPreparing.value = false
}

function cancelDownloadPreparation() {
  downloadSelectionToken++
  downloadSelectionPreparing.value = false
}

async function prepareDownloadSelection(invert: boolean) {
  if (downloadSelectionPreparing.value || downloadSelectionExecuting.value) return
  const token = ++downloadSelectionToken
  downloadSelectionPreparing.value = true
  try {
    const ids: string[] = []
    const records = new Map<string, DownloadTask>()
    for (const status of completedSelectionDomain.value
      ? (['completed'] as const)
      : (['active', 'failed'] as const)) {
      let cursor: string | undefined
      do {
        const page = await JmcomicService.getDownloadTasksPage({
          status,
          sort: sortMode.value,
          direction: sortDirection.value,
          cursor,
          limit: 50,
        })
        if (token !== downloadSelectionToken) return
        for (const item of page.items) {
          if (status === 'completed') {
            const group = item as CompletedDownloadGroup
            if (group.type === 'single' && group.chapters.length === 1) {
              ids.push(group.chapters[0].taskId)
              records.set(group.chapters[0].taskId, group.chapters[0])
            }
          } else {
            const task = item as DownloadTask
            if (task.status !== 'verifying') {
              ids.push(task.taskId)
              records.set(task.taskId, task)
            }
          }
        }
        cursor = page.nextCursor ?? undefined
      } while (cursor)
    }
    if (token !== downloadSelectionToken) return
    downloadSelectionRecords.value = records
    if (invert) invertDownloadItems(ids)
    else selectDownloadItems(ids)
  } catch {
    if (token === downloadSelectionToken)
      await showToast('读取下载任务失败，本次选择未生效', 'danger')
  } finally {
    if (token === downloadSelectionToken) downloadSelectionPreparing.value = false
  }
}

async function executeDownloadSelection(action: string) {
  const selected = [...downloadSelectedIds.value]
  const tasks = [
    ...downloadGroupStates.active.items,
    ...downloadGroupStates.failed.items,
    ...downloadGroupStates.completed.items,
  ]
    .flatMap((item) => ('chapters' in item ? item.chapters : [item]))
    .filter((task) => selected.includes(task.taskId))
  for (const task of downloadSelectionRecords.value.values())
    if (selected.includes(task.taskId) && !tasks.some((item) => item.taskId === task.taskId))
      tasks.push(task)
  const eligible = tasks.filter((task) =>
    action === 'pause'
      ? ['queued', 'downloading'].includes(task.status)
      : action === 'resume'
        ? task.status === 'paused'
        : action === 'retry'
          ? task.status === 'failed'
          : action === 'delete'
            ? ['completed', 'failed'].includes(task.status)
            : false,
  )
  if (action === 'export') {
    openBatchExportSheet(tasks.filter((task) => task.status === 'completed'))
    return
  }
  if (!eligible.length) return
  if (action === 'delete') {
    const alert = await createAppAlert({
      header: '确认删除',
      message: `确定删除选中的 ${eligible.length} 个章节文件吗？此操作不可恢复。`,
      buttons: [
        { text: '取消', role: 'cancel' },
        {
          text: '删除',
          role: 'destructive',
          handler: async () => runDownloadBatch(action, eligible),
        },
      ],
    })
    await alert.present()
    return
  }
  await runDownloadBatch(action, eligible)
}

async function runDownloadBatch(action: string, tasks: DownloadTask[]) {
  downloadSelectionExecuting.value = true
  downloadBatchRunning.value = true
  downloadBatchActionLabel.value =
    {
      pause: '正在暂停下载',
      resume: '正在继续下载',
      retry: '正在重试下载',
      delete: '正在删除章节',
    }[action] ?? '正在处理'
  downloadBatchCurrent.value = 0
  downloadBatchTotal.value = tasks.length
  let succeeded = 0
  let failed = 0
  try {
    for (const task of tasks) {
      downloadBatchCurrentTitle.value = task.chapterTitle || task.albumTitle
      try {
        if (action === 'pause') {
          if (!(await JmcomicService.pauseDownload(task.taskId)).success)
            throw new Error('暂停失败')
        } else if (action === 'resume') {
          if (!(await JmcomicService.resumeDownload(task.taskId)).success)
            throw new Error('继续失败')
        } else if (action === 'retry') {
          const result = await JmcomicService.downloadChapter(
            task.albumId,
            task.chapterId,
            task.albumTitle,
            task.chapterTitle,
            task.coverUrl,
          )
          if (!result.taskId) throw new Error('重试未提交')
          OfflineDownloadService.removeTask(task.taskId)
          OfflineDownloadService.addTask({
            ...task,
            taskId: result.taskId,
            status: 'queued',
            downloadedPages: 0,
            totalPages: 0,
            createdAt: Date.now(),
          })
        } else {
          await JmcomicService.deleteDownloaded(task.albumId, task.chapterId)
          OfflineDownloadService.removeTask(task.taskId)
        }
        succeeded++
      } catch {
        failed++
      }
      downloadBatchCurrent.value++
    }
  } finally {
    downloadSelectionExecuting.value = false
    downloadBatchRunning.value = false
    exitDownloadSelection()
    completedSelectionDomain.value = false
    downloadSelectionRecords.value = new Map()
    await syncDownloadState()
    await showToast(
      `操作完成：成功 ${succeeded} 项，失败 ${failed} 项`,
      failed ? 'medium' : 'success',
    )
  }
}

const rebuildVisibleTasks = () => {
  const next: DownloadTask[] = []
  for (const group of downloadGroups.value) {
    for (const item of group.items) {
      if ('chapters' in item) next.push(...item.chapters)
      else next.push(item)
    }
  }
  tasks.value = next
}

const setGroupPages = (group: DownloadGroupState, pages: DownloadGroupPage[]) => {
  group.pages = pages
  group.items = pages.flatMap((page) => page.items)
  group.previousCursor = pages[0]?.previousCursor ?? null
  group.nextCursor = pages[pages.length - 1]?.nextCursor ?? null
}

const resetGroup = (group: DownloadGroupState, keepWindow = false) => {
  group.requestId += 1
  group.loading = false
  if (!keepWindow) {
    setGroupPages(group, [])
    group.totalCount = 0
  }
  group.nextCursor = null
  group.previousCursor = null
  group.initialized = false
  group.error = false
}

let restoredDownloadScrollTop: number | null = null

const loadGroup = async (key: DownloadGroupKey, direction: 'next' | 'previous' = 'next') => {
  if (downloadSelectionActive.value) return
  const group = downloadGroupStates[key]
  if (group.loading || (group.collapsed && group.initialized)) return
  if (group.error && !group.nextCursor && !group.previousCursor) resetGroup(group, true)
  group.loadDirection = direction
  const cursor = direction === 'previous' ? group.previousCursor : group.nextCursor
  if (group.initialized && !cursor) return
  group.loading = true
  group.error = false
  const requestId = ++group.requestId
  try {
    const scrollElement = await downloadContentRef.value?.$el?.getScrollElement?.()
    if (requestId !== group.requestId || isUnmounted) return
    const result = await JmcomicService.getDownloadTasksPage({
      status: group.status,
      sort: sortMode.value,
      direction: sortDirection.value,
      ...(cursor ? { cursor } : {}),
      limit: 50,
    })
    if (requestId !== group.requestId || isUnmounted) return
    const viewport = scrollElement?.getBoundingClientRect()
    const anchor = viewport
      ? Array.from(document.querySelectorAll<HTMLElement>('[data-download-key]')).find(
          (element) => element.getBoundingClientRect().bottom > viewport.top,
        )
      : undefined
    const anchorTop = anchor?.getBoundingClientRect().top
    const page: DownloadGroupPage = {
      items: result.items as DownloadPageItem[],
      nextCursor: result.nextCursor,
      previousCursor: result.previousCursor,
    }
    const pages = !group.initialized
      ? [page]
      : direction === 'previous'
        ? [page, ...group.pages]
        : [...group.pages, page]
    setGroupPages(group, direction === 'previous' ? pages.slice(0, 3) : pages.slice(-3))
    group.totalCount = result.totalCount
    group.initialized = true
    spaceUsedMb.value = Math.round(result.usedBytes / (1024 * 1024))
    spaceAvailMb.value = Math.round(result.availableBytes / (1024 * 1024))
    hasStorageInfo.value = true
    rebuildVisibleTasks()
    await nextTick()
    if (scrollElement && anchor?.isConnected && anchorTop !== undefined) {
      const offset = anchor.getBoundingClientRect().top - anchorTop
      if (offset) {
        scrollElement.scrollTop += offset
        restoredDownloadScrollTop = scrollElement.scrollTop
      }
    }
  } catch {
    if (requestId === group.requestId) group.error = true
  } finally {
    if (requestId === group.requestId) group.loading = false
  }
}

const resetDownloadGroups = async (includeCollapsed = false, keepWindow = true) => {
  const groups = downloadGroups.value.filter((group) => includeCollapsed || !group.collapsed)
  for (const group of groups) resetGroup(group, keepWindow)
  await Promise.all(groups.map((group) => loadGroup(group.key)))
}

const refreshGroupFirstPage = async (key: DownloadGroupKey) => {
  const group = downloadGroupStates[key]
  resetGroup(group, true)
  await loadGroup(key)
}

const refreshGroupsFirstPage = async (keys: DownloadGroupKey[]) => {
  await Promise.all(keys.map(refreshGroupFirstPage))
}

const toggleGroup = (key: DownloadGroupKey) => {
  if (downloadSelectionActive.value) return
  const group = downloadGroupStates[key]
  group.collapsed = !group.collapsed
  if (!group.collapsed && !group.initialized) void loadGroup(key)
}

const onDownloadScroll = async (event: CustomEvent<{ scrollTop?: number }>) => {
  const scrollTop = event.detail?.scrollTop ?? 0
  if (restoredDownloadScrollTop !== null) {
    const restored = restoredDownloadScrollTop
    restoredDownloadScrollTop = null
    if (Math.abs(scrollTop - restored) < 1) return
  }
  const scrollElement = await downloadContentRef.value?.$el?.getScrollElement?.()
  if (!scrollElement) return

  if (shouldLoadDownloadPreviousPage(scrollTop)) {
    const previousGroup = downloadGroups.value.find(
      (candidate) => !candidate.collapsed && candidate.previousCursor,
    )
    if (previousGroup) {
      await loadGroup(previousGroup.key, 'previous')
      return
    }
  }

  for (const group of downloadGroups.value) {
    if (group.collapsed || group.loading || !group.nextCursor) continue
    const sentinel = document.querySelector<HTMLElement>(`[data-download-sentinel="${group.key}"]`)
    if (!sentinel) continue
    const containerRect = scrollElement.getBoundingClientRect()
    const sentinelRect = sentinel.getBoundingClientRect()
    const offset = sentinelRect.top - containerRect.top
    if (
      shouldLoadDownloadNextPage(
        offset,
        sentinelRect.bottom - containerRect.top,
        scrollElement.clientHeight,
      )
    ) {
      void loadGroup(group.key)
    }
  }
}

const removeVisibleTask = (taskId: string) => {
  for (const group of downloadGroups.value) {
    setGroupPages(
      group,
      group.pages.map((page) => ({
        ...page,
        items: page.items.flatMap<DownloadPageItem>((item): DownloadPageItem[] => {
          if (!('chapters' in item)) return item.taskId === taskId ? [] : [item]
          if (!item.chapters.some((chapter) => chapter.taskId === taskId)) return [item]
          const chapters = item.chapters.filter((chapter) => chapter.taskId !== taskId)
          return chapters.length
            ? [{ ...item, chapters, type: chapters.length > 1 ? 'multi' : 'single' }]
            : []
        }),
      })),
    )
  }
  rebuildVisibleTasks()
}

const updateVisibleTask = (taskId: string, update: (task: DownloadTask) => DownloadTask) => {
  for (const group of downloadGroups.value) {
    let changed = false
    const pages = group.pages.map((page) => {
      const items = page.items.map((item) => {
        if (!('chapters' in item)) {
          if (item.taskId !== taskId) return item
          changed = true
          return update(item)
        }
        if (!item.chapters.some((chapter) => chapter.taskId === taskId)) return item
        changed = true
        return {
          ...item,
          chapters: item.chapters.map((chapter) =>
            chapter.taskId === taskId ? update(chapter) : chapter,
          ),
        }
      })
      return changed ? { ...page, items } : page
    })
    if (changed) setGroupPages(group, pages)
  }
  rebuildVisibleTasks()
}

const syncDownloadState = async (includeCollapsed = false): Promise<void> => {
  syncCollapsedRequested ||= includeCollapsed
  syncRequested = true
  if (syncPromise) return syncPromise

  syncPromise = (async () => {
    while (syncRequested && !isUnmounted) {
      syncRequested = false
      const readCollapsed = syncCollapsedRequested
      syncCollapsedRequested = false
      const requestSequence = downloadStateSequence
      try {
        await resetDownloadGroups(readCollapsed)
        if (requestSequence !== downloadStateSequence) {
          syncRequested = true
          syncCollapsedRequested ||= readCollapsed
          continue
        }
        const failedGroups = downloadGroups.value.filter(
          (group) => (readCollapsed || !group.collapsed) && group.error,
        )
        if (failedGroups.length) {
          const cached = OfflineDownloadService.getAll()
          const cachedCompleted = OfflineDownloadService.getCompletedGroups()
          for (const group of failedGroups) {
            if (group.key === 'active') {
              group.items = cached.filter((task) =>
                ['queued', 'downloading', 'paused', 'verifying'].includes(task.status),
              )
            } else if (group.key === 'failed') {
              group.items = cached.filter((task) => task.status === 'failed')
            } else {
              group.items = cachedCompleted
            }
            setGroupPages(group, [
              { items: group.items.slice(0, 150), nextCursor: null, previousCursor: null },
            ])
            group.totalCount = group.items.length
            group.initialized = true
          }
          rebuildVisibleTasks()
          hasStorageInfo.value = false
        } else {
          OfflineDownloadService.setAll(tasks.value)
          const completed = downloadGroupStates.completed.items.filter(
            (item): item is CompletedDownloadGroup => 'chapters' in item,
          )
          OfflineDownloadService.setCompletedGroups(completed)
        }
      } catch {
        if (isUnmounted) return
        if (requestSequence !== downloadStateSequence) {
          syncRequested = true
          syncCollapsedRequested ||= readCollapsed
          continue
        }
        const cached = OfflineDownloadService.getAll()
        downloadGroupStates.active.items = cached.filter((task) =>
          ['queued', 'downloading', 'paused', 'verifying'].includes(task.status),
        )
        downloadGroupStates.failed.items = cached.filter((task) => task.status === 'failed')
        downloadGroupStates.completed.items = OfflineDownloadService.getCompletedGroups()
        for (const group of downloadGroups.value) {
          setGroupPages(group, [
            { items: group.items.slice(0, 150), nextCursor: null, previousCursor: null },
          ])
        }
        downloadGroupStates.active.totalCount = downloadGroupStates.active.items.length
        downloadGroupStates.failed.totalCount = downloadGroupStates.failed.items.length
        downloadGroupStates.completed.totalCount = downloadGroupStates.completed.items.length
        downloadGroupStates.active.initialized = true
        downloadGroupStates.failed.initialized = true
        downloadGroupStates.completed.initialized = true
        rebuildVisibleTasks()
        hasStorageInfo.value = false
      }
    }
  })()

  try {
    await syncPromise
  } finally {
    syncPromise = null
    if (syncRequested && !isUnmounted) void syncDownloadState()
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
        Math.round(((sample.latestBytes - sample.sampledBytes) * 1000) / (now - sample.sampledAt)),
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

const updateSpeedSample = (
  task: DownloadTask,
  downloadedBytes?: number,
  fallbackSpeed?: number,
) => {
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
  if (downloadSelectionActive.value) {
    ;(event.target as HTMLIonRefresherElement).complete()
    return
  }
  if (activeMainView.value === 'export') await exportManagementRef.value?.refresh()
  else await syncDownloadState(true)
  ;(event.target as HTMLIonRefresherElement).complete()
}

onMounted(async () => {
  isUnmounted = false

  const progressRegistration = JmcomicService.addDownloadProgressListener((data) => {
    downloadStateSequence++
    const task = tasks.value.find((t) => t.taskId === data.taskId)
    if (!task) {
      if (['queued', 'downloading', 'paused', 'verifying'].includes(data.status)) {
        if (!unseenActiveTaskIds.has(data.taskId)) {
          unseenActiveTaskIds.add(data.taskId)
          void refreshGroupFirstPage('active')
        }
      } else if (data.status === 'completed') {
        unseenActiveTaskIds.delete(data.taskId)
        void refreshGroupsFirstPage(['active', 'completed'])
      } else if (data.status === 'failed') {
        unseenActiveTaskIds.delete(data.taskId)
        void refreshGroupsFirstPage(['active', 'failed'])
      } else if (data.status === 'cancelled') {
        unseenActiveTaskIds.delete(data.taskId)
        void refreshGroupFirstPage('active')
      }
      return
    }
    unseenActiveTaskIds.delete(data.taskId)

    if (data.status === 'downloading') {
      updateVisibleTask(data.taskId, (current) => {
        const next = {
          ...current,
          downloadedPages: data.downloadedPages,
          totalPages: data.totalPages,
          status: 'downloading' as const,
        }
        updateSpeedSample(next, data.downloadedBytes, data.speed)
        return next
      })
      OfflineDownloadService.updateProgress(data.taskId, data.downloadedPages, data.totalPages)
    } else if (data.status === 'verifying') {
      updateVisibleTask(data.taskId, (current) => ({
        ...current,
        downloadedPages: data.downloadedPages,
        totalPages: data.totalPages || current.totalPages,
        status: 'verifying',
        speed: 0,
      }))
      speedSamples.delete(data.taskId)
      OfflineDownloadService.updateStatus(
        data.taskId,
        'verifying',
        data.downloadedPages,
        task.totalPages,
      )
    } else if (data.status === 'paused') {
      updateVisibleTask(data.taskId, (current) => ({
        ...current,
        downloadedPages: data.downloadedPages,
        totalPages: data.totalPages || current.totalPages,
        status: 'paused',
        speed: 0,
      }))
      speedSamples.delete(data.taskId)
      OfflineDownloadService.updateStatus(
        data.taskId,
        'paused',
        data.downloadedPages,
        task.totalPages,
      )
    } else if (data.status === 'cancelled') {
      removeVisibleTask(data.taskId)
      speedSamples.delete(data.taskId)
      OfflineDownloadService.removeTask(data.taskId)
      void refreshGroupFirstPage('active')
    } else if (data.status === 'completed') {
      removeVisibleTask(data.taskId)
      speedSamples.delete(data.taskId)
      OfflineDownloadService.updateStatus(
        data.taskId,
        'completed',
        data.downloadedPages,
        data.totalPages,
      )
      void refreshGroupsFirstPage(['active', 'completed'])
    } else if (data.status === 'failed') {
      speedSamples.delete(data.taskId)
      removeVisibleTask(data.taskId)
      OfflineDownloadService.updateStatus(
        data.taskId,
        'failed',
        data.downloadedPages,
        data.totalPages,
        data.error,
      )
      void refreshGroupsFirstPage(['active', 'failed'])
    }
  })
    .then((handle) => {
      if (isUnmounted) {
        void handle.remove()
        return
      }
      downloadProgressHandle = handle
    })
    .catch(() => {})

  const invalidationRegistration = JmcomicService.addStateInvalidatedListener?.(() => {
    downloadStateSequence++
    void syncDownloadState(true)
  })
    .then((handle) => {
      if (!handle) return
      if (isUnmounted) {
        void handle.remove()
        return
      }
      stateInvalidatedHandle = handle
    })
    .catch(() => {})

  await Promise.all([progressRegistration, invalidationRegistration])
  if (isUnmounted) return
  await syncDownloadState()
})

onIonViewWillEnter(() => {
  ionEnterCount++
  activeMainView.value =
    route.query.view === 'pdf' || route.query.view === 'export' ? 'export' : 'downloads'
  void syncDownloadState()
  if (activeMainView.value === 'export' && ionEnterCount > 1) {
    void exportManagementRef.value?.refresh()
  }
})

onUnmounted(() => {
  isUnmounted = true
  void downloadProgressHandle?.remove()
  void stateInvalidatedHandle?.remove()
  downloadProgressHandle = null
  stateInvalidatedHandle = null
  syncRequested = false
  stopSpeedTimer()
  speedSamples.clear()
  unseenActiveTaskIds.clear()
})

// ---- 文件导出 ----
const onOpenExportSheet = () => {
  downloadBatchExport.value = false
  const g = selectedGroup.value
  if (g?.type === 'multi') {
    chaptersForExport.value = g.chapters
  } else if (selectedTask.value) {
    const t = selectedTask.value
    chaptersForExport.value = ['source' in t ? t.downloadTask! : t]
  }
  showExportSheet.value = true
}

const openBatchExportSheet = (tasks: DownloadTask[]) => {
  downloadBatchExport.value = true
  chaptersForExport.value = tasks
  selectedGroup.value = null
  selectedTask.value = null
  showExportSheet.value = true
}

const requestOverwriteConfirmation = async (
  format: ExportFormat,
  paths: string[],
): Promise<boolean> => {
  let confirmed = false
  const visiblePaths = [...new Set(paths.filter((path) => path.trim().length > 0))].slice(0, 3)
  const pathSummary = visiblePaths.length
    ? `\n${visiblePaths.map((path) => `· ${path}`).join('\n')}`
    : ''
  const remaining = paths.length - visiblePaths.length
  const remainingSummary = remaining > 0 ? `\n另有 ${remaining} 个文件未展开。` : ''

  const alert = await createAppAlert({
    tone: 'danger',
    header: '文件已存在',
    message: `以下 ${format.toUpperCase()} 文件已存在，是否覆盖？${pathSummary}${remainingSummary}`,
    buttons: [
      { text: '取消', role: 'cancel' },
      {
        text: '覆盖',
        role: 'destructive',
        handler: () => {
          confirmed = true
        },
      },
    ],
  })
  await alert.present()
  await alert.onDidDismiss()
  return confirmed
}

const onExportConfirm = async (payload: {
  selectedChapters: DownloadTask[]
  format: ExportFormat
  mode: ExportMode
  useOriginal: boolean
  compressionRatio: number
  editedPath: string
  splitPages: number
}) => {
  showExportSheet.value = false

  let exportPlan: ExportPlan
  try {
    exportPlan = ExportService.buildExportPlan({
      ...payload,
      exportFolder: (() => {
        const selection = ExportService.getExportFolder()
        if (!selection) throw new Error('请先选择导出目录')
        return selection.folderRef
      })(),
      exportFolderDisplayPath: (() => {
        const selection = ExportService.getExportFolder()
        if (!selection) throw new Error('请先选择导出目录')
        return selection.displayPath
      })(),
    })
  } catch (e: any) {
    await showToast(sanitizeError(e, '无法创建导出任务'), 'danger')
    return
  }

  // 输出文件 ref 只在原生创建目标文档后产生，提交前不能把展示路径冒充 fileRef。
  const allowOverwrite = false

  await ensureNotificationPermission()

  try {
    const result = await JmcomicService.exportBatch(
      exportPlan.tasks.map((task) => ({ ...task, allowOverwrite })),
    )
    const conflictIndexes = result.tasks.flatMap((task, index) =>
      !task.accepted && task.errorCode === `${payload.format.toUpperCase()}_OUTPUT_EXISTS`
        ? [index]
        : [],
    )
    let finalTasks = result.tasks
    let overwriteConfirmed = false

    if (conflictIndexes.length > 0) {
      const conflictPaths = conflictIndexes.map((index) => {
        const resultPath = result.tasks[index]?.displayPath
        return (
          resultPath ||
          exportPlan.tasks[index]?.displayPath ||
          exportPlan.outputDisplayPaths[index] ||
          ''
        )
      })
      overwriteConfirmed = await requestOverwriteConfirmation(payload.format, conflictPaths)
      if (overwriteConfirmed) {
        try {
          const overwriteResult = await JmcomicService.exportBatch(
            conflictIndexes.map((index) => ({
              ...exportPlan.tasks[index],
              allowOverwrite: true,
            })),
          )
          finalTasks = result.tasks.slice()
          conflictIndexes.forEach((originalIndex, retryIndex) => {
            const retryTask = overwriteResult.tasks[retryIndex]
            if (retryTask) finalTasks[originalIndex] = retryTask
          })
        } catch (e: any) {
          await showToast(sanitizeError(e, '覆盖导出启动失败'), 'danger')
          return
        }
      }
    }

    const accepted = finalTasks.filter((task) => task.accepted).length
    const rejected = finalTasks.length - accepted
    if (conflictIndexes.length > 0 && !overwriteConfirmed) {
      const conflictIndexSet = new Set(conflictIndexes)
      const otherRejected = finalTasks.filter(
        (task, index) => !task.accepted && !conflictIndexSet.has(index),
      ).length
      if (otherRejected > 0) {
        await showToast(
          `已开始 ${accepted} 个，${conflictIndexes.length} 个已取消覆盖，另有 ${otherRejected} 个失败`,
          'medium',
        )
      } else if (accepted === 0) {
        await showToast(`检测到已有同名 ${payload.format.toUpperCase()}，已取消覆盖`, 'medium')
      } else {
        await showToast(`已开始 ${accepted} 个，${conflictIndexes.length} 个已取消覆盖`, 'medium')
      }
      return
    }
    if (accepted === 0) {
      await showToast(`${payload.format.toUpperCase()} 导出未开始，请查看任务失败原因`, 'danger')
    } else if (rejected > 0) {
      await showToast(`已开始 ${accepted} 个，${rejected} 个未进入队列，请查看导出任务`, 'medium')
    } else {
      await showToast(`${payload.format.toUpperCase()} 导出已开始，请查看通知`, 'success')
    }
    if (downloadBatchExport.value) {
      downloadBatchExport.value = false
      exitDownloadSelection()
    }
  } catch (e: any) {
    await showToast(sanitizeError(e, '导出启动失败'), 'danger')
  }
}

async function ensureNotificationPermission(): Promise<boolean> {
  try {
    const check = await JmcomicService.checkNotificationPermission()
    if (check.granted) return true

    const alert = await createAppAlert({
      tone: 'info',
      header: '需要通知权限',
      message: '导出将在后台进行，需要通过通知查看进度。拒绝后仍会继续导出，但不会显示系统通知。',
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
    removeVisibleTask(task.taskId)
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
    removeVisibleTask(task.taskId)
    // 重试后的排序与游标由受控刷新恢复，不向当前窗口强插记录。
    rebuildVisibleTasks()
    OfflineDownloadService.removeTask(task.taskId)
    OfflineDownloadService.addTask(newTask)
    void syncDownloadState()
  } catch (e: any) {
    await showToast(sanitizeError(e, '重试失败'), 'danger')
  }
}

const onRead = (entry: CompletedEntry | DownloadTask) => {
  const dt =
    'downloadTask' in entry && entry.downloadTask ? entry.downloadTask : (entry as DownloadTask)
  void router.push({
    path: `/album/${dt.albumId}/read/${dt.chapterId}`,
    query: {
      title: dt.chapterTitle,
      total: String(dt.totalPages),
      source: 'download',
    },
  })
}

const onReadGroup = (group: CompletedDownloadGroup) => {
  if (group.type === 'multi') {
    onOpenChapterSelect(group)
  } else {
    onRead(group.chapters[0])
  }
}

const onOpenChapterSelect = (group: CompletedDownloadGroup) => {
  void router.push(`/album/${group.albumId}/download-chapters`)
}

const onGoToAlbumDetail = (entry: CompletedEntry | DownloadTask) => {
  void router.push(`/album/${entry.albumId}`)
}

const onDelete = async (task: DownloadTask) => {
  try {
    await JmcomicService.deleteDownloaded(task.albumId, task.chapterId)
    removeVisibleTask(task.taskId)
    OfflineDownloadService.removeTask(task.taskId)
    await refreshGroupFirstPage(downloadRefreshGroupForStatus(task.status))
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
  actionMenuAnchor.value = null
}

const deleteChapters = async (chapters: CompletedEntry[]) => {
  try {
    const downloadChapters = chapters.filter((ch) => ch.source === 'download')

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
    for (const taskId of taskIds) removeVisibleTask(taskId)
    for (const taskId of taskIds) {
      OfflineDownloadService.removeTask(taskId)
    }

    const refreshGroups = new Set(
      deletedDownloadChapters.map((chapter) =>
        downloadRefreshGroupForStatus(chapter.downloadTask?.status ?? 'completed'),
      ),
    )
    await Promise.all([...refreshGroups].map((group) => refreshGroupFirstPage(group)))
    const deletedCount = deletedDownloadChapters.length
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
const clearTarget = ref<'completed' | 'failed'>('completed')

const clearAlertMessage = computed(() => {
  if (clearTarget.value === 'completed') {
    return `将删除所有已完成下载的文件和记录（${downloadGroupStates.completed.totalCount} 个本子），此操作不可恢复。`
  }
  return `将删除所有失败任务的文件和记录（${downloadGroupStates.failed.totalCount} 个），此操作不可恢复。`
})

const requestClear = async (target: 'completed' | 'failed') => {
  clearTarget.value = target
  const alert = await createAppAlert({
    header: '确认清空',
    message: clearAlertMessage.value,
    buttons: [
      { text: '取消', role: 'cancel' },
      { text: '确定清空', role: 'destructive', handler: executeClear },
    ],
  })
  await alert.present()
}

const executeClear = async () => {
  if (clearTarget.value === 'completed') await clearCompleted()
  else await clearFailed()
}

const clearCompleted = async () => {
  if (!downloadGroupStates.completed.totalCount) return
  const result = await JmcomicService.clearDownloadTasks('completed')
  resetGroup(downloadGroupStates.completed)
  await loadGroup('completed')
  if (result.failedCount)
    await showToast(`${result.failedCount} 个任务清理失败，已保留记录，可重试`, 'medium')
}

const clearFailed = async () => {
  if (!downloadGroupStates.failed.totalCount) return
  const result = await JmcomicService.clearDownloadTasks('failed')
  resetGroup(downloadGroupStates.failed)
  await loadGroup('failed')
  if (result.failedCount)
    await showToast(`${result.failedCount} 个任务清理失败，已保留记录，可重试`, 'medium')
}
</script>

<style scoped>
.download-page-shell {
  width: 100%;
  max-width: 1000px;
  margin-inline: auto;
  box-sizing: border-box;
}

.toolbar-start {
  padding: 0 0 8px 14px;
}

.view-tabs {
  display: flex;
  gap: 2px;
  margin-bottom: 10px;
  padding: 4px 14px;
  border-radius: 18px;
  background: #fffbf8;
}

.tab-btn {
  flex: 1;
  height: 34px;
  border: 0;
  border-radius: 14px;
  background: transparent;
  color: #8a6048;
  font-size: 12px;
  font-weight: 600;
  transition:
    background-color 0.18s ease,
    color 0.18s ease;
}

.tab-btn.active {
  background: linear-gradient(145deg, #fa9c69, #f28752);
  color: #fff;
}

.toolbar-action-label {
  color: #8a6048;
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
}

.sort-btn {
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 0;
  border-radius: 10px;
  background: #fffbf8;
  color: #8a6048;
  font-size: 16px;
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
  padding-top: 36vh;
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
  margin: 0 14px 10px;
  padding: 10px 14px;
  border: 1px solid rgb(245 210 188 / 0.5);
  border-radius: 8px;
  background: #fffbf8;
  font-size: 13px;
  color: #8a6048;
}

.storage-icon {
  font-size: 18px;
  color: #fa9c69;
}

.sort-label-row {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  margin: -2px 18px 4px;
}

.section {
  padding: 0 14px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 0;
  min-height: 44px;
  gap: 8px;
  padding: 0 4px;
}

.section-toggle {
  display: flex;
  min-width: 0;
  min-height: 44px;
  flex: 1;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border: 0;
  background: transparent;
  color: #8a6048;
  font: inherit;
  text-align: left;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

.section-toggle:focus-visible {
  outline: 2px solid #e8843c;
  outline-offset: 1px;
  border-radius: 6px;
}

.section-toggle-icon {
  flex-shrink: 0;
  margin-left: auto;
  font-size: 16px;
  transition: transform 0.24s ease;
}

.section-toggle-icon.collapsed {
  transform: rotate(-90deg);
}

.section-content {
  overflow: visible;
}

.download-list-loader,
.browse-group-loader,
.browse-group-retry,
.group-previous {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  color: #fa9c69;
}

.download-list-loader {
  padding-top: 30vh;
}

.browse-group-retry,
.group-previous {
  width: 100%;
  padding: 10px;
  border: 0;
  background: transparent;
  font-size: 12px;
  cursor: pointer;
}

.browse-group-retry {
  border-radius: 6px;
  background: #fff0e8;
  color: #c96d3a;
}

.browse-group-retry:focus-visible,
.group-previous:focus-visible {
  outline: 2px solid #e8843c;
  outline-offset: 1px;
  border-radius: 6px;
}

.group-sentinel {
  min-height: 12px;
  pointer-events: none;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: #8a6048;
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
  margin-bottom: 6px;
}

.bottom-spacer {
  height: 80px;
}

.sort-btn:focus-visible,
.clear-btn:focus-visible {
  outline: 2px solid #c06f45;
  outline-offset: 2px;
}

@media (max-width: 600px) {
  .sort-label-row {
    margin-inline: 14px;
  }

  .section {
    padding-inline: 10px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .section-toggle-icon {
    transition-duration: 0.01ms;
  }
}
.multi-select-overlay {
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
.download-batch-progress {
  position: fixed;
  inset: 0;
  z-index: 55;
  display: grid;
  place-content: center;
  gap: 12px;
  padding: 24px;
  background: rgb(24 20 18 / 0.32);
  color: #30201a;
  text-align: center;
  font-size: 16px;
  font-weight: 600;
}
</style>
