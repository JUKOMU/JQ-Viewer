<template>
  <div ref="containerRef" class="horizontal-container">
    <div class="strip" :style="stripStyle">
      <div
        v-for="idx in visibleIndices"
        :key="idx"
        class="page-slot"
        :style="slotStyle(idx)"
      >
        <div
          class="page-content"
          :style="idx === displayIndex ? contentStyle : undefined"
        >
          <template v-if="imageMap.get(idx + 1)">
            <img :src="imageMap.get(idx + 1)!" class="page-image" alt=""/>
          </template>
          <template v-else>
            <div class="skeleton-page"/>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({name: 'HorizontalPageView'})

const props = defineProps<{
  imageMap: Map<number, string>
  totalCount: number
  currentIndex: number
}>()

const emit = defineEmits<{
  'update:currentIndex': [index: number]
  'toggle-toolbar': []
}>()

import {computed, onMounted, onUnmounted, ref, watch} from 'vue'

const SWIPE_THRESHOLD = 60
const ZOOM_MAX = 4
const ZOOM_MIN = 1
const DOUBLE_TAP_MS = 280
const DOUBLE_TAP_DIST = 30

const containerRef = ref<HTMLElement | null>(null)
const displayIndex = ref(0)
const offsetX = ref(0)
const isAnimating = ref(false)
const slotWidth = ref(0)

// ---- 缩放状态 ----
const zoomScale = ref(1)
const zoomTx = ref(0)
const zoomTy = ref(0)

const contentStyle = computed(() => {
  if (zoomScale.value <= 1) return undefined
  return {
    transform: `translate(${zoomTx.value}px, ${zoomTy.value}px) scale(${zoomScale.value})`,
    transformOrigin: '0 0',
  }
})

function resetZoom() {
  zoomScale.value = 1
  zoomTx.value = 0
  zoomTy.value = 0
  if (tapTimer) { clearTimeout(tapTimer); tapTimer = null }
}

// ---- 手势临时变量 ----
let startX = 0, startY = 0, startTime = 0
let startTx = 0, startTy = 0
let startScale = 1
let pinchDist0 = 0
let pinchRelX = 0, pinchRelY = 0
let fingers = 0
let moved = false
let lastTapT = 0, lastTapX = 0, lastTapY = 0
let tapTimer: ReturnType<typeof setTimeout> | null = null

// ---- 可见槽位 ----
const visibleIndices = computed(() => {
  const di = displayIndex.value
  const r: number[] = []
  if (di > 0) r.push(di - 1)
  r.push(di)
  if (di < props.totalCount - 1) r.push(di + 1)
  return r
})

const stripStyle = computed(() => {
  const baseX = -displayIndex.value * slotWidth.value + offsetX.value
  return {
    transform: `translate3d(${baseX}px, 0, 0)`,
    transition: isAnimating.value ? 'transform 0.3s ease' : 'none',
    width: props.totalCount * slotWidth.value + 'px',
  }
})

watch(() => props.currentIndex, (val) => {
  if (!isAnimating.value) {
    if (displayIndex.value !== val) resetZoom()
    displayIndex.value = val
    offsetX.value = 0
  }
})

onMounted(() => {
  displayIndex.value = props.currentIndex
  updateSlotWidth()
  containerRef.value?.addEventListener('touchstart', onTouchStart, {passive: false})
  containerRef.value?.addEventListener('touchmove', onTouchMove, {passive: false})
  containerRef.value?.addEventListener('touchend', onTouchEnd)
  containerRef.value?.addEventListener('touchcancel', onTouchEnd)
})

onUnmounted(() => {
  if (tapTimer) { clearTimeout(tapTimer); tapTimer = null }
  containerRef.value?.removeEventListener('touchstart', onTouchStart)
  containerRef.value?.removeEventListener('touchmove', onTouchMove)
  containerRef.value?.removeEventListener('touchend', onTouchEnd)
  containerRef.value?.removeEventListener('touchcancel', onTouchEnd)
})

