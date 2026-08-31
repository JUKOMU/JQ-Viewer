<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar class="cache-toolbar">
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

    <IonContent ref="contentRef" :scroll-events="true" @ion-scroll="handleScroll">
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
          <span>暂无图片缓存</span>
        </div>

        <template v-else>
          <section v-for="group in groups" :key="group.photoId" class="cache-group">
            <div class="group-header">
              <button
                class="id-tag"
                type="button"
                :aria-label="`查看章节 ${group.photoId} 所属本子详情`"
                @click="openAlbum(group.photoId)"
              >
                {{ group.photoId }}
              </button>
              <button
                class="group-header-meta"
                type="button"
                :aria-expanded="!isGroupCollapsed(group.photoId)"
                :aria-controls="`cache-grid-${group.photoId}`"
                :aria-label="`${isGroupCollapsed(group.photoId) ? '展开' : '收起'}章节 ${group.photoId} 图片缓存`"
                @click="toggleGroup(group.photoId)"
              >
                <span class="group-summary"
                  >{{ group.pages.length }} 页 · {{ formatBytes(group.sizeBytes) }}</span
                >
                <IonIcon
                  class="group-toggle-icon"
                  :class="{ collapsed: isGroupCollapsed(group.photoId) }"
                  :icon="chevronDownOutline"
                  aria-hidden="true"
                />
              </button>
            </div>

            <Transition name="cache-drawer">
              <div
                v-if="!isGroupCollapsed(group.photoId)"
                :id="`cache-grid-${group.photoId}`"
                class="cache-drawer-content"
              >
                <div class="cache-drawer-inner">
                  <div class="group-title" :title="groupTitles[group.photoId]">
                    {{ groupTitles[group.photoId] ?? '正在获取标题...' }}
                  </div>
                  <div class="cache-grid">
                    <template v-for="item in group.items" :key="item.key">
                      <div v-if="item.gap" class="gap-slot" aria-hidden="true" />
                      <article v-else-if="item.page" class="cache-card">
                        <button
                          class="image-frame"
                          type="button"
                          :aria-label="`查看 ${group.photoId} 第 ${item.page.sortOrder} 页缓存图片`"
                          @click="openPreview(item.page)"
                        >
                          <img
                            :src="previewUrl(item.page)"
                            :alt="`${group.photoId} 第 ${item.page.sortOrder} 页`"
                            loading="lazy"
                            decoding="async"
                          />
                        </button>
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
              </div>
            </Transition>
          </section>
        </template>
      </div>
    </IonContent>

    <IonModal
      class="cache-preview-modal"
      :is-open="previewOpen"
      @did-dismiss="handlePreviewDismiss"
    >
      <button
        class="preview-stage"
        type="button"
        aria-label="关闭图片预览"
        @click="handlePreviewClick"
        @touchstart="handlePreviewTouchStart"
        @touchmove="handlePreviewTouchMove"
        @touchend="handlePreviewTouchEnd"
        @touchcancel="handlePreviewTouchEnd"
      >
        <img
          v-if="selectedPreview"
          :src="fullPreviewUrl(selectedPreview)"
          :alt="`${selectedPreview.photoId} 第 ${selectedPreview.sortOrder} 页缓存图片预览`"
          :style="previewImageStyle"
          draggable="false"
        />
      </button>
    </IonModal>
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({ name: 'CachePage' })

import { computed, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  IonBackButton,
  IonButton,
  IonButtons,
  IonContent,
  IonHeader,
  IonIcon,
  IonModal,
  IonPage,
  IonTitle,
  IonToolbar,
} from '@ionic/vue'
import { chevronDownOutline, refreshOutline } from 'ionicons/icons'
import { getImageUrl, JmcomicService, sanitizeError, showToast } from '@/services/JmcomicService'
import type { CacheCapacityInfo, ImageCacheEntry, PhotoDetail } from '@/services/JmcomicTypes'
import type { CachePageView } from '@/utils/imageCacheView'
import { buildImageCacheGroups } from '@/utils/imageCacheView'

