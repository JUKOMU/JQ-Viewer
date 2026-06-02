<!-- 区域 A：封面头部 -->
<template>
  <div ref="rootRef" class="header-area">
    <div class="header-bg">
      <img v-if="coverUrl" :src="coverUrl" class="header-bg-img" alt=""/>
      <div class="header-bg-mask"/>
    </div>
    <div class="header-content">
      <button type="button" class="back-btn" @click="$emit('back')">
        <ion-icon :icon="arrowBack"/>
      </button>
      <div class="header-body">
        <div class="cover-col">
          <img v-if="coverUrl" :src="coverUrl" class="cover-img" :alt="title"/>
          <div v-else class="cover-placeholder"/>
        </div>
        <div class="info-col">
          <h1 class="album-title">{{ title || '加载中...' }}</h1>
          <p v-if="authors" class="album-authors">{{ authors }}</p>
          <p v-if="!loading && !chapterLoading" class="album-pages">{{ pageCount }} 页</p>
          <div v-else class="pages-skeleton"/>
          <div class="read-actions">
            <div class="read-main-row">
              <button type="button" class="read-btn" @click="$emit('start-reading')">开始阅读</button>
              <button
                type="button"
                class="source-toggle-btn"
                :class="{ active: sourceMenuOpen }"
                aria-label="选择阅读来源"
                @click="$emit('toggle-source-menu')"
              >
                <ion-icon :icon="ellipsisVertical"/>
              </button>
            </div>
            <Transition name="source-menu">
              <div v-if="sourceMenuOpen" class="source-menu">
                <button
                  type="button"
                  class="source-btn source-network"
                  :class="{ available: networkAvailable }"
                  :disabled="!networkAvailable"
                  aria-label="网络图片阅读"
                  @click="$emit('select-source', 'network')"
                >
                  <ion-icon :icon="globeOutline"/>
                </button>
                <button
                  type="button"
                  class="source-btn source-image"
                  :class="{ available: imageAvailable }"
                  :disabled="!imageAvailable"
                  aria-label="本地图片阅读"
                  @click="$emit('select-source', 'download')"
                >
                  <ion-icon :icon="imageOutline"/>
                </button>
                <button
                  type="button"
                  class="source-btn source-pdf"
                  :class="{ available: pdfAvailable }"
                  :disabled="!pdfAvailable"
                  aria-label="PDF 阅读"
                  @click="$emit('select-source', 'pdf')"
                >
                  <ion-icon :icon="documentOutline"/>
                </button>
              </div>
            </Transition>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({name: 'AlbumHeader'})

defineProps<{
  coverUrl: string
  title: string
  authors: string
  pageCount: number
  loading: boolean
  chapterLoading?: boolean
  sourceMenuOpen: boolean
  networkAvailable: boolean
  imageAvailable: boolean
  pdfAvailable: boolean
}>()
defineEmits<{
  'start-reading': []
  'toggle-source-menu': []
  'select-source': [source: 'network' | 'download' | 'pdf']
  back: []
}>()
import {IonIcon} from '@ionic/vue'
import {
  arrowBack,
  ellipsisVertical,
  globeOutline,
  documentOutline,
  imageOutline,
} from 'ionicons/icons'
</script>

<style scoped>
.header-area {
  position: relative;
  overflow: hidden;
}

.header-bg {
  position: absolute;
  inset: 0;
  overflow: hidden;
}

.header-bg-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  filter: blur(20px) brightness(0.35);
  transform: scale(1.1);
}

.header-bg-mask {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgb(125 125 125 / 0.4) 0%, rgb(125 125 125 / 0.2) 100%);
}

.header-content {
  position: relative;
  z-index: 1;
  padding: calc(var(--ion-safe-area-top) + 8px) 16px 18px;
}

.back-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  margin-bottom: 10px;
  border: 0;
  border-radius: 50%;
  background: rgb(255 255 255 / 0.15);
  color: #fff;
  font-size: 20px;
  backdrop-filter: blur(6px);
}

