import { alertController } from '@ionic/vue'
import { shallowRef } from 'vue'
import type { UpdateManifest, UpdateProgressEvent } from './JmcomicTypes'
import { JmcomicService, showToast } from './JmcomicService'

const IDLE_STATE: UpdateProgressEvent = {
  revision: 0,
  phase: 'idle',
  source: '',
  githubBytes: 0,
  giteeBytes: 0,
  totalBytes: 0,
  error: '',
}

const manifest = shallowRef<UpdateManifest | null>(null)
const state = shallowRef<UpdateProgressEvent>({ ...IDLE_STATE })

let listenerPromise: Promise<void> | null = null
let listenerHandle: { remove: () => Promise<void> } | null = null
let checkPromise: Promise<UpdateCheckResult> | null = null
let notificationPromptPromise: Promise<boolean> | null = null
let promptPromise: Promise<unknown> | null = null
let installPromise: Promise<InstallResult> | null = null
let updateStarted = false

export interface UpdateCheckResult {
  updateAvailable: boolean
  manifest: UpdateManifest
}

export interface StartUpdateResult {
  started: boolean
  blocked?: 'notification_permission'
}

export interface InstallResult {
  started: boolean
  permissionRequired: boolean
}

async function ensureProgressListener(): Promise<void> {
  if (listenerHandle) return
  if (listenerPromise) return listenerPromise
  listenerPromise = JmcomicService.addUpdateProgressListener((event) => {
    state.value = { ...event }
    if (event.phase === 'ready_to_install' && updateStarted) {
      void installReadyUpdate()
    }
  })
    .then((handle) => {
      listenerHandle = handle
      return JmcomicService.getUpdateState()
        .then((snapshot) => {
          state.value = { ...snapshot }
        })
        .catch(() => {
          // Web 调试或旧版本原生插件可能没有状态快照。
        })
    })
    .catch(() => {
      // Web 调试环境没有原生事件插件，调用方仍可使用检查接口。
    })
    .finally(() => {
      listenerPromise = null
    })
  return listenerPromise
}

async function ensureNotificationPermission(): Promise<boolean> {
  if (notificationPromptPromise) return notificationPromptPromise
  notificationPromptPromise = (async () => {
    try {
      const current = await JmcomicService.checkNotificationPermission()
      if (current.granted) return true

      const alert = await alertController.create({
        header: '需要通知权限',
        message: '应用更新需要系统通知显示下载进度。拒绝后不会开始下载。',
        buttons: [
          { text: '取消', role: 'cancel' },
          { text: '打开设置', role: 'settings' },
          { text: '允许通知', role: 'confirm' },
        ],
      })
      await alert.present()
      const dismissed = await alert.onDidDismiss()
      if (dismissed.role === 'settings') {
        await JmcomicService.openNotificationSettings()
        return false
      }
      if (dismissed.role !== 'confirm') return false
      const requested = await JmcomicService.requestNotificationPermission()
      return requested.granted
    } catch {
      return false
    } finally {
      notificationPromptPromise = null
    }
  })()
  return notificationPromptPromise
}

async function runPrompt<T>(factory: () => Promise<T>): Promise<T | undefined> {
  if (promptPromise) {
    await promptPromise
    return undefined
  }
  const current = factory()
  promptPromise = current
  try {
    return await current
  } finally {
    promptPromise = null
  }
}

async function check(): Promise<UpdateCheckResult> {
  await ensureProgressListener()
  if (checkPromise) return checkPromise
  checkPromise = JmcomicService.checkUpdate()
    .then((result) => {
      manifest.value = result.manifest
      return result
    })
    .finally(() => {
      checkPromise = null
    })
  return checkPromise
}

async function start(): Promise<StartUpdateResult> {
  await ensureProgressListener()
  if (!(await ensureNotificationPermission())) {
    return { started: false, blocked: 'notification_permission' }
  }
  updateStarted = true
  try {
    const result = await JmcomicService.startUpdate()
    if (!result.started) updateStarted = false
    return result
  } catch (error) {
    updateStarted = false
    throw error
  }
}

async function cancel() {
  updateStarted = false
  return JmcomicService.cancelUpdate()
}

async function installReadyUpdate(): Promise<InstallResult> {
  if (installPromise) return installPromise
  installPromise = (async () => {
    try {
      const result = await JmcomicService.installUpdate()
      if (!result.permissionRequired) return result

      const alert = await alertController.create({
        header: '允许安装更新',
        message: '请在系统设置中允许 JQ Viewer 安装未知来源应用。返回应用后将继续安装。',
        buttons: [
          { text: '取消', role: 'cancel' },
          {
            text: '打开设置',
            role: 'confirm',
          },
        ],
      })
      await alert.present()
      const dismissed = await alert.onDidDismiss()
      if (dismissed.role === 'confirm') {
        await JmcomicService.requestInstallPermission()
      }
      return result
    } catch (error) {
      await showToast(error instanceof Error ? error.message : '无法启动安装器', 'danger', 2500)
      throw error
    } finally {
      installPromise = null
    }
  })()
  return installPromise
}

export const UpdateService = {
  manifest,
  state,
  init: ensureProgressListener,
  check,
  start,
  cancel,
  install: installReadyUpdate,
  runPrompt,
  formatMiB(bytes: number): string {
    if (!Number.isFinite(bytes) || bytes <= 0) return '0 MiB'
    const value = bytes / (1024 * 1024)
    return `${value >= 10 ? value.toFixed(0) : value.toFixed(1)} MiB`
  },
  dispose: async () => {
    await listenerHandle?.remove()
    listenerHandle = null
  },
}
