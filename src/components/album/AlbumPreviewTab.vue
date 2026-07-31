<!-- 预览 Tab：分批展开，占位骨架逐张刷出 -->
<template>
  <section class="preview-section">
    <template v-if="totalCount > 0 || loading">
      <div class="preview-grid">
        <div v-for="i in displayCount" :key="'slot-' + i" class="preview-item">
          <template v-if="slots[i - 1]">
            <img
              :src="slots[i - 1]!.dataUrl"
              :alt="'第 ' + i + ' 页'"
              class="preview-thumb"
              @click="$emit('openReader', i)"
            />
          </template>
          <template v-else>
            <div class="skeleton-thumb"/>
          </template>
          <span class="preview-page-num">{{ i }}</span>
        </div>
      </div>

      <div v-if="totalCount > 0" class="preview-footer">
        <button
          class="load-more-btn"
          :class="{'load-more-status': loadingMore || autoLoad || allVisible}"
          :aria-disabled="loadingMore || autoLoad || allVisible"
          @click="onLoadMore"
        >
          <template v-if="loadingMore">
            <ion-spinner name="dots" aria-hidden="true"/>
            <span>加载中...（{{ loadedCount }} / {{ totalCount }}）</span>
          </template>
          <span v-else-if="allVisible">已显示所有图片</span>
          <span v-else-if="autoLoad && loadedCount < visibleCount">
            上滑重新加载缺失图片...（{{ loadedCount }} / {{ visibleCount }}）
          </span>
          <span v-else-if="autoLoad">上滑加载更多...</span>
          <span v-else-if="loadedCount < visibleCount">
            重新加载缺失图片（{{ loadedCount }} / {{ totalCount }}）
          </span>
          <span v-else>查看更多图片（共 {{ totalCount }} 张）</span>
        </button>
        <span class="sr-only" role="status" aria-live="polite" aria-atomic="true">
          {{ statusMessage }}
        </span>
      </div>
    </template>

    <div v-else class="preview-empty">{{ emptyText }}</div>
  </section>
</template>

<script setup lang="ts">
import {computed} from 'vue'
import {IonSpinner} from '@ionic/vue'
import type {PreviewImage} from '@/composables/usePreviewBatches'

defineOptions({name: 'AlbumPreviewTab'})

const props = defineProps<{
  slots: (PreviewImage | null)[]
  totalCount: number
  visibleCount: number
  allVisible: boolean
  autoLoad: boolean
  loading: boolean
  loadingMore: boolean
  loadedCount: number
  emptyText?: string
}>()

const emit = defineEmits<{
  loadMore: []
  openReader: [page: number]
}>()

const DISPLAY_MAX = 20

const displayCount = computed(() => {
  if (props.visibleCount > 0) return Math.min(props.visibleCount, props.totalCount)
  if (props.loading) return DISPLAY_MAX
  return 0
})

const statusMessage = computed(() => {
  if (props.loadingMore) return `正在加载图片，已加载 ${props.loadedCount} / ${props.totalCount}`
  if (props.allVisible) return `已显示所有 ${props.totalCount} 张图片`
  if (props.autoLoad && props.loadedCount < props.visibleCount) {
    return `已启用上滑重新加载缺失图片，已加载 ${props.loadedCount} / ${props.visibleCount}`
  }
  if (props.autoLoad) return '已启用上滑加载更多图片'
  return ''
})

const onLoadMore = () => {
  if (props.loadingMore || props.autoLoad || props.allVisible) return
  emit('loadMore')
}
</script>

<style scoped>
.preview-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.preview-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 120px;
  color: #8a6048;
  font-size: 13px;
}

.preview-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
}

.preview-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.preview-thumb,
.skeleton-thumb {
  width: 100%;
  aspect-ratio: 3 / 4;
  border-radius: 6px;
  overflow: hidden;
}

.preview-thumb {
  object-fit: cover;
  display: block;
  background: linear-gradient(145deg, #f3ded0, #ffece0);
}

.skeleton-thumb {
  background: linear-gradient(145deg, #e8d5c5, #f3e0d2);
  animation: skeleton-pulse 1.5s ease-in-out infinite;
}

@keyframes skeleton-pulse {
  0%,
  100% {
    opacity: 0.4;
  }
  50% {
    opacity: 0.8;
  }
}

.preview-page-num {
  text-align: center;
  color: #8a6048;
  font-size: 10px;
  line-height: 1.4;
}

.preview-footer {
  padding: 8px 0;
  text-align: center;
}

.load-more-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px 20px;
  border: 1px solid #f28752;
  border-radius: 8px;
  background: transparent;
  color: #f28752;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background-color 0.18s ease,
  color 0.18s ease;
}

.load-more-btn.load-more-status {
  border-color: transparent;
  color: #8a6048;
  cursor: default;
}

.load-more-btn:not([aria-disabled='true']):active {
  background: #f28752;
  color: #fff;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

@media (min-width: 680px) {
  .preview-grid {
    grid-template-columns: repeat(4, 1fr);
  }
}
</style>
