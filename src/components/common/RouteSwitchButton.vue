<template>
  <button
    v-if="visible"
    class="route-switch-button"
    :class="{ 'is-reader': isReader() }"
    type="button"
    aria-label="切换 API 线路"
    title="切换 API 线路"
    @click="openPicker"
  >
    <IonIcon :icon="flashOutline" aria-hidden="true" />
  </button>

  <IonModal class="route-switch-modal" :is-open="modalOpen" @did-dismiss="modalOpen = false">
    <header class="route-modal-header">
      <h2>选择 API 线路</h2>
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
      <p v-if="errorMessage" class="route-error" role="alert">{{ errorMessage }}</p>
      <div class="route-modal-intro">
        <span class="eyebrow">API 线路</span>
        <strong>{{ route.currentDomain.value || '正在读取当前线路' }}</strong>
        <span class="intro-hint">打开时自动探活并测速，点击线路即可切换</span>
      </div>
      <div class="route-group">
        <div class="group-title">选择模式</div>
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
        <div class="group-title">线路状态</div>
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
              <span :class="item.reachable ? 'status-ok' : 'status-bad'">{{
                latencyText(item.domain, item.reachable)
              }}</span>
              <span v-if="item.domain === route.currentDomain.value"> · 当前线路</span>
            </p>
          </IonLabel>
        </IonItem>
        <IonItem v-if="!route.domains.value.length && !probeStore.domains.value.length">
          <IonLabel>线路状态暂不可用</IonLabel>
        </IonItem>
      </div>
    </IonContent>
  </IonModal>
</template>

<script setup lang="ts">
defineOptions({ name: 'RouteSwitchButton' })

import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { IonContent, IonIcon, IonItem, IonLabel, IonModal, IonRadio } from '@ionic/vue'
import { closeOutline, flashOutline } from 'ionicons/icons'
import { normalizeRuntimeError } from '@/runtime/errors'
import { useApiRoute } from '@/composables/useApiRoute'
import { useTimedReveal } from '@/composables/useTimedReveal'
import { initNetworkProbeStore, useNetworkProbeStore } from '@/composables/networkProbeStore'
import { JmcomicService } from '@/services/JmcomicService'

const route = useApiRoute()
const probeStore = useNetworkProbeStore()
const currentRoute = useRoute()
const modalOpen = ref(false)
const errorMessage = ref('')
const latencyMap = ref<Record<string, { latencyMs: number; timedOut: boolean }>>({})
const probing = ref(false)
const { visible, reveal, dispose } = useTimedReveal(1000)
let routeRefreshSequence = 0

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
  if (modalOpen.value) return
  reveal()
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
  modalOpen.value = true
  initNetworkProbeStore()
  probing.value = true
  void Promise.allSettled([
    refreshRoute(),
    JmcomicService.reprobeDomains(),
    JmcomicService.measureLatency(),
  ])
    .then(([, , latencyResult]) => {
      if (latencyResult.status === 'fulfilled') {
        latencyMap.value = Object.fromEntries(
          latencyResult.value.results.map((result) => [result.domain, result]),
        )
      }
    })
    .finally(() => {
      probing.value = false
    })
}

function latencyText(domain: string, reachable: boolean) {
  if (!reachable) return '不可达'
  const result = latencyMap.value[domain]
  if (!result) return probing.value ? '测速中' : '暂无测速'
  return result.timedOut ? '超时' : `${result.latencyMs} ms`
}

async function selectAuto() {
  await select('auto')
}

async function selectManual(domain: string) {
  await select('manual', domain)
}

async function select(mode: 'auto' | 'manual', domain = '') {
  errorMessage.value = ''
  try {
    await route.select(mode, domain)
    modalOpen.value = false
  } catch (error) {
    errorMessage.value = normalizeRuntimeError(error, '切换线路失败').message
  }
}

onMounted(() => {
  window.addEventListener('pointerdown', revealFromInteraction, { capture: true, passive: true })
  window.addEventListener('keydown', handleKeydown, { capture: true })
  void route.refresh().catch(() => undefined)
})

onBeforeUnmount(() => {
  window.removeEventListener('pointerdown', revealFromInteraction, true)
  window.removeEventListener('keydown', handleKeydown, true)
  dispose()
})
</script>

<style scoped>
.route-switch-button {
  position: fixed;
  z-index: 20;
  right: calc(18px + var(--ion-safe-area-right, 0px));
  bottom: calc(18px + var(--ion-safe-area-bottom, 0px));
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
.group-title {
  padding: 13px 16px 7px;
  background: #fffaf7;
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

ion-content {
  --padding-bottom: var(--ion-safe-area-bottom, 0px);
}
</style>
