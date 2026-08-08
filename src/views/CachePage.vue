<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <IonButtons slot="start">
          <IonBackButton default-href="/setting" />
        </IonButtons>
        <IonTitle class="toolbar-title">图片缓存</IonTitle>
        <IonButtons slot="end">
          <IonButton
            fill="clear"
            :disabled="loading"
            aria-label="刷新缓存内容"
            title="刷新缓存内容"
            @click="loadCache"
          >
            <IonIcon :icon="refreshOutline" aria-hidden="true" />
          </IonButton>
        </IonButtons>
      </IonToolbar>
    </IonHeader>

    <IonContent>
      <div class="cache-content">
        <div v-if="!loading && !error" class="cache-summary">
          <div class="summary-main">
            <span class="summary-label">缓存用量</span>
            <span class="summary-value">{{ cacheInfo.usedMb }} MB</span>
            <span class="summary-capacity">/ {{ cacheEffectiveMb }} MB</span>
          </div>
          <div class="summary-detail">
            {{ cachedPageCount }} 页 · {{ groups.length }} 个 章节 ·
            {{ formatBytes(cachedSizeBytes) }}
          </div>
          <div class="usage-bar" aria-hidden="true">
            <div class="usage-fill" :style="{ width: usagePercent + '%' }" />
          </div>
        </div>

        <div v-if="loading" class="state-message">正在读取缓存...</div>

        <div v-else-if="error" class="state-message error-state">
          <span>{{ error }}</span>
          <button class="retry-button" type="button" @click="loadCache">重试</button>
        </div>

        <div v-else-if="groups.length === 0" class="state-message empty-state">
          <span class="empty-icon" aria-hidden="true">□</span>
          <span>暂无图片缓存</span>
        </div>

        <template v-else>
          <section v-for="group in groups" :key="group.photoId" class="cache-group">
            <button
              class="group-header"
              type="button"
              :aria-expanded="!isGroupCollapsed(group.photoId)"
              :aria-controls="`cache-grid-${group.photoId}`"
              :aria-label="`${isGroupCollapsed(group.photoId) ? '展开' : '收起'}章节 ${group.photoId} 图片缓存`"
              @click="toggleGroup(group.photoId)"
            >
              <span class="id-tag">{{ group.photoId }}</span>
              <span class="group-header-meta">
                <span class="group-summary"
                  >{{ group.pages.length }} 页 · {{ formatBytes(group.sizeBytes) }}</span
                >
                <IonIcon
                  class="group-toggle-icon"
                  :class="{ collapsed: isGroupCollapsed(group.photoId) }"
                  :icon="chevronDownOutline"
                  aria-hidden="true"
                />
              </span>
            </button>

            <Transition name="cache-drawer">
              <div
                v-if="!isGroupCollapsed(group.photoId)"
                :id="`cache-grid-${group.photoId}`"
                class="cache-grid-wrap"
              >
                <div class="cache-grid">
                  <template v-for="item in group.items" :key="item.key">
                    <div v-if="item.gap" class="gap-slot" aria-hidden="true" />
                    <article v-else-if="item.page" class="cache-card">
                      <div class="image-frame">
                        <img
                          :src="previewUrl(item.page)"
                          :alt="`${group.photoId} 第 ${item.page.sortOrder} 页`"
                          loading="lazy"
                          decoding="async"
                        />
                      </div>
                      <div class="cache-card-footer">
                        <span class="page-number">第 {{ item.page.sortOrder }} 页</span>
                        <span class="type-badges">
                          <span v-if="item.page.full" class="type-badge full">原图</span>
                          <span v-if="item.page.small" class="type-badge small">缩略图</span>
                        </span>
                      </div>
                    </article>
                  </template>
                </div>
              </div>
            </Transition>
          </section>
        </template>
      </div>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({ name: 'CachePage' })

import { computed, onMounted, ref } from 'vue'
import {
  IonBackButton,
  IonButton,
  IonButtons,
  IonContent,
  IonHeader,
  IonIcon,
  IonPage,
  IonTitle,
  IonToolbar,
} from '@ionic/vue'
import { chevronDownOutline, refreshOutline } from 'ionicons/icons'
import { getImageUrl, JmcomicService } from '@/services/JmcomicService'
import type { CacheCapacityInfo, ImageCacheEntry } from '@/services/JmcomicTypes'
import type { CachePageView } from '@/utils/imageCacheView'
import { buildImageCacheGroups } from '@/utils/imageCacheView'

const loading = ref(true)
const error = ref('')
const entries = ref<ImageCacheEntry[]>([])
const cacheInfo = ref<CacheCapacityInfo>({ capacityMb: 0, usedMb: 0 })
const collapsedGroupIds = ref<Set<string>>(new Set())

const groups = computed(() => buildImageCacheGroups(entries.value))
const cachedPageCount = computed(() =>
  groups.value.reduce((total, group) => total + group.pages.length, 0),
)
const cachedSizeBytes = computed(() =>
  entries.value.reduce((total, entry) => total + entry.sizeBytes, 0),
)
const cacheEffectiveMb = computed(() => cacheInfo.value.effectiveMb ?? cacheInfo.value.capacityMb)
const usagePercent = computed(() => {
  if (cacheEffectiveMb.value <= 0) return 0
  return Math.min(100, Math.round((cacheInfo.value.usedMb / cacheEffectiveMb.value) * 100))
})

onMounted(() => {
  void loadCache()
})

async function loadCache() {
  loading.value = true
  error.value = ''
  try {
    const [info, result] = await Promise.all([
      JmcomicService.getCacheCapacityInfo(),
      JmcomicService.getImageCacheContents(),
    ])
    cacheInfo.value = info
    entries.value = result.entries ?? []
  } catch {
    error.value = '缓存读取失败'
  } finally {
    loading.value = false
  }
}

