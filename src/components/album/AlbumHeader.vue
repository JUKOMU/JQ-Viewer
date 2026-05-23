<!-- 区域 A：封面头部 -->
<template>
  <div class="header-area" ref="rootRef">
    <div class="header-bg">
      <img v-if="coverUrl" :src="coverUrl" class="header-bg-img" alt="" />
      <div class="header-bg-mask" />
    </div>
    <div class="header-content">
      <button type="button" class="back-btn" @click="$emit('back')">
        <ion-icon :icon="arrowBack" />
      </button>
      <div class="header-body">
        <div class="cover-col">
          <img
            v-if="coverUrl"
            :src="coverUrl"
            class="cover-img"
            :alt="title"
          />
          <div v-else class="cover-placeholder" />
        </div>
        <div class="info-col">
          <h1 class="album-title">{{ title || '加载中...' }}</h1>
          <p v-if="authors" class="album-authors">{{ authors }}</p>
          <p v-if="!loading && !chapterLoading" class="album-pages">{{ pageCount }} 页</p>
          <div v-else class="pages-skeleton" />
          <button type="button" class="read-btn" @click="$emit('start-reading')">
            开始阅读
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { IonIcon } from '@ionic/vue'
import { arrowBack } from 'ionicons/icons'

defineProps<{
  coverUrl: string
  title: string
  authors: string
  pageCount: number
  loading: boolean
  chapterLoading?: boolean
}>()

defineEmits<{
  'start-reading': []
  back: []
}>()

defineExpose({
  getRootElement: () => null as HTMLElement | null,
})
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
  background: linear-gradient(90deg, rgb(255 255 255 / 0.08) 25%, rgb(255 255 255 / 0.18) 50%, rgb(255 255 255 / 0.08) 75%);
  background-size: 200% 100%;
  animation: shimmer 1.4s ease infinite;
}

@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

.read-btn {
  align-self: flex-start;
  height: 36px;
  padding: 0 22px;
  margin-top: 4px;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(145deg, #fa9c69, #f07e49);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  box-shadow: 0 4px 14px rgb(240 126 73 / 0.4);
  transition: transform 0.16s ease, box-shadow 0.16s ease;
}

.read-btn:active {
  transform: scale(0.96);
  box-shadow: 0 2px 8px rgb(240 126 73 / 0.3);
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
