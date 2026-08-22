<template>
  <div class="main-menu" :class="{ interactive: isInteractive }" :aria-hidden="!isInteractive">
    <button
      type="button"
      class="main-menu-backdrop"
      aria-label="关闭侧边栏"
      :style="backdropStyle"
      tabindex="-1"
      @click="closeMenu"
    />

    <aside
      ref="panelRef"
      class="main-menu-panel"
      role="dialog"
      aria-modal="true"
      aria-label="主菜单"
      tabindex="-1"
      :style="panelStyle"
    >
      <IonHeader class="ion-no-border menu-header">
        <button type="button" class="menu-hero" @click="goUser">
          <img
            v-if="isLoggedIn && userInfo"
            :src="userInfo.avatarUrl"
            class="user-avatar"
            alt="头像"
          />
          <IonIcon v-else :icon="personCircleOutline" class="user-avatar-placeholder" />
          <div class="hero-copy">
            <div class="hero-title">
              {{ isLoggedIn && userInfo ? userInfo.username : '未登录' }}
            </div>
            <div class="hero-subtitle">
              {{
                isLoggedIn && userInfo
                  ? 'Lv.' + userInfo.level + ' ' + userInfo.levelName
                  : '点击查看账号信息'
              }}
            </div>
          </div>
        </button>
      </IonHeader>
      <IonContent class="menu-content">
        <IonList lines="none" class="menu-list">
          <IonItem
            button
            expand="block"
            router-link="/home"
            router-direction="forward"
            class="menu-item"
            :class="{ selected: isActive('/home') }"
            @click="handleMenuClick"
          >
            <IonIcon slot="start" class="menu-icon" :icon="homeSharp" />
            <IonLabel>
              <div class="item-title">首页</div>
              <div class="item-subtitle">关键词搜索入口</div>
            </IonLabel>
          </IonItem>
          <IonItem
            button
            expand="block"
            router-link="/category"
            router-direction="root"
            class="menu-item"
            :class="{ selected: isActive('/category') }"
            @click="handleMenuClick"
          >
            <IonIcon slot="start" class="menu-icon" :icon="searchSharp" />
            <IonLabel>
              <div class="item-title">分类</div>
              <div class="item-subtitle">分类筛选与检索</div>
            </IonLabel>
          </IonItem>
          <IonItem
            button
            expand="block"
            router-link="/favorite"
            router-direction="root"
            class="menu-item"
            :class="{ selected: isActive('/favorite') }"
            @click="handleMenuClick"
          >
            <IonIcon slot="start" class="menu-icon" :icon="heart" />
            <IonLabel>
              <div class="item-title">收藏夹</div>
              <div class="item-subtitle">保存喜欢的内容</div>
            </IonLabel>
          </IonItem>
          <IonItem
            button
            expand="block"
            router-link="/download"
            router-direction="root"
            class="menu-item download-menu-item"
            :class="{ selected: isActive('/download') }"
            @click="handleMenuClick"
          >
            <div
              v-if="shouldShowTaskProgress"
              slot="start"
              class="task-progress-background"
              aria-hidden="true"
            >
              <div
                v-if="downloadProgress"
                class="task-progress-band download-progress-band"
                :style="downloadProgressBandStyle"
              />
              <div
                v-if="pdfProgress"
                class="task-progress-band pdf-progress-band"
                :style="pdfProgressBandStyle"
              />
            </div>
            <IonIcon slot="start" class="menu-icon" :icon="downloadSharp" />
            <IonLabel class="download-menu-label">
              <div class="item-title">下载</div>
              <div class="item-subtitle">离线任务与管理</div>
            </IonLabel>
            <div v-if="shouldShowTaskProgress" class="task-progress-copy" aria-live="polite">
              <div v-if="downloadProgress" class="task-progress-row">
                <span>下载</span>
                <template v-if="downloadProgress.percent >= 100">
                  <IonSpinner name="crescent" class="task-progress-spinner" aria-hidden="true" />
                  <span class="task-progress-sr-only">完成，正在处理</span>
                </template>
                <span v-else>{{ Math.min(99, Math.round(downloadProgress.percent)) }}%</span>
              </div>
              <div v-if="pdfProgress" class="task-progress-row">
                <span>PDF</span>
                <template v-if="pdfProgress.percent >= 100">
                  <IonSpinner name="crescent" class="task-progress-spinner" aria-hidden="true" />
                  <span class="task-progress-sr-only">导出完成，正在处理</span>
                </template>
                <span v-else>{{ Math.min(99, Math.round(pdfProgress.percent)) }}%</span>
              </div>
            </div>
          </IonItem>
          <IonItem
            button
            expand="block"
            router-link="/history"
            router-direction="root"
            class="menu-item"
            :class="{ selected: isActive('/history') }"
            @click="handleMenuClick"
          >
            <IonIcon slot="start" class="menu-icon" :icon="timeOutline" />
            <IonLabel>
              <div class="item-title">历史</div>
              <div class="item-subtitle">浏览与解析记录</div>
            </IonLabel>
          </IonItem>
          <IonItem
            button
            expand="block"
            router-link="/setting"
            router-direction="forward"
            class="menu-item"
            :class="{ selected: isActive('/setting') }"
            @click="handleMenuClick"
          >
            <IonIcon slot="start" class="menu-icon" :icon="settingsSharp" />
            <IonLabel>
              <div class="item-title">设置</div>
              <div class="item-subtitle">偏好与通用配置</div>
            </IonLabel>
          </IonItem>
        </IonList>
      </IonContent>
    </aside>
  </div>
