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
          <ion-icon :icon="heart" />
          <span class="action-label">点赞 {{ album.likes }}</span>
        </button>
        <button
          type="button"
          class="action-btn"
          :class="{ active: album.isFavorite }"
          :disabled="actionBusy.favorite"
          @click="$emit('toggle-favorite')"
        >
          <ion-icon :icon="bookmark" />
          <span class="action-label">收藏</span>
        </button>
        <button type="button" class="action-btn" @click="$emit('download')">
          <ion-icon :icon="downloadOutline" />
          <span class="action-label">下载</span>
        </button>
      </div>

      <!-- 信息列表 -->
      <div class="info-list">
        <div class="info-row">
          <span class="info-label">ID</span>
          <span class="info-value">{{ album.id }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">标题</span>
          <span class="info-value">{{ album.title }}</span>
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
          <span class="info-label">Authors</span>
          <div class="info-tags">
            <span v-for="author in album.authors" :key="author" class="tag">{{ author }}</span>
          </div>
        </div>
        <div v-if="album.tags.length" class="info-row">
          <span class="info-label">Tags</span>
          <div class="info-tags">
            <span v-for="tag in album.tags" :key="tag" class="tag">{{ tag }}</span>
          </div>
        </div>
        <div v-if="album.actors.length" class="info-row">
          <span class="info-label">Actors</span>
          <div class="info-tags">
            <span v-for="actor in album.actors" :key="actor" class="tag">{{ actor }}</span>
          </div>
        </div>
        <div v-if="album.works.length" class="info-row">
          <span class="info-label">Works</span>
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
      </div>

      <!-- 相关作品 -->
      <div v-if="album.relatedAlbums.length" class="related-section">
        <h3 class="section-title">相关作品</h3>
        <div class="related-scroll">
          <div
            v-for="related in album.relatedAlbums"
            :key="related.id"
            class="related-card"
            @click="$emit('navigate-album', related)"
          >
            <div class="related-cover-wrap">
              <img :src="related.coverUrl" :alt="related.title" class="related-cover" />
            </div>
            <p class="related-title">{{ related.title }}</p>
          </div>
        </div>
      </div>
    </template>

    <!-- 骨架屏 -->
    <div v-else class="skeleton">
      <div class="sk-row" v-for="n in 5" :key="n">
        <div class="sk-line sk-line--short" />
        <div class="sk-line" />
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { IonIcon } from '@ionic/vue'
import { bookmark, downloadOutline, heart } from 'ionicons/icons'
import type { AlbumDetail, AlbumMeta } from '@/services/JmcomicTypes'

defineProps<{
  album: AlbumDetail | null
  actionBusy: { like: boolean; favorite: boolean }
}>()

defineEmits<{
  'toggle-like': []
  'toggle-favorite': []
  download: []
  'navigate-album': [related: AlbumMeta]
}>()

const descExpanded = ref(false)
</script>

<style scoped>
/* 交互栏 */
.action-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}

.action-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  flex: 1;
  padding: 10px 8px;
  border: 1px solid rgb(245 210 188 / 0.7);
  border-radius: 12px;
  background: #fffaf6;
  color: #785947;
  font-size: 11px;
  transition: background-color 0.18s ease, color 0.18s ease, border-color 0.18s ease;
}

.action-btn:disabled {
  opacity: 0.6;
}

.action-btn.active {
  background: #fff0e7;
  border-color: #fa9c69;
  color: #e07030;
}

.action-btn ion-icon {
  font-size: 20px;
}

.action-label {
  font-size: 11px;
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
  -webkit-line-clamp: 2;
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
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
</style>
