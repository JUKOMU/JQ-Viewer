<template>
  <div class="top-toolbar">
    <button type="button" class="back-btn" @click="$emit('back')">
      <ion-icon :icon="arrowBack"/>
    </button>
    <span class="chapter-title">{{ title }}</span>
  </div>
</template>

<script setup lang="ts">
defineOptions({name: 'ReaderTopToolbar'})

defineProps<{
  title: string
}>()
defineEmits<{
  back: []
}>()
import {IonIcon} from '@ionic/vue'
import {arrowBack} from 'ionicons/icons'
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
  gap: 12px;
  min-height: calc(50px + var(--jq-reader-safe-area-top, var(--ion-safe-area-top, 0px)));
  padding: 8px 14px;
  padding-top: calc(8px + var(--jq-reader-safe-area-top, var(--ion-safe-area-top, 0px)));
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(8px);
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
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
