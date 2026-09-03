<template>
  <nav
    v-show="isVisible"
    id="favorite-side-menu"
    class="fav-menu-backdrop"
    :class="{ 'fav-menu-pane': isPaneMode }"
    :style="backdropStyle"
    role="navigation"
    aria-label="收藏夹"
    @click.self="closeMenu"
    @touchstart.stop
  >
    <div
      ref="panelRef"
      class="fav-menu-panel"
      :class="{ 'fav-menu-panel--pane': isPaneMode }"
      :style="panelStyle"
    >
      <div class="menu-header">
        <div class="menu-title">
          <span>收藏夹</span>
          <IonSpinner
            v-if="onlineRefreshing || onlineLoading"
            name="circular"
            class="folder-loading-spinner"
            aria-label="正在更新收藏夹"
          />
        </div>
        <button type="button" class="menu-close-btn" aria-label="关闭收藏夹" @click="closeMenu">
          <IonIcon :icon="closeOutline" />
        </button>
      </div>

      <div class="menu-body">
        <div class="section-header">
          <span class="section-title">在线收藏夹</span>
          <button
            type="button"
            class="section-add-btn"
            aria-label="新建在线收藏夹"
            @click="emit('add-folder', 'online')"
          >
            <IonIcon :icon="addCircleOutline" />
          </button>
        </div>

        <div
          v-if="onlineLoading && !hasOnlineData"
          class="folder-status loading-state"
          role="status"
        >
          <IonSpinner name="dots" aria-hidden="true" />
          <span>正在加载收藏夹</span>
        </div>
        <div
          v-else-if="onlineErrorMessage && !hasOnlineData"
          class="folder-status error-state"
          role="alert"
        >
          <span>{{ onlineErrorMessage }}</span>
          <button type="button" class="retry-btn" @click="emit('retry-online')">重试</button>
        </div>
        <div v-else-if="onlineFolders.length > 0" class="folder-list">
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
              <IonIcon :icon="folderOpenOutline" class="folder-icon" />
              <span class="folder-name">{{ folder.name }}</span>
              <span v-if="onlineFolderCounts[folder.id] !== undefined" class="folder-count">
                {{ onlineFolderCounts[folder.id] }}
              </span>
            </button>
            <button
              type="button"
              class="folder-more-btn"
              :class="{ active: isContextMenuOpen(folder.id, true) }"
              aria-label="更多操作"
              :aria-expanded="isContextMenuOpen(folder.id, true)"
              @click.stop="toggleContextMenu(folder, true, $event)"
            >
              <IonIcon :icon="ellipsisVertical" />
            </button>
          </div>
        </div>

        <div v-if="onlineErrorMessage && hasOnlineData" class="refresh-error" role="status">
          <span>更新失败，正在显示上次结果：{{ onlineErrorMessage }}</span>
          <button type="button" class="retry-btn" @click="emit('retry-online')">重试</button>
        </div>

        <div
          v-if="
            !onlineLoading &&
            !onlineErrorMessage &&
            hasOnlineData &&
            onlineFolders.length === 0 &&
            offlineFolders.length === 0
          "
          class="empty-state"
        >
          暂无收藏夹
        </div>

        <div class="section-header">
          <span class="section-title">离线收藏夹</span>
          <button
            type="button"
            class="section-add-btn"
            aria-label="新建离线收藏夹"
            @click="emit('add-folder', 'offline')"
          >
            <IonIcon :icon="addCircleOutline" />
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
              <IonIcon :icon="folderOpenOutline" class="folder-icon" />
              <span class="folder-name">{{ folder.name }}</span>
              <span class="folder-count">{{ folder.count }}</span>
            </button>
            <button
              type="button"
              class="folder-more-btn"
              :class="{ active: isContextMenuOpen(folder.id, false) }"
              aria-label="更多操作"
              :aria-expanded="isContextMenuOpen(folder.id, false)"
              @click.stop="toggleContextMenu(folder, false, $event)"
            >
              <IonIcon :icon="ellipsisVertical" />
            </button>
          </div>
        </div>
      </div>
    </div>
    <CardContextMenu
      :visible="Boolean(contextMenu)"
      :anchor="contextMenu?.anchor ?? null"
      :actions="contextMenuActions"
      @close="closeContextMenu"
      @select="handleContextMenuAction"
    />
  </nav>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { IonIcon, IonSpinner } from '@ionic/vue'
