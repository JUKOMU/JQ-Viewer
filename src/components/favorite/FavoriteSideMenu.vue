<template>
  <div v-show="isVisible" class="fav-menu-backdrop" :style="backdropStyle" @click.self="closeMenu" @touchstart.stop>
    <div ref="panelRef" class="fav-menu-panel" :style="panelStyle">
      <div class="menu-header">
        <div class="menu-title">收藏夹</div>
        <button type="button" class="menu-close-btn" @click="closeMenu">
          <IonIcon :icon="closeOutline"/>
        </button>
      </div>

      <div class="menu-body">
        <template v-if="onlineFolders.length > 0">
          <div class="section-header">
            <span class="section-title">在线收藏夹</span>
            <button type="button" class="section-add-btn" aria-label="新建在线收藏夹" @click="emit('add-folder', 'online')">
              <IonIcon :icon="addCircleOutline"/>
            </button>
          </div>
          <div class="folder-list">
            <div
                v-for="folder in onlineFolders"
                :key="folder.id"
                class="folder-item-wrapper"
                :style="{ position: 'relative' }"
            >
              <button
                  type="button"
                  class="folder-item"
                  :class="{ selected: selectedOnlineId === folder.id }"
                  @click="selectOnlineFolder(folder.id)"
              >
                <IonIcon :icon="folderOpenOutline" class="folder-icon"/>
                <span class="folder-name">{{ folder.name }}</span>
                <span v-if="onlineFolderCounts[folder.id] !== undefined" class="folder-count">
                  {{ onlineFolderCounts[folder.id] }}
                </span>
              </button>
              <button
                  type="button"
                  class="folder-more-btn"
                  aria-label="更多操作"
                  @click.stop="toggleContextMenu(folder.id)"
              >
                <IonIcon :icon="ellipsisVertical"/>
              </button>
              <FavoriteFolderContextMenu
                  :visible="openMenuFolderId === folder.id"
                  :is-default-folder="folder.id === '0'"
                  @rename="onContextMenuRename(folder.id, folder.name, true)"
                  @delete="onContextMenuDelete(folder.id, folder.name, true)"
                  @copy="onContextMenuCopy(folder.id, folder.name, true)"
                  @export="onContextMenuExport(folder.id, folder.name, true)"
              />
            </div>
          </div>
        </template>

        <template v-if="onlineFolders.length > 0">
          <div style="margin-top: 24px" />
        </template>

        <div v-if="onlineFolders.length === 0 && offlineFolders.length === 0" class="empty-state">
          暂无收藏夹
        </div>

        <div class="section-header">
          <span class="section-title">离线收藏夹</span>
          <button type="button" class="section-add-btn" aria-label="新建离线收藏夹" @click="emit('add-folder', 'offline')">
            <IonIcon :icon="addCircleOutline"/>
          </button>
        </div>
        <div class="folder-list">
          <div
              v-for="folder in offlineFolders"
              :key="folder.id"
              class="folder-item-wrapper"
              :style="{ position: 'relative' }"
          >
            <button
                type="button"
                class="folder-item"
                :class="{ selected: selectedOfflineId === folder.id }"
                @click="selectOfflineFolder(folder.id)"
            >
              <IonIcon :icon="folderOpenOutline" class="folder-icon"/>
              <span class="folder-name">{{ folder.name }}</span>
              <span class="folder-count">{{ folder.count }}</span>
            </button>
            <button
                type="button"
                class="folder-more-btn"
                aria-label="更多操作"
                @click.stop="toggleContextMenu(folder.id)"
            >
              <IonIcon :icon="ellipsisVertical"/>
            </button>
            <FavoriteFolderContextMenu
                :visible="openMenuFolderId === folder.id"
                :is-default-folder="false"
                @rename="onContextMenuRename(folder.id, folder.name, false)"
                @delete="onContextMenuDelete(folder.id, folder.name, false)"
                @copy="onContextMenuCopy(folder.id, folder.name, false)"
                @export="onContextMenuExport(folder.id, folder.name, false)"
            />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import {computed, onBeforeUnmount, onMounted, ref} from 'vue'
import {IonIcon} from '@ionic/vue'
import {addCircleOutline, closeOutline, ellipsisVertical, folderOpenOutline} from 'ionicons/icons'
import {isDraggingRight, isSnappingClosed, rightDragProgress} from '@/composables/sideMenuState'
import FavoriteFolderContextMenu from '@/components/favorite/FavoriteFolderContextMenu.vue'

interface FolderEntry {
  id: string
  name: string
  count: number
}

const props = defineProps<{
  modelValue: boolean
  onlineFolders: FolderEntry[]
  offlineFolders: FolderEntry[]
  selectedOnlineId: string
  selectedOfflineId: string
  onlineFolderCounts: Record<string, number>
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'select-online-folder': [folderId: string]
  'select-offline-folder': [folderId: string]
  'add-folder': [source: 'online' | 'offline']
  'rename-folder': [payload: { folderId: string; folderName: string; isOnline: boolean }]
  'delete-folder': [payload: { folderId: string; folderName: string; isOnline: boolean }]
  'copy-folder': [payload: { folderId: string; folderName: string; isOnline: boolean }]
  'export-folder': [payload: { folderId: string; folderName: string; isOnline: boolean }]
}>()

