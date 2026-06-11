<template>
  <div class="panel-overlay" :class="{show: visible}" @click.self="$emit('close')">
    <div class="panel-card" :class="{show: visible}">
          <div class="panel-header">
            <span class="panel-title">阅读设置</span>
            <button type="button" class="close-btn" @click="$emit('close')">
              <ion-icon :icon="closeOutline"/>
            </button>
          </div>

          <div class="panel-body">
            <!-- 显示模式 -->
            <div class="setting-row">
              <span class="setting-label">显示模式</span>
              <div class="segmented">
                <button
                  :class="['seg-btn', {active: isVertical}]"
                  @click="onDisplayModeChange(true)"
                >纵向</button>
                <button
                  :class="['seg-btn', {active: !isVertical}]"
                  @click="onDisplayModeChange(false)"
                >横向</button>
              </div>
            </div>

            <!-- 屏幕方向 -->
            <div class="setting-row divider">
              <span class="setting-label">屏幕方向</span>
              <div class="segmented">
                <button
                  :class="['seg-btn', {active: localOrientation === 'auto'}]"
                  @click="onOrientationChange('auto')"
                >自动</button>
                <button
                  :class="['seg-btn', {active: localOrientation === 'portrait'}]"
                  @click="onOrientationChange('portrait')"
                >竖屏</button>
                <button
                  :class="['seg-btn', {active: localOrientation === 'landscape'}]"
                  @click="onOrientationChange('landscape')"
                >横屏</button>
              </div>
            </div>

            <!-- 亮度 -->
            <div class="setting-row divider">
              <div class="setting-left">
                <span class="setting-label">亮度</span>
                <span class="setting-sub">跟随系统</span>
              </div>
              <div class="setting-right">
                <IonToggle
                  :checked="isFollowSystem"
                  color="warning"
                  @ion-change="onFollowSystemChange"
                />
              </div>
            </div>
            <div v-if="!isFollowSystem" class="setting-row brightness-row">
              <IonRange
                class="brightness-slider"
                :min="0"
                :max="1"
                :step="0.05"
                :value="localBrightness"
                color="warning"
                @ion-change="onBrightnessChange"
              />
            </div>

            <!-- 防止熄屏 -->
            <div class="setting-row divider">
              <span class="setting-label">防止熄屏</span>
              <div class="setting-right">
                <IonToggle
                  :checked="localKeepScreenOn"
                  color="warning"
                  @ion-change="onKeepScreenOnChange"
                />
              </div>
            </div>

            <!-- 音量键翻页 -->
            <div class="setting-row divider">
              <div class="setting-left">
                <span class="setting-label">{{ platformCapabilities.readerText.volumeNavigationLabel }}</span>
                <span class="setting-sub">{{ platformCapabilities.readerText.volumeNavigationHint }}</span>
              </div>
              <div class="setting-right">
                <IonToggle
                  :checked="localVolumeNavigation"
                  color="warning"
                  @ion-change="onVolumeNavigationChange"
                />
              </div>
            </div>

            <!-- 预加载页数 -->
            <div class="setting-row divider">
              <div class="setting-left">
                <span class="setting-label">预加载页数</span>
                <span class="setting-sub">阅读时前后预载图片数量</span>
              </div>
              <div class="setting-right">
                <input
                  class="num-input"
                  type="number"
                  :value="localPreloadPages"
                  min="5"
                  max="50"
                  step="5"
                  @change="onPreloadPagesChange"
                />
                <span class="unit">页</span>
              </div>
            </div>

            <!-- 预加载并发数 -->
            <div class="setting-row divider">
              <div class="setting-left">
                <span class="setting-label">预加载并发数</span>
                <span class="setting-sub">同时加载图片的线程数，下次启动生效</span>
              </div>
              <div class="setting-right">
                <input
                  class="num-input"
                  type="number"
                  :value="localPreloadConcurrency"
                  min="1"
                  max="12"
                  @change="onPreloadConcurrencyChange"
                />
                <span class="unit">线程</span>
              </div>
            </div>
          </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import {nextTick, onMounted, ref} from 'vue'
import {IonIcon, IonRange, IonToggle} from '@ionic/vue'
import {closeOutline} from 'ionicons/icons'
import type {RangeCustomEvent} from '@ionic/vue'
import {JmcomicService, showToast} from '@/services/JmcomicService'
import {SettingsStore} from '@/services/SettingsService'
import {platformCapabilities} from '@/platform/activeCapabilities'

defineOptions({name: 'ReaderSettingsPanel'})

defineProps<{
  isVertical: boolean
}>()

const emit = defineEmits<{
  close: []
  'update:display-mode': [vertical: boolean]
}>()

const visible = ref(false)

// 本地状态
const localOrientation = ref(SettingsStore.getReaderScreenOrientation())
const localBrightness = ref(SettingsStore.getReaderBrightness())
const localKeepScreenOn = ref(SettingsStore.getReaderKeepScreenOn())
const localVolumeNavigation = ref(SettingsStore.getReaderVolumeNavigation())
const localPreloadPages = ref(SettingsStore.getReaderPreloadPages())
const localPreloadConcurrency = ref(SettingsStore.getPreloadConcurrency())

const isFollowSystem = ref(localBrightness.value < 0)

onMounted(() => {
  nextTick(() => {
    visible.value = true
  })
})

