import { createAppAlert } from './AppAlertService'
import type { FrontendRuntime } from '@/runtime/FrontendRuntime'
import { getRuntime as getCurrentRuntime } from '@/runtime/runtimeContext'

type RuntimeProvider = () => FrontendRuntime
type AlertFactory = typeof createAppAlert

/**
 * 下载通知权限工作流：只在需要运行时授权的平台查询并提示，宿主管理或不可用时直接跳过。
 * 服务实例在当前前端生命周期内记住处理结果，避免每次提交下载都重复打扰用户。
 */
export interface NotificationPermissionService {
  ensureDownloadPermission(): Promise<void>
}

/** 构造可注入的通知权限服务，测试可提供独立 runtime 与弹窗工厂。 */
export function createNotificationPermissionService(
  runtimeProvider: RuntimeProvider = getCurrentRuntime,
  alertFactory: AlertFactory = createAppAlert,
): NotificationPermissionService {
  let prompted = false
  let promptPromise: Promise<void> | null = null

  const ensureDownloadPermission = (): Promise<void> => {
    if (prompted) return Promise.resolve()
    if (promptPromise) return promptPromise

    const operation = (async () => {
      try {
        const policy = runtimeProvider().services.notifications
        if (policy.kind !== 'runtime-permission') return

        const check = await policy.permissions.check()
        if (check.granted) return

        const alert = await alertFactory({
          tone: 'info',
          header: '需要通知权限',
          message:
            '章节下载将在后台进行，需要通过通知查看进度。拒绝后仍会继续下载，但不会显示系统通知。',
          buttons: [
            { text: '暂不授权', role: 'cancel' },
            { text: '允许通知', role: 'confirm' },
          ],
        })
        await alert.present()
        const dismissed = await alert.onDidDismiss()
        if (dismissed.role === 'confirm') {
          await policy.permissions.request()
        }
      } catch {
        // 权限提示异常不应阻塞下载任务提交。
      } finally {
        prompted = true
        promptPromise = null
      }
    })()

    promptPromise = operation
    return operation
  }

  return { ensureDownloadPermission }
}

/** Android 下载流程使用的单例服务；其他平台由其 runtime policy 决定是否跳过。 */
export const NotificationPermissionService = createNotificationPermissionService()