</template>

<script setup lang="ts">
import {
  createGesture,
  type Gesture,
  IonContent,
  IonHeader,
  IonIcon,
  IonItem,
  IonLabel,
  IonList,
  IonSpinner,
} from '@ionic/vue'
import type { PluginListenerHandle } from '@capacitor/core'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  downloadSharp,
  heart,
  homeSharp,
  personCircleOutline,
  searchSharp,
  settingsSharp,
  timeOutline,
} from 'ionicons/icons'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '@/composables/useAuth'
import {
  closeLeftMenu,
  leftMenuGestureEnabled,
  leftMenuOpen,
  isMenuNavigation,
  openLeftMenu,
  rightMenuOpen,
} from '@/composables/useSideMenuState'
import { JmcomicService } from '@/services/JmcomicService'
import type {
  DownloadProgressEvent,
  DownloadStatus,
  DownloadTask,
  PdfExportProgressEvent,
  PdfExportStatus,
  PdfExportTaskRecord,
} from '@/services/JmcomicTypes'
import { calculatePageProgress, type PageProgressInput } from '@/utils/pageProgress'

defineOptions({ name: 'MainMenu' })

const props = defineProps<{
  contentId: string
  disabled?: boolean
}>()

const route = useRoute()
const router = useRouter()
const { userInfo, isLoggedIn } = useAuth()
const panelRef = ref<HTMLElement | null>(null)
const dragProgress = ref(0)
const isDragging = ref(false)
let gesture: Gesture | undefined
let previouslyFocused: HTMLElement | null = null

type DownloadProgressTask = PageProgressInput & {
  status: DownloadStatus
  eventSequence: number
}

type PdfProgressTask = PageProgressInput & {
  status: PdfExportStatus
  snapshotRevision: number
  eventSequence: number
}

const DOWNLOAD_PROGRESS_STATUSES = new Set<DownloadStatus>([
  'queued',
  'downloading',
  'paused',
  'verifying',
])
const PDF_PROGRESS_STATUSES = new Set<PdfExportStatus>(['queued', 'running', 'cancelling'])
const TASK_PROGRESS_TRANSITION_MS = 220
const TASK_PROGRESS_RENDER_INTERVAL_MS = 500
const MAIN_MENU_TRANSITION_MS = 220

const downloadProgressTasks = ref(new Map<string, DownloadProgressTask>())
const pdfProgressTasks = ref(new Map<string, PdfProgressTask>())
const downloadProgress = computed(() =>
  calculatePageProgress([...downloadProgressTasks.value.values()]),
)
const pdfProgress = computed(() => calculatePageProgress([...pdfProgressTasks.value.values()]))
const shouldShowTaskProgress = computed(
  () => !isActive('/download') && Boolean(downloadProgress.value || pdfProgress.value),
)
const hasBothProgress = computed(() => Boolean(downloadProgress.value && pdfProgress.value))
const downloadProgressBandStyle = computed(() => ({
  top: '0%',
  height: hasBothProgress.value ? '50%' : '100%',
  '--task-progress': `${downloadProgress.value?.percent ?? 0}%`,
}))
const pdfProgressBandStyle = computed(() => ({
  top: hasBothProgress.value ? '50%' : '0%',
  height: hasBothProgress.value ? '50%' : '100%',
  '--task-progress': `${pdfProgress.value?.percent ?? 0}%`,
}))

