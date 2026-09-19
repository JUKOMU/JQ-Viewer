<template>
  <div class="panel-overlay" :class="{ show: visible }" @click.self="$emit('close')">
    <div class="panel-card" :class="{ show: visible }">
      <div class="panel-header">
        <span class="panel-title">阅读设置</span>
        <button type="button" class="close-btn" @click="$emit('close')">
          <ion-icon :icon="closeOutline" />
        </button>
      </div>

      <div class="panel-body">
        <!-- 显示模式 -->
        <div class="setting-row">
          <span class="setting-label">显示模式</span>
          <div class="segmented">
            <button :class="['seg-btn', { active: isVertical }]" @click="onDisplayModeChange(true)">
              纵向
            </button>
            <button
              :class="['seg-btn', { active: !isVertical }]"
              @click="onDisplayModeChange(false)"
            >
              横向
            </button>
          </div>
        </div>

        <!-- 屏幕方向 -->
        <div
          class="setting-row divider"
          :class="{ 'capability-unavailable': !readerCapabilities.orientation.available }"
        >
          <div class="setting-left">
            <span class="setting-label">屏幕方向</span>
            <span v-if="!readerCapabilities.orientation.available" class="setting-sub">
              {{ readerCapabilities.orientation.reason }}
            </span>
          </div>
          <div class="segmented">
            <button
              :class="['seg-btn', { active: localOrientation === 'auto' }]"
              :disabled="!readerCapabilities.orientation.available"
              @click="onOrientationChange('auto')"
            >
              自动
            </button>
            <button
              :class="['seg-btn', { active: localOrientation === 'portrait' }]"
              :disabled="!readerCapabilities.orientation.available"
              @click="onOrientationChange('portrait')"
            >
              竖屏
            </button>
            <button
              :class="['seg-btn', { active: localOrientation === 'landscape' }]"
              :disabled="!readerCapabilities.orientation.available"
              @click="onOrientationChange('landscape')"
            >
              横屏
            </button>
          </div>
        </div>

        <!-- 亮度 -->
        <div
          class="setting-row divider"
          :class="{ 'capability-unavailable': !readerCapabilities.brightness.available }"
        >
          <div class="setting-left">
            <span class="setting-label">亮度</span>
            <span class="setting-sub">
              {{
                readerCapabilities.brightness.available
                  ? '跟随系统'
                  : readerCapabilities.brightness.reason
              }}
            </span>
          </div>
          <div class="setting-right">
            <IonToggle
              :checked="isFollowSystem"
              :disabled="!readerCapabilities.brightness.available"
              color="warning"
              @ion-change="onFollowSystemChange"
            />
          </div>
        </div>
        <div
          v-if="readerCapabilities.brightness.available && !isFollowSystem"
          class="setting-row brightness-row"
        >
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
        <div
          class="setting-row divider"
          :class="{ 'capability-unavailable': !readerCapabilities.keepAwake.available }"
        >
          <div class="setting-left">
            <span class="setting-label">防止熄屏</span>
            <span v-if="!readerCapabilities.keepAwake.available" class="setting-sub">
              {{ readerCapabilities.keepAwake.reason }}
            </span>
          </div>
          <div class="setting-right">
            <IonToggle
              :checked="localKeepScreenOn"
              :disabled="!readerCapabilities.keepAwake.available"
              color="warning"
              @ion-change="onKeepScreenOnChange"
            />
          </div>
        </div>

        <!-- 音量键翻页 -->
        <div
          class="setting-row divider"
          :class="{ 'capability-unavailable': !readerCapabilities.volumeKeys.available }"
        >
          <div class="setting-left">
            <span class="setting-label">音量键翻页</span>
            <span v-if="!readerCapabilities.volumeKeys.available" class="setting-sub">
              {{ readerCapabilities.volumeKeys.reason }}
            </span>
          </div>
          <div class="setting-right">
            <IonToggle
              :checked="localVolumeNavigation"
              :disabled="!readerCapabilities.volumeKeys.available"
              color="warning"
              @ion-change="onVolumeNavigationChange"
            />
          </div>
        </div>

        <div class="setting-row divider">
          <div class="setting-left">
            <span class="setting-label">页面全屏</span>
            <span class="setting-sub">
              {{
                readerCapabilities.fullscreen.available
                  ? '隐藏工具栏时进入浏览器全屏'
                  : readerCapabilities.fullscreen.reason
              }}
            </span>
          </div>
          <span
            class="capability-status"
            :class="{ unavailable: !readerCapabilities.fullscreen.available }"
          >
            {{ readerCapabilities.fullscreen.available ? '可用' : '不可用' }}
          </span>
        </div>

        <div class="setting-row divider">
          <div class="setting-left">
            <span class="setting-label">阅读器宿主状态</span>
            <span class="setting-sub">
              {{
                readerCapabilities.hostState.available
                  ? '离开阅读页时自动恢复宿主状态'
                  : readerCapabilities.hostState.reason
              }}
            </span>
          </div>
          <span
            class="capability-status"
            :class="{ unavailable: !readerCapabilities.hostState.available }"
          >
            {{ readerCapabilities.hostState.available ? '已接入' : '不可用' }}
          </span>
        </div>

        <!-- 阅读结束时展开工具栏 -->
        <div class="setting-row divider">
          <div class="setting-left">
            <span class="setting-label">阅读结束时展开工具栏</span>
            <span class="setting-sub">纵向到底或横向进入最后一页时自动展开</span>
          </div>
          <div class="setting-right">
            <IonToggle
              :checked="localAutoShowToolbarAtEnd"
              aria-label="阅读结束时展开工具栏"
              color="warning"
              @ion-change="onAutoShowToolbarAtEndChange"
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
            <span class="setting-sub">同时加载图片的线程数，应用进程重启后生效</span>
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
import { nextTick, onMounted, ref } from 'vue'
import type { RangeCustomEvent } from '@ionic/vue'
import { IonIcon, IonRange, IonToggle } from '@ionic/vue'
import { closeOutline } from 'ionicons/icons'
import { getRuntime } from '@/runtime/runtimeContext'
import { JmcomicService, sanitizeError, showToast } from '@/services/JmcomicService'
import { persistReaderSettingValue } from '@/services/ReaderSettingPersistence'
import { persistPreloadConcurrency, SettingsStore } from '@/services/SettingsService'

