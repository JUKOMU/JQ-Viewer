<template>
  <button
    v-if="visible && !leftMenuOpen"
    class="route-switch-button"
    :class="{ 'is-reader': isReader(), 'has-quick-actions': hasQuickActions }"
    type="button"
    aria-label="切换 API 线路"
    title="切换 API 线路"
    @click="openPicker"
  >
    <IonIcon :icon="flashOutline" aria-hidden="true" />
  </button>

  <IonModal class="route-switch-modal" :is-open="modalOpen" @did-dismiss="modalOpen = false">
    <header class="route-modal-header">
      <h2>选择 API 和图片线路</h2>
      <button
        class="route-modal-close"
        type="button"
        aria-label="关闭线路选择"
        @click="modalOpen = false"
      >
        <IonIcon :icon="closeOutline" aria-hidden="true" />
      </button>
    </header>
    <IonContent class="route-modal-content">
      <div class="route-modal-intro">
        <span class="eyebrow">API 线路探测</span>
        <strong>{{ route.currentDomain.value || '正在读取当前线路' }}</strong>
        <span class="intro-hint">打开时自动探活并测速，也可单独测量线路延迟</span>
      </div>
      <p v-if="errorMessage" class="route-error" role="alert">{{ errorMessage }}</p>
      <div class="route-group">
        <div class="group-title">API 线路模式</div>
        <IonItem :button="true" :detail="false" @click="selectAuto">
          <IonRadio slot="start" value="auto" :checked="route.mode.value === 'auto'" />
          <IonLabel>
            <h2>自动优选</h2>
            <p>
              {{
                route.mode.value === 'auto' && route.currentDomain.value
                  ? `当前：${route.currentDomain.value}`
                  : '由客户端选择可用线路'
              }}
            </p>
          </IonLabel>
        </IonItem>
      </div>
      <div class="route-group">
        <div class="group-heading">
          <div class="group-title">API 线路探测</div>
          <button
            class="measure-button"
            type="button"
            :disabled="probing || measuringDomains || !!measuringDomain"
            aria-label="测量线路延迟"
            title="测量线路延迟"
            @click="handleMeasureDomains"
          >
            <IonIcon :icon="speedometerOutline" aria-hidden="true" />
          </button>
        </div>
        <IonItem
          v-for="item in route.domains.value.length
            ? route.domains.value
            : probeStore.domains.value"
          :key="item.domain"
          :button="true"
          :detail="false"
          @click="selectManual(item.domain)"
        >
          <IonRadio
            slot="start"
            :value="item.domain"
            :checked="route.mode.value === 'manual' && route.selectedDomain.value === item.domain"
          />
          <IonLabel>
            <h2>{{ item.domain }}</h2>
            <p>
              <span :class="item.reachable ? 'status-ok' : 'status-bad'">
                {{ measuringDomain === item.domain ? '测速中' : latencyText(item.domain, item.reachable) }}
                <IonSpinner
                  v-if="(probing || measuringDomains) && item.reachable && !latencyMap[item.domain]"
                  class="status-spinner"
                  name="crescent"
                  aria-label="测速中"
                />
              </span>
              <span v-if="item.domain === route.currentDomain.value"> · 当前线路</span>
            </p>
          </IonLabel>
          <IonButton
            slot="end"
            fill="clear"
            class="row-measure-button"
            :disabled="probing || measuringDomains || !!measuringDomain"
            :aria-label="`测速 ${item.domain}`"
            :title="`测速 ${item.domain}`"
            @click.stop="handleMeasureDomain(item.domain)"
          >
            <IonIcon :icon="speedometerOutline" aria-hidden="true" />
          </IonButton>
        </IonItem>
        <IonItem v-if="!route.domains.value.length && !probeStore.domains.value.length">
          <IonLabel>线路状态暂不可用</IonLabel>
        </IonItem>
      </div>
      <p v-if="imageRouteError" class="route-error" role="alert">{{ imageRouteError }}</p>
      <div class="route-modal-intro">
        <span class="eyebrow">图片线路探测</span>
        <strong>{{ imageRouteSettingsReady ? imageRouteHost || '尚未优选' : '正在读取当前线路' }}</strong>
      </div>
      <div class="route-group">
        <div class="group-title">图片线路模式</div>
        <IonItem
          :button="true"
          :detail="false"
          :disabled="!imageRouteSettingsReady || imageRouteSelecting"
          @click="selectImageRoute('auto')"
        >
          <IonRadio slot="start" value="auto" :checked="imageRouteMode === 'auto'" />
          <IonLabel>
            <h2>自动优选</h2>
            <p>{{ imageRouteMode === 'auto' && imageRouteHost ? `当前：${imageRouteHost}` : '由客户端选择可用图片线路' }}</p>
          </IonLabel>
        </IonItem>
      </div>
      <div class="route-group">
        <div class="group-heading">
          <div class="group-title">图片线路探测</div>
          <button
            class="measure-button"
            type="button"
            :disabled="imageMeasuring || !!measuringImageHost || !imageHosts.length"
            aria-label="测量图片线路"
            title="测量图片线路"
            @click="handleMeasureImageHosts"
          >
            <IonIcon :icon="speedometerOutline" aria-hidden="true" />
          </button>
        </div>
        <IonItem
          v-for="host in imageHosts"
          :key="host"
          :button="true"
          :detail="false"
          :class="{ 'image-host-selected': imageRouteMode === 'manual' && imageRouteHost === host }"
          :disabled="!imageRouteSettingsReady || imageRouteSelecting"
          @click="selectImageRoute('manual', host)"
        >
          <IonRadio
            slot="start"
            :value="host"
            :checked="imageRouteMode === 'manual' && imageRouteHost === host"
            :disabled="!imageRouteSettingsReady || imageRouteSelecting"
          />
          <IonLabel>
            <h2>{{ host }}</h2>
            <p>
              <span :class="imageHostIsMeasuring(host) ? 'status-ok' : imageHostStatusClass(host)">
                {{ imageHostIsMeasuring(host) ? '测速中' : imageHostLatencyText(host) }}
              </span>
              <IonSpinner
                v-if="imageHostIsMeasuring(host)"
                class="status-spinner"
                name="crescent"
                aria-label="测速中"
              />
              <span v-if="imageRouteMode === 'manual' && imageRouteHost === host"> · 当前线路</span>
            </p>
          </IonLabel>
          <IonButton
            slot="end"
            fill="clear"
            class="row-measure-button"
            :class="{ spinning: measuringImageHost === host }"
            :disabled="imageMeasuring || !!measuringImageHost"
            :aria-label="`测速 ${host}`"
            :title="`测速 ${host}`"
            @click.stop="handleMeasureImageHost(host)"
          >
            <IonIcon :icon="speedometerOutline" aria-hidden="true" />
          </IonButton>
        </IonItem>
        <IonItem v-if="!imageHosts.length">
          <IonLabel>{{ imageHostsLoading ? '正在读取图片线路...' : '暂无图片线路' }}</IonLabel>
        </IonItem>
      </div>
    </IonContent>
  </IonModal>
