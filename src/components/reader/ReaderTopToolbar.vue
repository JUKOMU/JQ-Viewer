<template>
  <div class="top-toolbar">
    <div class="top-toolbar-content">
      <button type="button" class="back-btn" @click="$emit('back')">
        <ion-icon :icon="arrowBack" />
      </button>
      <span class="chapter-title">{{ title }}</span>
      <div v-if="showDesktopControls" class="desktop-controls">
        <button
          type="button"
          class="control-btn"
          title="缩小"
          aria-label="缩小"
          @click="$emit('zoom-out')"
        >
          <ion-icon :icon="removeOutline" aria-hidden="true" />
        </button>
        <button
          type="button"
          class="control-btn"
          title="放大"
          aria-label="放大"
          @click="$emit('zoom-in')"
        >
          <ion-icon :icon="addOutline" aria-hidden="true" />
        </button>
        <button
          type="button"
          class="control-btn"
          title="重置缩放"
          aria-label="重置缩放"
          @click="$emit('reset-zoom')"
        >
          <ion-icon :icon="scanOutline" aria-hidden="true" />
        </button>
        <button
          type="button"
          class="control-btn"
          :title="isFullscreen ? '退出页面全屏' : '进入页面全屏'"
          :aria-label="isFullscreen ? '退出页面全屏' : '进入页面全屏'"
          :aria-pressed="isFullscreen"
          @click="$emit('toggle-fullscreen')"
        >
          <ion-icon :icon="isFullscreen ? contractOutline : expandOutline" aria-hidden="true" />
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'ReaderTopToolbar' })

defineProps<{
  title: string
  showDesktopControls?: boolean
  isFullscreen?: boolean
}>()
defineEmits<{
  back: []
  'zoom-in': []
  'zoom-out': []
  'reset-zoom': []
  'toggle-fullscreen': []
}>()
import { IonIcon } from '@ionic/vue'
import {
  addOutline,
  arrowBack,
  contractOutline,
  expandOutline,
  removeOutline,
  scanOutline,
} from 'ionicons/icons'
</script>

<style scoped>
.top-toolbar {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  min-height: calc(50px + var(--jq-reader-safe-area-top, var(--ion-safe-area-top, 0px)));
  padding: 8px 14px;
  padding-top: calc(8px + var(--jq-reader-safe-area-top, var(--ion-safe-area-top, 0px)));
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(8px);
}

.top-toolbar-content {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  max-width: 720px;
  margin: 0 auto;
}

.back-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 50%;
  background: rgb(255 255 255 / 0.12);
  color: #fff;
  font-size: 20px;
  flex-shrink: 0;
}

.chapter-title {
  min-width: 0;
  flex: 1;
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.desktop-controls {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.control-btn {
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
}

.back-btn:focus-visible,
.control-btn:focus-visible {
  outline: 2px solid #fff;
  outline-offset: 2px;
}
</style>
