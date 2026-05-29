<template>
  <div ref="containerRef" class="vertical-container" :class="{noscroll: zoomScale > 1}" @scroll="onScroll">
    <div ref="wrapperRef" class="zoom-wrapper" :style="wrapperStyle">
      <div
        v-for="item in visibleSlots"
        :key="item.sortOrder"
        ref="imageEls"
        :data-sort-order="item.sortOrder"
        class="image-wrapper"
      >
        <template v-if="item.dataUrl">
          <img :src="item.dataUrl" class="reader-image" alt=""/>
        </template>
        <template v-else>
          <div class="skeleton-image"/>
        </template>
      </div>
      <div class="end-indicator">— — — — E N D — — — —</div>
    </div>
    <div class="scroll-spacer" :style="{ height: bottomSpacerHeight + 'px' }"/>
  </div>
</template>

<script setup lang="ts">
defineOptions({name: 'VerticalScrollView'})

const props = defineProps<{
  imageMap: Map<number, string>
  totalCount: number
  initialIndex: number
}>()

const emit = defineEmits<{
  'update:currentIndex': [index: number]
}>()

import {computed, nextTick, onMounted, onUnmounted, ref, watch} from 'vue'

interface ImageSlot { sortOrder: number; dataUrl: string | null }

const ZOOM_MAX = 4
const ZOOM_MIN = 1
const DOUBLE_TAP_MS = 280
const DOUBLE_TAP_DIST = 30

const containerRef = ref<HTMLElement | null>(null)
const wrapperRef = ref<HTMLElement | null>(null)
const imageEls = ref<HTMLElement[]>([])
const scrollTop = ref(0)
const imageTops = ref<number[]>([])
const containerHeight = ref(0)
const bottomSpacerHeight = computed(() => Math.max(0, containerHeight.value / 2))

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

let savedScrollTop = 0

function resetZoom() {
  zoomScale.value = 1; zoomTx.value = 0; zoomTy.value = 0
  if (containerRef.value && savedScrollTop > 0) {
    isAdjusting = true
    containerRef.value.scrollTop = savedScrollTop
    isAdjusting = false
    savedScrollTop = 0
  }
}

// ---- 手势临时变量 ----
let fingers0 = 0
let startX = 0, startY = 0
let startTx = 0, startTy = 0
let startScale = 1
let pinchD0 = 0
let pinchRX = 0, pinchRY = 0
let moved = false
let lastTapT = 0, lastTapX = 0, lastTapY = 0

// ---- non-passive 事件注册（在 container 级别，拦截滚动） ----
onMounted(() => {
  nextTick(() => recalcImagePositions())
  const el = containerRef.value
  if (!el) return
  el.addEventListener('touchstart', onTouch, {passive: false})
  el.addEventListener('touchmove', onTouch, {passive: false})
  el.addEventListener('touchend', onTouch)
  el.addEventListener('touchcancel', onTouch)
})

onUnmounted(() => {
  if (scrollRafId !== null) cancelAnimationFrame(scrollRafId)
  const el = containerRef.value
  if (!el) return
  el.removeEventListener('touchstart', onTouch)
  el.removeEventListener('touchmove', onTouch)
  el.removeEventListener('touchend', onTouch)
  el.removeEventListener('touchcancel', onTouch)
})

function dist(t: TouchList) {
  if (t.length < 2) return 0
  const dx = t[0].clientX - t[1].clientX, dy = t[0].clientY - t[1].clientY
  return Math.sqrt(dx * dx + dy * dy)
}
function mid(t: TouchList) { return {x: (t[0].clientX + t[1].clientX) / 2, y: (t[0].clientY + t[1].clientY) / 2} }

// ---- 统一手势处理 ----
function onTouch(ev: TouchEvent) {
  if (ev.type === 'touchstart') onTS(ev)
  else if (ev.type === 'touchmove') onTM(ev)
  else if (ev.type === 'touchend' || ev.type === 'touchcancel') onTE(ev)
}