function updateSlotWidth() {
  slotWidth.value = containerRef.value?.clientWidth ?? 0
}

function slotStyle(idx: number) {
  const zoomed = idx === displayIndex.value && zoomScale.value > 1
  return {
    left: idx * 100 + 'vw',
    zIndex: zoomed ? 1 : 0,
    overflow: zoomed ? 'visible' : 'hidden',
  }
}

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

// ==================== 手势 ====================

function onTouchStart(ev: TouchEvent) {
  updateSlotWidth()
  isAnimating.value = false
  fingers = ev.touches.length

  if (ev.touches.length >= 2) {
    ev.preventDefault()
    // 取消滑动偏移
    if (offsetX.value !== 0) { offsetX.value = 0 }

    pinchDist0 = dist(ev.touches)
    startScale = zoomScale.value
    startTx = zoomTx.value
    startTy = zoomTy.value
    const m = mid(ev.touches)
    pinchRelX = m.x - offsetX.value
    pinchRelY = m.y
    return
  }

  startX = ev.touches[0].clientX
  startY = ev.touches[0].clientY
  startTime = Date.now()
  moved = false

  if (zoomScale.value > 1) {
    startTx = zoomTx.value
    startTy = zoomTy.value
  }
}

function onTouchMove(ev: TouchEvent) {
  if (ev.touches.length >= 2) {
    ev.preventDefault()
    const d = dist(ev.touches)
    if (pinchDist0 <= 0) return
    const ns = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, startScale * (d / pinchDist0)))
    // tx = relX * (1 - s2/s1) + startTx * (s2/s1)
    const ratio = ns / startScale
    zoomScale.value = ns
    zoomTx.value = pinchRelX * (1 - ratio) + startTx * ratio
    zoomTy.value = pinchRelY * (1 - ratio) + startTy * ratio
    moved = true
    return
  }

  if (zoomScale.value > 1) {
    ev.preventDefault()
    const dx = ev.touches[0].clientX - startX
    const dy = ev.touches[0].clientY - startY
    if (Math.abs(dx) > 2 || Math.abs(dy) > 2) moved = true
    const cw = slotWidth.value
    const ch = containerRef.value?.clientHeight ?? 0
    const minTx = cw - cw * zoomScale.value
    const minTy = ch - ch * zoomScale.value
    zoomTx.value = Math.max(minTx, Math.min(0, startTx + dx))
    zoomTy.value = Math.max(minTy, Math.min(0, startTy + dy))
    return
  }

  const dx = ev.touches[0].clientX - startX
  if (Math.abs(dx) > 6) moved = true
  offsetX.value = dx
}