const loading = ref(true)
const error = ref('')
const entries = ref<ImageCacheEntry[]>([])
const cacheInfo = ref<CacheCapacityInfo>({ capacityMb: 0, usedMb: 0 })
const collapsedGroupIds = ref<Set<string>>(new Set())
const groupTitles = ref<Record<string, string>>({})
const contentRef = ref<InstanceType<typeof IonContent> | null>(null)
const selectedPreview = ref<CachePageView | null>(null)
const previewOpen = ref(false)
const previewScale = ref(1)
const previewTranslateX = ref(0)
const previewTranslateY = ref(0)
let titleLoadVersion = 0
let cacheScrollTop = 0

const router = useRouter()

const PREVIEW_ZOOM_MIN = 1
const PREVIEW_ZOOM_MAX = 5
const PREVIEW_MOVE_THRESHOLD = 6
const PREVIEW_DOUBLE_TAP_MS = 280
const PREVIEW_DOUBLE_TAP_DISTANCE = 30
const TOUCH_CLICK_DELAY_MS = 500

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
const previewImageStyle = computed(() => ({
  transform: `translate3d(${previewTranslateX.value}px, ${previewTranslateY.value}px, 0) scale(${previewScale.value})`,
  transformOrigin: '0 0',
}))

let previewTouchStartX = 0
let previewTouchStartY = 0
let previewStartTranslateX = 0
let previewStartTranslateY = 0
let previewStartScale = 1
let previewPinchDistance = 0
let previewPinchOriginX = 0
let previewPinchOriginY = 0
let previewGestureMoved = false
let previewGestureUsedMultipleTouches = false
let lastPreviewTouchEndAt = 0
let previewLastTapAt = 0
let previewLastTapX = 0
let previewLastTapY = 0
let previewTapTimer: ReturnType<typeof setTimeout> | null = null

type IonContentElement = HTMLElement & {
  getScrollElement?: () => Promise<HTMLElement | null>
}

const resolveCacheScrollElement = async (): Promise<HTMLElement | null> => {
  const ionContentEl = contentRef.value?.$el as IonContentElement | undefined
  if (!ionContentEl) return null
  return (await ionContentEl.getScrollElement?.()) ?? null
}

const saveScrollPosition = async () => {
  const el = await resolveCacheScrollElement()
  if (el) cacheScrollTop = el.scrollTop
}

const restoreScrollPosition = async () => {
  await nextTick()
  const el = await resolveCacheScrollElement()
  if (el) el.scrollTop = Math.max(0, cacheScrollTop)
}

const handleScroll = (event: CustomEvent<{ scrollTop?: number }>) => {
  const scrollTop = event.detail?.scrollTop
  if (typeof scrollTop === 'number') cacheScrollTop = scrollTop
}

onMounted(() => {
  void loadCache()
})

onActivated(() => {
  void restoreScrollPosition()
})

onDeactivated(() => {
  void saveScrollPosition()
})

onUnmounted(() => {
  titleLoadVersion += 1
  clearPreviewTapState()
})

async function loadCache() {
  const loadVersion = ++titleLoadVersion
  loading.value = true
  error.value = ''
  groupTitles.value = {}
  try {
    const [info, result] = await Promise.all([
      JmcomicService.getCacheCapacityInfo(),
      JmcomicService.getImageCacheContents(),
    ])
    cacheInfo.value = info
    entries.value = result.entries ?? []
    void loadGroupTitles(
      buildImageCacheGroups(entries.value).map((group) => group.photoId),
      loadVersion,
    )
  } catch {
    error.value = '缓存读取失败'
  } finally {
    loading.value = false
  }
}

async function loadGroupTitles(photoIds: string[], loadVersion: number) {
  for (let start = 0; start < photoIds.length; start += 4) {
    const batch = photoIds.slice(start, start + 4)
    const results = await Promise.all(
      batch.map(async (photoId) => {
        try {
          const photo = await JmcomicService.getPhoto(photoId)
          return [photoId, formatGroupTitle(photo)] as const
        } catch {
          return [photoId, '标题获取失败'] as const
        }
      }),
    )
    if (loadVersion !== titleLoadVersion) return
    groupTitles.value = { ...groupTitles.value, ...Object.fromEntries(results) }
  }
}

