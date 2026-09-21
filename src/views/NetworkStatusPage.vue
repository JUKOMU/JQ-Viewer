<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar class="toolbar">
        <IonButtons slot="start">
          <IonBackButton default-href="/setting" />
        </IonButtons>
        <IonTitle class="toolbar-title">网络状态</IonTitle>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <div class="page-content">
        <div class="status-grid">
          <section class="status-section">
            <!-- 域名连通性 -->
            <div class="section-header">
              <span class="section-label">域名连通性</span>
              <div class="header-actions">
                <IonIcon
                  :icon="speedometerOutline"
                  class="speed-btn"
                  :class="{ spinning: measuring }"
                  @click="handleMeasureLatency"
                />
                <IonIcon
                  :icon="refreshOutline"
                  class="refresh-btn"
                  :class="{ spinning: refreshing }"
                  @click="handleRefresh"
                />
              </div>
            </div>
            <div class="card">
              <div v-if="visibleError" class="operation-error">{{ visibleError }}</div>
              <div v-if="store.domains.value.length" class="domain-list">
                <div v-for="d in store.domains.value" :key="d.domain" class="domain-row">
                  <span
                    class="domain-dot"
                    :class="store.allDeadFallback.value ? 'dead' : d.reachable ? 'alive' : 'dead'"
                  />
                  <span class="domain-name">{{ d.domain }}</span>
                  <span class="latency" :class="latencyClass(d.domain, d.reachable)">
                    {{ latencyText(d.domain, d.reachable) }}
                  </span>
                </div>
              </div>
              <div v-else-if="store.loading.value" class="empty-state">正在读取域名状态...</div>
              <div v-else-if="store.clientState.value.state === 'initializing'" class="empty-state">
                在线客户端正在初始化
              </div>
              <div v-else-if="store.clientState.value.state === 'unavailable'" class="empty-state">
                {{ clientUnavailableText }}
              </div>
              <div v-else-if="!visibleError" class="empty-state">暂无域名状态</div>
            </div>
          </section>

          <section class="status-section">
            <!-- 事件日志 -->
            <div class="section-label section-title">事件日志</div>
            <div class="card">
              <div v-if="store.events.value.length" class="log-list">
                <div v-for="(evt, i) in store.events.value" :key="i" class="log-row">
                  <span class="log-time">{{ formatTime(evt.timestamp) }}</span>
                  <span class="log-dot" :class="phaseClass(evt.phase)" />
                  <span class="log-msg">{{ evt.message }}</span>
                </div>
              </div>
              <div v-else class="empty-state">暂无事件</div>
            </div>
          </section>
        </div>
      </div>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({ name: 'NetworkStatusPage' })

import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  IonBackButton,
  IonButtons,
  IonContent,
  IonHeader,
  IonIcon,
  IonPage,
  IonTitle,
  IonToolbar,
} from '@ionic/vue'
import { refreshOutline, speedometerOutline } from 'ionicons/icons'
import { JmcomicService } from '@/services/JmcomicService'
import type { ListenerHandle } from '@/runtime/BackendEvents'
import { normalizeRuntimeError } from '@/runtime/errors'
import { initNetworkProbeStore, useNetworkProbeStore } from '@/composables/networkProbeStore'

const store = useNetworkProbeStore()
const refreshing = ref(false)
const measuring = ref(false)
const latencyMap = ref<Record<string, { latencyMs: number; timedOut: boolean }>>({})
const operationError = ref('')
const visibleError = computed(() => operationError.value || store.errorMessage.value)
const clientUnavailableText = computed(() =>
  store.clientState.value.reason === 'no_network'
    ? '当前网络不可用，离线功能仍可使用'
    : '在线客户端暂不可用，离线功能仍可使用',
)
let probeHandle: ListenerHandle | null = null
let refreshTimer: ReturnType<typeof setTimeout> | null = null
let disposed = false
const PROBE_EVENT_TIMEOUT_MS = 30_000

onMounted(() => {
  disposed = false
  initNetworkProbeStore()
  JmcomicService.addNetworkProbeListener((data) => {
    if (data.phase === 'probing' || data.phase === 'result') operationError.value = ''
    if (data.phase === 'error') operationError.value = data.message
    if (data.phase === 'result' || data.phase === 'error') {
      refreshing.value = false
      clearRefreshTimer()
    }
  })
    .then((h) => {
      if (disposed) {
        void h.remove().catch(() => {})
        return
      }
      probeHandle = h
    })
    .catch((error) => {
      operationError.value = normalizeRuntimeError(error, '网络事件监听失败').message
    })
})

onUnmounted(() => {
  disposed = true
  void probeHandle?.remove().catch(() => {})
  probeHandle = null
  clearRefreshTimer()
})

function handleRefresh() {
  if (refreshing.value) return
  refreshing.value = true
  operationError.value = ''
  latencyMap.value = {}
  void JmcomicService.reprobeDomains().catch((error) => {
    operationError.value = normalizeRuntimeError(error, '重新探活失败').message
    refreshing.value = false
    clearRefreshTimer()
  })
  refreshTimer = setTimeout(() => {
    refreshing.value = false
    operationError.value = '等待探活结果超时，请稍后重试'
    refreshTimer = null
  }, PROBE_EVENT_TIMEOUT_MS)
}

