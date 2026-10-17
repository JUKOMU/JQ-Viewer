import type { UpdateManifest, UpdateProgressEvent } from '@/services/JmcomicTypes'
import type { ListenerHandle } from './BackendEvents'

/**
 * 更新流程中需要用户介入的动作。仅携带 JSON 数据（不含函数 handler），
 * id 与 stateRevision 用于拒绝过期动作、保证重复确认幂等。
 */
export type UpdateUserAction = {
  id: string
  kind: 'grant-install-permission'
  stateRevision: number
}

/** 更新状态：在底层进度事件之上附加当前待执行的用户动作。 */
export type UpdateState = UpdateProgressEvent & {
  requiredUserAction?: UpdateUserAction
}

/**
 * 平台更新器：查询、检查、启动、取消、安装与用户动作执行，
 * 通过 onProgress 暴露 revision 可比较的状态流。
 */
export interface UpdaterService {
  getState(): Promise<UpdateState>
  check(): Promise<{ updateAvailable: boolean; manifest: UpdateManifest }>
  start(): Promise<{ started: boolean }>
  cancel(): Promise<{ cancelled: boolean }>
  install(): Promise<{ started: boolean; permissionRequired: boolean }>
  performUserAction(action: UpdateUserAction): Promise<void>
  onProgress(handler: (state: UpdateState) => void): Promise<ListenerHandle>
}