function formatGroupTitle(photo: PhotoDetail): string {
  const title = photo.title.trim() || '未命名章节'
  const isMultiEpisode =
    photo.isSingleEpisode === false ||
    (photo.isSingleEpisode === undefined && photo.albumId !== photo.id)
  if (!isMultiEpisode || photo.sortOrder <= 0) return title
  return `${title} · 第${photo.sortOrder}话`
}

async function openAlbum(photoId: string) {
  try {
    const photo = await JmcomicService.getPhoto(photoId)
    const targetAlbumId = photo.albumId.trim()
    if (!targetAlbumId) throw new Error('未找到所属本子')
    await router.push({
      path: `/album/${targetAlbumId}`,
      query: { chapterId: photoId },
    })
  } catch (cause) {
    await showToast(sanitizeError(cause, '打开详情失败'), 'danger')
  }
}

function previewUrl(page: CachePageView): string {
  return getImageUrl(page.photoId, page.sortOrder, page.small ? 'thumb' : 'image')
}

function fullPreviewUrl(page: CachePageView): string {
  return getImageUrl(page.photoId, page.sortOrder, page.full ? 'image' : 'thumb')
}

function openPreview(page: CachePageView) {
  clearPreviewTapState()
  resetPreviewTransform()
  selectedPreview.value = page
  previewOpen.value = true
}

function closePreview() {
  clearPreviewTapState()
  previewOpen.value = false
}

function handlePreviewDismiss() {
  previewOpen.value = false
  selectedPreview.value = null
  clearPreviewTapState()
  resetPreviewTransform()
}

function resetPreviewTransform() {
  previewScale.value = PREVIEW_ZOOM_MIN
  previewTranslateX.value = 0
  previewTranslateY.value = 0
}

function clearPreviewTapState() {
  cancelPreviewTapTimer()
  previewLastTapAt = 0
  previewLastTapX = 0
  previewLastTapY = 0
}

function cancelPreviewTapTimer() {
  if (previewTapTimer) {
    clearTimeout(previewTapTimer)
    previewTapTimer = null
  }
}

function previewTouchDistance(touches: TouchList): number {
  if (touches.length < 2) return 0
  return Math.hypot(
    touches[0].clientX - touches[1].clientX,
    touches[0].clientY - touches[1].clientY,
  )
}

function previewTouchMidpoint(touches: TouchList) {
  return {
    x: (touches[0].clientX + touches[1].clientX) / 2,
    y: (touches[0].clientY + touches[1].clientY) / 2,
  }
}

function getPreviewImageMetrics(stage: HTMLElement) {
  const width = stage.clientWidth
  const height = stage.clientHeight
  const image = stage.querySelector('img')
  const naturalWidth = image?.naturalWidth ?? 0
  const naturalHeight = image?.naturalHeight ?? 0
  if (!width || !height || !naturalWidth || !naturalHeight) {
    return { offsetX: 0, offsetY: 0, width, height }
  }

  const containScale = Math.min(width / naturalWidth, height / naturalHeight)
  return {
    offsetX: (width - naturalWidth * containScale) / 2,
    offsetY: (height - naturalHeight * containScale) / 2,
    width: naturalWidth * containScale,
    height: naturalHeight * containScale,
  }
}

function clampPreviewAxis(
  stageSize: number,
  offset: number,
  renderedSize: number,
  scale: number,
  translation: number,
) {
  const scaledOffset = offset * scale
  const scaledSize = renderedSize * scale
  if (scaledSize <= stageSize) return (stageSize - scaledSize) / 2 - scaledOffset

  const minTranslation = stageSize - scaledOffset - scaledSize
  const maxTranslation = -scaledOffset
  return Math.max(minTranslation, Math.min(maxTranslation, translation))
}

function clampPreviewTranslation(target: EventTarget | null) {
  const stage = target as HTMLElement | null
  if (!stage) return
  const metrics = getPreviewImageMetrics(stage)
  previewTranslateX.value = clampPreviewAxis(
    stage.clientWidth,
    metrics.offsetX,
    metrics.width,
    previewScale.value,
    previewTranslateX.value,
  )
  previewTranslateY.value = clampPreviewAxis(
    stage.clientHeight,
    metrics.offsetY,
    metrics.height,
    previewScale.value,
    previewTranslateY.value,
  )
}