defineOptions({ name: 'ReaderSettingsPanel' })

defineProps<{
  isVertical: boolean
}>()

const emit = defineEmits<{
  close: []
  'update:display-mode': [vertical: boolean]
}>()

const visible = ref(false)
const readerCapabilities = getRuntime().services.reader

// 本地状态
const localOrientation = ref(SettingsStore.getReaderScreenOrientation())
const localBrightness = ref(SettingsStore.getReaderBrightness())
const localKeepScreenOn = ref(SettingsStore.getReaderKeepScreenOn())
const localVolumeNavigation = ref(SettingsStore.getReaderVolumeNavigation())
const localAutoShowToolbarAtEnd = ref(SettingsStore.getReaderAutoShowToolbarAtEnd())
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
async function onOrientationChange(orientation: string) {
  if (!readerCapabilities.orientation.available) return
  const operation = persistReaderSettingValue(
    'orientation',
    orientation,
    SettingsStore.getReaderScreenOrientation(),
    () => JmcomicService.setReaderScreenOrientation(orientation),
  )
  localOrientation.value = orientation
  SettingsStore.setReaderScreenOrientation(orientation)
  try {
    await operation.promise
  } catch (error) {
    if (!operation.isLatest()) return
    const confirmed = operation.getConfirmedValue()
    localOrientation.value = confirmed
    SettingsStore.setReaderScreenOrientation(confirmed)
    await showToast(sanitizeError(error, '切换屏幕方向失败'), 'danger')
  }
}

// ---- 亮度 ----
async function onFollowSystemChange(e: CustomEvent) {
  if (!readerCapabilities.brightness.available) return
  const follow = e.detail.checked
  const brightness = follow ? -1 : 0.5
  const operation = persistReaderSettingValue(
    'brightness',
    brightness,
    SettingsStore.getReaderBrightness(),
    () => JmcomicService.setReaderBrightness(brightness),
  )
  isFollowSystem.value = follow
  localBrightness.value = brightness
  SettingsStore.setReaderBrightness(brightness)
  try {
    await operation.promise
  } catch (error) {
    if (!operation.isLatest()) return
    const confirmed = operation.getConfirmedValue()
    localBrightness.value = confirmed
    isFollowSystem.value = confirmed < 0
    SettingsStore.setReaderBrightness(confirmed)
    await showToast(sanitizeError(error, '调整亮度失败'), 'danger')
  }
}

