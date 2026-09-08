import { beforeEach, describe, expect, test, vi } from 'vitest'
import type { UpdateProgressEvent } from '@/services/JmcomicTypes'

const mocks = vi.hoisted(() => ({
  alertCreate: vi.fn(),
  checkNotificationPermission: vi.fn(),
  requestNotificationPermission: vi.fn(),
  openNotificationSettings: vi.fn(),
  addUpdateProgressListener: vi.fn(),
  getUpdateState: vi.fn(),
  startUpdate: vi.fn(),
  cancelUpdate: vi.fn(),
  installUpdate: vi.fn(),
  requestInstallPermission: vi.fn(),
  performUserAction: vi.fn(),
  showToast: vi.fn(),
}))

vi.mock('@ionic/vue', () => ({
  alertController: { create: mocks.alertCreate },
}))
vi.mock('@/services/AppAlertService', () => ({ createAppAlert: mocks.alertCreate }))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    checkNotificationPermission: mocks.checkNotificationPermission,
    requestNotificationPermission: mocks.requestNotificationPermission,
    openNotificationSettings: mocks.openNotificationSettings,
    addUpdateProgressListener: mocks.addUpdateProgressListener,
    getUpdateState: mocks.getUpdateState,
    startUpdate: mocks.startUpdate,
    cancelUpdate: mocks.cancelUpdate,
    installUpdate: mocks.installUpdate,
    requestInstallPermission: mocks.requestInstallPermission,
  },
  showToast: mocks.showToast,
}))
vi.mock('@/runtime/runtimeContext', () => ({
  getRuntime: () => ({
    services: {
      updater: {
        available: true,
        api: {
          getState: mocks.getUpdateState,
          performUserAction: mocks.performUserAction,
        },
      },
    },
  }),
}))

async function loadUpdateService() {
  vi.resetModules()
  return (await import('@/services/UpdateService')).UpdateService
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.addUpdateProgressListener.mockResolvedValue({ remove: vi.fn() })
  mocks.getUpdateState.mockResolvedValue({
    revision: 0,
    phase: 'idle',
    source: '',
    githubBytes: 0,
    giteeBytes: 0,
    totalBytes: 0,
    speedBytesPerSecond: 0,
    error: '',
  })
  mocks.checkNotificationPermission.mockResolvedValue({ granted: false })
  mocks.requestNotificationPermission.mockResolvedValue({ granted: true })
  mocks.openNotificationSettings.mockResolvedValue({ opened: true })
  mocks.startUpdate.mockResolvedValue({ started: true })
  mocks.performUserAction.mockResolvedValue(undefined)
  mocks.showToast.mockResolvedValue(undefined)
})

describe('UpdateService 通知权限', () => {
  test('MiB 始终保留一位小数', async () => {
    const service = await loadUpdateService()

    expect(service.formatMiB(0)).toBe('0.0 MiB')
    expect(service.formatMiB(10 * 1024 * 1024)).toBe('10.0 MiB')
  })

  test('用户取消后再次点击仍可重新请求权限', async () => {
    mocks.alertCreate
      .mockResolvedValueOnce({
        present: vi.fn(),
        onDidDismiss: vi.fn().mockResolvedValue({ role: 'cancel' }),
      })
      .mockResolvedValueOnce({
        present: vi.fn(),
        onDidDismiss: vi.fn().mockResolvedValue({ role: 'confirm' }),
      })
    const service = await loadUpdateService()

    await expect(service.start()).resolves.toEqual({
      started: false,
      blocked: 'notification_permission',
    })
    await expect(service.start()).resolves.toEqual({ started: true })

    expect(mocks.alertCreate).toHaveBeenCalledTimes(2)
    expect(mocks.requestNotificationPermission).toHaveBeenCalledTimes(1)
    expect(mocks.startUpdate).toHaveBeenCalledTimes(1)
  })

  test('打开通知设置后不开始下载', async () => {
    mocks.alertCreate.mockResolvedValue({
      present: vi.fn(),
      onDidDismiss: vi.fn().mockResolvedValue({ role: 'settings' }),
    })
    const service = await loadUpdateService()

    await expect(service.start()).resolves.toEqual({
      started: false,
      blocked: 'notification_permission',
    })

    expect(mocks.openNotificationSettings).toHaveBeenCalledTimes(1)
    expect(mocks.requestNotificationPermission).not.toHaveBeenCalled()
    expect(mocks.startUpdate).not.toHaveBeenCalled()
  })
})

describe('UpdateService 安装权限', () => {
  test('弹窗关闭后再请求安装来源权限', async () => {
    const onDidDismiss = vi.fn().mockResolvedValue({ role: 'confirm' })
    mocks.alertCreate.mockResolvedValue({ present: vi.fn(), onDidDismiss })
    mocks.installUpdate.mockResolvedValue({ started: false, permissionRequired: true })
    mocks.getUpdateState.mockResolvedValueOnce({
      revision: 4,
      phase: 'install_permission_required',
      source: '',
      githubBytes: 0,
      giteeBytes: 0,
      totalBytes: 0,
      speedBytesPerSecond: 0,
      error: '',
      requiredUserAction: {
        id: 'grant-install-permission:4',
        kind: 'grant-install-permission',
        stateRevision: 4,
      },
    })
    const service = await loadUpdateService()

    await service.install()

    expect(onDidDismiss).toHaveBeenCalledTimes(1)
    expect(mocks.performUserAction).toHaveBeenCalledWith({
      id: 'grant-install-permission:4',
      kind: 'grant-install-permission',
      stateRevision: 4,
    })
    expect(onDidDismiss.mock.invocationCallOrder[0]).toBeLessThan(
      mocks.performUserAction.mock.invocationCallOrder[0],
    )
  })

  test('订阅后收到的新状态不会被较旧快照覆盖', async () => {
    let eventHandler: ((event: UpdateProgressEvent) => void) | undefined
    let resolveSnapshot!: (state: UpdateProgressEvent) => void
    mocks.addUpdateProgressListener.mockImplementation((handler) => {
      eventHandler = handler
      return Promise.resolve({ remove: vi.fn() })
    })
    mocks.getUpdateState.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveSnapshot = resolve
        }),
    )

    const service = await loadUpdateService()
    const initPromise = service.init()
    await new Promise((resolve) => setTimeout(resolve, 0))

    eventHandler?.({
      revision: 2,
      phase: 'ready_to_install',
      source: '',
      githubBytes: 2,
      giteeBytes: 0,
      totalBytes: 2,
      speedBytesPerSecond: 1,
      error: '',
    })
    resolveSnapshot({
      revision: 1,
      phase: 'selected',
      source: '',
      githubBytes: 1,
      giteeBytes: 0,
      totalBytes: 2,
      speedBytesPerSecond: 1,
      error: '',
    })

    await initPromise
    expect(service.state.value.revision).toBe(2)
    expect(service.state.value.phase).toBe('ready_to_install')
  })
})
