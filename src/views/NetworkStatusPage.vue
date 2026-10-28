<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar class="toolbar desktop-page-content">
        <IonButtons slot="start">
          <IonBackButton default-href="/setting" />
        </IonButtons>
        <IonTitle class="toolbar-title">网络状态</IonTitle>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <div class="page-content desktop-page-content">
        <div class="status-grid">
          <section class="status-section">
            <div class="section-header"><span class="section-label">当前 API 线路</span></div>
            <div class="card route-card">
              <div class="route-summary">
                <div>
                  <span class="summary-caption">API 线路模式</span
                  ><strong>{{ route.mode.value === 'auto' ? '自动优选' : '手动固定' }}</strong>
                </div>
                <span class="summary-domain">{{ route.currentDomain.value || '读取中' }}</span>
              </div>
              <div v-if="routeError" class="operation-error" role="alert">{{ routeError }}</div>
              <button
                class="route-mode-row"
                type="button"
                :aria-pressed="route.mode.value === 'auto'"
                :disabled="route.loading.value"
                @click="selectRoute('auto')"
              >
                <span class="mode-mark" :class="{ selected: route.mode.value === 'auto' }">A</span>
                <span><strong>自动优选</strong><small>由客户端选择当前可用线路</small></span>
                <span class="route-choice">{{
                  route.mode.value === 'auto' ? '当前' : '切换'
                }}</span>
              </button>
            </div>

            <div class="section-header domain-header">
              <span class="section-label">API 线路探测</span>
              <div class="header-actions">
                <button
                  class="icon-action speed-btn"
                  :class="{ spinning: measuring }"
                  type="button"
                  :disabled="measuring"
                  aria-label="测量线路延迟"
                  title="测量线路延迟"
                  @click="handleMeasureLatency"
                >
                  <IonIcon :icon="speedometerOutline" aria-hidden="true" />
                </button>
                <button
                  class="icon-action refresh-btn"
                  :class="{ spinning: refreshing }"
                  type="button"
                  :disabled="refreshing"
                  aria-label="刷新线路状态"
                  title="刷新线路状态"
                  @click="handleRefresh"
                >
                  <IonIcon :icon="refreshOutline" aria-hidden="true" />
                </button>
              </div>
            </div>
            <div class="card domain-card">
              <div v-if="visibleError" class="operation-error">{{ visibleError }}</div>
              <div v-if="store.domains.value.length" class="domain-list">
                <button
                  v-for="d in store.domains.value"
                  :key="d.domain"
                  class="domain-row route-row"
                  :aria-pressed="
                    route.mode.value === 'manual' && route.selectedDomain.value === d.domain
                  "
                  :disabled="route.loading.value"
                  type="button"
                  @click="selectRoute('manual', d.domain)"
                >
                  <span
                    class="domain-dot"
                    :class="store.allDeadFallback.value ? 'dead' : d.reachable ? 'alive' : 'dead'"
                  />
                  <span class="domain-name">{{ d.domain }}</span>
                  <span class="latency" :class="latencyClass(d.domain, d.reachable)">
                    {{ latencyText(d.domain, d.reachable) }}
                  </span>
                  <span class="route-choice">
                    {{
                      route.mode.value === 'manual' && route.selectedDomain.value === d.domain
                        ? '已选择'
                        : '选择'
                    }}
                  </span>
                </button>
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

            <div class="section-header domain-header">
              <span class="section-label">当前图片线路</span>
            </div>
            <div class="card route-card image-route-card">
              <div class="route-summary">
                <div>
                  <span class="summary-caption">图片线路模式</span>
                  <strong>{{ imageRouteMode === 'auto' ? '自动优选' : '手动固定' }}</strong>
                </div>
                <span class="summary-domain">{{ imageRouteHost || '尚未优选' }}</span>
              </div>
              <button
                class="route-mode-row"
                type="button"
                :aria-pressed="imageRouteMode === 'auto'"
                :disabled="!imageRouteSettingsReady || imageMeasuring"
                @click="selectImageRoute('auto')"
              >
                <span class="mode-mark" :class="{ selected: imageRouteMode === 'auto' }">A</span>
                <span><strong>自动优选</strong><small>启动后沿用有效 Host，失效时后台重选</small></span>
                <span class="route-choice">{{ imageRouteMode === 'auto' ? '当前' : '切换' }}</span>
              </button>
            </div>

            <div class="section-header domain-header">
              <span class="section-label">图片线路探测</span>
              <div class="header-actions">
                <button
                  class="icon-action speed-btn"
                  :class="{ spinning: imageMeasuring }"
                  type="button"
                  :disabled="!imageRouteSettingsReady || imageMeasuring"
                  aria-label="测量图片线路"
                  title="测量图片线路"
                  @click="handleMeasureImageHosts()"
                >
                  <IonIcon :icon="speedometerOutline" aria-hidden="true" />
                </button>
              </div>
            </div>
            <div class="card domain-card image-route-card">
              <div v-if="imageApplyError || imageRouteSettingsError || imageRouteError" class="operation-error">
                {{ imageApplyError || imageRouteSettingsError || imageRouteError }}
              </div>
              <div v-if="imageHostCandidates.length" class="domain-list">
                <button
                  v-for="host in imageHostCandidates"
                  :key="host"
                  class="domain-row route-row"
                  :aria-pressed="imageRouteMode === 'manual' && imageRouteHost === host"
                  :disabled="!imageRouteSettingsReady || imageMeasuring"
                  type="button"
                  @click="selectImageRoute('manual', host)"
                >
                  <span class="domain-dot" :class="imageHostStatusClass(host)" />
                  <span class="domain-name">{{ host }}</span>
                  <IonSpinner
                    v-if="imageHostIsMeasuring(host)"
                    class="host-spinner"
                    name="crescent"
                    role="status"
                    aria-label="图片线路测速中"
                  />
                  <span v-else class="latency" :class="imageHostLatencyClass(host)">
                    {{ imageHostLatencyText(host) }}
                  </span>
                  <span class="route-choice">
                    {{ imageRouteMode === 'manual' && imageRouteHost === host ? '已选择' : '选择' }}
                  </span>
                </button>
              </div>
              <div v-else-if="imageMeasuring" class="empty-state">正在测量图片线路...</div>
              <div v-else-if="!imageRouteError" class="empty-state">暂无图片线路</div>
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