.header-body {
  display: flex;
  gap: 14px;
  align-items: flex-end;
}

.cover-col {
  flex-shrink: 0;
  width: 120px;
  border-radius: 10px;
  overflow: hidden;
  box-shadow: 0 8px 30px rgb(0 0 0 / 0.4);
}

.cover-img {
  display: block;
  width: 100%;
  aspect-ratio: 3 / 4;
  object-fit: cover;
}

.cover-placeholder {
  width: 100%;
  aspect-ratio: 3 / 4;
  background: rgb(255 255 255 / 0.1);
  border-radius: 10px;
}

.info-col {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  gap: 6px;
  padding-bottom: 4px;
}

.album-title {
  margin: 0;
  color: #fff;
  font-size: 17px;
  font-weight: 700;
  line-height: 1.35;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  overflow: hidden;
}

.album-authors {
  margin: 0;
  color: rgb(255 255 255 / 0.7);
  font-size: 12px;
  line-height: 1.3;
}

.album-pages {
  margin: 0;
  color: rgb(255 255 255 / 0.55);
  font-size: 11px;
}

.pages-skeleton {
  width: 40%;
  height: 14px;
  border-radius: 6px;
  background: linear-gradient(
    90deg,
    rgb(255 255 255 / 0.08) 25%,
    rgb(255 255 255 / 0.18) 50%,
    rgb(255 255 255 / 0.08) 75%
  );
  background-size: 200% 100%;
  animation: shimmer 1.4s ease infinite;
}

@keyframes shimmer {
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}

.read-actions {
  position: relative;
  align-self: flex-start;
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 4px;
}

.read-main-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.read-btn {
  height: 36px;
  padding: 0 22px;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(145deg, #fa9c69, #f07e49);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  box-shadow: 0 4px 14px rgb(240 126 73 / 0.4);
  transition: transform 0.16s ease,
  box-shadow 0.16s ease;
}

.read-btn:active {
  transform: scale(0.96);
  box-shadow: 0 2px 8px rgb(240 126 73 / 0.3);
}

.source-toggle-btn,
.source-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  border: 1px solid rgb(255 255 255 / 0.28);
  background: rgb(255 255 255 / 0.13);
  color: rgb(255 255 255 / 0.72);
  font-size: 18px;
  backdrop-filter: blur(6px);
  transition: background-color 0.18s ease,
  border-color 0.18s ease,
  color 0.18s ease,
  transform 0.12s ease,
  opacity 0.18s ease;
}

.source-toggle-btn.active {
  background: rgb(255 255 255 / 0.24);
  color: #fff;
}

.source-toggle-btn:active,
.source-btn:active {
  transform: scale(0.92);
}

.source-menu {
  position: absolute;
  top: 100%;
  left: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  z-index: 20;
  background: rgb(0 0 0 / 0.4);
  padding: 8px;
  border-radius: 24px;
  backdrop-filter: blur(8px);
}

.source-btn {
  color: rgb(255 255 255 / 0.38);
  opacity: 0.72;
}

.source-btn.available {
  opacity: 1;
}

.source-network.available {
  border-color: #6dbf87;
  background: #eaf7ef;
  color: #3a8c52;
}

.source-image.available {
  border-color: #4a9fd8;
  background: #e8f4fc;
  color: #2d7ab5;
}

.source-pdf.available {
  border-color: #e05555;
  background: #fdf0f0;
  color: #c03939;
}

.source-btn:disabled {
  pointer-events: none;
}

.source-menu-enter-active,
.source-menu-leave-active {
  transition: opacity 0.18s ease,
  transform 0.18s ease;
}

.source-menu-enter-from,
.source-menu-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (min-width: 680px) {
  .header-content {
    max-width: 720px;
    margin: 0 auto;
    padding-left: 0;
    padding-right: 0;
  }

  .cover-col {
    width: 150px;
  }

  .album-title {
    font-size: 20px;
  }
}
</style>
