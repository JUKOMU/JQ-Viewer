import type { JmcomicClient } from '@/services/jmcomic/JmcomicClient'
import type { ListenerHandle } from '../BackendEvents'
import { normalizeRuntimeError, withRuntimeError } from '../errors'
import type { UpdateState, UpdateUserAction, UpdaterService } from '../UpdateTypes'
import { createAndroidBackendEvents } from './androidBackendEvents'

const ACTION_KIND = 'grant-install-permission' as const

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
        throw new Error(`Unsupported update action: ${action.kind}`)
      }
      const current = await withRuntimeError(async () => toUpdateState(await native.getUpdateState()))
      if (current.requiredUserAction?.id !== action.id || current.revision !== action.stateRevision) {
        throw new Error('Update action is stale')
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