type PendingDownloadProgressEvent = {
  event: DownloadProgressEvent
  eventSequence: number
}

type PendingPdfProgressEvent = {
  event: PdfExportProgressEvent
  eventSequence: number
}

let downloadEventSequence = 0
let pdfEventSequence = 0
const downloadLatestEventSequences = new Map<string, number>()
const pdfLatestEvents = new Map<
  string,
  { eventSequence: number; snapshotRevision: number; status: PdfExportStatus }
>()
const pendingDownloadProgressEvents = new Map<string, PendingDownloadProgressEvent>()
const pendingPdfProgressEvents = new Map<string, PendingPdfProgressEvent>()
let downloadClearTimer: ReturnType<typeof setTimeout> | null = null
let pdfClearTimer: ReturnType<typeof setTimeout> | null = null
let taskProgressRenderTimer: ReturnType<typeof setTimeout> | null = null
let downloadProgressHandle: PluginListenerHandle | null = null
let pdfProgressHandle: PluginListenerHandle | null = null
let progressUnmounted = false

const hasActiveDownloadTasks = (
  tasks: Map<string, DownloadProgressTask> = downloadProgressTasks.value,
) => [...tasks.values()].some((task) => DOWNLOAD_PROGRESS_STATUSES.has(task.status))

const hasActivePdfTasks = (tasks: Map<string, PdfProgressTask> = pdfProgressTasks.value) =>
  [...tasks.values()].some((task) => PDF_PROGRESS_STATUSES.has(task.status))

const clearDownloadTimer = () => {
  if (downloadClearTimer) {
    clearTimeout(downloadClearTimer)
    downloadClearTimer = null
  }
}

const clearPdfTimer = () => {
  if (pdfClearTimer) {
    clearTimeout(pdfClearTimer)
    pdfClearTimer = null
  }
}

const clearTaskProgressRenderTimer = () => {
  if (taskProgressRenderTimer) {
    clearTimeout(taskProgressRenderTimer)
    taskProgressRenderTimer = null
  }
}

const scheduleDownloadClear = () => {
  if (downloadProgressTasks.value.size === 0 || hasActiveDownloadTasks() || downloadClearTimer) {
    return
  }
  downloadClearTimer = setTimeout(() => {
    downloadClearTimer = null
    if (!hasActiveDownloadTasks()) downloadProgressTasks.value = new Map()
  }, TASK_PROGRESS_TRANSITION_MS)
}

const schedulePdfClear = () => {
  if (pdfProgressTasks.value.size === 0 || hasActivePdfTasks() || pdfClearTimer) return
  pdfClearTimer = setTimeout(() => {
    pdfClearTimer = null
    if (!hasActivePdfTasks()) pdfProgressTasks.value = new Map()
  }, TASK_PROGRESS_TRANSITION_MS)
}

const applyDownloadProgressEvent = (event: DownloadProgressEvent, eventSequence: number) => {
  const next = new Map(downloadProgressTasks.value)
  const existing = next.get(event.taskId)

  if (DOWNLOAD_PROGRESS_STATUSES.has(event.status)) {
    if (!hasActiveDownloadTasks(next)) next.clear()
    clearDownloadTimer()
    next.set(event.taskId, {
      currentPages: event.downloadedPages,
      totalPages: event.totalPages,
      status: event.status,
      eventSequence,
    })
  } else if (event.status === 'completed') {
    if (existing) {
      next.set(event.taskId, {
        currentPages: event.downloadedPages,
        totalPages: event.totalPages,
        status: event.status,
        eventSequence,
      })
    }
  } else {
    next.delete(event.taskId)
  }

  downloadProgressTasks.value = next
  scheduleDownloadClear()
}