</template>

<script setup lang="ts">
defineOptions({ name: 'RouteSwitchButton' })

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { IonButton, IonContent, IonIcon, IonItem, IonLabel, IonModal, IonRadio, IonSpinner } from '@ionic/vue'
import { closeOutline, flashOutline, speedometerOutline } from 'ionicons/icons'
import { normalizeRuntimeError } from '@/runtime/errors'
import { useApiRoute } from '@/composables/useApiRoute'
import { useTimedReveal } from '@/composables/useTimedReveal'
import { initNetworkProbeStore, useNetworkProbeStore } from '@/composables/networkProbeStore'
import { JmcomicService } from '@/services/JmcomicService'
import type { NetworkProbeEvent } from '@/services/JmcomicTypes'
import type { ListenerHandle } from '@/runtime/BackendEvents'
import { leftMenuOpen } from '@/composables/useSideMenuState'

const route = useApiRoute()
const probeStore = useNetworkProbeStore()
const currentRoute = useRoute()
const modalOpen = ref(false)
const errorMessage = ref('')
const latencyMap = ref<Record<string, { latencyMs: number; timedOut: boolean }>>({})
const probing = ref(false)
const measuringDomains = ref(false)
const measuringDomain = ref('')
const imageHosts = ref<string[]>([])
const imageHostResults = ref<Record<string, { available: boolean; bytesPerSecond: number }>>({})
const imageRouteMode = ref<'auto' | 'manual'>('auto')
const imageRouteHost = ref('')
const imageRouteSettingsReady = ref(false)
const imageRouteSelecting = ref(false)
const imageMeasuring = ref(false)
const imageCurrentHost = ref<string | null>(null)
const measuringImageHost = ref('')
const imageHostsLoading = ref(false)
const imageRouteError = ref('')
const { visible, reveal, dispose } = useTimedReveal(1000)
let routeRefreshSequence = 0
let imageProbeHandle: ListenerHandle | null = null
let imageProbeRunId = 0
let imageProbeRevision = 0

