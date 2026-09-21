/**
 * 模块级网络探活事件 store —— 应用启动时初始化一次，持续记录自启动以来的全部事件。
 * 页面切换不丢失，NetworkStatusPage 等组件只读。
 */
import { computed, ref } from 'vue'
import { JmcomicService } from '@/services/JmcomicService'
import type { ClientStateSnapshot, NetworkProbeEvent } from '@/services/JmcomicTypes'
import type { JmcomicListenerHandle } from '@/services/jmcomic/JmcomicClient'
import { normalizeRuntimeError } from '@/runtime/errors'

interface DomainState {
  domain: string
  reachable: boolean
}

interface LogEntry {
  phase: string
  message: string
  timestamp: number
}

const domains = ref<DomainState[]>([])
const clientState = ref<ClientStateSnapshot>({ state: 'initializing', timestamp: 0 })
const allDeadFallback = ref(false)
const events = ref<LogEntry[]>([])
const loading = ref(false)
const snapshotErrorMessage = ref('')
const listenerErrorMessage = ref('')
const probeErrorMessage = ref('')
const errorMessage = computed(
  () => listenerErrorMessage.value || probeErrorMessage.value || snapshotErrorMessage.value,
)

const LISTENER_RETRY_DELAY_MS = 1_000
const MAX_LISTENER_RETRIES = 2

let initiated = false
let generation = 0
let latestClientStateTimestamp = -Infinity
let refreshSequence = 0
let listenerRetryCount = 0
let listenerRetryTimer: ReturnType<typeof setTimeout> | null = null
let probeHandle: JmcomicListenerHandle | null = null
let invalidationHandle: JmcomicListenerHandle | null = null
let clientStateHandle: JmcomicListenerHandle | null = null

export async function refreshDomainStates() {
  if (clientState.value.state !== 'ready') {
    refreshSequence++
    loading.value = false
    domains.value = []
    allDeadFallback.value = false
    snapshotErrorMessage.value = ''
    return
  }
  const sequence = ++refreshSequence
  loading.value = true
  try {
    const state = await JmcomicService.getDomainStates()
    if (sequence !== refreshSequence) return
    domains.value = state.domains
    allDeadFallback.value = state.allDeadFallback
    snapshotErrorMessage.value = ''
    probeErrorMessage.value = ''
  } catch (error) {
    if (sequence !== refreshSequence) return
    snapshotErrorMessage.value = normalizeRuntimeError(error, '获取域名状态失败').message
  } finally {
    if (sequence === refreshSequence) loading.value = false
  }
}

export function initNetworkProbeStore() {
  startNetworkProbeStore(true)
}

function startNetworkProbeStore(resetRetries: boolean) {
  if (initiated) return
  if (resetRetries) listenerRetryCount = 0
  initiated = true
  const currentGeneration = ++generation
  latestClientStateTimestamp = -Infinity

  const clientStateRegistration = JmcomicService.addClientStateListener((snapshot) => {
    applyClientStateForGeneration(currentGeneration, snapshot)
  })

  const probeRegistration = JmcomicService.addNetworkProbeListener((data: NetworkProbeEvent) => {
    if (!initiated || generation !== currentGeneration) return
    if (data.domains) {
      refreshSequence++
      loading.value = false
      domains.value = data.domains
      allDeadFallback.value = !!data.allDeadFallback
    }
    if (data.phase === 'result') probeErrorMessage.value = ''
    if (data.phase === 'error') probeErrorMessage.value = data.message
    events.value.push({
      phase: data.phase,
      message: data.message,
      timestamp: data.timestamp || Date.now(),
    })
    if (events.value.length > 50) {
      events.value = events.value.slice(-50)
    }
  })

  const invalidationRegistration = JmcomicService.addStateInvalidatedListener(() => {
    if (initiated && generation === currentGeneration) {
      void refreshClientState(currentGeneration)
    }
  })

  void finishListenerRegistration(
    currentGeneration,
    clientStateRegistration,
    probeRegistration,
    invalidationRegistration,
  )

  void refreshClientState(currentGeneration)
}

async function refreshClientState(currentGeneration: number) {
  try {
    applyClientStateForGeneration(currentGeneration, await JmcomicService.getClientState())
  } catch {
    // 旧 Desktop 后端没有状态事件时仍由其 ready 快照兼容层提供结果。
  }
}