const applyPdfProgressEvent = (event: PdfExportProgressEvent, eventSequence: number) => {
  const next = new Map(pdfProgressTasks.value)
  const existing = next.get(event.exportId)
  if (existing && event.snapshotRevision < existing.snapshotRevision) return

  if (PDF_PROGRESS_STATUSES.has(event.status)) {
    if (!hasActivePdfTasks(next)) next.clear()
    clearPdfTimer()
    next.set(event.exportId, {
      currentPages: event.currentPage,
      totalPages: event.totalPages,
      status: event.status,
      snapshotRevision: event.snapshotRevision,
      eventSequence,
    })
  } else if (event.status === 'completed') {
    if (existing) {
      next.set(event.exportId, {
        currentPages: event.currentPage,
        totalPages: event.totalPages,
        status: event.status,
        snapshotRevision: event.snapshotRevision,
        eventSequence,
      })
    }
  } else {
    next.delete(event.exportId)
  }

  pdfProgressTasks.value = next
  schedulePdfClear()
}

const flushPendingTaskProgress = () => {
  clearTaskProgressRenderTimer()
  const downloadEvents = [...pendingDownloadProgressEvents.values()].sort(
    (left, right) => left.eventSequence - right.eventSequence,
  )
  const pdfEvents = [...pendingPdfProgressEvents.values()].sort(
    (left, right) => left.eventSequence - right.eventSequence,
  )
  pendingDownloadProgressEvents.clear()
  pendingPdfProgressEvents.clear()

  for (const { event, eventSequence } of downloadEvents) {
    applyDownloadProgressEvent(event, eventSequence)
  }
  for (const { event, eventSequence } of pdfEvents) {
    applyPdfProgressEvent(event, eventSequence)
  }
}

const scheduleTaskProgressRender = () => {
  if (taskProgressRenderTimer) return
  taskProgressRenderTimer = setTimeout(() => {
    taskProgressRenderTimer = null
    flushPendingTaskProgress()
  }, TASK_PROGRESS_RENDER_INTERVAL_MS)
}

const queueDownloadProgressEvent = (event: DownloadProgressEvent) => {
  downloadEventSequence += 1
  const eventSequence = downloadEventSequence
  downloadLatestEventSequences.set(event.taskId, eventSequence)
  const isActive = DOWNLOAD_PROGRESS_STATUSES.has(event.status)
  if (isActive) clearDownloadTimer()

  const pendingEntry = pendingDownloadProgressEvents.get(event.taskId)
  const pending = pendingEntry?.event
  const current = downloadProgressTasks.value.get(event.taskId)
  if (
    (pending &&
      pending.downloadedPages === event.downloadedPages &&
      pending.totalPages === event.totalPages &&
      pending.status === event.status) ||
    (!pending &&
      current?.currentPages === event.downloadedPages &&
      current.totalPages === event.totalPages &&
      current.status === event.status)
  ) {
    return
  }

  if (!isActive && pendingEntry) {
    pendingDownloadProgressEvents.delete(event.taskId)
    applyDownloadProgressEvent(pendingEntry.event, pendingEntry.eventSequence)
  }
  pendingDownloadProgressEvents.set(event.taskId, { event, eventSequence })
  if (isActive) scheduleTaskProgressRender()
  else flushPendingTaskProgress()
}

const queuePdfProgressEvent = (event: PdfExportProgressEvent) => {
  pdfEventSequence += 1
  const eventSequence = pdfEventSequence
  const latestEvent = pdfLatestEvents.get(event.exportId)
  if (latestEvent && event.snapshotRevision < latestEvent.snapshotRevision) return

  const isActive = PDF_PROGRESS_STATUSES.has(event.status)
  if (isActive) clearPdfTimer()
  pdfLatestEvents.set(event.exportId, {
    eventSequence,
    snapshotRevision: event.snapshotRevision,
    status: event.status,
  })
  const pendingEntry = pendingPdfProgressEvents.get(event.exportId)
  if (!isActive && pendingEntry) {
    pendingPdfProgressEvents.delete(event.exportId)
    applyPdfProgressEvent(pendingEntry.event, pendingEntry.eventSequence)
  }
  pendingPdfProgressEvents.set(event.exportId, { event, eventSequence })
  if (isActive) scheduleTaskProgressRender()
  else flushPendingTaskProgress()
}

