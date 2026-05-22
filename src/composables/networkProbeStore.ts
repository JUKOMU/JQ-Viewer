/**
 * 模块级网络探活事件 store —— 应用启动时初始化一次，持续记录自启动以来的全部事件。
 * 页面切换不丢失，NetworkStatusPage 等组件只读。
 */
import { ref } from 'vue'
import { JmcomicService } from '@/services/JmcomicService'
import type { NetworkProbeEvent } from '@/services/JmcomicTypes'

interface DomainState { domain: string; reachable: boolean }
interface LogEntry { phase: string; message: string; timestamp: number }

const domains = ref<DomainState[]>([])
const allDeadFallback = ref(false)
const events = ref<LogEntry[]>([])

let initiated = false

export function initNetworkProbeStore() {
  if (initiated) return
  initiated = true

  JmcomicService.addNetworkProbeListener((data: NetworkProbeEvent) => {
    if (data.domains) {
      domains.value = data.domains
      allDeadFallback.value = !!data.allDeadFallback
    }
    events.value.push({
      phase: data.phase,
      message: data.message,
      timestamp: data.timestamp || Date.now(),
    })
    if (events.value.length > 50) {
      events.value = events.value.slice(-50)
    }
  })

  // 拉取已有域名状态（来自 AbstractJmClient 构造时的初始探活）
  JmcomicService.getDomainStates().then(state => {
    domains.value = state.domains
    allDeadFallback.value = state.allDeadFallback
  }).catch(() => {
    // client 尚未就绪时静默忽略，等待后续网络变化事件
  })
}

export function useNetworkProbeStore() {
  return { domains, allDeadFallback, events }
}
