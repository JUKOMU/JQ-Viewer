<template>
  <div
    v-if="visible"
    class="context-menu"
    @mousedown.stop
    @touchstart.stop
  >
    <button
      v-if="!isDefaultFolder"
      type="button"
      class="menu-item"
      @click.stop="$emit('rename')"
    >
      <IonIcon :icon="pencilOutline" class="menu-icon"/>
      <span>重命名</span>
    </button>
    <button
      type="button"
      class="menu-item"
      @click.stop="$emit('move')"
    >
      <IonIcon :icon="swapHorizontalOutline" class="menu-icon"/>
      <span>移动</span>
    </button>
    <button
      type="button"
      class="menu-item"
      @click.stop="$emit('copy')"
    >
      <IonIcon :icon="copyOutline" class="menu-icon"/>
      <span>复制</span>
    </button>
    <button
      v-if="!isDefaultFolder"
      type="button"
      class="menu-item menu-item--danger"
      @click.stop="$emit('delete')"
    >
      <IonIcon :icon="trashOutline" class="menu-icon"/>
      <span>删除</span>
    </button>
    <button
      type="button"
      class="menu-item"
      @click.stop="$emit('export')"
    >
      <IonIcon :icon="downloadOutline" class="menu-icon"/>
      <span>导出</span>
    </button>
  </div>
</template>

<script setup lang="ts">
import { IonIcon } from '@ionic/vue'
import { copyOutline, downloadOutline, pencilOutline, swapHorizontalOutline, trashOutline } from 'ionicons/icons'

defineProps<{
  visible: boolean
  isDefaultFolder: boolean
  isOnline: boolean
}>()

defineEmits<{
  rename: []
  move: []
  copy: []
  delete: []
  export: []
}>()
</script>

<style scoped>
.context-menu {
  position: absolute;
  top: calc(100% + 4px);
  right: 0;
  z-index: 100;
  min-width: 140px;
  background: #fffbf8;
  border: 1px solid rgb(250 156 105 / 0.3);
  border-radius: 14px;
  box-shadow: 0 8px 22px rgb(76 42 24 / 0.12);
  overflow: hidden;
}

.menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 44px;
  padding: 10px 16px;
  border: 0;
  border-bottom: 1px solid rgb(245 210 188 / 0.4);
  background: transparent;
  color: #3a261d;
  font: inherit;
  font-size: 14px;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.14s ease;
}

.menu-item:last-child {
  border-bottom: 0;
}

.menu-item:hover,
.menu-item:active {
  background: #fff0e7;
}

.menu-item--danger {
  color: #d4533e;
}

.menu-icon {
  font-size: 16px;
  flex-shrink: 0;
}
</style>
