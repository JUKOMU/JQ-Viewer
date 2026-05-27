<template>
  <div v-if="modelValue" class="picker-backdrop" @click.self="close">
    <div class="picker-panel">
      <div class="picker-header">
        <span class="picker-title">选择收藏夹</span>
        <div class="picker-header-actions">
          <button
            type="button"
            class="picker-add-btn"
            aria-label="新建收藏夹"
            @click="$emit('add-folder')"
          >
            <IonIcon :icon="addOutline"/>
          </button>
          <button type="button" class="picker-close-btn" @click="close">
            <IonIcon :icon="closeOutline"/>
          </button>
        </div>
      </div>

      <div class="picker-body">
        <template v-if="onlineFolders.length > 0">
          <div class="section-title">在线收藏夹</div>
          <div class="folder-list">
            <button
              v-for="folder in onlineFolders"
              :key="'online-' + folder.id"
              type="button"
              class="folder-item"
              @click="select(folder.id, 'online')"
            >
              <IonIcon :icon="folderOpenOutline" class="folder-icon"/>
              <span class="folder-name">{{ folder.name }}</span>
              <span v-if="onlineFolderCounts[folder.id] !== undefined" class="folder-count">
                {{ onlineFolderCounts[folder.id] }}
              </span>
            </button>
          </div>
        </template>

        <div class="section-title">离线收藏夹</div>
        <div class="folder-list">
          <button
            v-for="folder in offlineFolders"
            :key="'offline-' + folder.id"
            type="button"
            class="folder-item"
            @click="select(folder.id, 'offline')"
          >
            <IonIcon :icon="folderOpenOutline" class="folder-icon"/>
            <span class="folder-name">{{ folder.name }}</span>
            <span class="folder-count">{{ folder.count }}</span>
          </button>
        </div>

        <div v-if="onlineFolders.length === 0 && offlineFolders.length === 0" class="empty-state">
          暂无收藏夹，请点击 + 创建
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({name: 'FavoriteFolderPicker'})

defineProps<{
  modelValue: boolean
  onlineFolders: FolderEntry[]
  offlineFolders: FolderEntry[]
  onlineFolderCounts: Record<string, number>
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'add-folder': []
  select: [payload: { folderId: string; source: 'online' | 'offline' }]
}>()
import {IonIcon} from '@ionic/vue'
import {addOutline, closeOutline, folderOpenOutline} from 'ionicons/icons'
import type {FolderEntry} from '@/services/JmcomicTypes'

const close = () => {
  emit('update:modelValue', false)
}

const select = (folderId: string, source: 'online' | 'offline') => {
  emit('select', {folderId, source})
}
</script>

<style scoped>
.picker-backdrop {
  position: fixed;
  inset: 0;
  z-index: 60;
  background: rgb(16 12 10 / 0.28);
  backdrop-filter: blur(2px);
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.picker-panel {
  width: 100%;
  max-width: 480px;
  max-height: min(60vh, 480px);
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, #fffaf6 0%, #fff3eb 100%);
  border-radius: 20px 20px 0 0;
  box-shadow: 0 -8px 36px rgb(76 42 24 / 0.16);
  padding-bottom: var(--ion-safe-area-bottom);
}

.picker-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 16px 12px;
  border-bottom: 1px solid rgb(245 210 188 / 0.6);
  flex-shrink: 0;
}

.picker-title {
  font-size: 18px;
  font-weight: 700;
  color: #3a261d;
}

.picker-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.picker-add-btn,
.picker-close-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 999px;
  background: #fff;
  color: #8a6048;
  font-size: 18px;
  box-shadow: 0 4px 12px rgb(115 67 38 / 0.1);
  cursor: pointer;
}

.picker-add-btn:active,
.picker-close-btn:active {
  transform: scale(0.94);
}

.picker-body {
  flex: 1;
  overflow-y: auto;
  padding: 14px 16px 18px;
}

.section-title {
  margin-bottom: 10px;
  margin-top: 8px;
  color: #7a5743;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.section-title + .section-title {
  margin-top: 20px;
}

.folder-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.folder-item {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  min-height: 48px;
  padding: 12px 14px;
  border: 1px solid rgb(245 210 188 / 0.72);
  border-radius: 20px;
  background: #fff;
  color: #3a261d;
  font: inherit;
  font-size: 14px;
  font-weight: 700;
  text-align: left;
  box-shadow: 0 12px 28px rgb(115 67 38 / 0.08);
  cursor: pointer;
  transition: background-color 0.16s ease,
  border-color 0.16s ease;
}

.folder-item:hover,
.folder-item:active {
  background: #fff0e7;
}

.folder-icon {
  flex-shrink: 0;
  font-size: 16px;
  color: #c96d3a;
}

.folder-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.folder-count {
  flex-shrink: 0;
  font-size: 11px;
  color: #9a725b;
  background: #fff0e7;
  padding: 2px 8px;
  border-radius: 999px;
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 80px;
  color: #9e7d6a;
  font-size: 13px;
}
</style>
