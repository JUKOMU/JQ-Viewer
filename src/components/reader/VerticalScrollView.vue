<template>
  <div ref="containerRef" class="vertical-container" :class="{noscroll: zoomScale > 1}" @scroll="onScroll">
    <div class="virtual-inner" :style="{ height: innerHeight + 'px' }">
      <div ref="wrapperRef" class="zoom-wrapper" :style="wrapperStyle">
        <div v-if="totalCount > 0" class="edge-indicator">
          - - - - S T A R T - - - -
        </div>
        <div
          v-for="item in visibleItems"
          :key="item.index"
          ref="imageEls"
          class="image-wrapper"
          :style="item.style"
          :data-index="item.index"
        >
          <template v-if="item.dataUrl">
            <img :src="item.dataUrl" class="reader-image" alt="" @load="onImageLoad(item.index, $event)"/>
          </template>
          <template v-else>
            <div class="skeleton-image"/>
          </template>
        </div>
        <div
          v-if="totalCount > 0"
          class="edge-indicator end-indicator"
          :style="{ transform: `translateY(${contentOffset + totalHeight}px)` }"
        >
          - - - - E N D - - - -
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import {computed, nextTick, onMounted, onUnmounted, ref, watch} from 'vue'

defineOptions({name: 'VerticalScrollView'})

const props = defineProps<{
  imageMap: Map<number, string>
  totalCount: number
  currentIndex: number
}>()

const emit = defineEmits<{
  'update:currentIndex': [index: number]
  'requestRange': [range: { start: number; end: number; center: number }]
}>()

interface VisibleItem {
  index: number
  dataUrl: string | null
  style: {
    height: string
    transform: string
  }
}

const ZOOM_MAX = 4
const ZOOM_MIN = 1
const DOUBLE_TAP_MS = 280
const DOUBLE_TAP_DIST = 30
const MAX_DOM_ITEMS = 100
// 与 .edge-indicator 高度保持一致，首尾提示区不参与图片高度缩放。
const EDGE_INDICATOR_HEIGHT = 80
const SCROLL_SYNC_RETRY_MAX = 30

const containerRef = ref<HTMLElement | null>(null)
const wrapperRef = ref<HTMLElement | null>(null)
const imageEls = ref<HTMLElement[]>([])
const containerHeight = ref(0)
const containerWidth = ref(0)
const heights = ref<number[]>([])
const prefixSums = ref<number[]>([0])
const visibleStart = ref(0)
const visibleEnd = ref(0)
const lastEmitIndex = ref(-1)

// ---- 缩放 ----
const zoomScale = ref(1)
const zoomTx = ref(0)
const zoomTy = ref(0)

const wrapperStyle = computed(() => {
  if (zoomScale.value <= 1 && zoomTx.value === 0 && zoomTy.value === 0) return undefined
  return {
    transform: `translate(${zoomTx.value}px, ${zoomTy.value}px) scale(${zoomScale.value})`,
    transformOrigin: '0 0',
  }
})

const contentOffset = computed(() => props.totalCount > 0 ? EDGE_INDICATOR_HEIGHT : 0)
const totalHeight = computed(() => prefixSums.value[props.totalCount] ?? 0)
const innerHeight = computed(() => contentOffset.value + totalHeight.value
  + (props.totalCount > 0 ? EDGE_INDICATOR_HEIGHT : 0))

function getPageTop(index: number) {
  return contentOffset.value + (prefixSums.value[index] ?? 0)
}

function getScrollTarget(index: number) {
  return index === 0 ? 0 : getPageTop(index)
}

const visibleItems = computed<VisibleItem[]>(() => {
  const items: VisibleItem[] = []
  const end = Math.min(visibleEnd.value, props.totalCount)
  for (let index = visibleStart.value; index < end; index++) {
    items.push({
      index,
      dataUrl: props.imageMap.get(index + 1) ?? null,
      style: {
        height: `${heights.value[index] ?? getEstimatedHeight()}px`,
        transform: `translateY(${getPageTop(index)}px)`,
      },
    })
  }
  return items
})

function getEstimatedHeight() {
  const width = containerWidth.value || window.innerWidth || 360
  return Math.max(320, width * 4 / 3)
}

function clampIndex(index: number) {
  if (props.totalCount <= 0) return 0
  return Math.min(props.totalCount - 1, Math.max(0, index))
}