const hasQuickActions = computed(() =>
  ['/search', '/category', '/favorite'].includes(currentRoute.path),
)

function refreshRoute() {
  const sequence = ++routeRefreshSequence
  return route
    .refresh()
    .then(() => {
      if (sequence === routeRefreshSequence) errorMessage.value = ''
    })
    .catch((error) => {
      if (sequence === routeRefreshSequence) {
        errorMessage.value = normalizeRuntimeError(error, '读取线路状态失败').message
      }
      throw error
    })
}

watch(
  () => probeStore.clientState.value.state,
  (state, previousState) => {
    if (state !== 'ready' || previousState === 'ready' || !modalOpen.value) return
    void refreshRoute().catch(() => undefined)
  },
)

const isReader = () =>
  currentRoute.path === '/pdf-reader' ||
  currentRoute.path === '/cbz-reader' ||
  /^\/album\/[^/]+\/read\/[^/]+$/.test(currentRoute.path)

function revealFromInteraction() {
  if (modalOpen.value || leftMenuOpen.value) return
  reveal()
}

let revealTimer: ReturnType<typeof setTimeout> | null = null

function handlePointerUp(event: PointerEvent) {
  if (event.target instanceof Element && event.target.closest('.main-menu')) return
  if (modalOpen.value || leftMenuOpen.value) return
  if (revealTimer) clearTimeout(revealTimer)
  revealTimer = setTimeout(() => {
    revealTimer = null
    revealFromInteraction()
  }, 0)
}

function isEditable(target: EventTarget | null): boolean {
  return (
    target instanceof HTMLElement &&
    (target.isContentEditable ||
      !!target.closest(
        'input, textarea, select, ion-input, ion-textarea, ion-select, [contenteditable="true"]',
      ))
  )
}

function handleKeydown(event: KeyboardEvent) {
  if (isEditable(event.target) || ['Shift', 'Control', 'Alt', 'Meta'].includes(event.key)) return
  revealFromInteraction()
}

function openPicker() {
  errorMessage.value = ''
  latencyMap.value = {}
  imageHosts.value = []
  imageHostResults.value = {}
  imageRouteError.value = ''
  imageRouteSettingsReady.value = false
  modalOpen.value = true
  initNetworkProbeStore()
  probing.value = true
  imageHostsLoading.value = true
  void Promise.allSettled([
    refreshRoute(),
    JmcomicService.reprobeDomains(),
    JmcomicService.measureLatency(),
    JmcomicService.getImageRouteCandidates(),
    JmcomicService.getAllSettings(),
  ])
    .then(([, , latencyResult, imageCandidatesResult, settingsResult]) => {
      if (latencyResult.status === 'fulfilled') {
        latencyMap.value = Object.fromEntries(
          latencyResult.value.results.map((result) => [result.domain, result]),
        )
      }
      if (imageCandidatesResult.status === 'fulfilled') {
        imageHosts.value = imageCandidatesResult.value.hosts
      } else {
        imageRouteError.value = normalizeRuntimeError(imageCandidatesResult.reason, '获取图片线路失败').message
      }
      if (settingsResult.status === 'fulfilled') {
        imageRouteMode.value = settingsResult.value.imageRouteMode === 'manual' ? 'manual' : 'auto'
        imageRouteHost.value = settingsResult.value.imageRouteHost ?? ''
        imageRouteSettingsReady.value = true
      } else {
        imageRouteSettingsReady.value = false
        imageRouteError.value = normalizeRuntimeError(settingsResult.reason, '获取图片线路设置失败').message
      }
    })
    .finally(() => {
      probing.value = false
      imageHostsLoading.value = false
    })
}

function applyImageProbeEvent(event: NetworkProbeEvent) {
  if (!event.phase.startsWith('image_route_')) return
  if (event.runId === undefined || event.revision === undefined) return
  if (event.runId < imageProbeRunId) return
  if (event.runId === imageProbeRunId && event.revision < imageProbeRevision) return
  imageProbeRunId = event.runId
  imageProbeRevision = event.revision
  imageMeasuring.value = event.running === true
  imageCurrentHost.value = event.running ? event.currentHost ?? null : null
  if (event.hosts) {
    imageHostResults.value = {
      ...imageHostResults.value,
      ...Object.fromEntries(event.hosts.map((item) => [item.host, item])),
    }
  }
  if (event.error) imageRouteError.value = event.error
  else if (!event.running) imageRouteError.value = ''
}