import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  IonBackButton,
  IonButtons,
  IonContent,
  IonHeader,
  IonIcon,
  IonPage,
  IonSpinner,
  IonTitle,
  IonToolbar,
} from '@ionic/vue'
import { refreshOutline, speedometerOutline } from 'ionicons/icons'
import { JmcomicService } from '@/services/JmcomicService'
import type { ListenerHandle } from '@/runtime/BackendEvents'
import { normalizeRuntimeError } from '@/runtime/errors'
import { initNetworkProbeStore, useNetworkProbeStore } from '@/composables/networkProbeStore'
import { useApiRoute } from '@/composables/useApiRoute'
import { SettingsStore } from '@/services/SettingsService'

const store = useNetworkProbeStore()
const route = useApiRoute()
const refreshing = ref(false)
const measuring = ref(false)
const latencyMap = ref<Record<string, { latencyMs: number; timedOut: boolean }>>({})
const operationError = ref('')
const visibleError = computed(() => operationError.value || store.errorMessage.value)
const routeError = ref('')
const imageRouteMode = ref<'auto' | 'manual'>(SettingsStore.getImageRouteMode())
const imageRouteHost = ref(SettingsStore.getImageRouteHost())
const imageHostCandidates = ref<string[]>([])
const imageHostResults = ref<Array<{
  host: string
  available: boolean
  elapsedMs: number
  bytes: number
  bytesPerSecond: number
  error?: string
}>>([])
const imageMeasuring = ref(false)
const imageCurrentHost = ref<string | null>(null)
const imageRunId = ref(0)
const imageRevision = ref(0)
const imageMeasureRequestPending = ref(false)
const imageRouteError = ref('')
const imageApplyError = ref('')
const imageRouteSettingsReady = ref(false)
const imageRouteSettingsError = ref('')
const clientUnavailableText = computed(() =>
  store.clientState.value.reason === 'no_network'
    ? '当前网络不可用，离线功能仍可使用'
    : '在线客户端暂不可用，离线功能仍可使用',
)
let probeHandle: ListenerHandle | null = null
let refreshTimer: ReturnType<typeof setTimeout> | null = null
let disposed = false
let routeRefreshSequence = 0
let imageStatusQuerySequence = 0
const PROBE_EVENT_TIMEOUT_MS = 30_000

