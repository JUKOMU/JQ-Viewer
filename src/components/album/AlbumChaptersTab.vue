<!-- 章节 Tab -->
<template>
  <section class="chapters-section">
    <!-- 操作栏 -->
    <div v-if="photoMetas.length" class="chapters-toolbar">
      <div class="toolbar-left">
        <template v-if="batchMode">
          <button type="button" class="toolbar-btn" @click="selectAll">全选</button>
          <button type="button" class="toolbar-btn" @click="invertSelection">反选</button>
          <button type="button" class="toolbar-btn" @click="clearSelection">取消</button>
        </template>
      </div>
      <div class="toolbar-right">
        <button
          v-if="batchMode"
          type="button"
          class="toolbar-btn toolbar-btn-icon"
          @click="exitBatchMode"
        >
          <ion-icon :icon="closeOutline"/>
        </button>
        <button
          type="button"
          class="toolbar-btn toolbar-btn-icon"
          @click="toggleDisplayMode"
        >
          <ion-icon :icon="displayMode === 'grid' ? listOutline : gridOutline"/>
        </button>
        <button
          type="button"
          class="toolbar-btn"
          :class="{
            'toolbar-btn-primary': !batchMode || hasSelection,
            disabled: batchMode && !hasSelection,
          }"
          @click="onBatchDownloadClick"
        >
          {{ batchMode ? `开始下载(${selectedIds.size})` : '批量下载' }}
        </button>
      </div>
    </div>

    <!-- 列表模式 -->
    <div v-if="photoMetas.length && displayMode === 'list'" class="chapter-list">
      <div
        v-for="meta in photoMetas"
        :key="meta.id"
        class="list-item"
        :class="{
          selected: selectedChapterId === meta.id && !batchMode,
          downloaded: chapterDownloadStatuses.get(meta.id) === 'completed',
          'batch-selected': batchMode && selectedIds.has(meta.id),
          'batch-disabled': batchMode && isDownloadDisabled(meta.id),
        }"
        @click="onCardClick(meta.id)"
      >
        <span class="list-item-text">第{{ meta.sortOrder }}话  {{ meta.title }}</span>
        <span
          v-if="chapterDownloadStatuses.get(meta.id) === 'completed' || chapterPdfStatuses.get(meta.id)"
          class="list-item-chips"
        >
          <span v-if="chapterDownloadStatuses.get(meta.id) === 'completed'"
                class="chapter-source-chip source-chip-image">
            <ion-icon :icon="imageOutline"/>
          </span>
          <span v-if="chapterPdfStatuses.get(meta.id)" class="chapter-source-chip source-chip-pdf">
            <ion-icon :icon="documentOutline"/>
          </span>
        </span>
        <span
          v-if="batchMode"
          class="batch-check-inline"
          :class="{ checked: selectedIds.has(meta.id), disabled: isDownloadDisabled(meta.id) }"
          @click.stop="toggleSelect(meta.id)"
        >
          <ion-icon v-if="selectedIds.has(meta.id)" :icon="checkmark"/>
          <span v-else class="check-empty"/>
        </span>
      </div>
    </div>

    <!-- 网格模式 -->
    <div v-else-if="photoMetas.length" class="chapter-grid">
      <div
        v-for="meta in photoMetas"
        :key="meta.id"
        class="chapter-item"
      >
        <div class="card-wrapper">
          <button
            type="button"
            class="chapter-card"
            :class="{
              selected: selectedChapterId === meta.id && !batchMode,
              downloaded: chapterDownloadStatuses.get(meta.id) === 'completed',
              'batch-selected': batchMode && selectedIds.has(meta.id),
            }"
            @click="onCardClick(meta.id)"
          >
            <Transition name="action-swap" mode="out-in">
              <span
                v-if="!(showActions && selectedChapterId === meta.id && !batchMode)"
                key="num"
                class="chapter-num"
              >
                第{{ meta.sortOrder }}话
              </span>
              <div v-else key="actions" class="chapter-actions">
                <button
                  type="button"
                  class="action-btn"
                  :class="{ disabled: isDownloadDisabled(meta.id) }"
                  :disabled="isDownloadDisabled(meta.id)"
                  @click.stop="$emit('download-chapter', meta.id)"
                >
                  <ion-icon :icon="cloudDownloadOutline"/>
                </button>
                <button type="button" class="action-btn" @click.stop="$emit('dismiss-actions')">
                  <ion-icon :icon="arrowBack"/>
                </button>
              </div>
            </Transition>
            <span class="chapter-title">{{ meta.title }}</span>
          </button>
          <span
            v-if="batchMode"
            class="batch-check"
            :class="{ checked: selectedIds.has(meta.id), disabled: isDownloadDisabled(meta.id) }"
            @click.stop="toggleSelect(meta.id)"
          >
            <ion-icon v-if="selectedIds.has(meta.id)" :icon="checkmark"/>
            <span v-else class="check-empty"/>
          </span>
        </div>
        <span
          v-if="chapterDownloadStatuses.get(meta.id) === 'completed' || chapterPdfStatuses.get(meta.id)"
          class="chapter-source-row"
        >
          <span v-if="chapterDownloadStatuses.get(meta.id) === 'completed'"
                class="chapter-source-chip source-chip-image">
            <ion-icon :icon="imageOutline"/>
          </span>
          <span v-if="chapterPdfStatuses.get(meta.id)" class="chapter-source-chip source-chip-pdf">
            <ion-icon :icon="documentOutline"/>
          </span>
        </span>
      </div>
    </div>
    <div v-else-if="loading" class="chapter-grid">
      <div v-for="n in 6" :key="n" class="skeleton-card">
        <div class="sk-line sk-line--short"/>
        <div class="sk-line"/>
      </div>
    </div>
    <div v-else class="chapters-empty">暂无章节</div>
  </section>