function onTouchEnd(ev: TouchEvent) {
  if (ev.touches.length > 0) return

  if (fingers >= 2) {
    if (zoomScale.value < 1.05) resetZoom()
    return
  }

  // ---- 缩放后的操作 ----
  if (zoomScale.value > 1) {
    const ex = ev.changedTouches[0].clientX
    const ey = ev.changedTouches[0].clientY

    const elapsed = Date.now() - lastTapT
    const tapDist = Math.abs(ex - lastTapX) + Math.abs(ey - lastTapY)
    lastTapT = Date.now()
    lastTapX = ex
    lastTapY = ey

    // 双击 → 恢复
    if (!moved && elapsed < DOUBLE_TAP_MS && tapDist < DOUBLE_TAP_DIST) {
      resetZoom()
      return
    }

    // 单击 → 翻页（左/右 20%）或工具栏（中 60%）
    if (!moved && elapsed > DOUBLE_TAP_MS) {
      const sw = slotWidth.value || window.innerWidth
      if (ex < sw * 0.2) {
        if (displayIndex.value > 0) {
          resetZoom()
          emit('update:currentIndex', displayIndex.value - 1)
          displayIndex.value--
          offsetX.value = 0
        }
      } else if (ex > sw * 0.8) {
        if (displayIndex.value < props.totalCount - 1) {
          resetZoom()
          emit('update:currentIndex', displayIndex.value + 1)
          displayIndex.value++
          offsetX.value = 0
        }
      } else {
        emit('toggle-toolbar')
      }
    }
    return
  }

  // ---- 1x 下的操作 ----
  const ex = ev.changedTouches[0].clientX
  const ey = ev.changedTouches[0].clientY
  const dx = ex - startX
  const elapsed = Date.now() - startTime

  if (moved && Math.abs(dx) > SWIPE_THRESHOLD) {
    if (dx > 0 && displayIndex.value > 0) snapTo(displayIndex.value - 1)
    else if (dx < 0 && displayIndex.value < props.totalCount - 1) snapTo(displayIndex.value + 1)
    else snapBack()
    return
  }
  if (moved) { snapBack(); return }

  if (elapsed < 300) {
    const sw = slotWidth.value || window.innerWidth
    if (ex < sw * 0.3) {
      if (displayIndex.value > 0) {
        resetZoom()
        emit('update:currentIndex', displayIndex.value - 1)
        displayIndex.value--
        offsetX.value = 0
      }
    } else if (ex > sw * 0.6) {
      if (displayIndex.value < props.totalCount - 1) {
        resetZoom()
        emit('update:currentIndex', displayIndex.value + 1)
        displayIndex.value++
        offsetX.value = 0
      }
    } else {
      const tapDist = Math.abs(ex - lastTapX) + Math.abs(ey - lastTapY)
      const le = Date.now() - lastTapT
      if (le < DOUBLE_TAP_MS && tapDist < DOUBLE_TAP_DIST) {
        if (tapTimer) { clearTimeout(tapTimer); tapTimer = null }
        const relX = ex - offsetX.value
        const relY = ey
        const os = zoomScale.value || 1
        const ratio = 2 / os
        zoomScale.value = 2
        zoomTx.value = relX * (1 - ratio) + zoomTx.value * ratio
        zoomTy.value = relY * (1 - ratio) + zoomTy.value * ratio
      } else {
        if (tapTimer) clearTimeout(tapTimer)
        tapTimer = setTimeout(() => { emit('toggle-toolbar'); tapTimer = null }, DOUBLE_TAP_MS)
      }
      lastTapT = Date.now()
      lastTapX = ex
      lastTapY = ey
    }
  }
}

function snapTo(target: number) {
  if (target === displayIndex.value) { snapBack(); return }
  resetZoom()
  isAnimating.value = true
  offsetX.value = -(target - displayIndex.value) * slotWidth.value
  setTimeout(() => {
    isAnimating.value = false
    displayIndex.value = target
    offsetX.value = 0
    emit('update:currentIndex', target)
  }, 320)
}

function snapBack() {
  isAnimating.value = true
  offsetX.value = 0
  setTimeout(() => { isAnimating.value = false }, 320)
}

function scrollToIndex(index: number) {
  if (index < 0 || index >= props.totalCount) return
  resetZoom()
  isAnimating.value = false
  displayIndex.value = index
  offsetX.value = 0
}

defineExpose({scrollToIndex})
</script>

<style scoped>
.horizontal-container {
  width: 100%;
  height: 100%;
  background: #000;
  overflow: hidden;
  display: flex;
  align-items: center;
}
.strip {
  position: relative;
  height: 100%;
  will-change: transform;
}
.page-slot {
  position: absolute;
  top: 0;
  width: 100vw;
  height: 100%;
  overflow: hidden;
}
.page-content {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  will-change: transform;
}
.page-image {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  display: block;
  pointer-events: none;
  user-select: none;
  -webkit-user-drag: none;
}
.skeleton-page {
  width: 80%;
  height: 60%;
  border-radius: 4px;
  background: linear-gradient(90deg, #222 25%, #3a3a3a 50%, #222 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s ease-in-out infinite;
}
@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
</style>