function nextPreviewDoubleTapScale(): number {
  if (previewScale.value < 2) return 2
  if (previewScale.value < 3) return 3
  if (previewScale.value < PREVIEW_ZOOM_MAX) return PREVIEW_ZOOM_MAX
  return PREVIEW_ZOOM_MIN
}

function zoomPreviewAtPoint(target: EventTarget | null, clientX: number, clientY: number) {
  const nextScale = nextPreviewDoubleTapScale()
  if (nextScale === PREVIEW_ZOOM_MIN) {
    resetPreviewTransform()
    return
  }

  const stage = target as HTMLElement | null
  const rect = stage?.getBoundingClientRect()
  const relativeX = clientX - (rect?.left ?? 0)
  const relativeY = clientY - (rect?.top ?? 0)
  const ratio = nextScale / previewScale.value
  previewTranslateX.value = relativeX * (1 - ratio) + previewTranslateX.value * ratio
  previewTranslateY.value = relativeY * (1 - ratio) + previewTranslateY.value * ratio
  previewScale.value = nextScale
  clampPreviewTranslation(target)
}

function handlePreviewTap(event: TouchEvent) {
  const touch = event.changedTouches[0]
  if (!touch) return
  const now = Date.now()
  const tapDistance =
    Math.abs(touch.clientX - previewLastTapX) + Math.abs(touch.clientY - previewLastTapY)
  if (
    previewLastTapAt > 0 &&
    now - previewLastTapAt < PREVIEW_DOUBLE_TAP_MS &&
    tapDistance < PREVIEW_DOUBLE_TAP_DISTANCE
  ) {
    clearPreviewTapState()
    zoomPreviewAtPoint(event.currentTarget, touch.clientX, touch.clientY)
    return
  }

  clearPreviewTapState()
  previewLastTapAt = now
  previewLastTapX = touch.clientX
  previewLastTapY = touch.clientY
  previewTapTimer = setTimeout(() => {
    previewTapTimer = null
    previewLastTapAt = 0
    closePreview()
  }, PREVIEW_DOUBLE_TAP_MS)
}

function handlePreviewTouchStart(event: TouchEvent) {
  if (event.touches.length === 1) {
    const touch = event.touches[0]
    const tapDistance =
      Math.abs(touch.clientX - previewLastTapX) + Math.abs(touch.clientY - previewLastTapY)
    if (
      previewLastTapAt > 0 &&
      Date.now() - previewLastTapAt < PREVIEW_DOUBLE_TAP_MS &&
      tapDistance < PREVIEW_DOUBLE_TAP_DISTANCE
    ) {
      cancelPreviewTapTimer()
    }
    previewGestureMoved = false
    previewGestureUsedMultipleTouches = false
    previewTouchStartX = touch.clientX
    previewTouchStartY = touch.clientY
    previewStartTranslateX = previewTranslateX.value
    previewStartTranslateY = previewTranslateY.value
    return
  }

  if (event.touches.length < 2) return
  event.preventDefault()
  clearPreviewTapState()
  previewGestureUsedMultipleTouches = true
  previewPinchDistance = previewTouchDistance(event.touches)
  previewStartScale = previewScale.value
  previewStartTranslateX = previewTranslateX.value
  previewStartTranslateY = previewTranslateY.value
  const midpoint = previewTouchMidpoint(event.touches)
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  previewPinchOriginX = midpoint.x - rect.left
  previewPinchOriginY = midpoint.y - rect.top
}