function rebuildPrefixSums(nextHeights = heights.value) {
  const nextPrefix = new Array(props.totalCount + 1)
  nextPrefix[0] = 0
  for (let i = 0; i < props.totalCount; i++) {
    nextPrefix[i + 1] = nextPrefix[i] + (nextHeights[i] ?? getEstimatedHeight())
  }
  prefixSums.value = nextPrefix
}

function resetHeightModel() {
  const estimate = getEstimatedHeight()
  heights.value = Array.from({length: props.totalCount}, () => estimate)
  rebuildPrefixSums(heights.value)
  updateVisibleRange(containerRef.value?.scrollTop ?? 0)
}

function upperBound(values: number[], target: number) {
  let low = 0
  let high = values.length
  while (low < high) {
    const mid = (low + high) >> 1
    if (values[mid] <= target) low = mid + 1
    else high = mid
  }
  return low
}

function findIndexByOffset(offset: number) {
  if (props.totalCount <= 0) return 0
  const contentY = Math.max(0, offset - contentOffset.value)
  const idx = upperBound(prefixSums.value, contentY) - 1
  return clampIndex(idx)
}

function getCurrentIndex(scrollTop: number) {
  const ch = containerHeight.value || containerRef.value?.clientHeight || 0
  return findIndexByOffset(scrollTop + ch * 0.35)
}

function getRenderRange(scrollTop: number) {
  const ch = containerHeight.value || containerRef.value?.clientHeight || window.innerHeight
  const overscanPx = Math.max(ch * 3, getEstimatedHeight())
  let start = findIndexByOffset(scrollTop - overscanPx)
  let end = findIndexByOffset(scrollTop + ch + overscanPx) + 1

  if (end - start > MAX_DOM_ITEMS) {
    const center = getCurrentIndex(scrollTop)
    const before = Math.floor(MAX_DOM_ITEMS / 2)
    start = Math.max(0, center - before)
    end = Math.min(props.totalCount, start + MAX_DOM_ITEMS)
    start = Math.max(0, end - MAX_DOM_ITEMS)
  }

  return {start, end}
}

function updateVisibleRange(scrollTop: number) {
  const range = getRenderRange(scrollTop)
  visibleStart.value = range.start
  visibleEnd.value = range.end
  requestRange(range.start, range.end, getCurrentIndex(scrollTop))
}

let lastRequestedStart = -1
let lastRequestedEnd = -1
let lastRequestedCenter = -1

function requestRange(start: number, end: number, center: number) {
  if (
    start === lastRequestedStart &&
    end === lastRequestedEnd &&
    center === lastRequestedCenter
  ) {
    return
  }
  lastRequestedStart = start
  lastRequestedEnd = end
  lastRequestedCenter = center
  emit('requestRange', {start, end, center})
}

function emitCurrentIndex(index: number) {
  const next = clampIndex(index)
  if (next === lastEmitIndex.value) return
  lastEmitIndex.value = next
  emit('update:currentIndex', next)
}

function getZoomFocusContentY() {
  const el = containerRef.value
  const ch = containerHeight.value || el?.clientHeight || 0
  const focusY = ch * 0.35
  return ((el?.scrollTop ?? 0) + focusY - zoomTy.value) / (zoomScale.value || 1)
}

function getZoomViewportContentTop() {
  const scrollTop = containerRef.value?.scrollTop ?? 0
  return Math.max(0, (scrollTop - zoomTy.value) / (zoomScale.value || 1))
}

function updateZoomCurrentIndex() {
  if (zoomScale.value <= 1) return
  updateVisibleRange(getZoomViewportContentTop())
  emitCurrentIndex(findIndexByOffset(getZoomFocusContentY()))
}

function resetZoom(preserveViewport = false) {
  const el = containerRef.value
  const focusContentY = preserveViewport ? getZoomFocusContentY() : 0
  const ch = containerHeight.value || el?.clientHeight || 0
  zoomScale.value = 1
  zoomTx.value = 0
  zoomTy.value = 0
  if (preserveViewport && el) {
    const targetTop = Math.max(0, focusContentY - ch * 0.35)
    isAdjusting = true
    el.scrollTop = targetTop
    updateVisibleRange(targetTop)
    emitCurrentIndex(getCurrentIndex(targetTop))
    window.requestAnimationFrame(() => {
      isAdjusting = false
    })
  }
}