import {
  addCircleOutline,
  closeOutline,
  copyOutline,
  downloadOutline,
  ellipsisVertical,
  folderOpenOutline,
  pencilOutline,
  swapHorizontalOutline,
  trashOutline,
} from 'ionicons/icons'
import { useSideMenuState } from '@/composables/useSideMenuState'
import CardContextMenu from '@/components/common/CardContextMenu.vue'
import type { FolderEntry } from '@/services/JmcomicTypes'
import { OFFLINE_ALL_FOLDER_ID } from '@/services/JmcomicTypes'

type DisplayMode = 'overlay' | 'pane'

defineOptions({ name: 'FavoriteSideMenu' })

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    displayMode?: DisplayMode
    paneOpen?: boolean
    onlineFolders: FolderEntry[]
    offlineFolders: FolderEntry[]
    selectedOnlineId: string
    selectedOfflineId: string
    onlineFolderCounts: Record<string, number>
    onlineHasSuccessfulData?: boolean
    onlineLoading?: boolean
    onlineRefreshing?: boolean
    onlineErrorMessage?: string
  }>(),
  {
    displayMode: 'overlay',
    paneOpen: false,
    onlineHasSuccessfulData: undefined,
    onlineLoading: false,
    onlineRefreshing: false,
    onlineErrorMessage: '',
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'update:paneOpen': [value: boolean]
  'select-online-folder': [folderId: string]
  'select-offline-folder': [folderId: string]
  'add-folder': [source: 'online' | 'offline']
  'retry-online': []
  'rename-folder': [payload: { folderId: string; folderName: string; isOnline: boolean }]
  'delete-folder': [payload: { folderId: string; folderName: string; isOnline: boolean }]
  'move-folder': [payload: { folderId: string; folderName: string; isOnline: boolean }]
  'copy-folder': [payload: { folderId: string; folderName: string; isOnline: boolean }]
  'export-folder': [payload: { folderId: string; folderName: string; isOnline: boolean }]
}>()

const hasOnlineData = computed(
  () => props.onlineHasSuccessfulData ?? (!props.onlineLoading && !props.onlineErrorMessage),
)

const { isDraggingRight, isSnappingClosed, rightDragProgress } = useSideMenuState()

const isPaneMode = computed(() => props.displayMode === 'pane')

const panelRef = ref<HTMLElement | null>(null)
interface ContextMenuState {
  folder: FolderEntry
  isOnline: boolean
  anchor: HTMLElement
}

const contextMenu = ref<ContextMenuState | null>(null)

const closeContextMenu = () => {
  contextMenu.value = null
}

const isContextMenuOpen = (folderId: string, isOnline: boolean) =>
  contextMenu.value?.folder.id === folderId && contextMenu.value.isOnline === isOnline

const toggleContextMenu = (folder: FolderEntry, isOnline: boolean, event: MouseEvent) => {
  const anchor = event.currentTarget as HTMLElement
  if (contextMenu.value?.anchor === anchor) {
    closeContextMenu()
    return
  }
  contextMenu.value = { folder, isOnline, anchor }
}

const contextMenuActions = computed(() => {
  const menu = contextMenu.value
  if (!menu) return []
  const isDefaultFolder = menu.isOnline
    ? menu.folder.id === '0'
    : menu.folder.id === OFFLINE_ALL_FOLDER_ID
  return [
    ...(isDefaultFolder ? [] : [{ id: 'rename', label: '重命名', icon: pencilOutline }]),
    { id: 'move', label: '移动', icon: swapHorizontalOutline },
    { id: 'copy', label: '复制', icon: copyOutline },
    ...(isDefaultFolder ? [] : [{ id: 'delete', label: '删除', icon: trashOutline, danger: true }]),
    { id: 'export', label: '导出', icon: downloadOutline },
  ]
})

const handleContextMenuAction = (action: string) => {
  const menu = contextMenu.value
  if (!menu) return
  const { folder, isOnline } = menu
  if (action === 'rename') onContextMenuRename(folder.id, folder.name, isOnline)
  else if (action === 'move') onContextMenuMove(folder.id, folder.name, isOnline)
  else if (action === 'copy') onContextMenuCopy(folder.id, folder.name, isOnline)
  else if (action === 'delete') onContextMenuDelete(folder.id, folder.name, isOnline)
  else if (action === 'export') onContextMenuExport(folder.id, folder.name, isOnline)
}

const onContextMenuRename = (folderId: string, folderName: string, isOnline: boolean) => {
  closeContextMenu()
  emit('rename-folder', { folderId, folderName, isOnline })
}

const onContextMenuDelete = (folderId: string, folderName: string, isOnline: boolean) => {
  closeContextMenu()
  emit('delete-folder', { folderId, folderName, isOnline })
}

const onContextMenuMove = (folderId: string, folderName: string, isOnline: boolean) => {
  closeContextMenu()
  emit('move-folder', { folderId, folderName, isOnline })
}