function refreshRoute() {
  const sequence = ++routeRefreshSequence
  return route
    .refresh()
    .then(() => {
      if (sequence === routeRefreshSequence) routeError.value = ''
    })
    .catch((error) => {
      if (sequence === routeRefreshSequence) {
        routeError.value = normalizeRuntimeError(error, '获取线路状态失败').message
      }
    })
}

watch(
  () => store.clientState.value.state,
  (state, previousState) => {
    if (state !== 'ready' || previousState === 'ready') return
    void refreshRoute()
  },
)

onMounted(() => {
  disposed = false
  initNetworkProbeStore()
  void refreshRoute()
  void JmcomicService.getAllSettings()
    .then((all) => {
      imageRouteMode.value = all.imageRouteMode === 'manual' ? 'manual' : 'auto'
      imageRouteHost.value = all.imageRouteHost ?? ''
      SettingsStore.setImageRoute(imageRouteMode.value, imageRouteHost.value)
      imageRouteSettingsError.value = ''
      imageRouteSettingsReady.value = true
    })
    .catch((error) => {
      imageRouteSettingsReady.value = false
      imageRouteSettingsError.value = normalizeRuntimeError(error, '获取图片线路设置失败').message
    })
  void JmcomicService.getImageRouteCandidates()
    .then((result) => {
      imageHostCandidates.value = result.hosts
    })
    .catch((error) => {
      imageRouteError.value = normalizeRuntimeError(error, '获取图片线路失败').message
    })
  JmcomicService.addNetworkProbeListener((data) => {
    applyImageRouteEvent(data)
    if (data.phase === 'probing' || data.phase === 'result') operationError.value = ''
    if (data.phase === 'error') operationError.value = data.message
    if (data.phase === 'result' || data.phase === 'error' || data.phase === 'network_restored') {
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
      void syncImageRouteStatus()
    })
    .catch((error) => {
      operationError.value = normalizeRuntimeError(error, '网络事件监听失败').message
    })
})

function applyImageRouteEvent(data: {
  phase: string
  message: string
  runId?: number
  revision?: number
  running?: boolean
  currentHost?: string
  selectedHost?: string | null
  hosts?: typeof imageHostResults.value
  error?: string
}) {
  if (data.phase === 'image_route_status' && imageMeasureRequestPending.value) return
  if (data.runId === undefined || data.revision === undefined) return
  if (data.runId < imageRunId.value) return
  if (data.runId === imageRunId.value && data.revision < imageRevision.value) return
  imageRunId.value = data.runId
  imageRevision.value = data.revision
  imageMeasuring.value = data.running === true
  imageCurrentHost.value = data.running ? data.currentHost ?? null : null
  if (data.hosts) imageHostResults.value = data.hosts
  if (data.error) imageRouteError.value = data.error
  else if (!data.running) imageRouteError.value = ''
  if (data.phase === 'image_route_result' && SettingsStore.getImageRouteMode() === 'auto') {
    imageRouteMode.value = 'auto'
    imageRouteHost.value = data.selectedHost ?? ''
    SettingsStore.setImageRoute(imageRouteMode.value, imageRouteHost.value)
  }
}

async function syncImageRouteStatus(clearLocalMeasurementOnError = false) {
  const sequence = ++imageStatusQuerySequence
  try {
    const status = await JmcomicService.getImageRouteStatus()
    if (sequence !== imageStatusQuerySequence) return
    applyImageRouteEvent({ phase: 'image_route_status', message: '', ...status })
  } catch (error) {
    if (sequence !== imageStatusQuerySequence) return
    if (clearLocalMeasurementOnError) {
      imageMeasuring.value = false
      imageCurrentHost.value = null
    }
    imageRouteError.value = normalizeRuntimeError(error, '获取图片线路状态失败').message
  }
}

function imageHostResult(host: string) {
  return imageHostResults.value.find((item) => item.host === host)
}

function imageHostStatusClass(host: string): string {
  const result = imageHostResult(host)
  if (!result) return ''
  return result.available ? 'alive' : 'dead'
}

function imageHostIsMeasuring(host: string): boolean {
  return imageMeasuring.value && imageCurrentHost.value === host
}

/** 将图片线路测速返回的字节吞吐量转换为便于阅读的速率文本。 */
function imageHostLatencyText(host: string): string {
  const result = imageHostResult(host)
  if (!result) return '/'
  if (!result.available) return '不可用'
  const speed = result.bytesPerSecond
  if (speed >= 1024 * 1024) return `${(speed / (1024 * 1024)).toFixed(1)} MB/s`
  if (speed >= 1024) return `${(speed / 1024).toFixed(1)} KB/s`
  return `${speed} B/s`
}

function imageHostLatencyClass(host: string): string {
  const result = imageHostResult(host)
  if (!result) return 'lat-yellow'
  return result.available ? 'lat-green' : 'lat-red'
}

async function selectImageRoute(mode: 'auto' | 'manual', host = '') {
  imageRouteError.value = ''
  imageApplyError.value = ''
  if (mode === 'auto') {
    if (imageRouteHost.value && imageHostCandidates.value.includes(imageRouteHost.value)) {
      try {
        await JmcomicService.applyImageRoute({ mode: 'auto', host: imageRouteHost.value })
        imageRouteMode.value = 'auto'
        SettingsStore.setImageRoute('auto', imageRouteHost.value)
      } catch (error) {
        imageApplyError.value = normalizeRuntimeError(error, '应用图片线路失败').message
      }
    } else {
      await handleMeasureImageHosts(true)
    }
    return
  }
  try {
    await JmcomicService.applyImageRoute({ mode, host: mode === 'manual' ? host : imageRouteHost.value })
    imageRouteMode.value = mode
    imageRouteHost.value = mode === 'manual' ? host : imageRouteHost.value
    SettingsStore.setImageRoute(imageRouteMode.value, imageRouteHost.value)
  } catch (error) {
    imageApplyError.value = normalizeRuntimeError(error, '应用图片线路失败').message
  }
}

async function handleMeasureImageHosts(applySelectedHost = imageRouteMode.value === 'auto') {
  if (!imageRouteSettingsReady.value || imageMeasuring.value) return
  imageStatusQuerySequence++
  imageMeasureRequestPending.value = true
  imageMeasuring.value = true
  imageCurrentHost.value = null
  imageHostResults.value = []
  imageRouteError.value = ''
  imageApplyError.value = ''
  try {
    const result = await JmcomicService.measureImageHosts()
    applyImageRouteEvent({
      phase: result.selectedHost ? 'image_route_result' : 'image_route_error',
      message: result.selectedHost ? '图片线路测速完成' : '没有可用的图片线路',
      runId: result.runId,
      revision: result.revision,
      running: false,
      selectedHost: result.selectedHost,
      hosts: result.hosts,
    })
    if (!result.selectedHost) {
      imageRouteError.value = '没有可用的图片线路'
      return
    }
    if (!applySelectedHost || !imageRouteSettingsReady.value) return
    try {
      await JmcomicService.applyImageRoute({ mode: 'auto', host: result.selectedHost })
    } catch (error) {
      imageApplyError.value = normalizeRuntimeError(error, '应用图片线路失败').message
      return
    }
    imageRouteMode.value = 'auto'
    imageRouteHost.value = result.selectedHost
    SettingsStore.setImageRoute('auto', result.selectedHost)
  } catch (error) {
    imageMeasuring.value = false
    imageCurrentHost.value = null
    imageRouteError.value = normalizeRuntimeError(error, '图片线路测速失败').message
  } finally {
    imageMeasureRequestPending.value = false
    void syncImageRouteStatus(true)
  }
}

async function selectRoute(mode: 'auto' | 'manual', domain = '') {
  routeError.value = ''
  try {
    await route.select(mode, domain)
  } catch (error) {
    routeError.value = normalizeRuntimeError(error, '切换线路失败').message
  }
}

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
  if (!reachable) return '不可达'
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
    case 'network_restored':
      return 'phase-ok'
    case 'probing':
      return 'phase-active'
    case 'result':
      return 'phase-ok'
    case 'image_route_result':
      return 'phase-ok'
    case 'image_route_probing':
      return 'phase-active'
    case 'image_route_progress':
      return 'phase-active'
    case 'image_route_error':
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
  --desktop-page-max-width: 1000px;
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
  --desktop-page-max-width: 1000px;
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
  gap: 22px;
}