const seedDownloadProgress = (tasks: DownloadTask[], requestSequence: number) => {
  const next = new Map(downloadProgressTasks.value)
  const activeTasks = tasks.filter((task) => DOWNLOAD_PROGRESS_STATUSES.has(task.status))
  if (activeTasks.length > 0 && !hasActiveDownloadTasks(next)) {
    const hasNewerEvent = [...next.values()].some((task) => task.eventSequence > requestSequence)
    if (!hasNewerEvent) next.clear()
  }

  for (const task of activeTasks) {
    const existing = next.get(task.taskId)
    const latestEventSequence = downloadLatestEventSequences.get(task.taskId) ?? 0
    if (
      latestEventSequence > requestSequence ||
      (existing && existing.eventSequence > requestSequence)
    ) {
      continue
    }
    next.set(task.taskId, {
      currentPages: task.downloadedPages,
      totalPages: task.totalPages,
      status: task.status,
      eventSequence: existing?.eventSequence ?? 0,
    })
  }

  downloadProgressTasks.value = next
  if (hasActiveDownloadTasks()) clearDownloadTimer()
  scheduleDownloadClear()
}

const seedPdfProgress = (tasks: PdfExportTaskRecord[], requestSequence: number) => {
  const latestTasks = new Map<string, PdfExportTaskRecord>()
  for (const task of tasks) {
    if (!PDF_PROGRESS_STATUSES.has(task.status)) continue
    const existing = latestTasks.get(task.exportId)
    if (!existing || task.snapshotRevision >= existing.snapshotRevision) {
      latestTasks.set(task.exportId, task)
    }
  }

  const next = new Map(pdfProgressTasks.value)
  if (latestTasks.size > 0 && !hasActivePdfTasks(next)) {
    const hasNewerEvent = [...next.values()].some((task) => task.eventSequence > requestSequence)
    if (!hasNewerEvent) next.clear()
  }

  for (const task of latestTasks.values()) {
    const existing = next.get(task.exportId)
    const latestEvent = pdfLatestEvents.get(task.exportId)
    if (
      (latestEvent &&
        (latestEvent.eventSequence > requestSequence ||
          task.snapshotRevision < latestEvent.snapshotRevision ||
          (task.snapshotRevision === latestEvent.snapshotRevision &&
            !PDF_PROGRESS_STATUSES.has(latestEvent.status)))) ||
      (existing &&
        (existing.eventSequence > requestSequence ||
          task.snapshotRevision < existing.snapshotRevision))
    ) {
      continue
    }
    next.set(task.exportId, {
      currentPages: task.currentPage,
      totalPages: task.totalPages,
      status: task.status,
      snapshotRevision: task.snapshotRevision,
      eventSequence: existing?.eventSequence ?? 0,
    })
  }

  pdfProgressTasks.value = next
  if (hasActivePdfTasks()) clearPdfTimer()
  schedulePdfClear()
}

const refreshDownloadProgress = async (requestSequence: number) => {
  try {
    const result = await JmcomicService.getDownloadTasks()
    if (!progressUnmounted) seedDownloadProgress(result.tasks, requestSequence)
  } catch {
    // Web 调试或原生桥接未就绪时保留已收到的实时进度。
  }
}

const refreshPdfProgress = async (requestSequence: number) => {
  const statuses: PdfExportStatus[] = ['queued', 'running', 'cancelling']
  const tasks: PdfExportTaskRecord[] = []
  try {
    for (const status of statuses) {
      let cursor: string | undefined
      do {
        const result = await JmcomicService.getPdfExportTasks({ status, cursor, limit: 100 })
        tasks.push(...result.tasks)
        cursor = result.nextCursor || undefined
      } while (cursor && !progressUnmounted)
    }
    if (!progressUnmounted) seedPdfProgress(tasks, requestSequence)
  } catch {
    // Web 调试或原生桥接未就绪时保留已收到的实时进度。
  }
}

const registerProgressListeners = async () => {
  await Promise.all([
    (async () => {
      try {
        const handle = await JmcomicService.addDownloadProgressListener(queueDownloadProgressEvent)
        if (progressUnmounted) void handle.remove()
        else downloadProgressHandle = handle
      } catch {
        // Web 调试或旧版本原生插件可能没有该监听器。
      }
    })(),
    (async () => {
      try {
        const handle = await JmcomicService.addPdfExportProgressListener(queuePdfProgressEvent)
        if (progressUnmounted) void handle.remove()
        else pdfProgressHandle = handle
      } catch {
        // Web 调试或旧版本原生插件可能没有该监听器。
      }
    })(),
  ])
}