function onTS(ev: TouchEvent) {
  fingers0 = ev.touches.length

  if (ev.touches.length >= 2) {
    ev.preventDefault()
    // 首次进入缩放：把 scrollTop 归零补偿到 ty，同时保存原值
    if (zoomScale.value <= 1 && containerRef.value) {
      const st = containerRef.value.scrollTop
      if (st > 0) {
        savedScrollTop = st
        isAdjusting = true
        containerRef.value.scrollTop = 0
        zoomTy.value -= st
        isAdjusting = false
      }
    }
    pinchD0 = dist(ev.touches)
    startScale = zoomScale.value
    startTx = zoomTx.value; startTy = zoomTy.value
    const m = mid(ev.touches)
    const rect = containerRef.value?.getBoundingClientRect()
    pinchRX = m.x - (rect?.left ?? 0)
    pinchRY = m.y - (rect?.top ?? 0)
    moved = false
    return
  }

  startX = ev.touches[0].clientX
  startY = ev.touches[0].clientY
  moved = false

  if (zoomScale.value > 1) {
    startTx = zoomTx.value; startTy = zoomTy.value
  }
}

function onTM(ev: TouchEvent) {
  if (ev.touches.length >= 2) {
    ev.preventDefault()
    const d = dist(ev.touches)
    if (pinchD0 <= 0) return
    const ns = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, startScale * (d / pinchD0)))
    // tx = relX * (1 - s2/s1) + startTx * (s2/s1)
    const ratio = ns / startScale
    zoomScale.value = ns
    zoomTx.value = pinchRX * (1 - ratio) + startTx * ratio
    zoomTy.value = pinchRY * (1 - ratio) + startTy * ratio
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
    const wh = wrapperRef.value?.scrollHeight ?? 0
    const minTx = cw - cw * zoomScale.value
    const minTy = ch - wh * zoomScale.value
    zoomTx.value = Math.max(minTx, Math.min(0, startTx + dx))
    zoomTy.value = Math.max(minTy, Math.min(0, startTy + dy))
    return
  }

  // 1x: 不 preventDefault，让原生滚动处理
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
    if (zoomScale.value < 1.05) resetZoom()
    return
  }

  // 缩放后的操作
  if (zoomScale.value > 1) {
    const ex = ev.changedTouches[0].clientX
    const ey = ev.changedTouches[0].clientY
    const elapsed = Date.now() - lastTapT
    const tapDist = Math.abs(ex - lastTapX) + Math.abs(ey - lastTapY)
    lastTapT = Date.now(); lastTapX = ex; lastTapY = ey
    if (!moved && elapsed < DOUBLE_TAP_MS && tapDist < DOUBLE_TAP_DIST) resetZoom()
    return
  }

  // 1x 双击放大
  const ex = ev.changedTouches[0].clientX
  const ey = ev.changedTouches[0].clientY
  const elapsed = Date.now() - lastTapT
  const tapDist = Math.abs(ex - lastTapX) + Math.abs(ey - lastTapY)
  if (!moved && elapsed < DOUBLE_TAP_MS && tapDist < DOUBLE_TAP_DIST) {
    // scrollTop 归零补偿
    if (containerRef.value) {
      const st = containerRef.value.scrollTop
      if (st > 0) {
        savedScrollTop = st
        isAdjusting = true
        containerRef.value.scrollTop = 0
        zoomTy.value -= st
        isAdjusting = false
      }
    }
    const rect = containerRef.value?.getBoundingClientRect()
    const relX = ex - (rect?.left ?? 0)
    const relY = ey - (rect?.top ?? 0)
    const os = zoomScale.value || 1
    const ratio = 2 / os
    zoomScale.value = 2
    zoomTx.value = relX * (1 - ratio) + zoomTx.value * ratio
    zoomTy.value = relY * (1 - ratio) + zoomTy.value * ratio
  }
  lastTapT = Date.now(); lastTapX = ex; lastTapY = ey
}

// ---- 内容 slot ----
const visibleSlots = computed<ImageSlot[]>(() => {
  const slots: ImageSlot[] = []
  for (let i = 0; i < props.totalCount; i++) {
    slots.push({sortOrder: i + 1, dataUrl: props.imageMap.get(i + 1) ?? null})
  }
  return slots
})