async function onBrightnessChange(e: RangeCustomEvent) {
  if (!readerCapabilities.brightness.available) return
  const val = Number(e.detail.value)
  const operation = persistReaderSettingValue(
    'brightness',
    val,
    SettingsStore.getReaderBrightness(),
    () => JmcomicService.setReaderBrightness(val),
  )
  localBrightness.value = val
  SettingsStore.setReaderBrightness(val)
  try {
    await operation.promise
  } catch (error) {
    if (!operation.isLatest()) return
    const confirmed = operation.getConfirmedValue()
    localBrightness.value = confirmed
    isFollowSystem.value = confirmed < 0
    SettingsStore.setReaderBrightness(confirmed)
    await showToast(sanitizeError(error, '调整亮度失败'), 'danger')
  }
}

// ---- 防止熄屏 ----
async function onKeepScreenOnChange(e: CustomEvent) {
  if (!readerCapabilities.keepAwake.available) return
  const enabled = e.detail.checked
  const operation = persistReaderSettingValue(
    'keepAwake',
    enabled,
    SettingsStore.getReaderKeepScreenOn(),
    () => JmcomicService.setReaderKeepScreenOn(enabled),
  )
  localKeepScreenOn.value = enabled
  SettingsStore.setReaderKeepScreenOn(enabled)
  try {
    await operation.promise
  } catch (error) {
    if (!operation.isLatest()) return
    const confirmed = operation.getConfirmedValue()
    localKeepScreenOn.value = confirmed
    SettingsStore.setReaderKeepScreenOn(confirmed)
    await showToast(sanitizeError(error, '切换屏幕常亮失败'), 'danger')
  }
}

// ---- 音量键翻页 ----
async function onVolumeNavigationChange(e: CustomEvent) {
  if (!readerCapabilities.volumeKeys.available) return
  const enabled = e.detail.checked
  const operation = persistReaderSettingValue(
    'volumeNavigation',
    enabled,
    SettingsStore.getReaderVolumeNavigation(),
    () => JmcomicService.setReaderVolumeNavigation(enabled),
  )
  localVolumeNavigation.value = enabled
  SettingsStore.setReaderVolumeNavigation(enabled)
  try {
    await operation.promise
  } catch (error) {
    if (!operation.isLatest()) return
    const confirmed = operation.getConfirmedValue()
    localVolumeNavigation.value = confirmed
    SettingsStore.setReaderVolumeNavigation(confirmed)
    await showToast(sanitizeError(error, '切换音量键翻页失败'), 'danger')
  }
}

// ---- 阅读结束时展开工具栏 ----
function onAutoShowToolbarAtEndChange(e: CustomEvent) {
  const enabled = e.detail.checked
  localAutoShowToolbarAtEnd.value = enabled
  SettingsStore.setReaderAutoShowToolbarAtEnd(enabled)
  JmcomicService.setReaderAutoShowToolbarAtEnd(enabled).catch(() => {})
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
  persistPreloadConcurrency(n)
    .then(() => showToast('已保存，应用进程重启后生效', 'success'))
    .catch(() => {
      if (localPreloadConcurrency.value === n) {
        localPreloadConcurrency.value = SettingsStore.getPreloadConcurrency()
      }
      return showToast('保存失败', 'danger')
    })
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
  max-width: 720px;
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
  flex: 1;
  min-width: 0;
  margin-right: 12px;
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

.capability-unavailable {
  opacity: 0.68;
}

.capability-status {
  flex-shrink: 0;
  font-size: 12px;
  color: #4f7a52;
}

.capability-status.unavailable {
  color: #9a7560;
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
  transition:
    background 0.15s,
    color 0.15s;
}

.seg-btn:not(:last-child) {
  border-right: 1px solid #e0cfc4;
}

.seg-btn.active {
  background: #f0a060;
  color: #fff;
}

.seg-btn:disabled {
  cursor: not-allowed;
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