const onContextMenuCopy = (folderId: string, folderName: string, isOnline: boolean) => {
  closeContextMenu()
  emit('copy-folder', { folderId, folderName, isOnline })
}

const onContextMenuExport = (folderId: string, folderName: string, isOnline: boolean) => {
  closeContextMenu()
  emit('export-folder', { folderId, folderName, isOnline })
}

const isVisible = computed(() =>
  isPaneMode.value
    ? props.paneOpen
    : props.modelValue || isDraggingRight.value || isSnappingClosed.value,
)

const currentProgress = computed(() => {
  if (isPaneMode.value) return props.paneOpen ? 1 : 0
  if (isDraggingRight.value) return rightDragProgress.value
  if (isSnappingClosed.value) return 0
  return props.modelValue ? 1 : 0
})

const useTransition = computed(() => !isDraggingRight.value)

const panelStyle = computed(() => ({
  transform: isPaneMode.value ? 'none' : `translateX(${(1 - currentProgress.value) * 100}%)`,
  transition: isPaneMode.value
    ? 'none'
    : useTransition.value
      ? 'transform 0.24s cubic-bezier(0.22, 0.61, 0.36, 1)'
      : 'none',
}))

const backdropStyle = computed(() =>
  isPaneMode.value
    ? {}
    : {
        opacity: currentProgress.value > 0 ? currentProgress.value : 0,
        transition: useTransition.value ? 'opacity 0.24s ease' : 'none',
      },
)

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
  closeContextMenu()
  if (isPaneMode.value) {
    emit('update:paneOpen', false)
    return
  }
  animateClose()
}

const selectOnlineFolder = (folderId: string) => {
  emit('select-online-folder', folderId)
  if (!isPaneMode.value) animateClose()
}

const selectOfflineFolder = (folderId: string) => {
  emit('select-offline-folder', folderId)
  if (!isPaneMode.value) animateClose()
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

.fav-menu-pane {
  position: relative;
  inset: auto;
  flex: 0 0 280px;
  width: 280px;
  min-width: 280px;
  height: 100%;
  overflow: hidden;
  background: linear-gradient(180deg, #fffaf6 0%, #fff3eb 100%);
  box-shadow: -12px 0 36px rgb(76 42 24 / 0.14);
  backdrop-filter: none;
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

.fav-menu-panel--pane {
  position: relative;
  inset: auto;
  width: 100%;
  height: 100%;
  box-shadow: none;
}

.menu-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: calc(var(--ion-safe-area-top) + 14px) 16px 12px;
  border-bottom: 1px solid rgb(245 210 188 / 0.6);
}

.menu-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 18px;
  font-weight: 700;
  color: #3a261d;
}

.folder-loading-spinner {
  flex: 0 0 20px;
  width: 20px;
  height: 20px;
  margin-left: 8px;
  color: #e8843c;
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
  cursor: pointer;
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
  background: transparent;
  color: #c96d3a;
  font-size: 18px;
  cursor: pointer;
  transition: transform 0.12s ease;
}

.section-add-btn:active {
  transform: scale(0.8);
  background: #ffece0;
  box-shadow: 0 2px 8px rgb(115 67 38 / 0.2);
}

.folder-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.folder-status,
.refresh-error {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 56px;
  color: #9e7d6a;
  font-size: 13px;
}

.error-state {
  flex-direction: column;
  color: #a6543c;
}

.refresh-error {
  justify-content: flex-start;
  min-height: 36px;
  padding: 4px 8px 0;
  color: #a6543c;
  font-size: 12px;
}

.retry-btn {
  border: 0;
  border-radius: 999px;
  padding: 4px 10px;
  background: #fff0e7;
  color: #b55e32;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
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
  padding: 12px 44px 12px 14px;
  border: 1px solid rgb(245 210 188 / 0.72);
  border-radius: 20px;
  background: #fff;
  color: #3a261d;
  font: inherit;
  font-size: 14px;
  font-weight: 700;
  text-align: left;
  box-shadow: 0 12px 28px rgb(115 67 38 / 0.08);
  transition:
    background-color 0.16s ease,
    border-color 0.16s ease;
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
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #8a6048;
  font-size: 18px;
  cursor: pointer;
}

.folder-more-btn:active,
.folder-more-btn.active {
  background: rgb(250 156 105 / 0.15);
  color: #c96d3a;
}

.menu-close-btn:focus-visible,
.section-add-btn:focus-visible,
.folder-item:focus-visible,
.folder-more-btn:focus-visible,
.retry-btn:focus-visible {
  outline: 3px solid rgb(201 109 58 / 0.45);
  outline-offset: 2px;
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