</template>

<script setup lang="ts">
import {computed, ref} from 'vue'
import {IonIcon} from '@ionic/vue'
import {
  arrowBack,
  checkmark,
  closeOutline,
  cloudDownloadOutline,
  documentOutline,
  gridOutline,
  imageOutline,
  listOutline,
} from 'ionicons/icons'
import type {PhotoMeta} from '@/services/JmcomicTypes'

defineOptions({name: 'AlbumChaptersTab'})
const props = defineProps<{
  photoMetas: PhotoMeta[]
  selectedChapterId: string
  loading?: boolean
  showActions: boolean
  chapterDownloadStatuses: Map<string, string>
  chapterPdfStatuses: Map<string, boolean>
}>()

const emit = defineEmits<{
  'select-chapter': [chapterId: string]
  'download-chapter': [chapterId: string]
  'dismiss-actions': []
  'batch-download': [chapterIds: string[]]
}>()

const isDownloadDisabled = (chapterId: string): boolean => {
  const status = props.chapterDownloadStatuses.get(chapterId)
  return (
    status === 'queued' || status === 'downloading' || status === 'paused' || status === 'completed'
  )
}

// ---- 批量选择 ----
const batchMode = ref(false)
const selectedIds = ref<Set<string>>(new Set())
const hasSelection = computed(() => selectedIds.value.size > 0)

const enterBatchMode = () => {
  batchMode.value = true
  selectedIds.value = new Set()
}

const exitBatchMode = () => {
  batchMode.value = false
  selectedIds.value = new Set()
}

const toggleSelect = (chapterId: string) => {
  if (isDownloadDisabled(chapterId)) return
  const next = new Set(selectedIds.value)
  if (next.has(chapterId)) {
    next.delete(chapterId)
  } else {
    next.add(chapterId)
  }
  selectedIds.value = next
}

const selectAll = () => {
  selectedIds.value = new Set(
    props.photoMetas.filter((m) => !isDownloadDisabled(m.id)).map((m) => m.id),
  )
}

