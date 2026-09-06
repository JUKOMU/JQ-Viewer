import type { JmcomicClient } from '@/services/jmcomic/JmcomicClient'
import type { ListenerHandle } from '../BackendEvents'
import { normalizeRuntimeError, RuntimeError, withRuntimeError } from '../errors'
import type { UpdateState, UpdateUserAction, UpdaterService } from '../UpdateTypes'
import { createAndroidBackendEvents } from './androidBackendEvents'

const ACTION_KIND = 'grant-install-permission' as const

/**
 * 把 Android 原生更新状态转换为平台无关的 UpdateState：
 * 当原生 phase 表示需要安装权限时，生成一个带 id 与 stateRevision 的
 * UpdateUserAction，供 UI 触发并校验过期动作。
 */
function toUpdateState(event: Awaited<ReturnType<JmcomicClient['getUpdateState']>>): UpdateState {
  return {
    ...event,
    ...(event.phase === 'install_permission_required'
      ? {
          requiredUserAction: {
            id: `${ACTION_KIND}:${event.revision}`,
            kind: ACTION_KIND,
            stateRevision: event.revision,
          },
        }
      : {}),
  }
}

/**
 * 构造 Android 更新器。performUserAction 会先校验动作是否过期，
 * 再映射到原生的 requestInstallPermission；同一动作重复确认时幂等跳过。
 */
export function createAndroidUpdater(native: JmcomicClient): UpdaterService {
  const events = createAndroidBackendEvents(native)
  let lastActionId: string | null = null

  return {
    getState: () => withRuntimeError(async () => toUpdateState(await native.getUpdateState())),
    check: () => withRuntimeError(() => native.checkUpdate()),
    start: () => withRuntimeError(() => native.startUpdate()),
    cancel: () => withRuntimeError(() => native.cancelUpdate()),
    install: () => withRuntimeError(() => native.installUpdate()),
    performUserAction: async (action: UpdateUserAction) => {
      if (action.kind !== ACTION_KIND) {
        throw new RuntimeError('unavailable', `Unsupported update action: ${action.kind}`)
      }
      const current = await withRuntimeError(async () => toUpdateState(await native.getUpdateState()))
      if (current.requiredUserAction?.id !== action.id || current.revision !== action.stateRevision) {
        throw new RuntimeError('conflict', 'Update action is stale')
      }
      if (lastActionId === action.id) return
      try {
        await withRuntimeError(() => native.requestInstallPermission())
        lastActionId = action.id
      } catch (error) {
        throw normalizeRuntimeError(error)
      }
    },
    onProgress: async (handler) => {
      const handle = await events.onUpdateProgress((event) => handler(toUpdateState(event)))
      return handle as ListenerHandle
    },
  }
}
