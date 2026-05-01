<!-- 预览 Tab：首页仅显示前 20 张，占位骨架逐张刷出 -->
<template>
  <section class="preview-section">
    <template v-if="totalCount > 0 || loading">
      <div class="preview-grid">
        <div
          v-for="i in displayCount"
          :key="'slot-' + i"
          class="preview-item"
        >
          <template v-if="slotMap[i - 1]">
            <img
              :src="slotMap[i - 1]!.dataUrl"
              :alt="'第 ' + i + ' 页'"
              class="preview-thumb"
            />
          </template>
          <template v-else>
            <div class="skeleton-thumb" />
          </template>
          <span class="preview-page-num">{{ i }}</span>
        </div>
      </div>

      <div v-if="totalCount > 0" class="preview-footer">
        <template v-if="displayMountedCount >= totalCount">
          <p class="footer-text">已显示所有图片</p>
        </template>
        <template v-else>
          <button class="load-more-btn" @click="$emit('loadMore')">
            查看更多图片（共 {{ totalCount }} 张）
          </button>
        </template>
      </div>
    </template>

    <div v-else class="preview-empty">{{ emptyText }}</div>
  </section>
</template>

<script setup lang="ts">
import {computed} from 'vue'

interface PreviewImage {
  sortOrder: number
  dataUrl: string
}

const props = defineProps<{
  images: PreviewImage[]
  totalCount: number
  loading: boolean
  emptyText?: string
}>()

defineEmits<{
  loadMore: []
}>()

const DISPLAY_MAX = 20

// 槽位数：totalCount 已知时取 min(totalCount,20)，加载中未知时取 20
const displayCount = computed(() => {
  if (props.totalCount > 0) return Math.min(props.totalCount, DISPLAY_MAX)
  if (props.loading) return DISPLAY_MAX
  return 0
})

// 已挂载图片数（用于判断是否全部显示）
const displayMountedCount = computed(() => Math.min(props.totalCount, DISPLAY_MAX))

// 将 images 按 sortOrder 映射到槽位数组
const slotMap = computed(() => {
  const map = new Array<PreviewImage | null>(props.totalCount).fill(null)
  for (const img of props.images) {
    const idx = img.sortOrder - 1
    if (idx >= 0 && idx < props.totalCount) {
      map[idx] = img
    }
  }
  return map
})
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
  0%, 100% { opacity: 0.4; }
  50% { opacity: 0.8; }
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

.footer-text {
  margin: 0;
  color: #8a6048;
  font-size: 12px;
}

.load-more-btn {
  display: inline-block;
  padding: 8px 20px;
  border: 1px solid #f28752;
  border-radius: 8px;
  background: transparent;
  color: #f28752;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background-color 0.18s ease, color 0.18s ease;
}

.load-more-btn:active {
  background: #f28752;
  color: #fff;
}

@media (min-width: 680px) {
  .preview-grid {
    grid-template-columns: repeat(4, 1fr);
  }
}
</style>