function updateContainerSize() {
  const el = containerRef.value
  if (!el) return false
  const nextHeight = el.clientHeight
  const nextWidth = el.clientWidth
  const changed = nextHeight !== containerHeight.value || nextWidth !== containerWidth.value
  containerHeight.value = nextHeight
  containerWidth.value = nextWidth
  return changed
}

function updateMeasuredHeight(index: number, nextHeight: number) {
  if (index < 0 || index >= props.totalCount || nextHeight <= 0) return
  const prevHeight = heights.value[index] ?? getEstimatedHeight()
  if (Math.abs(prevHeight - nextHeight) < 1) return

  const el = containerRef.value
  const anchorScrollTop = el?.scrollTop ?? 0
  const anchorIndex = getCurrentIndex(anchorScrollTop)
  const anchorOffset = anchorScrollTop - getPageTop(anchorIndex)

  const nextHeights = heights.value.slice()
  nextHeights[index] = nextHeight
  heights.value = nextHeights
  rebuildPrefixSums(nextHeights)

  if (el) {
    isAdjusting = true
    el.scrollTop = Math.max(0, getPageTop(anchorIndex) + anchorOffset)
    window.requestAnimationFrame(() => {
      isAdjusting = false
      updateVisibleRange(el.scrollTop)
    })
  }
}

function onImageLoad(index: number, ev: Event) {
  const img = ev.target as HTMLImageElement | null
  if (!img || img.naturalWidth <= 0 || img.naturalHeight <= 0) return
  const width = containerWidth.value || containerRef.value?.clientWidth || img.clientWidth
  if (width <= 0) return
  updateMeasuredHeight(index, width * img.naturalHeight / img.naturalWidth)
}

let scrollRafId: number | null = null
let scrollSyncRafId: number | null = null
let scrollSyncToken = 0
let isAdjusting = false

function onScroll() {
  if (!containerRef.value || isAdjusting || zoomScale.value > 1) return
  if (scrollRafId !== null) return
  scrollRafId = requestAnimationFrame(() => {
    scrollRafId = null
    const el = containerRef.value
    if (!el) return
    updateContainerSize()
    const st = el.scrollTop
    updateVisibleRange(st)
    emitCurrentIndex(getCurrentIndex(st))
  })
}

function applyScrollToIndex(index: number) {
  if (!containerRef.value || props.totalCount <= 0) return true
  resetZoom(false)
  updateContainerSize()
  const next = clampIndex(index)
  const targetTop = getScrollTarget(next)
  isAdjusting = true
  containerRef.value.scrollTop = targetTop
  const actualTop = containerRef.value.scrollTop
  updateVisibleRange(actualTop)
  emitCurrentIndex(next)
  window.requestAnimationFrame(() => {
    isAdjusting = false
  })
  return next === 0 || Math.abs(actualTop - targetTop) < 2 || getCurrentIndex(actualTop) === next
}

function scheduleScrollToIndex(index: number, retries = SCROLL_SYNC_RETRY_MAX) {
  const token = ++scrollSyncToken
  nextTick(() => {
    scrollSyncRafId = window.requestAnimationFrame(() => {
      scrollSyncRafId = null
      if (token !== scrollSyncToken) return
      const done = applyScrollToIndex(index)
      if (!done && retries > 0) {
        scheduleScrollToIndex(index, retries - 1)
      }
    })
  })
}

function scrollToIndex(index: number) {
  scheduleScrollToIndex(index)
}

function refreshAfterResize() {
  const oldWidth = containerWidth.value
  if (!updateContainerSize()) return
  if (oldWidth <= 0) {
    updateVisibleRange(containerRef.value?.scrollTop ?? 0)
    scheduleScrollToIndex(props.currentIndex)
    return
  }
  if (oldWidth === containerWidth.value) {
    updateVisibleRange(containerRef.value?.scrollTop ?? 0)
    return
  }

  const ratio = containerWidth.value / oldWidth
  const anchorScrollTop = containerRef.value?.scrollTop ?? 0
  const anchorIndex = getCurrentIndex(anchorScrollTop)
  const anchorOffset = anchorScrollTop - getPageTop(anchorIndex)
  const nextHeights = heights.value.map((h) => h * ratio)
  heights.value = nextHeights
  rebuildPrefixSums(nextHeights)
  if (containerRef.value) {
    containerRef.value.scrollTop = getPageTop(anchorIndex) + anchorOffset * ratio
    updateVisibleRange(containerRef.value.scrollTop)
  }
}

