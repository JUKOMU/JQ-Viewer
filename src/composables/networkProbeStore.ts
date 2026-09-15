/**
 * 模块级网络探活事件 store —— 应用启动时初始化一次，持续记录自启动以来的全部事件。
 * 页面切换不丢失，NetworkStatusPage 等组件只读。
 */
import { ref } from 'vue'
import { JmcomicService } from '@/services/JmcomicService'
import type { NetworkProbeEvent } from '@/services/JmcomicTypes'
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
const allDeadFallback = ref(false)
const events = ref<LogEntry[]>([])
const loading = ref(false)
const errorMessage = ref('')

let initiated = false
let generation = 0
let refreshSequence = 0
let probeHandle: JmcomicListenerHandle | null = null
let invalidationHandle: JmcomicListenerHandle | null = null

export async function refreshDomainStates() {
  const sequence = ++refreshSequence
  loading.value = true
  try {
    const state = await JmcomicService.getDomainStates()
    if (sequence !== refreshSequence) return
    domains.value = state.domains
    allDeadFallback.value = state.allDeadFallback
    errorMessage.value = ''
  } catch (error) {
    if (sequence !== refreshSequence) return
    errorMessage.value = normalizeRuntimeError(error, '获取域名状态失败').message
  } finally {
    if (sequence === refreshSequence) loading.value = false
  }
}

export function initNetworkProbeStore() {
  if (initiated) return
  initiated = true
  const currentGeneration = ++generation

  void JmcomicService.addNetworkProbeListener((data: NetworkProbeEvent) => {
    if (!initiated || generation !== currentGeneration) return
    if (data.domains) {
      refreshSequence++
      loading.value = false
      domains.value = data.domains
      allDeadFallback.value = !!data.allDeadFallback
    }
    if (data.phase === 'result') errorMessage.value = ''
    if (data.phase === 'error') errorMessage.value = data.message
    events.value.push({
      phase: data.phase,
      message: data.message,
      timestamp: data.timestamp || Date.now(),
    })
    if (events.value.length > 50) {
      events.value = events.value.slice(-50)
    }
  })
    .then((handle) => {
      if (!initiated || generation !== currentGeneration) {
        void handle.remove().catch(() => {})
        return
      }
      probeHandle = handle
    })
    .catch(() => {})

  const invalidationRegistration = JmcomicService.addStateInvalidatedListener?.(() => {
    if (initiated && generation === currentGeneration) void refreshDomainStates()
  })
  if (invalidationRegistration) {
    void invalidationRegistration
      .then((handle) => {
        if (!handle) return
        if (!initiated || generation !== currentGeneration) {
          void handle.remove().catch(() => {})
          return
        }
        invalidationHandle = handle
      })
      .catch(() => {})
  }

  void refreshDomainStates()
}

export async function disposeNetworkProbeStore() {
  initiated = false
  generation++
  refreshSequence++
  const handles = [probeHandle, invalidationHandle].filter(
    (handle): handle is JmcomicListenerHandle => handle !== null,
  )
  probeHandle = null
  invalidationHandle = null
  loading.value = false
  domains.value = []
  allDeadFallback.value = false
  events.value = []
  errorMessage.value = ''
  await Promise.allSettled(handles.map((handle) => handle.remove()))
}

export function useNetworkProbeStore() {
  return { domains, allDeadFallback, events, loading, errorMessage, refreshDomainStates }
}
