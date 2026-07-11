<!-- 本子信息 Tab -->
<template>
  <section class="info-section">
    <template v-if="album">
      <!-- 交互栏 -->
      <div class="action-bar">
        <button
          type="button"
          class="action-btn"
          :class="{ active: album.isLiked }"
          :disabled="actionBusy.like"
          @click="$emit('toggle-like')"
        >
          <ion-icon :icon="heart"/>
          <span class="action-label">点赞 {{ album.likes }}</span>
        </button>
        <button
          type="button"
          class="action-btn"
          :class="{ active: album.isFavorite }"
          :disabled="actionBusy.favorite"
          @click="$emit('toggle-favorite')"
        >
          <ion-icon :icon="bookmark"/>
          <span class="action-label">收藏</span>
        </button>
        <div class="download-col">
          <button
            type="button"
            class="action-btn"
            :class="downloadClass"
            @click="$emit('download')"
          >
            <ion-icon :icon="downloadIcon"/>
            <span class="action-label">{{ downloadLabel }}</span>
          </button>
          <span class="source-state-row">
            <span class="source-state-chip source-chip-image" :class="{ active: imageAvailable }">
              <ion-icon :icon="imageOutline"/>
            </span>
            <span class="source-state-chip source-chip-pdf" :class="{ active: pdfAvailable }">
              <ion-icon :icon="documentOutline"/>
            </span>
          </span>
        </div>
      </div>

      <!-- 信息列表 -->
      <div class="info-list">
        <div class="info-row">
          <span class="info-label">ID</span>
          <span class="info-value clickable" @click="copyText(String(album.id))">
            {{ album.id }}
            <ion-icon class="copy-icon" :icon="copyOutline"/>
          </span>
        </div>
        <div class="info-row">
          <span class="info-label">标题</span>
          <span class="info-value clickable" @click="copyText(album.title)">
            {{ album.title }}
            <ion-icon class="copy-icon" :icon="copyOutline"/>
          </span>
        </div>
        <div v-if="album.description" class="info-row">
          <span class="info-label">描述</span>
          <div class="info-value">
            <p class="desc-text" :class="{ expanded: descExpanded }">{{ album.description }}</p>
            <button
              v-if="album.description.length > 200"
              type="button"
              class="desc-toggle"
              @click="descExpanded = !descExpanded"
            >
              {{ descExpanded ? '收起' : '展开' }}
            </button>
          </div>
        </div>
        <div v-if="album.authors.length" class="info-row">
          <span class="info-label">作者</span>
          <div class="info-tags">
            <span v-for="author in album.authors" :key="author" class="tag tag-clickable" @click="searchByTag(author)">{{
                author
              }}</span>
          </div>
        </div>
        <div v-if="album.tags.length" class="info-row">
          <span class="info-label">标签</span>
          <div class="info-tags">
            <span v-for="tag in album.tags" :key="tag" class="tag tag-clickable" @click="searchByTag(tag)">{{
                tag
              }}</span>
          </div>
        </div>
        <div v-if="album.actors.length" class="info-row">
          <span class="info-label">登场人物</span>
          <div class="info-tags">
            <span v-for="actor in album.actors" :key="actor" class="tag">{{ actor }}</span>
          </div>
        </div>
        <div v-if="album.works.length" class="info-row">
          <span class="info-label">作品</span>
          <div class="info-tags">
            <span v-for="work in album.works" :key="work" class="tag">{{ work }}</span>
          </div>
        </div>
        <div v-if="album.category" class="info-row">
          <span class="info-label">分类</span>
          <span class="info-value">
            {{ album.category.title }}
            <template v-if="album.subCategory"> / {{ album.subCategory.title }}</template>
          </span>
        </div>
        <div v-if="album.addTime" class="info-row">
          <span class="info-label">发布日期</span>
          <span class="info-value">{{ formatDate(album.addTime) }}</span>
        </div>
        <div v-if="album.views" class="info-row">
          <span class="info-label">浏览</span>
          <span class="info-value">{{ album.views }}</span>
        </div>
      </div>

      <!-- 相关作品 -->
      <div v-if="album.relatedAlbums.length" class="related-section">
        <h3 class="section-title">相关作品</h3>
        <div
          ref="relatedScrollRef"
          class="related-scroll"
          @touchstart.passive="onRelatedScrollTouchStart"
          @touchmove="onRelatedScrollTouchMove"
          @touchend="onRelatedScrollTouchEnd"
        >
          <div
            v-for="related in album.relatedAlbums"
            :key="related.id"
            class="related-card"
            @click="$emit('navigate-album', related)"
          >
            <div class="related-cover-wrap">
              <img :src="related.coverUrl" :alt="related.title" class="related-cover"/>
            </div>
            <p class="related-title">{{ related.title }}</p>
          </div>
        </div>
      </div>
    </template>

    <!-- 骨架屏 -->
    <div v-else class="skeleton">
      <div v-for="n in 5" :key="n" class="sk-row">
        <div class="sk-line sk-line--short"/>
        <div class="sk-line"/>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import {computed, ref} from 'vue'