const setupTaskProgress = async () => {
  await registerProgressListeners()
  if (progressUnmounted) return
  const downloadRequestSequence = downloadEventSequence
  const pdfRequestSequence = pdfEventSequence
  await Promise.all([
    refreshDownloadProgress(downloadRequestSequence),
    refreshPdfProgress(pdfRequestSequence),
  ])
}

const isInteractive = computed(() => leftMenuOpen.value || isDragging.value)
const currentProgress = computed(() =>
  isDragging.value ? dragProgress.value : leftMenuOpen.value ? 1 : 0,
)
const panelStyle = computed(() => ({
  transform: `translate3d(${(currentProgress.value - 1) * 100}%, 0, 0)`,
  transition: isDragging.value
    ? 'none'
    : `transform ${MAIN_MENU_TRANSITION_MS}ms cubic-bezier(0.22, 0.61, 0.36, 1)`,
}))
const backdropStyle = computed(() => ({
  opacity: isInteractive.value ? 0.32 : 0,
  transition: 'none',
}))

const getPanelWidth = () => panelRef.value?.offsetWidth || (window.innerWidth <= 340 ? 264 : 304)

const isBlockedTarget = (target: EventTarget | null) => {
  if (!(target instanceof Element)) return false
  return Boolean(
    target.closest(
      'input, textarea, select, ion-range, [contenteditable="true"], [data-menu-gesture-block], .sheet-backdrop, .panel-overlay, .picker-backdrop',
    ),
  )
}

const canStartGesture = (detail: { event: UIEvent }) => {
  const menuIsOpen = leftMenuOpen.value
  if (!menuIsOpen && (props.disabled || !leftMenuGestureEnabled.value || rightMenuOpen.value)) {
    return false
  }
  if (
    !menuIsOpen &&
    document.querySelector(
      'ion-modal.show-modal, ion-alert.show-alert, ion-action-sheet.show-action-sheet',
    )
  ) {
    return false
  }
  return !isBlockedTarget(detail.event?.target)
}

const settleGesture = (wasOpen: boolean, velocityX: number) => {
  const shouldOpen =
    Math.abs(velocityX) >= 0.12 ? velocityX > 0 : dragProgress.value >= (wasOpen ? 0.82 : 0.18)

  if (shouldOpen) openLeftMenu()
  else closeLeftMenu()

  void nextTick(() => {
    isDragging.value = false
    dragProgress.value = shouldOpen ? 1 : 0
  })
}

const setupGesture = () => {
  gesture?.destroy()
  gesture = createGesture({
    el: document,
    gestureName: 'main-left-menu-swipe',
    gesturePriority: 30,
    threshold: 6,
    maxAngle: 30,
    direction: 'x',
    passive: true,
    disableScroll: true,
    canStart: canStartGesture,
    onStart: () => {
      isDragging.value = true
      dragProgress.value = leftMenuOpen.value ? 1 : 0
    },
    onMove: (detail) => {
      const startProgress = leftMenuOpen.value ? 1 : 0
      const progress = startProgress + detail.deltaX / getPanelWidth()
      dragProgress.value = Math.max(0, Math.min(1, progress))
    },
    onEnd: (detail) => {
      settleGesture(leftMenuOpen.value, detail.velocityX)
    },
  })
  gesture.enable(true)
}

const closeMenu = () => {
  closeLeftMenu()
}

let accessibilityTimer: ReturnType<typeof setTimeout> | null = null

const clearAccessibilityTimer = () => {
  if (accessibilityTimer) {
    clearTimeout(accessibilityTimer)
    accessibilityTimer = null
  }
}

const applyContentAccessibility = (open: boolean) => {
  const content = document.getElementById(props.contentId)
  if (open) {
    content?.setAttribute('aria-hidden', 'true')
    content?.setAttribute('inert', '')
    return
  }

  content?.removeAttribute('aria-hidden')
  content?.removeAttribute('inert')
  if (previouslyFocused?.isConnected) previouslyFocused.focus({ preventScroll: true })
  previouslyFocused = null
}