const invertSelection = () => {
  const next = new Set<string>()
  for (const meta of props.photoMetas) {
    const wasSelected = selectedIds.value.has(meta.id)
    const disabled = isDownloadDisabled(meta.id)
    if (disabled) continue
    if (!wasSelected) next.add(meta.id)
  }
  selectedIds.value = next
}

const clearSelection = () => {
  selectedIds.value = new Set()
}

const onBatchDownloadClick = () => {
  if (!batchMode.value) {
    enterBatchMode()
    return
  }
  if (!hasSelection.value) return
  emit('batch-download', [...selectedIds.value])
  exitBatchMode()
}

const onCardClick = (chapterId: string) => {
  if (batchMode.value) {
    toggleSelect(chapterId)
  } else {
    emit('select-chapter', chapterId)
  }
}

// ---- 显示模式 ----
const displayMode = ref<'grid' | 'list'>('grid')

const toggleDisplayMode = () => {
  displayMode.value = displayMode.value === 'grid' ? 'list' : 'grid'
}
</script>

<style scoped>
/* ---- 操作栏 ---- */
.chapters-toolbar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 0 10px;
}

.toolbar-left {
  display: flex;
  gap: 4px;
  flex: 1;
}

.toolbar-right {
  display: flex;
  gap: 4px;
  margin-left: auto;
}

.toolbar-btn {
  height: 28px;
  padding: 0 10px;
  border: 1px solid rgb(245 210 188 / 0.5);
  border-radius: 6px;
  background: #fffaf6;
  color: #8a6048;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  transition: background-color 0.18s ease, opacity 0.18s ease;
  white-space: nowrap;
}

.toolbar-btn:active {
  background: #ffe4d1;
}

.toolbar-btn-icon {
  width: 28px;
  padding: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
}

.toolbar-btn-primary {
  background: linear-gradient(145deg, #fa9c69, #f28752);
  color: #fff;
  border-color: transparent;
}

.toolbar-btn-primary:active {
  background: linear-gradient(145deg, #f28752, #e07030);
}

.toolbar-btn-primary.disabled,
.toolbar-btn.disabled {
  background: #d0c7c0;
  color: #9a9088;
  border-color: transparent;
  pointer-events: none;
}

/* ---- 列表模式 ---- */
.chapter-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.list-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid rgb(245 210 188 / 0.5);
  border-radius: 8px;
  background: #fffaf6;
  cursor: pointer;
  transition: background-color 0.18s ease, border-color 0.18s ease;
}

.list-item:active {
  background: #fff0e7;
}

.list-item.selected {
  border-color: #fa9c69;
}

.list-item.downloaded {
  background: #f0faf3;
  border-color: #6dbf87;
}

.list-item.batch-selected {
  border-color: #fa9c69;
  background: #fff0e7;
}

.list-item.batch-disabled {
  opacity: 0.45;
}

.list-item-text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: #5a3d2e;
  min-width: 0;
}

.list-item-chips {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}

.batch-check-inline {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  flex-shrink: 0;
  cursor: pointer;
  color: #d0c7c0;
  font-size: 20px;
  border-radius: 50%;
}

.batch-check-inline.checked {
  color: #ffffff;
}

.batch-check-inline.checked ion-icon {
  background-color: #fa9c69;
  border-radius: 50%;
  font-size: 16px;
  padding: 1px;
}

.batch-check-inline.disabled {
  opacity: 0.3;
  pointer-events: none;
}

.check-empty {
  width: 18px;
  height: 18px;
  border: 2px solid #d0c7c0;
  border-radius: 50%;
  box-sizing: border-box;
}

/* ---- 网格模式 ---- */
.chapter-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}

.card-wrapper {
  position: relative;
  width: 85%;
}