const panelRef = ref<HTMLElement | null>(null)
const openMenuFolderId = ref<string | null>(null)

const closeContextMenu = () => {
  openMenuFolderId.value = null
}

const toggleContextMenu = (folderId: string) => {
  openMenuFolderId.value = openMenuFolderId.value === folderId ? null : folderId
}

const onContextMenuRename = (folderId: string, folderName: string, isOnline: boolean) => {
  closeContextMenu()
  emit('rename-folder', { folderId, folderName, isOnline })
}

const onContextMenuDelete = (folderId: string, folderName: string, isOnline: boolean) => {
  closeContextMenu()
  emit('delete-folder', { folderId, folderName, isOnline })
}

const onContextMenuCopy = (folderId: string, folderName: string, isOnline: boolean) => {
  closeContextMenu()
  emit('copy-folder', { folderId, folderName, isOnline })
}

const onContextMenuExport = (folderId: string, folderName: string, isOnline: boolean) => {
  closeContextMenu()
  emit('export-folder', { folderId, folderName, isOnline })
}

const handleClickOutside = (e: Event) => {
  const panel = panelRef.value
  if (!panel || !openMenuFolderId.value) return
  if (!panel.contains(e.target as Node)) {
    closeContextMenu()
  }
}

onMounted(() => {
  document.addEventListener('mousedown', handleClickOutside, true)
  document.addEventListener('touchstart', handleClickOutside, { capture: true })
})

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', handleClickOutside, true)
  document.removeEventListener('touchstart', handleClickOutside, { capture: true })
})

const isVisible = computed(() =>
  props.modelValue || isDraggingRight.value || isSnappingClosed.value
)

const currentProgress = computed(() => {
  if (isDraggingRight.value) return rightDragProgress.value
  if (isSnappingClosed.value) return 0
  return props.modelValue ? 1 : 0
})

const useTransition = computed(() => !isDraggingRight.value)

const panelStyle = computed(() => ({
  transform: `translateX(${(1 - currentProgress.value) * 100}%)`,
  transition: useTransition.value ? 'transform 0.24s cubic-bezier(0.22, 0.61, 0.36, 1)' : 'none',
}))

const backdropStyle = computed(() => ({
  opacity: currentProgress.value > 0 ? currentProgress.value : 0,
  transition: useTransition.value ? 'opacity 0.24s ease' : 'none',
}))

const animateClose = () => {
  if (isDraggingRight.value) return
  if (!props.modelValue) return
  isSnappingClosed.value = true
  rightDragProgress.value = 0
  setTimeout(() => {
    emit('update:modelValue', false)
    isSnappingClosed.value = false
  }, 260)
}

const closeMenu = () => {
  animateClose()
}

const selectOnlineFolder = (folderId: string) => {
  emit('select-online-folder', folderId)
  animateClose()
}

const selectOfflineFolder = (folderId: string) => {
  emit('select-offline-folder', folderId)
  animateClose()
}

defineExpose({ panelRef })
</script>

<style scoped>
.fav-menu-backdrop {
  position: fixed;
  inset: 0;
  z-index: 50;
  background: rgb(16 12 10 / 0.28);
  backdrop-filter: blur(2px);
}

.fav-menu-panel {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(78vw, 320px);
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, #fffaf6 0%, #fff3eb 100%);
  box-shadow: -12px 0 36px rgb(76 42 24 / 0.14);
}

.menu-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: calc(var(--ion-safe-area-top) + 14px) 16px 12px;
  border-bottom: 1px solid rgb(245 210 188 / 0.6);
}

.menu-title {
  font-size: 18px;
  font-weight: 700;
  color: #3a261d;
}

.menu-close-btn {
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
}

.menu-body {
  flex: 1;
  overflow-y: auto;
  padding: 14px 16px calc(18px + var(--ion-safe-area-bottom));
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  margin-top: 10px;
}

.section-title {
  color: #7a5743;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.section-add-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: 999px;
  background: #fff;
  color: #c96d3a;
  font-size: 18px;
  box-shadow: 0 2px 8px rgb(115 67 38 / 0.08);
  cursor: pointer;
  transition: transform 0.12s ease;
}

.section-add-btn:active {
  transform: scale(0.9);
}

.folder-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.folder-item-wrapper {
  position: relative;
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
  transition: background-color 0.16s ease, border-color 0.16s ease;
}

.folder-item:hover,
.folder-item:active {
  background: #fff0e7;
}

.folder-item.selected {
  background: linear-gradient(145deg, #fa9c69, #f28752);
  border-color: transparent;
  color: #fff;
  box-shadow: 0 16px 34px rgb(240 126 73 / 0.26);
}

.folder-icon {
  flex-shrink: 0;
  font-size: 16px;
  color: #c96d3a;
}

.folder-item.selected .folder-icon {
  color: #fff;
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

.folder-item.selected .folder-count {
  background: rgb(255 255 255 / 0.25);
  color: #fff;
}

.folder-more-btn {
  position: absolute;
  right: 8px;
  top: 50%;
  transform: translateY(-50%);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: #8a6048;
  font-size: 18px;
  cursor: pointer;
  transition: background-color 0.14s ease;
}

.folder-more-btn:hover,
.folder-more-btn:active {
  background: rgb(115 67 38 / 0.08);
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 120px;
  color: #9e7d6a;
  font-size: 13px;
}

</style>
