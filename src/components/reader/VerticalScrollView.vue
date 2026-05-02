<template>
  <div ref="containerRef" class="vertical-container" @scroll="onScroll">
    <div
      v-for="item in visibleSlots"
      :key="item.sortOrder"
      ref="imageEls"
      :data-sort-order="item.sortOrder"
      class="image-wrapper"
    >
      <template v-if="item.dataUrl">
        <img :src="item.dataUrl" class="reader-image" alt="" />
      </template>
      <template v-else>
        <div class="skeleton-image" />
      </template>
    </div>
    <div class="scroll-spacer" :style="{ height: bottomSpacerHeight + 'px' }" />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'

interface ImageSlot {
  sortOrder: number
  dataUrl: string | null
}

const props = defineProps<{
  imageMap: Map<number, string>
  totalCount: number
  initialIndex: number
}>()

const emit = defineEmits<{
  'update:currentIndex': [index: number]
}>()

const containerRef = ref<HTMLElement | null>(null)
const imageEls = ref<HTMLElement[]>([])
const scrollTop = ref(0)
const imageTops = ref<number[]>([])

const bottomSpacerHeight = computed(() => Math.max(0, containerHeight.value))

// All slots: totalCount 个，显示 dataUrl 或 null（骨架）
const visibleSlots = computed<ImageSlot[]>(() => {
  const slots: ImageSlot[] = []
  for (let i = 0; i < props.totalCount; i++) {
    const sortOrder = i + 1
    slots.push({
      sortOrder,
      dataUrl: props.imageMap.get(sortOrder) ?? null,
    })
  }
  return slots
})

const containerHeight = ref(0)
const lastEmitIndex = ref(-1)

const recalcImagePositions = () => {
  const els = imageEls.value
  if (!els || els.length === 0) return
  const tops: number[] = []
  for (const el of els) {
    tops.push(el.offsetTop)
  }
  imageTops.value = tops
  if (containerRef.value) {
    containerHeight.value = containerRef.value.clientHeight
  }
}

// 加权区域评分：根据每张图片中点所在屏幕区域计算得分，得分最高者为当前页
const findCurrentIndex = (st: number): number => {
  const tops = imageTops.value
  const els = imageEls.value
  const ch = containerRef.value?.clientHeight ?? 0
  if (tops.length === 0 || ch === 0) return 0

  const screenTop = st
  const screenBottom = st + ch
  const zoneA_top = screenTop + ch * 0.25
  const zoneA_bottom = screenTop + ch * 0.75

  let bestIndex = 0
  let bestScore = -1

  for (let i = 0; i < tops.length; i++) {
    const top = tops[i]
    const height = els[i]?.offsetHeight ?? 0
    if (height === 0) continue

    const midpoint = top + height / 2
    if (midpoint < screenTop || midpoint > screenBottom) continue  // 不可见, score=0

    const score = (midpoint >= zoneA_top && midpoint <= zoneA_bottom) ? 3 : 1
    // 平局时取靠近顶部的（更小的 index）
    if (score > bestScore) {
      bestScore = score
      bestIndex = i
    }
  }

  return bestIndex
}

let scrollRafId: number | null = null
const onScroll = () => {
  if (!containerRef.value) return
  scrollTop.value = containerRef.value.scrollTop
  if (scrollRafId !== null) return
  scrollRafId = requestAnimationFrame(() => {
    scrollRafId = null
    recalcImagePositions()
    const idx = findCurrentIndex(scrollTop.value)
    if (idx !== lastEmitIndex.value) {
      lastEmitIndex.value = idx
      emit('update:currentIndex', idx)
    }
  })
}

let trackedIndex = -1
let trackedTop = 0
let initialScrollDone = false

onMounted(() => {
  nextTick(() => recalcImagePositions())
})

onUnmounted(() => {
  if (scrollRafId !== null) cancelAnimationFrame(scrollRafId)
})

// 数据就绪（totalCount 从 0 变为有值）→ 执行初始滚动
watch(
  () => props.totalCount,
  (count) => {
    if (initialScrollDone || count <= 0) return
    initialScrollDone = true
    trackedIndex = props.initialIndex
    nextTick(() => {
      recalcImagePositions()
      if (trackedIndex >= 0 && trackedIndex < imageEls.value.length) {
        const targetEl = imageEls.value[trackedIndex]
        if (targetEl && containerRef.value) {
          targetEl.scrollIntoView({ block: 'start', behavior: 'instant' })
          trackedTop = containerRef.value.scrollTop
          scrollTop.value = trackedTop
          lastEmitIndex.value = trackedIndex
          emit('update:currentIndex', trackedIndex)
        }
      }
    })
  },
)

// 图片加载后动态修正 scrollTop，追着目标图片顶部走
watch(
  () => props.imageMap.size,
  () => {
    nextTick(() => {
      recalcImagePositions()
      if (trackedIndex >= 0 && trackedIndex < imageTops.value.length) {
        const newTop = imageTops.value[trackedIndex]
        const delta = newTop - trackedTop
        if (delta !== 0 && containerRef.value) {
          containerRef.value.scrollTop += delta
          scrollTop.value = containerRef.value.scrollTop
          trackedTop = newTop
        }
      }
    })
  },
)

// 暴露给父组件：进度条拖拽跳转
const scrollToIndex = (index: number) => {
  if (index < 0 || index >= imageEls.value.length) return
  const targetEl = imageEls.value[index]
  if (!targetEl || !containerRef.value) return
  trackedIndex = index
  targetEl.scrollIntoView({ block: 'start', behavior: 'instant' })
  trackedTop = containerRef.value.scrollTop
  scrollTop.value = trackedTop
}

defineExpose({ scrollToIndex })
</script>

<style scoped>
.vertical-container {
  height: 100%;
  overflow-y: auto;
  overflow-x: hidden;
  background: #000;
  -webkit-overflow-scrolling: touch;
}

.image-wrapper {
  width: 100%;
  font-size: 0;
  line-height: 0;
  background: #111;
  content-visibility: auto;
  contain-intrinsic-size: auto 500px;
}

.reader-image {
  display: block;
  width: 100%;
  height: auto;
  min-height: 120px;
}

.skeleton-image {
  width: 100%;
  aspect-ratio: 3 / 4;
  background: linear-gradient(90deg, #222 25%, #3a3a3a 50%, #222 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s ease-in-out infinite;
}

@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

.scroll-spacer {
  width: 100%;
}
</style>
