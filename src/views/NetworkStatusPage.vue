<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <IonButtons slot="start">
          <IonBackButton default-href="/setting"/>
        </IonButtons>
        <IonTitle class="toolbar-title">网络状态</IonTitle>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <div class="page-content">
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
          <div v-else class="empty-state">等待首次探活...</div>
        </div>

        <!-- 事件日志 -->
        <div class="section-label" style="margin: 8px 0 8px 6px">事件日志</div>
        <div class="card">
          <div v-if="store.events.value.length" class="log-list">
            <div v-for="(evt, i) in store.events.value" :key="i" class="log-row">
              <span class="log-time">{{ formatTime(evt.timestamp) }}</span>
              <span class="log-dot" :class="phaseClass(evt.phase)"/>
              <span class="log-msg">{{ evt.message }}</span>
            </div>
          </div>
          <div v-else class="empty-state">暂无事件</div>
        </div>
      </div>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({name: 'NetworkStatusPage'})

import {onMounted, onUnmounted, ref} from 'vue'
import {IonBackButton, IonButtons, IonContent, IonHeader, IonIcon, IonPage, IonTitle, IonToolbar,} from '@ionic/vue'
import {refreshOutline, speedometerOutline} from 'ionicons/icons'
import {JmcomicService} from '@/services/JmcomicService'
import type {PlatformListenerHandle} from '@/services/platform/EventPort'
import {useNetworkProbeStore} from '@/composables/networkProbeStore'

const store = useNetworkProbeStore()
const refreshing = ref(false)
const measuring = ref(false)
const latencyMap = ref<Record<string, { latencyMs: number; timedOut: boolean }>>({})
let probeHandle: PlatformListenerHandle | null = null
let refreshTimer: ReturnType<typeof setTimeout> | null = null

onMounted(() => {
  JmcomicService.addNetworkProbeListener((data) => {
    if (data.phase === 'result' || data.phase === 'error') {
      refreshing.value = false
    }
  }).then((h) => {
    probeHandle = h
  })
})

onUnmounted(() => {
  probeHandle?.remove()
  probeHandle = null
  if (refreshTimer) {
    clearTimeout(refreshTimer)
    refreshTimer = null
  }
})

function handleRefresh() {
  if (refreshing.value) return
  refreshing.value = true
  JmcomicService.reprobeDomains()
  // result/error 事件到达时 store 自动更新，超时后恢复按钮状态
  refreshTimer = setTimeout(() => {
    refreshing.value = false
  }, 5000)
}

function handleMeasureLatency() {
  if (measuring.value) return
  measuring.value = true
  latencyMap.value = {}
  JmcomicService.measureLatency()
    .then((ret) => {
      const map: Record<string, { latencyMs: number; timedOut: boolean }> = {}
      for (const r of ret.results) {
        map[r.domain] = {latencyMs: r.latencyMs, timedOut: r.timedOut}
      }
      latencyMap.value = map
    })
    .catch(() => {
      // 测速失败静默处理
    })
    .finally(() => {
      measuring.value = false
    })
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
  return new Date(ts).toLocaleTimeString('zh-CN', {hour12: false})
}
</script>

<style scoped>
:deep(ion-toolbar) {
  --min-height: auto;
}

.toolbar-title {
  font-size: 16px;
  font-weight: 600;
  color: #4c2a18;
}

.page-content {
  padding: 8px 16px 32px;
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
  overflow: hidden;
}

/* 域名列表 */
.domain-row {
  display: flex;
  align-items: center;
  gap: 8px;
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
}

.empty-state {
  padding: 28px 16px;
  text-align: center;
  font-size: 13px;
  color: #b89a84;
}
</style>