// ---- 显示模式 ----
function onDisplayModeChange(vertical: boolean) {
  SettingsStore.setReaderDisplayMode(vertical ? 'vertical' : 'horizontal')
  JmcomicService.setReaderDisplayMode(vertical ? 'vertical' : 'horizontal').catch(() => {})
  emit('update:display-mode', vertical)
}

// ---- 屏幕方向 ----
function onOrientationChange(orientation: string) {
  localOrientation.value = orientation
  SettingsStore.setReaderScreenOrientation(orientation)
  JmcomicService.setReaderScreenOrientation(orientation).catch(() => {})
}

// ---- 亮度 ----
function onFollowSystemChange(e: CustomEvent) {
  const follow = e.detail.checked
  isFollowSystem.value = follow
  if (follow) {
    localBrightness.value = -1
    SettingsStore.setReaderBrightness(-1)
    JmcomicService.setReaderBrightness(-1).catch(() => {})
  } else {
    localBrightness.value = 0.5
    SettingsStore.setReaderBrightness(0.5)
    JmcomicService.setReaderBrightness(0.5).catch(() => {})
  }
}

function onBrightnessChange(e: RangeCustomEvent) {
  const val = Number(e.detail.value)
  localBrightness.value = val
  SettingsStore.setReaderBrightness(val)
  JmcomicService.setReaderBrightness(val).catch(() => {})
}

// ---- 防止熄屏 ----
function onKeepScreenOnChange(e: CustomEvent) {
  const enabled = e.detail.checked
  localKeepScreenOn.value = enabled
  SettingsStore.setReaderKeepScreenOn(enabled)
  JmcomicService.setReaderKeepScreenOn(enabled).catch(() => {})
}

// ---- 音量键翻页 ----
function onVolumeNavigationChange(e: CustomEvent) {
  const enabled = e.detail.checked
  localVolumeNavigation.value = enabled
  SettingsStore.setReaderVolumeNavigation(enabled)
  JmcomicService.setReaderVolumeNavigation(enabled).catch(() => {})
}

// ---- 预加载页数 ----
function onPreloadPagesChange(e: Event) {
  const val = parseInt((e.target as HTMLInputElement).value, 10)
  if (!Number.isFinite(val)) return
  const n = Math.max(5, Math.min(50, val))
  localPreloadPages.value = n
  SettingsStore.setReaderPreloadPages(n)
  JmcomicService.setReaderPreloadPages(n)
    .then(() => showToast('已保存，下次阅读生效', 'success'))
    .catch(() => showToast('保存失败', 'danger'))
}

// ---- 预加载并发数 ----
function onPreloadConcurrencyChange(e: Event) {
  const val = parseInt((e.target as HTMLInputElement).value, 10)
  if (!Number.isFinite(val)) return
  const n = Math.max(1, Math.min(12, val))
  localPreloadConcurrency.value = n
  SettingsStore.setPreloadConcurrency(n)
  JmcomicService.setPreloadConcurrency(n)
    .then(() => showToast('已保存，下次启动生效', 'success'))
    .catch(() => showToast('保存失败', 'danger'))
}
</script>

<style scoped>
.panel-overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.panel-card {
  width: 100%;
  max-height: 80vh;
  background: #fff;
  border-radius: 16px 16px 0 0;
  overflow-y: auto;
  padding-bottom: calc(16px + var(--ion-safe-area-bottom, 0px));
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 20px 12px;
}

.panel-title {
  font-size: 17px;
  font-weight: 600;
  color: #4c2a18;
}

.close-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 50%;
  background: #f5ebe4;
  color: #8c6b5a;
  font-size: 20px;
  cursor: pointer;
}

.panel-body {
  padding: 0 16px;
}

.setting-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 4px;
  min-height: 48px;
}

.setting-row.divider {
  border-top: 1px solid #f5ebe4;
}

.setting-left {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.setting-label {
  font-size: 15px;
  color: #4c2a18;
  font-weight: 500;
}

.setting-sub {
  font-size: 12px;
  color: #b89a84;
}

.setting-right {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

/* 分段按钮 */
.segmented {
  display: flex;
  border: 1px solid #e0cfc4;
  border-radius: 8px;
  overflow: hidden;
}

.seg-btn {
  padding: 6px 14px;
  border: 0;
  background: #fdfaf8;
  color: #8c6b5a;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.seg-btn:not(:last-child) {
  border-right: 1px solid #e0cfc4;
}

.seg-btn.active {
  background: #f0a060;
  color: #fff;
}

/* 亮度滑块 */
.brightness-row {
  padding-top: 0;
  padding-bottom: 8px;
}

.brightness-slider {
  width: 100%;
  --bar-background: #f0e4db;
  --bar-background-active: #f0a060;
  --knob-background: #f0a060;
  --knob-size: 18px;
  --height: 4px;
  padding: 0;
}

/* 数字输入 */
.num-input {
  width: 56px;
  height: 32px;
  border: 1px solid #e0cfc4;
  border-radius: 8px;
  text-align: center;
  font-size: 14px;
  color: #4c2a18;
  background: #fdfaf8;
  outline: none;
}

.num-input:focus {
  border-color: #f0a060;
}

.unit {
  font-size: 13px;
  color: #b89a84;
}

/* 过渡动画 */
.panel-overlay {
  opacity: 0;
  transition: opacity 0.25s ease;
}

.panel-overlay.show {
  opacity: 1;
}

.panel-card {
  transform: translateY(100%);
  transition: transform 0.3s ease;
}

.panel-card.show {
  transform: translateY(0);
}
</style>