function previewUrl(page: CachePageView): string {
  return getImageUrl(page.photoId, page.sortOrder, page.small ? 'thumb' : 'image')
}

function isGroupCollapsed(photoId: string): boolean {
  return collapsedGroupIds.value.has(photoId)
}

function toggleGroup(photoId: string) {
  const next = new Set(collapsedGroupIds.value)
  if (next.has(photoId)) {
    next.delete(photoId)
  } else {
    next.add(photoId)
  }
  collapsedGroupIds.value = next
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const kb = bytes / 1024
  if (kb < 1024) return `${kb < 10 ? kb.toFixed(1) : Math.round(kb)} KB`
  const mb = kb / 1024
  return `${mb < 10 ? mb.toFixed(1) : mb.toFixed(0)} MB`
}
</script>

<style scoped>
:deep(ion-toolbar) {
  --min-height: auto;
}

.toolbar-title {
  font-size: 16px;
  font-weight: 600;
  color: #4c2a18;
}

.cache-content {
  padding: 8px 16px 32px;
}

.cache-summary {
  margin: 8px 0 22px;
  padding: 14px 16px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(115, 67, 38, 0.06);
}

.summary-main,
.group-header,
.cache-card-footer {
  display: flex;
  align-items: center;
}

.summary-main {
  gap: 6px;
}

.summary-label,
.summary-capacity,
.summary-detail,
.group-summary {
  color: #b89a84;
  font-size: 12px;
}

.summary-value {
  color: #4c2a18;
  font-size: 18px;
  font-weight: 700;
}

.summary-detail {
  margin-top: 4px;
}

.usage-bar {
  width: 100%;
  height: 6px;
  margin-top: 12px;
  overflow: hidden;
  background: #f0e4db;
  border-radius: 3px;
}

.usage-fill {
  height: 100%;
  background: #e8843c;
  border-radius: 3px;
  transition: width 0.3s ease;
}

.cache-group + .cache-group {
  margin-top: 14px;
}

.cache-group {
  overflow: hidden;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 2px 10px rgba(115, 67, 38, 0.08);
}

.group-header {
  width: 100%;
  min-height: 44px;
  justify-content: space-between;
  gap: 10px;
  margin: 0;
  padding: 9px 10px;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

.group-header:focus-visible {
  outline: 2px solid #e8843c;
  outline-offset: -2px;
}

.group-header-meta {
  display: inline-flex;
  min-width: 0;
  align-items: center;
  justify-content: flex-end;
  gap: 5px;
}

.group-toggle-icon {
  flex-shrink: 0;
  color: #9b5a35;
  font-size: 16px;
  transition: transform 0.24s ease;
}

.group-toggle-icon.collapsed {
  transform: rotate(-90deg);
}

.id-tag,
.type-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  font-weight: 600;
  white-space: nowrap;
}

.id-tag {
  padding: 3px 8px;
  background: #fff7f2;
  color: #9b5a35;
  font-size: 12px;
  font-weight: 500;
}

.cache-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  align-items: start;
}

.cache-grid-wrap {
  display: grid;
  grid-template-rows: 1fr;
  overflow: hidden;
}

.cache-grid-wrap > .cache-grid {
  min-height: 0;
}

.cache-drawer-enter-active,
.cache-drawer-leave-active {
  transition:
    grid-template-rows 0.24s ease,
    opacity 0.18s ease;
}

.cache-drawer-enter-from,
.cache-drawer-leave-to {
  grid-template-rows: 0fr;
  opacity: 0;
}

.gap-slot {
  min-width: 0;
  aspect-ratio: 3 / 4;
  margin-bottom: 48px;
  pointer-events: none;
}

.cache-card {
  min-width: 0;
  overflow: hidden;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 10px rgba(115, 67, 38, 0.08);
}

.image-frame {
  width: 100%;
  aspect-ratio: 3 / 4;
  overflow: hidden;
  background: #f8f1ed;
}

.image-frame img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cache-card-footer {
  box-sizing: border-box;
  height: 48px;
  flex-direction: column;
  align-items: stretch;
  justify-content: center;
  gap: 3px;
  padding: 4px 7px;
}

.page-number {
  min-width: 0;
  overflow: hidden;
  color: #6d4a37;
  font-size: 11px;
  text-align: center;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.type-badges {
  display: inline-flex;
  flex-shrink: 0;
  justify-content: center;
  gap: 3px;
}

.type-badge {
  min-width: 20px;
  height: 18px;
  padding: 0 5px;
  font-size: 8px;
}

.type-badge.full {
  background: #fff0e7;
  color: #e8843c;
}

.type-badge.small {
  background: #e9f5ec;
  color: #4f9964;
}

.state-message {
  display: flex;
  min-height: 180px;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #b89a84;
  font-size: 14px;
}

.error-state {
  flex-direction: column;
}

.retry-button {
  min-height: 36px;
  padding: 0 16px;
  border: 1px solid #e0cfc4;
  border-radius: 8px;
  background: #fff;
  color: #9b5a35;
  font: inherit;
}

.empty-icon {
  color: #d4bcae;
  font-size: 26px;
}

@media (min-width: 600px) {
  .cache-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

@media (min-width: 900px) {
  .cache-grid {
    grid-template-columns: repeat(5, minmax(0, 1fr));
  }
}

@media (prefers-reduced-motion: reduce) {
  .group-toggle-icon,
  .cache-drawer-enter-active,
  .cache-drawer-leave-active {
    transition-duration: 0.01ms;
  }
}
</style>
