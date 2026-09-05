import type { UpdateManifest, UpdateProgressEvent } from '@/services/JmcomicTypes'
import type { ListenerHandle } from './BackendEvents'

export type UpdateUserAction = {
  id: string
  kind: 'grant-install-permission'
  stateRevision: number
}

export type UpdateState = UpdateProgressEvent & {
  requiredUserAction?: UpdateUserAction
}

export interface UpdaterService {
  getState(): Promise<UpdateState>
  check(): Promise<{ updateAvailable: boolean; manifest: UpdateManifest }>
  start(): Promise<{ started: boolean }>
  cancel(): Promise<{ cancelled: boolean }>
  install(): Promise<{ started: boolean; permissionRequired: boolean }>
  performUserAction(action: UpdateUserAction): Promise<void>
  onProgress(handler: (state: UpdateState) => void): Promise<ListenerHandle>
}
