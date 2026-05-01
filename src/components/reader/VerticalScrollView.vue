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
import { computed, nextTick, onMounted, ref, watch } from 'vue'

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

// 根据 scrollTop 查找当前页
const findCurrentIndex = (st: number): number => {
  const tops = imageTops.value
  if (tops.length === 0) return 0
  for (let i = tops.length - 1; i >= 0; i--) {
    if (tops[i] <= st + 4) {
      return i
    }
  }
  return 0
}

let scrollTimer: ReturnType<typeof setTimeout> | null = null
const onScroll = () => {
  if (!containerRef.value) return
  scrollTop.value = containerRef.value.scrollTop
  if (scrollTimer) return
  scrollTimer = setTimeout(() => {
    scrollTimer = null
    recalcImagePositions()
    const idx = findCurrentIndex(scrollTop.value)
    if (idx !== lastEmitIndex.value) {
      lastEmitIndex.value = idx
      emit('update:currentIndex', idx)
    }
  }, 80)
}

let trackedIndex = -1
let trackedTop = 0
let initialScrollDone = false

onMounted(() => {
  nextTick(() => recalcImagePositions())
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
      if (trackedIndex >= 0 && trackedIndex < imageTops.value.length) {
        trackedTop = imageTops.value[trackedIndex]
        if (containerRef.value) {
          containerRef.value.scrollTop = trackedTop
        }
        scrollTop.value = trackedTop
        lastEmitIndex.value = trackedIndex
        emit('update:currentIndex', trackedIndex)
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
  if (index < 0 || index >= imageTops.value.length) return
  trackedIndex = index
  trackedTop = imageTops.value[index]
  if (containerRef.value) {
    containerRef.value.scrollTop = trackedTop
    scrollTop.value = trackedTop
  }
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
  background: linear-gradient(180deg, #1a1a1a, #222);
  animation: skeleton-pulse 1.5s ease-in-out infinite;
}

@keyframes skeleton-pulse {
  0%, 100% { opacity: 0.3; }
  50% { opacity: 0.6; }
}

.scroll-spacer {
  width: 100%;
}
</style>