.status-section {
  min-width: 0;
}

.section-title {
  margin: 20px 0 8px 6px;
}

.section-label {
  margin: 20px 0 8px 6px;
  color: #b89a84;
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 20px 0 8px 6px;
}

.section-header .section-label {
  margin: 0;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.icon-action {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #b89a84;
  cursor: pointer;
  font-size: 18px;
  transition:
    background-color 0.2s,
    color 0.2s;
}

.icon-action:hover,
.icon-action:focus-visible {
  background: #fff0e7;
  color: #e8843c;
}

.icon-action:focus-visible {
  outline: 2px solid #e8843c;
  outline-offset: 1px;
}

.icon-action:disabled {
  cursor: default;
  opacity: 0.58;
}

.icon-action.spinning {
  animation: spin 0.8s linear infinite;
  pointer-events: none;
}

.host-spinner {
  flex-shrink: 0;
  width: 16px;
  height: 16px;
  color: #e8843c;
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
  background: #fff;
  border-radius: 14px;
  box-shadow: 0 2px 12px rgba(115, 67, 38, 0.06);
  min-width: 0;
  overflow: hidden;
}

.operation-error {
  padding: 15px 18px;
  border-bottom: 1px solid #f5d7d7;
  color: #c94444;
  font-size: 12px;
  line-height: 1.5;
}

/* 域名列表 */
.domain-row {
  box-sizing: border-box;
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  min-height: 48px;
  padding: 10px 16px;
}

.route-selection {
  border-bottom: 1px solid var(--ion-color-step-100, #e8e8e8);
}

.route-selection-heading {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px 4px;
  color: var(--ion-color-medium);
  font-size: 12px;
}

.route-row {
  width: 100%;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.route-row[aria-pressed='true'] {
  background: var(--ion-color-step-50, #f5f5f5);
}

.route-row:disabled {
  opacity: 0.6;
}

.route-choice {
  flex-shrink: 0;
  color: var(--ion-color-primary);
  font-size: 12px;
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
  background: #5cb85c;
}

.domain-dot.dead {
  background: #d4533e;
}

.domain-name {
  font-size: 15px;
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
  color: #4eaa73;
}

.latency.lat-yellow {
  color: #bd652a;
}

.latency.lat-red {
  color: #d15d55;
}

/* 事件日志 */
.log-row {
  box-sizing: border-box;
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  min-height: 48px;
  padding: 10px 18px;
}

.log-row + .log-row {
  border-top: 1px solid #f5ebe4;
}

.log-time {
  color: #b89a84;
  font:
    11px ui-monospace,
    SFMono-Regular,
    Consolas,
    monospace;
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
  background: #d4b870;
}

.log-dot.phase-active {
  background: #5b9bd5;
}

.log-dot.phase-ok {
  background: #5cb85c;
}

.log-dot.phase-err {
  background: #d4533e;
}

.log-msg {
  font-size: 14px;
  color: #4c2a18;
  min-width: 0;
  flex: 1;
  overflow-wrap: anywhere;
}

.empty-state {
  padding: 36px 18px;
  text-align: center;
  font-size: 13px;
  color: #b89a84;
}

.route-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px;
  border-bottom: 1px solid #f1e8e2;
}
.route-summary > div {
  display: grid;
  gap: 5px;
}
.route-summary strong {
  color: #4c2a18;
  font-size: 19px;
}
.summary-caption {
  color: #a28571;
  font-size: 12px;
}
.summary-domain {
  color: #8e5634;
  font-size: 14px;
  font-weight: 600;
  overflow-wrap: anywhere;
  text-align: right;
}
.route-mode-row {
  box-sizing: border-box;
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  min-height: 56px;
  padding: 12px 16px;
  border: 0;
  background: #fff;
  color: #4c2a18;
  text-align: left;
  cursor: pointer;
}
.route-mode-row > span:nth-child(2) {
  display: grid;
  flex: 1;
  gap: 4px;
}
.route-mode-row strong {
  font-size: 15px;
}
.route-mode-row small {
  color: #9b8578;
  font-size: 12px;
}
.mode-mark {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border-radius: 50%;
  background: #f8eee8;
  color: #9b735a;
  font-size: 13px;
  font-weight: 700;
}
.mode-mark.selected {
  background: #fce3d5;
  color: #de713d;
}
.domain-header {
  margin-top: 20px;
}
.domain-card .route-row[aria-pressed='true'] {
  background: #fff0e7;
}
.domain-card .route-choice {
  min-width: 48px;
  text-align: right;
}
.image-route-card {
  overflow: hidden;
}

@media (prefers-reduced-motion: reduce) {
  .icon-action.spinning {
    animation: none;
  }
}
</style>
