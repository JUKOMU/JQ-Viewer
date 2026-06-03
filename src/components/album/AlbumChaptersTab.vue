<!-- 章节 Tab -->
<template>
  <section class="chapters-section">
    <div v-if="photoMetas.length" class="chapter-grid">
      <div
        v-for="meta in photoMetas"
        :key="meta.id"
        class="chapter-item"
      >
        <button
          type="button"
          class="chapter-card"
          :class="{
            selected: selectedChapterId === meta.id,
            downloaded: chapterDownloadStatuses.get(meta.id) === 'completed',
          }"
          @click="$emit('select-chapter', meta.id)"
        >
          <Transition name="action-swap" mode="out-in">
            <span
              v-if="!(showActions && selectedChapterId === meta.id)"
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
          v-if="chapterDownloadStatuses.get(meta.id) === 'completed' || chapterPdfStatuses.get(meta.id)"
          class="chapter-source-row"
        >
          <span v-if="chapterDownloadStatuses.get(meta.id) === 'completed'" class="chapter-source-chip source-chip-image">
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
defineOptions({name: 'AlbumChaptersTab'})

const props = defineProps<{
  photoMetas: PhotoMeta[]
  selectedChapterId: string
  loading?: boolean
  showActions: boolean
  chapterDownloadStatuses: Map<string, string>
  chapterPdfStatuses: Map<string, boolean>
}>()
defineEmits<{
  'select-chapter': [chapterId: string]
  'download-chapter': [chapterId: string]
  'dismiss-actions': []
}>()
import {IonIcon} from '@ionic/vue'
import {arrowBack, cloudDownloadOutline, documentOutline, imageOutline} from 'ionicons/icons'
import type {PhotoMeta} from '@/services/JmcomicTypes'

const isDownloadDisabled = (chapterId: string): boolean => {
  const status = props.chapterDownloadStatuses.get(chapterId)
  return (
    status === 'queued' || status === 'downloading' || status === 'paused' || status === 'completed'
  )
}
</script>

<style scoped>
.chapter-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}

.chapter-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  width: 100%;
  min-height: 76px;
  box-sizing: border-box;
  padding: 14px 10px;
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

.chapter-num {
  font-size: 11px;
  font-weight: 700;
  color: #a07858;
  line-height: 28px; /* 与 .action-btn 高度一致，消除切换时高度跳变 */
}

.chapter-card.selected .chapter-num {
  color: #e07030;
}

.chapter-card.downloaded .chapter-num {
  color: #3a8c52;
}

.chapter-card.selected.downloaded .chapter-num {
  color: #e07030;
}

.chapter-title {
  font-size: 11px;
  line-height: 1.3;
  min-height: 29px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
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
