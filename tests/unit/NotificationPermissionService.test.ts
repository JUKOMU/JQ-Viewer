import { describe, expect, test, vi } from 'vitest'
import type { FrontendRuntime } from '@/runtime/FrontendRuntime'
import type { NotificationPolicy } from '@/runtime/PlatformServices'
import { createNotificationPermissionService } from '@/services/NotificationPermissionService'
import type { createAppAlert } from '@/services/AppAlertService'

type Deferred<T> = {
  promise: Promise<T>
  resolve: (value: T) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

function runtimeWith(notifications: NotificationPolicy): FrontendRuntime {
  return { services: { notifications } } as unknown as FrontendRuntime
}

function createAlertFactory(role: string) {
  const alert = {
    present: vi.fn().mockResolvedValue(undefined),
    onDidDismiss: vi.fn().mockResolvedValue({ role }),
  }
  const factory = vi.fn().mockResolvedValue(alert) as unknown as typeof createAppAlert
  return { alert, factory }
}

function createPermissionPort() {
  return {
    check: vi.fn(),
    request: vi.fn(),
    openSettings: vi.fn(),
  }
}

describe('NotificationPermissionService', () => {
  test('已授权时不弹窗，也不重复查询', async () => {
    const permissions = createPermissionPort()
    permissions.check.mockResolvedValue({ granted: true })
    const { factory } = createAlertFactory('confirm')
    const service = createNotificationPermissionService(
      () => runtimeWith({ kind: 'runtime-permission', permissions }),
      factory,
    )

    await service.ensureDownloadPermission()
    await service.ensureDownloadPermission()

    expect(permissions.check).toHaveBeenCalledOnce()
    expect(factory).not.toHaveBeenCalled()
    expect(permissions.request).not.toHaveBeenCalled()
  })

  test('用户拒绝时保持下载可继续且不请求系统权限', async () => {
    const permissions = createPermissionPort()
    permissions.check.mockResolvedValue({ granted: false })
    const { alert, factory } = createAlertFactory('cancel')
    const service = createNotificationPermissionService(
      () => runtimeWith({ kind: 'runtime-permission', permissions }),
      factory,
    )

    await expect(service.ensureDownloadPermission()).resolves.toBeUndefined()
    await service.ensureDownloadPermission()

    expect(factory).toHaveBeenCalledWith(
      expect.objectContaining({
        header: '需要通知权限',
        message:
          '章节下载将在后台进行，需要通过通知查看进度。拒绝后仍会继续下载，但不会显示系统通知。',
        buttons: [
          { text: '暂不授权', role: 'cancel' },
          { text: '允许通知', role: 'confirm' },
        ],
      }),
    )
    expect(alert.present).toHaveBeenCalledOnce()
    expect(permissions.request).not.toHaveBeenCalled()
  })

  test('host-managed 平台跳过 Android 权限检查和弹窗', async () => {
    const permissions = createPermissionPort()
    const { factory } = createAlertFactory('confirm')
    const service = createNotificationPermissionService(
      () => runtimeWith({ kind: 'host-managed' }),
      factory,
    )

    await service.ensureDownloadPermission()

    expect(permissions.check).not.toHaveBeenCalled()
    expect(permissions.request).not.toHaveBeenCalled()
    expect(factory).not.toHaveBeenCalled()
  })

  test('并发调用复用同一个通知权限 workflow Promise', async () => {
    const permissions = createPermissionPort()
    const check = deferred<{ granted: boolean }>()
    permissions.check.mockReturnValue(check.promise)
    const { factory } = createAlertFactory('confirm')
    const service = createNotificationPermissionService(
      () => runtimeWith({ kind: 'runtime-permission', permissions }),
      factory,
    )

    const first = service.ensureDownloadPermission()
    const second = service.ensureDownloadPermission()
    expect(first).toBe(second)
    expect(permissions.check).toHaveBeenCalledOnce()

    check.resolve({ granted: false })
    await Promise.all([first, second])
    expect(factory).toHaveBeenCalledOnce()
    expect(permissions.request).toHaveBeenCalledOnce()
  })
})