async function handleMeasureDomains() {
  if (probing.value || measuringDomains.value || measuringDomain.value) return
  measuringDomains.value = true
  errorMessage.value = ''
  latencyMap.value = {}
  try {
    const result = await JmcomicService.measureLatency()
    latencyMap.value = Object.fromEntries(result.results.map((item) => [item.domain, item]))
  } catch (error) {
    errorMessage.value = normalizeRuntimeError(error, '测速失败').message
  } finally {
    measuringDomains.value = false
  }
}

async function handleMeasureDomain(domain: string) {
  if (probing.value || measuringDomains.value || measuringDomain.value) return
  measuringDomain.value = domain
  errorMessage.value = ''
  try {
    const result = await JmcomicService.measureDomainLatency(domain)
    latencyMap.value = { ...latencyMap.value, [domain]: result }
  } catch (error) {
    errorMessage.value = normalizeRuntimeError(error, '测速失败').message
  } finally {
    measuringDomain.value = ''
  }
}

async function handleMeasureImageHosts() {
  if (imageMeasuring.value || measuringImageHost.value) return
  imageMeasuring.value = true
  imageRouteError.value = ''
  try {
    const result = await JmcomicService.measureImageHosts()
    imageProbeRunId = result.runId
    imageProbeRevision = result.revision
    imageHostResults.value = {
      ...imageHostResults.value,
      ...Object.fromEntries(result.hosts.map((item) => [item.host, item])),
    }
  } catch (error) {
    imageRouteError.value = normalizeRuntimeError(error, '图片线路测速失败').message
  } finally {
    imageMeasuring.value = false
  }
}

function imageHostIsMeasuring(host: string) {
  return measuringImageHost.value === host || (imageMeasuring.value && imageCurrentHost.value === host)
}

async function handleMeasureImageHost(host: string) {
  if (imageMeasuring.value || measuringImageHost.value) return
  measuringImageHost.value = host
  imageRouteError.value = ''
  try {
    imageHostResults.value = {
      ...imageHostResults.value,
      [host]: await JmcomicService.measureImageHost(host),
    }
  } catch (error) {
    imageRouteError.value = normalizeRuntimeError(error, '图片线路测速失败').message
  } finally {
    measuringImageHost.value = ''
  }
}

function latencyText(domain: string, reachable: boolean) {
  if (!reachable) return '不可达'
  const result = latencyMap.value[domain]
  if (probing.value || measuringDomains.value) return '测速中'
  if (!result) return probing.value ? '测速中' : '暂无测速'
  return result.timedOut ? '超时' : `${result.latencyMs} ms`
}

function imageHostStatusClass(host: string) {
  const result = imageHostResults.value[host]
  if (!result) return 'status-ok'
  return result.available ? 'status-ok' : 'status-bad'
}

function imageHostLatencyText(host: string) {
  const result = imageHostResults.value[host]
  if (!result) return '暂无测速'
  if (!result.available) return '不可用'
  const speed = result.bytesPerSecond
  if (speed >= 1024 * 1024) return `${(speed / (1024 * 1024)).toFixed(1)} MB/s`
  if (speed >= 1024) return `${(speed / 1024).toFixed(1)} KB/s`
  return `${speed} B/s`
}

async function selectImageRoute(mode: 'auto' | 'manual', host = imageRouteHost.value) {
  if (!imageRouteSettingsReady.value || imageRouteSelecting.value) return
  imageRouteSelecting.value = true
  imageRouteError.value = ''
  try {
    if (mode === 'auto' && (!host || !imageHosts.value.includes(host))) {
      imageMeasuring.value = true
      try {
        const result = await JmcomicService.measureImageHosts()
        if (!result.selectedHost) {
          imageRouteError.value = '没有可用的图片线路'
          return
        }
        host = result.selectedHost
      } finally {
        imageMeasuring.value = false
      }
    }
    await JmcomicService.applyImageRoute({ mode, host })
    imageRouteMode.value = mode
    imageRouteHost.value = host
  } catch (error) {
    imageRouteError.value = normalizeRuntimeError(error, '应用图片线路失败').message
  } finally {
    imageRouteSelecting.value = false
  }
}

async function selectAuto() {
  await select('auto')
}

async function selectManual(domain: string) {
  await select('manual', domain)
}

