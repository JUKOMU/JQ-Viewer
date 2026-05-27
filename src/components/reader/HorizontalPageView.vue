<template>
  <div
    ref="containerRef"
    class="horizontal-container"
    @touchstart="onTouchStart"
    @touchmove="onTouchMove"
    @touchend="onTouchEnd"
    @touchcancel="onTouchEnd"
  >
    <div class="strip" :style="stripStyle">
      <div
        v-for="idx in visibleIndices"
        :key="idx"
        class="page-slot"
        :style="{ left: idx * 100 + 'vw' }"
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

import {computed, onMounted, ref, watch} from 'vue'

const SWIPE_THRESHOLD = 60

const containerRef = ref<HTMLElement | null>(null)
const displayIndex = ref(0)
const offsetX = ref(0)
const isAnimating = ref(false)
const slotWidth = ref(0)

let startX = 0
let startTime = 0
let hasMoved = false

// 只渲染当前页前后各一张
const visibleIndices = computed(() => {
  const di = displayIndex.value
  const result: number[] = []
  if (di > 0) result.push(di - 1)
  result.push(di)
  if (di < props.totalCount - 1) result.push(di + 1)
  return result
})

// 基于全量 strip 坐标系的 transform，动画后重置不跳变
const stripStyle = computed(() => {
  const baseX = -displayIndex.value * slotWidth.value + offsetX.value
  return {
    transform: `translate3d(${baseX}px, 0, 0)`,
    transition: isAnimating.value ? 'transform 0.3s ease' : 'none',
    width: props.totalCount * slotWidth.value + 'px',
  }
})

watch(
  () => props.currentIndex,
  (val) => {
    if (!isAnimating.value) {
      displayIndex.value = val
      offsetX.value = 0
    }
  },
)

onMounted(() => {
  displayIndex.value = props.currentIndex
  updateSlotWidth()
})

const updateSlotWidth = () => {
  if (containerRef.value) {
    slotWidth.value = containerRef.value.clientWidth
  }
}

// ---- 手势 ----
const onTouchStart = (ev: TouchEvent) => {
  updateSlotWidth()
  isAnimating.value = false
  startX = ev.touches[0].clientX
  startTime = Date.now()
  hasMoved = false
}

const onTouchMove = (ev: TouchEvent) => {
  const dx = ev.touches[0].clientX - startX
  if (Math.abs(dx) > 6) hasMoved = true
  offsetX.value = dx
}

const onTouchEnd = (ev: TouchEvent) => {
  const endX = ev.changedTouches[0].clientX
  const dx = endX - startX
  const elapsed = Date.now() - startTime

  // 划动 → 吸附翻页
  if (hasMoved && Math.abs(dx) > SWIPE_THRESHOLD) {
    if (dx > 0 && displayIndex.value > 0) {
      snapTo(displayIndex.value - 1)
    } else if (dx < 0 && displayIndex.value < props.totalCount - 1) {
      snapTo(displayIndex.value + 1)
    } else {
      snapBack()
    }
    return
  }

  if (hasMoved) {
    snapBack()
    return
  }

  // 点击 → 直接切页，无动画
  if (elapsed < 300) {
    const sw = window.innerWidth
    if (endX < sw * 0.3) {
      if (displayIndex.value > 0) {
        emit('update:currentIndex', displayIndex.value - 1)
        displayIndex.value--
        offsetX.value = 0
      }
    } else if (endX > sw * 0.6) {
      if (displayIndex.value < props.totalCount - 1) {
        emit('update:currentIndex', displayIndex.value + 1)
        displayIndex.value++
        offsetX.value = 0
      }
    } else {
      emit('toggle-toolbar')
    }
  }
}

const snapTo = (targetIndex: number) => {
  if (targetIndex === displayIndex.value) {
    snapBack()
    return
  }
  isAnimating.value = true
  // 保持 displayIndex 不变，用 offsetX 过渡到目标页在全量坐标系中的位置
  const targetOffsetX = -(targetIndex - displayIndex.value) * slotWidth.value
  offsetX.value = targetOffsetX

  setTimeout(() => {
    // 动画结束：静默重置，transform 不变
    isAnimating.value = false
    displayIndex.value = targetIndex
    offsetX.value = 0
    emit('update:currentIndex', targetIndex)
  }, 320)
}

const snapBack = () => {
  isAnimating.value = true
  offsetX.value = 0
  setTimeout(() => {
    isAnimating.value = false
  }, 320)
}

const scrollToIndex = (index: number) => {
  if (index < 0 || index >= props.totalCount) return
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
  display: flex;
  align-items: center;
  justify-content: center;
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
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}
</style>
