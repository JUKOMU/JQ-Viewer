<template>
  <div class="bottom-toolbar">
    <button type="button" class="mode-btn" @click="$emit('toggle-mode')">
      <ion-icon :icon="isVertical ? gridOutline : listOutline"/>
    </button>
    <span class="page-indicator">{{ current }} / {{ total }}</span>
    <ion-range
      class="progress-bar"
      :min="1"
      :max="total"
      :value="current"
      :disabled="total <= 1"
      @ion-change="onRangeChange"
      @ion-input="onRangeInput"
      @ion-knob-move-start="$emit('progress-drag-start')"
      @ion-knob-move-end="$emit('progress-drag-end')"
    />
  </div>
</template>

<script setup lang="ts">
defineOptions({name: 'ReaderBottomToolbar'})

defineProps<{
  current: number
  total: number
  isVertical: boolean
}>()
const emit = defineEmits<{
  'toggle-mode': []
  'update:current': [page: number]
  'update:current-input': [page: number]
  'progress-drag-start': []
  'progress-drag-end': []
}>()
import type {RangeCustomEvent} from '@ionic/vue'
import {IonIcon, IonRange} from '@ionic/vue'
import {gridOutline, listOutline} from 'ionicons/icons'

const onRangeChange = (ev: RangeCustomEvent) => {
  const page = Number(ev.detail.value)
  emit('update:current', page)
}

const onRangeInput = (ev: RangeCustomEvent) => {
  const page = Number(ev.detail.value)
  emit('update:current-input', page)
}
</script>

<style scoped>
.bottom-toolbar {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 56px;
  padding: 0 14px;
  padding-bottom: var(--ion-safe-area-bottom, 0px);
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(8px);
}

.mode-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 50%;
  background: rgb(255 255 255 / 0.12);
  color: #fff;
  font-size: 18px;
  flex-shrink: 0;
}

.page-indicator {
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  flex-shrink: 0;
}

.progress-bar {
  flex: 1;
  --bar-background: rgba(255, 255, 255, 0.25);
  --bar-background-active: #fa9c69;
  --knob-background: #fa9c69;
  --knob-size: 18px;
  --height: 4px;
  padding: 0;
}
</style>