let resizeObserver: ResizeObserver | null = null

// ---- 手势临时变量 ----
let fingers0 = 0
let startX = 0
let startY = 0
let startTx = 0
let startTy = 0
let startScale = 1
let pinchD0 = 0
let pinchRX = 0
let pinchRY = 0
let pinchContentX = 0
let pinchContentY = 0
let moved = false
let lastTapT = 0
let lastTapX = 0
let lastTapY = 0

function dist(t: TouchList) {
  if (t.length < 2) return 0
  const dx = t[0].clientX - t[1].clientX
  const dy = t[0].clientY - t[1].clientY
  return Math.sqrt(dx * dx + dy * dy)
}

function mid(t: TouchList) {
  return {
    x: (t[0].clientX + t[1].clientX) / 2,
    y: (t[0].clientY + t[1].clientY) / 2,
  }
}

function getViewportContentPoint(relX: number, relY: number, scale = zoomScale.value) {
  const scrollTop = containerRef.value?.scrollTop ?? 0
  const safeScale = scale || 1
  return {
    x: (relX - zoomTx.value) / safeScale,
    y: (scrollTop + relY - zoomTy.value) / safeScale,
  }
}

function applyZoomAtContentPoint(scale: number, relX: number, relY: number, contentX: number, contentY: number) {
  const scrollTop = containerRef.value?.scrollTop ?? 0
  zoomScale.value = scale
  zoomTx.value = relX - contentX * scale
  zoomTy.value = scrollTop + relY - contentY * scale
}

function zoomAtViewportPoint(relX: number, relY: number, scale: number) {
  const contentPoint = getViewportContentPoint(relX, relY)
  applyZoomAtContentPoint(scale, relX, relY, contentPoint.x, contentPoint.y)
}

function onTouch(ev: TouchEvent) {
  if (ev.type === 'touchstart') onTS(ev)
  else if (ev.type === 'touchmove') onTM(ev)
  else if (ev.type === 'touchend' || ev.type === 'touchcancel') onTE(ev)
}

function onTS(ev: TouchEvent) {
  fingers0 = ev.touches.length

  if (ev.touches.length >= 2) {
    ev.preventDefault()
    pinchD0 = dist(ev.touches)
    startScale = zoomScale.value
    startTx = zoomTx.value
    startTy = zoomTy.value
    const m = mid(ev.touches)
    const rect = containerRef.value?.getBoundingClientRect()
    pinchRX = m.x - (rect?.left ?? 0)
    pinchRY = m.y - (rect?.top ?? 0)
    const contentPoint = getViewportContentPoint(pinchRX, pinchRY, startScale)
    pinchContentX = contentPoint.x
    pinchContentY = contentPoint.y
    moved = false
    return
  }

  startX = ev.touches[0].clientX
  startY = ev.touches[0].clientY
  moved = false

  if (zoomScale.value > 1) {
    startTx = zoomTx.value
    startTy = zoomTy.value
  }
}

function onTM(ev: TouchEvent) {
  if (ev.touches.length >= 2) {
    ev.preventDefault()
    const d = dist(ev.touches)
    if (pinchD0 <= 0) return
    const ns = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, startScale * (d / pinchD0)))
    applyZoomAtContentPoint(ns, pinchRX, pinchRY, pinchContentX, pinchContentY)
    updateZoomCurrentIndex()
    moved = true
    return
  }

  if (zoomScale.value > 1) {
    ev.preventDefault()
    const dx = ev.touches[0].clientX - startX
    const dy = ev.touches[0].clientY - startY
    if (Math.abs(dx) > 2 || Math.abs(dy) > 2) moved = true
    const cw = containerRef.value?.clientWidth ?? 0
    const ch = containerRef.value?.clientHeight ?? 0
    const st = containerRef.value?.scrollTop ?? 0
    const contentHeight = Math.max(innerHeight.value, ch)
    const minTx = cw - cw * zoomScale.value
    const minTy = st + ch - contentHeight * zoomScale.value
    const maxTy = st
    zoomTx.value = Math.max(minTx, Math.min(0, startTx + dx))
    zoomTy.value = Math.max(minTy, Math.min(maxTy, startTy + dy))
    updateZoomCurrentIndex()
    return
  }

  const dx = ev.touches[0].clientX - startX
  const dy = ev.touches[0].clientY - startY
  if (Math.abs(dx) > 6 || Math.abs(dy) > 6) moved = true
}