// ---- 页码检测 ----
const lastEmitIndex = ref(-1)
function recalcImagePositions() {
  const els = imageEls.value
  if (!els || els.length === 0) return
  const tops: number[] = []
  for (const el of els) tops.push(el.offsetTop)
  imageTops.value = tops
  if (containerRef.value) containerHeight.value = containerRef.value.clientHeight
}

function findCurrentIndex(st: number): number {
  const tops = imageTops.value
  const els = imageEls.value
  const ch = containerRef.value?.clientHeight ?? 0
  if (tops.length === 0 || ch === 0) return 0
  const st2 = st + ch
  const za = st + ch * 0.25, zb = st + ch * 0.75
  let best = 0, bestS = -1
  for (let i = 0; i < tops.length; i++) {
    const h = els[i]?.offsetHeight ?? 0
    if (h === 0) continue
    const mp = tops[i] + h / 2
    if (mp < st || mp > st2) continue
    const s = mp >= za && mp <= zb ? 3 : 1
    if (s > bestS) { bestS = s; best = i }
  }
  return best
}

let scrollRafId: number | null = null
function onScroll() {
  if (!containerRef.value || isAdjusting || zoomScale.value > 1) return
  if (scrollRafId !== null) return
  scrollRafId = requestAnimationFrame(() => {
    scrollRafId = null
    const st = containerRef.value?.scrollTop ?? 0
    scrollTop.value = st
    recalcImagePositions()
    const idx = findCurrentIndex(st)
    if (idx !== lastEmitIndex.value) {
      lastEmitIndex.value = idx
      trackedIdx = idx
      trackedTop = imageTops.value[idx] ?? trackedTop
      emit('update:currentIndex', idx)
    }
  })
}

let trackedIdx = -1, trackedTop = 0, initialDone = false, isAdjusting = false

watch(() => props.totalCount, (c) => {
  if (initialDone || c <= 0) return
  initialDone = true; trackedIdx = props.initialIndex
  nextTick(() => {
    recalcImagePositions()
    if (trackedIdx >= 0 && trackedIdx < imageEls.value.length) {
      const el = imageEls.value[trackedIdx]
      if (el && containerRef.value) {
        el.scrollIntoView({block: 'start', behavior: 'instant'})
        trackedTop = containerRef.value.scrollTop
        scrollTop.value = trackedTop
        lastEmitIndex.value = trackedIdx
        emit('update:currentIndex', trackedIdx)
      }
    }
  })
}, { immediate: true })

watch(() => props.imageMap.size, () => {
  nextTick(() => {
    recalcImagePositions()
    if (trackedIdx >= 0 && trackedIdx < imageTops.value.length) {
      const nt = imageTops.value[trackedIdx]
      const delta = nt - trackedTop
      if (delta !== 0 && containerRef.value) {
        isAdjusting = true
        if (scrollRafId !== null) { cancelAnimationFrame(scrollRafId); scrollRafId = null }
        containerRef.value.scrollTop += delta
        scrollTop.value = containerRef.value.scrollTop
        trackedTop = nt
        nextTick(() => { isAdjusting = false })
      }
    }
  })
})

function scrollToIndex(index: number) {
  if (index < 0 || index >= imageEls.value.length) return
  const el = imageEls.value[index]
  if (!el || !containerRef.value) return
  resetZoom()
  trackedIdx = index; lastEmitIndex.value = index
  el.scrollIntoView({block: 'start', behavior: 'instant'})
  trackedTop = containerRef.value.scrollTop
  scrollTop.value = trackedTop
}

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
.zoom-wrapper { will-change: transform; }
.image-wrapper {
  width: 100%;
  font-size: 0; line-height: 0;
  background: #111;
  content-visibility: auto;
  contain-intrinsic-size: auto 500px;
}
.reader-image { display: block; width: 100%; height: auto; min-height: 120px; }
.skeleton-image {
  width: 100%; aspect-ratio: 3/4;
  background: linear-gradient(90deg, #222 25%, #3a3a3a 50%, #222 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s ease-in-out infinite;
}
@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
.end-indicator {
  width: 100%; padding: 32px 0;
  text-align: center; color: #666; font-size: 14px;
  letter-spacing: 2px; user-select: none;
}
.vertical-container.noscroll {
  overflow-y: hidden;
  touch-action: none;
}
.scroll-spacer { width: 100%; }
</style>