const updateContentAccessibility = (open: boolean, immediate = false) => {
  clearAccessibilityTimer()

  if (open && !previouslyFocused) {
    previouslyFocused =
      document.activeElement instanceof HTMLElement ? document.activeElement : null
    void nextTick(() => panelRef.value?.focus({ preventScroll: true }))
  }

  if (immediate) {
    applyContentAccessibility(open)
    return
  }

  accessibilityTimer = setTimeout(() => {
    accessibilityTimer = null
    if (leftMenuOpen.value !== open) return
    applyContentAccessibility(open)
  }, MAIN_MENU_TRANSITION_MS)
}

const handleKeyDown = (event: KeyboardEvent) => {
  if (event.key === 'Escape' && leftMenuOpen.value) {
    event.preventDefault()
    closeMenu()
  }
}

function goUser() {
  isMenuNavigation.value = true
  closeMenu()
  void router.push('/user')
}

function isActive(path: string) {
  return getTopPath(route.path) === getTopPath(path)
}

function getTopPath(path: string) {
  if (!path || path === '/') return '/'

  const first = path.split('/').filter(Boolean)[0]
  return first ? first : '/'
}

function handleMenuClick() {
  isMenuNavigation.value = true
  closeMenu()
}

watch(leftMenuOpen, (open) => updateContentAccessibility(open))
watch(
  () => props.disabled,
  (disabled) => {
    if (disabled) closeMenu()
  },
)

onMounted(() => {
  progressUnmounted = false
  void setupTaskProgress()
  setupGesture()
  document.addEventListener('keydown', handleKeyDown)
})

onUnmounted(() => {
  progressUnmounted = true
  clearDownloadTimer()
  clearPdfTimer()
  clearTaskProgressRenderTimer()
  clearAccessibilityTimer()
  void downloadProgressHandle?.remove()
  void pdfProgressHandle?.remove()
  downloadProgressHandle = null
  pdfProgressHandle = null
  downloadProgressTasks.value = new Map()
  pdfProgressTasks.value = new Map()
  downloadLatestEventSequences.clear()
  pdfLatestEvents.clear()
  pendingDownloadProgressEvents.clear()
  pendingPdfProgressEvents.clear()
  downloadEventSequence = 0
  pdfEventSequence = 0
  gesture?.destroy()
  document.removeEventListener('keydown', handleKeyDown)
  applyContentAccessibility(false)
})
</script>

<style scoped>
.main-menu {
  position: fixed;
  inset: 0;
  z-index: 1000;
  pointer-events: none;
}

.main-menu.interactive {
  pointer-events: auto;
}

.main-menu-backdrop {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  padding: 0;
  border: 0;
  background: rgb(16 12 10 / 1);
  will-change: opacity;
}