async function select(mode: 'auto' | 'manual', domain = '') {
  errorMessage.value = ''
  modalOpen.value = false
  try {
    await route.select(mode, domain)
  } catch (error) {
    errorMessage.value = normalizeRuntimeError(error, '切换线路失败').message
  }
}

onMounted(() => {
  void JmcomicService.addNetworkProbeListener(applyImageProbeEvent).then((handle) => {
    imageProbeHandle = handle
  })
  window.addEventListener('pointerup', handlePointerUp, { capture: true, passive: true })
  window.addEventListener('keydown', handleKeydown, { capture: true })
  void route.refresh().catch(() => undefined)
})

onBeforeUnmount(() => {
  window.removeEventListener('pointerup', handlePointerUp, true)
  window.removeEventListener('keydown', handleKeydown, true)
  if (revealTimer) clearTimeout(revealTimer)
  void imageProbeHandle?.remove()
  dispose()
})
</script>

<style scoped>
.route-switch-button {
  position: fixed;
  z-index: 20;
  right: calc(18px + var(--ion-safe-area-right, 0px));
  bottom: calc(86px + var(--ion-safe-area-bottom, 0px));
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border: 1px solid var(--ion-color-medium-shade);
  border-radius: 50%;
  background: var(--ion-background-color);
  color: var(--ion-text-color);
  opacity: 0.42;
  box-shadow: 0 2px 8px rgb(0 0 0 / 18%);
}

.route-switch-button:hover,
.route-switch-button:focus-visible {
  opacity: 0.88;
}

.route-switch-button.is-reader {
  bottom: calc(126px + var(--ion-safe-area-bottom, 0px));
}

.route-switch-button.has-quick-actions {
  right: calc(16px + 52px + 10px + var(--ion-safe-area-right, 0px));
  bottom: calc(18px + var(--ion-safe-area-bottom, 0px));
}

.route-switch-button ion-icon {
  font-size: 21px;
}

.route-error {
  padding: 8px 16px;
  color: var(--ion-color-danger);
  font-size: 14px;
}

.route-modal-content {
  --background: #fffaf7;
}

.route-modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 70px;
  padding: 0 22px;
  background: #fffbf8;
}

.route-modal-header h2 {
  margin: 0;
  color: #4c2a18;
  font-size: 22px;
  font-weight: 600;
  line-height: 1.2;
}

.route-modal-close {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #4c2a18;
  cursor: pointer;
}

.route-modal-close:hover {
  background: #f5ebe4;
}
.route-modal-close ion-icon {
  font-size: 22px;
}
.route-modal-intro {
  display: grid;
  gap: 5px;
  padding: 22px 22px 16px;
  color: #4c2a18;
}
.route-modal-intro strong {
  font-size: 18px;
  font-weight: 650;
}
.eyebrow,
.group-title {
  color: #b38d73;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.intro-hint {
  color: #9b8578;
  font-size: 12px;
}
.route-group {
  margin: 0 16px 16px;
  overflow: hidden;
  border: 1px solid #f1e3db;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 4px 16px rgb(115 67 38 / 6%);
}
.group-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 42px;
  padding-right: 10px;
  background: #fffaf7;
}
.measure-button,
.row-measure-button {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #9b6748;
}
.measure-button:disabled,
.row-measure-button:disabled {
  opacity: 0.45;
}
.measure-button ion-icon,
.row-measure-button ion-icon {
  font-size: 19px;
}
.group-title {
  padding: 13px 16px 7px;
  background: #fffaf7;
}
.row-measure-button.spinning ion-icon {
  animation: route-measure-spin 1s linear infinite;
}
@keyframes route-measure-spin {
  to { transform: rotate(360deg); }
}
.route-group ion-item {
  --background: #fff;
  --border-color: #f1e8e2;
  --padding-start: 12px;
  --inner-padding-end: 12px;
  min-height: 66px;
}
.route-group ion-item:last-of-type {
  --border-width: 0;
  --inner-border-width: 0;
}
.route-group ion-label h2 {
  color: #4c2a18;
  font-size: 15px;
  font-weight: 600;
}
.route-group ion-label p {
  color: #9b8578;
  font-size: 12px;
}
.status-ok {
  color: #4eaa73;
}
.status-bad {
  color: #d15d55;
}
.status-spinner {
  width: 12px;
  height: 12px;
  margin-left: 4px;
  color: inherit;
  vertical-align: -2px;
}

ion-content {
  --padding-bottom: var(--ion-safe-area-bottom, 0px);
}
</style>