function handlePreviewTouchMove(event: TouchEvent) {
  if (event.touches.length >= 2) {
    event.preventDefault()
    const distance = previewTouchDistance(event.touches)
    if (previewPinchDistance <= 0) return
    const nextScale = Math.max(
      PREVIEW_ZOOM_MIN,
      Math.min(PREVIEW_ZOOM_MAX, previewStartScale * (distance / previewPinchDistance)),
    )
    const ratio = nextScale / previewStartScale
    previewScale.value = nextScale
    previewTranslateX.value = previewPinchOriginX * (1 - ratio) + previewStartTranslateX * ratio
    previewTranslateY.value = previewPinchOriginY * (1 - ratio) + previewStartTranslateY * ratio
    clampPreviewTranslation(event.currentTarget)
    previewGestureMoved = true
    return
  }

  if (event.touches.length !== 1) return
  const deltaX = event.touches[0].clientX - previewTouchStartX
  const deltaY = event.touches[0].clientY - previewTouchStartY
  if (Math.abs(deltaX) > PREVIEW_MOVE_THRESHOLD || Math.abs(deltaY) > PREVIEW_MOVE_THRESHOLD) {
    previewGestureMoved = true
    clearPreviewTapState()
  }
  if (previewScale.value <= PREVIEW_ZOOM_MIN) return
  event.preventDefault()
  previewTranslateX.value = previewStartTranslateX + deltaX
  previewTranslateY.value = previewStartTranslateY + deltaY
  clampPreviewTranslation(event.currentTarget)
}

function handlePreviewTouchEnd(event: TouchEvent) {
  if (event.touches.length > 0) {
    if (previewGestureUsedMultipleTouches && event.touches.length === 1) {
      previewTouchStartX = event.touches[0].clientX
      previewTouchStartY = event.touches[0].clientY
      previewStartTranslateX = previewTranslateX.value
      previewStartTranslateY = previewTranslateY.value
    }
    return
  }

  lastPreviewTouchEndAt = Date.now()
  if (event.type === 'touchcancel') {
    clampPreviewTranslation(event.currentTarget)
    return
  }
  if (previewGestureUsedMultipleTouches) {
    if (previewScale.value < 1.05) {
      resetPreviewTransform()
    } else {
      clampPreviewTranslation(event.currentTarget)
    }
    return
  }
  if (previewGestureMoved) {
    clampPreviewTranslation(event.currentTarget)
    return
  }
  handlePreviewTap(event)
}

function handlePreviewClick() {
  if (Date.now() - lastPreviewTouchEndAt < TOUCH_CLICK_DELAY_MS) return
  closePreview()
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

.cache-content,
:deep(ion-toolbar.cache-toolbar) {
  width: 100%;
  max-width: 1000px;
  margin-inline: auto;
  box-sizing: border-box;
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
  color: inherit;
  text-align: left;
}

.id-tag:focus-visible,
.group-header-meta:focus-visible {
  outline: 2px solid #e8843c;
  outline-offset: 1px;
}

.group-header-meta {
  display: inline-flex;
  flex: 1;
  min-width: 0;
  align-self: stretch;
  align-items: center;
  justify-content: flex-end;
  gap: 5px;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
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
  flex-shrink: 0;
  padding: 3px 8px;
  border: 0;
  background: #fff7f2;
  color: #9b5a35;
  font-size: 12px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  box-shadow: 0 1px 2px rgb(113 72 45 / 0.2);
}

.group-title {
  padding: 0 10px 9px;
  color: #9a7a68;
  font-size: 10px;
  line-height: 16px;
  overflow-wrap: anywhere;
  text-align: left;
}

.cache-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  align-items: start;
}

.cache-drawer-content {
  display: grid;
  grid-template-rows: 1fr;
  overflow: hidden;
}

.cache-drawer-inner {
  min-height: 0;
  overflow: hidden;
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
  display: block;
  width: 100%;
  aspect-ratio: 3 / 4;
  padding: 0;
  overflow: hidden;
  border: 0;
  background: #f8f1ed;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

.image-frame:focus-visible {
  outline: 2px solid #e8843c;
  outline-offset: -2px;
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

.cache-preview-modal {
  --width: 100%;
  --height: 100%;
  --border-radius: 0;
  --background: transparent;
  --backdrop-opacity: 1;
}

.cache-preview-modal::part(backdrop) {
  background: rgba(255, 255, 255, 0.55);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
}

.preview-stage {
  display: flex;
  width: 100%;
  height: 100%;
  padding: 0;
  align-items: center;
  justify-content: center;
  border: 0;
  background: transparent;
  cursor: pointer;
  touch-action: none;
  user-select: none;
  -webkit-tap-highlight-color: transparent;
}

.preview-stage img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
  pointer-events: none;
  user-select: none;
  will-change: transform;
  -webkit-user-drag: none;
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