import {useRouter} from 'vue-router'
import {IonIcon, menuController} from '@ionic/vue'
import {
  bookmark,
  checkmarkCircleOutline,
  cloudDownloadOutline,
  copyOutline,
  downloadOutline,
  heart,
  documentOutline,
  imageOutline,
  refreshOutline,
  timeOutline,
} from 'ionicons/icons'
import type {AlbumDetail, AlbumMeta} from '@/services/JmcomicTypes'
import {showToast} from '@/services/JmcomicService'

defineOptions({name: 'AlbumInfoTab'})

const props = defineProps<{
  album: AlbumDetail | null
  actionBusy: { like: boolean; favorite: boolean }
  downloadStatus?: string
  imageAvailable: boolean
  pdfAvailable: boolean
}>()

defineEmits<{
  'toggle-like': []
  'toggle-favorite': []
  download: []
  'navigate-album': [related: AlbumMeta]
}>()

const router = useRouter()

function formatDate(raw: string): string {
  const ts = parseInt(raw, 10)
  if (!ts) return raw
  const d = new Date(ts * 1000)
  if (isNaN(d.getTime())) return raw
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const descExpanded = ref(false)

const copyText = async (text: string) => {
  try {
    await navigator.clipboard.writeText(text)
    await showToast('已复制', 'success')
  } catch {
    // clipboard 不可用，静默失败
  }
}

const searchByTag = (keyword: string) => {
  void router.push({path: '/search', query: {keyword}})
}

// ---- 相关作品横向滑动 vs 侧边栏手势 ----
const relatedScrollRef = ref<HTMLElement | null>(null)
let tsx = 0
let tsy = 0
let tsScrollLeft = 0

function onRelatedScrollTouchStart(e: TouchEvent) {
  const t = e.touches[0]
  tsx = t.clientX
  tsy = t.clientY
  tsScrollLeft = relatedScrollRef.value?.scrollLeft ?? 0
  void menuController.swipeGesture(false)
}

function onRelatedScrollTouchMove(e: TouchEvent) {
  const t = e.touches[0]
  const dx = t.clientX - tsx
  const dy = t.clientY - tsy

  if (Math.abs(dx) < Math.abs(dy)) return

  if (dx > 60 && tsScrollLeft === 0) {
    void menuController.swipeGesture(true)
    void menuController.open()
    void menuController.swipeGesture(false)
  }
}

function onRelatedScrollTouchEnd() {
  void menuController.swipeGesture(true)
}

const downloadClass = computed(() => {
  if (!props.downloadStatus) return {}
  return {[`status-${props.downloadStatus}`]: true}
})

const downloadLabel = computed(() => {
  switch (props.downloadStatus) {
    case 'queued':
      return '队列中'
    case 'downloading':
      return '下载中'
    case 'paused':
      return '已暂停'
    case 'completed':
      return '已下载'
    case 'failed':
      return '重试'
    default:
      return '下载'
  }
})

const downloadIcon = computed(() => {
  switch (props.downloadStatus) {
    case 'queued':
      return timeOutline
    case 'downloading':
      return cloudDownloadOutline
    case 'paused':
      return timeOutline
    case 'completed':
      return checkmarkCircleOutline
    case 'failed':
      return refreshOutline
    default:
      return downloadOutline
  }
})
</script>

<style scoped>
/* 交互栏 */
.action-bar {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 12px;
  margin-bottom: 16px;
  padding-bottom: 28px;
}

.action-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 10px 8px;
  border: 1px solid rgb(245 210 188 / 0.7);
  border-radius: 12px;
  background: #fffaf6;
  color: #785947;
  font-size: 11px;
  transition: background-color 0.18s ease,
  color 0.18s ease,
  border-color 0.18s ease;
}

.download-col {
  position: relative;
}

.download-col .action-btn {
  width: 100%;
}

.source-state-row {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  display: flex;
  justify-content: center;
  gap: 5px;
}

.action-btn:disabled {
  opacity: 0.6;
}