.chapter-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  width: 100%;
  min-height: 76px;
  box-sizing: border-box;
  padding: 10px 8px;
  border: 1px solid rgb(245 210 188 / 0.7);
  border-radius: 12px;
  background: #fffaf6;
  color: #5a3d2e;
  text-align: center;
  transition: background-color 0.18s ease,
  border-color 0.18s ease;
}

.chapter-card.selected {
  background: #fff0e7;
  border-color: #fa9c69;
}

.chapter-card.downloaded {
  background: #f0faf3;
  border-color: #6dbf87;
}

.chapter-card.selected.downloaded {
  background: #fff0e7;
  border-color: #fa9c69;
}

.chapter-card.batch-selected {
  background: #fff0e7;
  border-color: #fa9c69;
  box-shadow: 0 0 0 1px #fa9c69;
}

.chapter-num {
  font-size: 11px;
  font-weight: 700;
  color: #a07858;
  line-height: 28px; /* 与 .action-btn 高度一致，消除切换时高度跳变 */
}

.chapter-card.selected .chapter-num {
  color: #5e8afd;
}

.chapter-card.downloaded .chapter-num {
  color: #3a8c52;
}

.chapter-card.selected.downloaded .chapter-num {
  color: #e07030;
}

.chapter-card.batch-selected .chapter-num {
  color: #e07030;
}

.chapter-title {
  font-size: 11px;
  line-height: 1.3;
  min-height: 29px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-box-orient: vertical;
}

.chapter-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 5px;
}

.chapter-source-row {
  display: flex;
  justify-content: center;
  gap: 5px;
  min-height: 18px;
}

.chapter-source-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border: 1px solid #d0c7c0;
  border-radius: 6px;
  background: #f2f0ee;
  color: #9a9088;
  font-size: 11px;
}

.source-chip-image {
  border-color: #4a9fd8;
  background: #e8f4fc;
  color: #2d7ab5;
}

.source-chip-pdf {
  border-color: #e05555;
  background: #fdf0f0;
  color: #c03939;
}

/* ---- 批量勾选框（网格模式） ---- */
.batch-check {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: #d0c7c0;
  font-size: 20px;
  z-index: 999;
  transition: color 0.18s ease;
  border-radius: 50%;
}

.batch-check.checked {
  color: #ffffff;
}

.batch-check.checked ion-icon {
  background-color: #fa9c69;
  border-radius: 50%;
  font-size: 16px;
  padding: 1px;
}

.batch-check.disabled {
  opacity: 0.3;
  pointer-events: none;
}

/* ---- 操作按钮 ---- */
.chapter-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 50%;
  background: #ffe4d1;
  color: #e07030;
  font-size: 16px;
  transition: background-color 0.18s ease,
  opacity 0.18s ease,
  transform 0.12s ease;
  cursor: pointer;
}

.action-btn:active {
  transform: scale(0.92);
}

.action-btn.disabled,
.action-btn:disabled {
  opacity: 0.3;
  pointer-events: none;
}

/* action-swap 过渡 */
.action-swap-enter-active,
.action-swap-leave-active {
  transition: opacity 0.2s ease;
}

.action-swap-enter-from,
.action-swap-leave-to {
  opacity: 0;
}

.chapters-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 120px;
  color: #8a6048;
  font-size: 13px;
}

/* 骨架卡片 */
.skeleton-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 14px 10px;
  border: 1px solid rgb(245 210 188 / 0.3);
  border-radius: 12px;
  background: #fffaf6;
}

.skeleton-card .sk-line {
  height: 12px;
  width: 70%;
  border-radius: 6px;
  background: linear-gradient(90deg, #f3ded0 25%, #ffece0 50%, #f3ded0 75%);
  background-size: 200% 100%;
  animation: shimmer 1.4s ease infinite;
}

.skeleton-card .sk-line--short {
  width: 45%;
  height: 11px;
}

@keyframes shimmer {
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}

@media (min-width: 680px) {
  .chapter-grid {
    grid-template-columns: repeat(3, 1fr);
  }
}
</style>