.main-menu-panel {
  position: absolute;
  inset: 0 auto 0 0;
  width: 304px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background-color: #fffaf4;
  background-image:
    radial-gradient(ellipse at 10% 8%, rgb(255 255 255 / 0.72), transparent 36%),
    radial-gradient(ellipse at 92% 42%, rgb(248 199 175 / 0.2), transparent 43%),
    radial-gradient(ellipse at 18% 92%, rgb(255 255 255 / 0.36), transparent 38%),
    linear-gradient(148deg, #fffaf4 0%, #ffece1 48%, #fffaf4 100%);
  background-blend-mode: soft-light, normal, normal, normal;
  background-size: 100% 100%;
  box-shadow: 4px 0 16px rgb(76 42 24 / 0.18);
  touch-action: pan-y;
  will-change: transform;
}

.main-menu-panel:focus {
  outline: none;
}

@media (max-width: 340px) {
  .main-menu-panel {
    width: 264px;
  }
}

.menu-hero {
  width: 100%;
  padding: calc(18px + var(--ion-safe-area-top)) 18px 14px;
  border: 0;
  background: transparent;
  display: flex;
  align-items: center;
  gap: 14px;
  cursor: pointer;
  text-align: left;
  font: inherit;
}

.user-avatar {
  width: 60px;
  height: 60px;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid #f5d0b8;
  flex-shrink: 0;
}

.user-avatar-placeholder {
  font-size: 48px;
  color: #e0b694;
  flex-shrink: 0;
}

.hero-copy {
  min-width: 0;
}

.hero-title {
  color: #3a261d;
  font-size: 22px;
  font-weight: 700;
}

.hero-subtitle {
  margin-top: 5px;
  color: #8d634a;
  font-size: 12px;
}

.menu-header {
  --background: transparent;
  background: transparent;
}

.menu-content {
  --background: transparent;
  flex: 1;
  min-height: 0;
}

.menu-list {
  --ion-item-background: transparent;
  padding: 14px 14px 0;
  background: transparent !important;
}

.menu-item {
  position: relative;
  overflow: hidden;
  --background: rgb(255 255 255 / 0.5);
  --border-radius: 20px;
  --padding-start: 14px;
  --inner-padding-end: 14px;
  --min-height: 64px;
  margin-bottom: 10px;
  border: 1px solid rgb(245 210 188 / 0.72);
  border-radius: 20px;
  box-shadow: -6px -4px 12px rgb(115 67 38 / 0.1);
}

.task-progress-background {
  position: absolute;
  inset: 0;
  z-index: 0;
  margin: 0;
  overflow: hidden;
  border-radius: inherit;
  pointer-events: none;
}

.task-progress-band {
  position: absolute;
  left: 0;
  width: 100%;
  min-width: 0;
  overflow: hidden;
  transform: translate3d(calc(var(--task-progress) - 100%), 0, 0);
  will-change: transform;
  transition:
    top 220ms ease,
    transform 180ms ease,
    height 220ms ease,
    opacity 180ms ease;
}

.task-progress-band::after {
  position: absolute;
  top: 0;
  bottom: 0;
  left: calc(100% - var(--task-progress));
  width: 100%;
  background-image: linear-gradient(
    90deg,
    transparent 0%,
    rgb(255 255 255 / 0.18) 20%,
    rgb(255 255 255 / 0.7) 50%,
    rgb(255 255 255 / 0.18) 80%,
    transparent 100%
  );
  background-position: left;
  background-repeat: no-repeat;
  background-size: 34px 100%;
  content: '';
  opacity: 0;
  pointer-events: none;
}

.main-menu.interactive .task-progress-band::after {
  animation: task-progress-glint 1.65s ease-in-out infinite;
}

@keyframes task-progress-glint {
  0% {
    transform: translate3d(-34px, 0, 0);
    opacity: 0;
  }

  8% {
    opacity: 1;
  }

  68% {
    transform: translate3d(var(--task-progress), 0, 0);
    opacity: 1;
  }

  76%,
  100% {
    transform: translate3d(var(--task-progress), 0, 0);
    opacity: 0;
  }
}

.download-progress-band {
  background: #adf4c1;
}

.pdf-progress-band {
  background: #f1acac;
}

.download-menu-label,
.download-menu-item > .menu-icon,
.task-progress-copy {
  position: relative;
  z-index: 1;
}

.download-menu-label {
  min-width: 0;
}

.task-progress-copy {
  flex: 0 0 auto;
  min-width: 54px;
  margin-left: 8px;
  color: #9a725b;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  line-height: 1.4;
  text-align: right;
  white-space: nowrap;
}

.task-progress-row {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  min-height: 15px;
}

.task-progress-spinner {
  width: 12px;
  height: 12px;
  color: currentColor;
}

.task-progress-sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

.menu-item.selected {
  --background: linear-gradient(145deg, #faa271, #f28752);
  border-color: transparent;
  box-shadow: -6px -4px 16px rgb(240 126 73 / 0.35);
  color: rgb(255, 255, 255);
}

.menu-icon {
  font-size: 18px;
  margin-right: 12px;
  flex-shrink: 0;
  color: #c96d3a;
}

.menu-item.selected .menu-icon {
  color: #fff;
}

.item-title {
  font-size: 14px;
  font-weight: 700;
}

.item-subtitle {
  margin-top: 3px;
  color: #9a725b;
  font-size: 11px;
}

.menu-item.selected .item-subtitle {
  color: rgb(255 244 237 / 0.9);
}

@media (prefers-reduced-motion: reduce) {
  .task-progress-band {
    transition: none;
  }

  .main-menu.interactive .task-progress-band::after {
    animation: none;
  }
}
</style>