function onTE(ev: TouchEvent) {
  if (ev.touches.length > 0) {
    if (fingers0 >= 2 && zoomScale.value > 1) {
      startX = ev.touches[0].clientX
      startY = ev.touches[0].clientY
      startTx = zoomTx.value
      startTy = zoomTy.value
    }
    return
  }

  if (fingers0 >= 2) {
    if (zoomScale.value < 1.05) resetZoom(true)
    return
  }

  const ex = ev.changedTouches[0].clientX
  const ey = ev.changedTouches[0].clientY
  const elapsed = Date.now() - lastTapT
  const tapDist = Math.abs(ex - lastTapX) + Math.abs(ey - lastTapY)

  if (zoomScale.value > 1) {
    lastTapT = Date.now()
    lastTapX = ex
    lastTapY = ey
    if (!moved && elapsed < DOUBLE_TAP_MS && tapDist < DOUBLE_TAP_DIST) resetZoom(true)
    return
  }

  if (!moved && elapsed < DOUBLE_TAP_MS && tapDist < DOUBLE_TAP_DIST) {
    const rect = containerRef.value?.getBoundingClientRect()
    const relX = ex - (rect?.left ?? 0)
    const relY = ey - (rect?.top ?? 0)
    zoomAtViewportPoint(relX, relY, 2)
  }

  lastTapT = Date.now()
  lastTapX = ex
  lastTapY = ey
}

watch(() => props.totalCount, () => {
  nextTick(() => {
    updateContainerSize()
    resetHeightModel()
    scheduleScrollToIndex(props.currentIndex)
  })
}, {immediate: true})

watch(() => props.currentIndex, (index) => {
  if (index < 0 || index >= props.totalCount) return
  if (index === lastEmitIndex.value) return
  scheduleScrollToIndex(index)
})

onMounted(() => {
  nextTick(() => {
    updateContainerSize()
    resetHeightModel()
    scheduleScrollToIndex(props.currentIndex)
  })

  const el = containerRef.value
  if (!el) return
  el.addEventListener('touchstart', onTouch, {passive: false})
  el.addEventListener('touchmove', onTouch, {passive: false})
  el.addEventListener('touchend', onTouch)
  el.addEventListener('touchcancel', onTouch)

  resizeObserver = new ResizeObserver(() => refreshAfterResize())
  resizeObserver.observe(el)
})

onUnmounted(() => {
  if (scrollRafId !== null) cancelAnimationFrame(scrollRafId)
  if (scrollSyncRafId !== null) cancelAnimationFrame(scrollSyncRafId)
  resizeObserver?.disconnect()
  resizeObserver = null
  const el = containerRef.value
  if (!el) return
  el.removeEventListener('touchstart', onTouch)
  el.removeEventListener('touchmove', onTouch)
  el.removeEventListener('touchend', onTouch)
  el.removeEventListener('touchcancel', onTouch)
})

defineExpose({scrollToIndex, containerRef})
</script>

<style scoped>
.vertical-container {
  height: 100%;
  overflow-y: auto;
  overscroll-behavior: none;
  overflow-x: hidden;
  background: #000;
  -webkit-overflow-scrolling: touch;
  touch-action: pan-y;
}
.virtual-inner {
  position: relative;
  width: 100%;
}
.zoom-wrapper {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  will-change: transform;
}
.image-wrapper {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  font-size: 0;
  line-height: 0;
  background: #111;
  will-change: transform;
  overflow: hidden;
}
.reader-image {
  display: block;
  width: 100%;
  height: auto;
  min-height: 120px;
  pointer-events: none;
  user-select: none;
  -webkit-user-drag: none;
}
.skeleton-image {
  width: 100%;
  height: 100%;
  min-height: 320px;
  background: linear-gradient(90deg, #222 25%, #3a3a3a 50%, #222 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s ease-in-out infinite;
}
@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
.edge-indicator {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 80px;
  padding: 32px 0;
  text-align: center;
  color: #666;
  font-size: 14px;
  letter-spacing: 2px;
  user-select: none;
}
.vertical-container.noscroll {
  overflow-y: hidden;
  touch-action: none;
}
</style>