function applyClientStateForGeneration(currentGeneration: number, snapshot: ClientStateSnapshot) {
  if (
    !initiated ||
    generation !== currentGeneration ||
    snapshot.timestamp <= latestClientStateTimestamp
  ) {
    return
  }
  latestClientStateTimestamp = snapshot.timestamp
  applyClientState(snapshot)
}

function applyClientState(snapshot: ClientStateSnapshot) {
  clientState.value = snapshot
  if (snapshot.state === 'ready') {
    void refreshDomainStates()
  } else {
    refreshSequence++
    loading.value = false
    domains.value = []
    allDeadFallback.value = false
    snapshotErrorMessage.value = ''
  }
}

async function finishListenerRegistration(
  currentGeneration: number,
  clientStateRegistration: Promise<JmcomicListenerHandle>,
  probeRegistration: Promise<JmcomicListenerHandle>,
  invalidationRegistration: Promise<JmcomicListenerHandle | null>,
) {
  const [clientStateResult, probeResult, invalidationResult] = await Promise.allSettled([
    clientStateRegistration,
    probeRegistration,
    invalidationRegistration,
  ])
  const handles = [
    clientStateResult.status === 'fulfilled' ? clientStateResult.value : null,
    probeResult.status === 'fulfilled' ? probeResult.value : null,
    invalidationResult.status === 'fulfilled' ? invalidationResult.value : null,
  ].filter((handle): handle is JmcomicListenerHandle => handle !== null)

  if (!initiated || generation !== currentGeneration) {
    await removeListenerHandles(handles)
    return
  }

  if (clientStateResult.status === 'rejected') {
    await handleListenerRegistrationFailure(currentGeneration, handles, clientStateResult.reason)
    return
  }
  if (probeResult.status === 'rejected') {
    await handleListenerRegistrationFailure(currentGeneration, handles, probeResult.reason)
    return
  }
  if (invalidationResult.status === 'rejected') {
    await handleListenerRegistrationFailure(currentGeneration, handles, invalidationResult.reason)
    return
  }

  clientStateHandle = clientStateResult.value
  probeHandle = probeResult.value
  invalidationHandle = invalidationResult.value
  listenerErrorMessage.value = ''
  listenerRetryCount = 0
}

async function handleListenerRegistrationFailure(
  currentGeneration: number,
  handles: JmcomicListenerHandle[],
  reason: unknown,
) {
  await removeListenerHandles(handles)
  if (!initiated || generation !== currentGeneration) return
  listenerErrorMessage.value = normalizeRuntimeError(reason, '网络状态监听失败').message
  initiated = false
  generation++
  latestClientStateTimestamp = -Infinity
  scheduleListenerRetry()
}

function scheduleListenerRetry() {
  if (listenerRetryTimer || listenerRetryCount >= MAX_LISTENER_RETRIES) return
  listenerRetryCount++
  listenerRetryTimer = setTimeout(() => {
    listenerRetryTimer = null
    startNetworkProbeStore(false)
  }, LISTENER_RETRY_DELAY_MS)
}

async function removeListenerHandles(handles: JmcomicListenerHandle[]) {
  await Promise.allSettled(handles.map((handle) => handle.remove()))
}

export async function disposeNetworkProbeStore() {
  initiated = false
  generation++
  refreshSequence++
  if (listenerRetryTimer) {
    clearTimeout(listenerRetryTimer)
    listenerRetryTimer = null
  }
  listenerRetryCount = 0
  const handles = [probeHandle, invalidationHandle, clientStateHandle].filter(
    (handle): handle is JmcomicListenerHandle => handle !== null,
  )
  probeHandle = null
  invalidationHandle = null
  clientStateHandle = null
  loading.value = false
  domains.value = []
  clientState.value = { state: 'initializing', timestamp: 0 }
  allDeadFallback.value = false
  events.value = []
  snapshotErrorMessage.value = ''
  listenerErrorMessage.value = ''
  probeErrorMessage.value = ''
  await removeListenerHandles(handles)
}

export function useNetworkProbeStore() {
  return {
    domains,
    clientState,
    allDeadFallback,
    events,
    loading,
    errorMessage,
    refreshDomainStates,
  }
}
