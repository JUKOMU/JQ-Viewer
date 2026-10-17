export type ReaderSettingPersistenceKey =
  | 'orientation'
  | 'brightness'
  | 'keepAwake'
  | 'volumeNavigation'

interface ReaderSettingPersistenceState {
  version: number
  initialized: boolean
  confirmedValue: unknown
  queue: Promise<void>
}

export interface ReaderSettingPersistence<T> {
  promise: Promise<void>
  isLatest(): boolean
  getConfirmedValue(): T
}

const states = new Map<ReaderSettingPersistenceKey, ReaderSettingPersistenceState>()

function getState(key: ReaderSettingPersistenceKey): ReaderSettingPersistenceState {
  let state = states.get(key)
  if (!state) {
    state = {
      version: 0,
      initialized: false,
      confirmedValue: undefined,
      queue: Promise.resolve(),
    }
    states.set(key, state)
  }
  return state
}

/** 串行持久化同一阅读器设置，并为最新操作保留最后确认成功的回滚值。 */
export function persistReaderSettingValue<T>(
  key: ReaderSettingPersistenceKey,
  value: T,
  initialConfirmedValue: T,
  persist: () => Promise<unknown>,
): ReaderSettingPersistence<T> {
  const state = getState(key)
  if (!state.initialized) {
    state.confirmedValue = initialConfirmedValue
    state.initialized = true
  }

  const version = ++state.version
  const promise = state.queue.then(async () => {
    await persist()
    state.confirmedValue = value
  })
  state.queue = promise.catch(() => undefined)

  return {
    promise,
    isLatest: () => state.version === version,
    getConfirmedValue: () => state.confirmedValue as T,
  }
}