.action-btn.active {
  background: #fff0e7;
  border-color: #fa9c69;
  color: #e07030;
}

/* 下载状态 */
.action-btn.status-queued {
  background: #f2faf5;
  border-color: #8cc9a0;
  color: #4a8c5e;
}

.action-btn.status-downloading {
  background: #e8f7ed;
  border-color: #5bbf7a;
  color: #3a7a4e;
}

.action-btn.status-paused {
  background: #fefaf2;
  border-color: #d4b870;
  color: #8a7030;
}

.action-btn.status-completed {
  background: #eaf7ef;
  border-color: #6dbf87;
  color: #3a8c52;
}

.action-btn.status-failed {
  background: #fff7f7;
  border-color: #e8a8a8;
  color: #b04040;
}

.action-btn ion-icon {
  font-size: 20px;
}

.action-label {
  font-size: 11px;
}

.source-state-row {
  position: absolute;
  bottom: 4px;
  left: 0;
  right: 0;
  display: flex;
  justify-content: center;
  gap: 5px;
}

.source-state-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: 1px solid #d0c7c0;
  border-radius: 6px;
  background: #f2f0ee;
  color: #9a9088;
  font-size: 12px;
}

.source-state-chip.active {
  opacity: 1;
}

.source-chip-image.active {
  border-color: #4a9fd8;
  background: #e8f4fc;
  color: #2d7ab5;
}

.source-chip-pdf.active {
  border-color: #e05555;
  background: #fdf0f0;
  color: #c03939;
}

/* 信息列表 */
.info-list {
  display: flex;
  flex-direction: column;
  gap: 0;
  border-radius: 14px;
  overflow: hidden;
  background: #fffaf6;
  box-shadow: 0 6px 18px rgb(76 42 24 / 0.06);
}

.info-row {
  display: flex;
  gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid rgb(245 210 188 / 0.3);
}

.info-row:last-child {
  border-bottom: 0;
}

.info-label {
  flex-shrink: 0;
  width: 64px;
  color: #a07858;
  font-size: 11px;
  font-weight: 700;
}

.info-value {
  flex: 1;
  min-width: 0;
  color: #3a261d;
  font-size: 12px;
  line-height: 1.45;
}

.info-value.clickable {
  cursor: pointer;
  position: relative;
  padding-right: 18px;
  transition: color 0.15s ease;
}

.info-value.clickable:hover {
  color: #fa9c69;
}

.copy-icon {
  position: absolute;
  top: 0;
  right: 0;
  font-size: 12px;
  color: #c4a48c;
}

.desc-text {
  margin: 0;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  overflow: hidden;
}

.desc-text.expanded {
  display: block;
}

.desc-toggle {
  margin-top: 4px;
  padding: 0;
  border: 0;
  background: none;
  color: #fa9c69;
  font-size: 11px;
}

.info-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tag {
  display: inline-block;
  padding: 3px 8px;
  border-radius: 999px;
  background: #fff0e7;
  color: #9b5a35;
  font-size: 10px;
}

.tag-clickable {
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.tag-clickable:hover {
  background: #fadcc8;
}

/* 相关作品 */
.related-section {
  margin-top: 16px;
}

.section-title {
  margin: 0 0 10px;
  color: #3a261d;
  font-size: 14px;
  font-weight: 700;
}

.related-scroll {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 6px;
  scroll-snap-type: x mandatory;
  -webkit-overflow-scrolling: touch;
  touch-action: manipulation;
}

.related-card {
  flex-shrink: 0;
  width: 100px;
  scroll-snap-align: start;
  cursor: pointer;
}

.related-cover-wrap {
  width: 100%;
  aspect-ratio: 3 / 4;
  border-radius: 8px;
  overflow: hidden;
  background: linear-gradient(145deg, #f3ded0, #ffece0);
  box-shadow: 0 4px 12px rgb(76 42 24 / 0.12);
}

.related-cover {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.related-title {
  margin: 6px 0 0;
  color: #5a3d2e;
  font-size: 10px;
  line-height: 1.3;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  overflow: hidden;
}

/* 骨架屏 */
.skeleton {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 4px 0;
}

.sk-row {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sk-line {
  height: 14px;
  border-radius: 6px;
  background: linear-gradient(90deg, #f3ded0 25%, #ffece0 50%, #f3ded0 75%);
  background-size: 200% 100%;
  animation: shimmer 1.4s ease infinite;
}

.sk-line--short {
  width: 40%;
}

@keyframes shimmer {
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}
</style>