function handleMeasureLatency() {
  if (measuring.value) return
  measuring.value = true
  operationError.value = ''
  latencyMap.value = {}
  JmcomicService.measureLatency()
    .then((ret) => {
      const map: Record<string, { latencyMs: number; timedOut: boolean }> = {}
      for (const r of ret.results) {
        map[r.domain] = { latencyMs: r.latencyMs, timedOut: r.timedOut }
      }
      latencyMap.value = map
    })
    .catch((error) => {
      operationError.value = normalizeRuntimeError(error, '测速失败').message
    })
    .finally(() => {
      measuring.value = false
    })
}

function clearRefreshTimer() {
  if (!refreshTimer) return
  clearTimeout(refreshTimer)
  refreshTimer = null
}

function latencyText(domain: string, reachable: boolean): string {
  if (!reachable) return '9999 ms'
  const r = latencyMap.value[domain]
  if (!r) return '/ ms'
  if (r.timedOut) return '超时'
  return r.latencyMs + ' ms'
}

function latencyClass(domain: string, reachable: boolean): string {
  if (!reachable) return 'lat-red'
  const r = latencyMap.value[domain]
  if (!r) return 'lat-yellow'
  if (r.timedOut) return 'lat-red'
  return 'lat-green'
}

function phaseClass(phase: string): string {
  switch (phase) {
    case 'network_changed':
      return 'phase-warn'
    case 'network_lost':
      return 'phase-err'
    case 'probing':
      return 'phase-active'
    case 'result':
      return 'phase-ok'
    case 'error':
      return 'phase-err'
    default:
      return ''
  }
}

function formatTime(ts: number): string {
  return new Date(ts).toLocaleTimeString('zh-CN', { hour12: false })
}
</script>

<style scoped>
.toolbar {
  width: 100%;
  max-width: 1000px;
  margin-inline: auto;
  box-sizing: border-box;
}

:deep(ion-toolbar) {
  --min-height: auto;
}

.toolbar-title {
  font-size: 16px;
  font-weight: 600;
  color: #4c2a18;
}

.page-content {
  box-sizing: border-box;
  width: 100%;
  max-width: 1000px;
  margin: 0 auto;
  padding: 8px 16px 32px;
}

.status-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-items: start;
}

.status-section {
  min-width: 0;
}

.section-title {
  margin: 8px 0 8px 6px;
}

.section-label {
  font-size: 12px;
  font-weight: 600;
  color: #b89a84;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 8px 0 8px 6px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.refresh-btn,
.speed-btn {
  font-size: 16px;
  color: #b89a84;
  cursor: pointer;
  transition: color 0.2s;
}

.refresh-btn:active,
.speed-btn:active {
  color: #4c2a18;
}

.refresh-btn.spinning,
.speed-btn.spinning {
  animation: spin 0.8s linear infinite;
  color: #89b4fa;
  pointer-events: none;
}

@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.card {
  background: #fffbf8;
  border-radius: 14px;
  box-shadow: 0 2px 12px rgba(115, 67, 38, 0.06);
  min-width: 0;
  overflow: hidden;
}

.operation-error {
  padding: 10px 16px;
  border-bottom: 1px solid #f5d7d7;
  color: #c94444;
  font-size: 12px;
  line-height: 1.5;
}

/* 域名列表 */
.domain-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 10px 16px;
}

.domain-row + .domain-row {
  border-top: 1px solid #f5ebe4;
}

.domain-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.domain-dot.alive {
  background: #6dbf87;
}

.domain-dot.dead {
  background: #e05555;
}

.domain-name {
  font-size: 13px;
  color: #4c2a18;
  word-break: break-all;
  min-width: 0;
  flex: 1;
}

.latency {
  font-size: 12px;
  font-weight: 500;
  flex-shrink: 0;
  margin-left: 8px;
}

.latency.lat-green {
  color: #6dbf87;
}

.latency.lat-yellow {
  color: #e0b040;
}

.latency.lat-red {
  color: #e05555;
}

/* 事件日志 */
.log-row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  padding: 6px 16px;
}

.log-row + .log-row {
  border-top: 1px solid #f5ebe4;
}

.log-time {
  font-size: 11px;
  color: #b89a84;
  flex-shrink: 0;
  width: 52px;
}

.log-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}

.log-dot.phase-warn {
  background: #f9e2af;
}

.log-dot.phase-active {
  background: #89b4fa;
}

.log-dot.phase-ok {
  background: #6dbf87;
}

.log-dot.phase-err {
  background: #e05555;
}

.log-msg {
  font-size: 12px;
  color: #4c2a18;
  min-width: 0;
  flex: 1;
  overflow-wrap: anywhere;
}

.empty-state {
  padding: 28px 16px;
  text-align: center;
  font-size: 13px;
  color: #b89a84;
}

@media (min-width: 992px) {
  .status-grid {
    grid-template-columns: minmax(0, 2fr) minmax(0, 3fr);
    column-gap: 16px;
  }
}
</style>
