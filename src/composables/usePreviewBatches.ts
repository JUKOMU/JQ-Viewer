import {computed, ref, type Ref} from 'vue'

export const PREVIEW_BATCH = 20

export interface PreviewImage {
  sortOrder: number
  dataUrl: string
}

export type PreviewImageSlotSetter = (sortOrder: number, dataUrl: string) => void

type PreviewBatchLoader = (
  start: number,
  end: number,
  setImageSlot: PreviewImageSlotSetter,
) => Promise<void>

const wait = (duration: number) => new Promise<void>((resolve) => setTimeout(resolve, duration))

/** 共用预览页的槽位、游标和分批加载行为。 */
export function usePreviewBatches(totalCount: Ref<number>, loadBatch: PreviewBatchLoader) {
  const slots = ref<(PreviewImage | null)[]>([])
  const displayCount = ref(0)
  const loadingMore = ref(false)
  const cursor = ref(0)
  const pendingSlots = new Map<number, PreviewImage>()
  let generation = 0

  const loadedCount = computed(() => slots.value.filter((slot) => slot !== null).length)
  const allVisible = computed(() => {
    const total = totalCount.value
    if (total <= 0) return true
    return displayCount.value >= total && slots.value.slice(0, total).every((slot) => slot !== null)
  })

  const setImageSlot = (targetGeneration: number, sortOrder: number, dataUrl: string) => {
    if (targetGeneration !== generation) return
    const index = sortOrder - 1
    if (index < 0) return

    const image = {sortOrder, dataUrl}
    if (index >= slots.value.length) {
      pendingSlots.set(index, image)
      return
    }
    slots.value[index] = image
  }

  const createImageSlotSetterFor =
    (targetGeneration: number): PreviewImageSlotSetter =>
      (sortOrder, dataUrl) =>
        setImageSlot(targetGeneration, sortOrder, dataUrl)

  const createImageSlotSetter = (): PreviewImageSlotSetter => createImageSlotSetterFor(generation)

  const flushPendingSlots = () => {
    for (const [index, image] of pendingSlots) {
      if (index >= slots.value.length) continue
      slots.value[index] = image
      pendingSlots.delete(index)
    }
  }

  const reset = () => {
    generation += 1
    slots.value = []
    displayCount.value = 0
    loadingMore.value = false
    cursor.value = 0
    pendingSlots.clear()
  }

  const initialize = async () => {
    if (loadingMore.value) return
    const activeGeneration = generation
    const firstCount = Math.min(PREVIEW_BATCH, totalCount.value)
    slots.value = new Array(firstCount).fill(null)
    displayCount.value = firstCount
    cursor.value = 0
    flushPendingSlots()

    if (!firstCount) return

    loadingMore.value = true
    try {
      await loadBatch(0, firstCount, createImageSlotSetterFor(activeGeneration))
      if (activeGeneration !== generation) return
      cursor.value = firstCount
      await wait(300)
      if (activeGeneration !== generation) return
    } catch {
      // 单批加载失败不阻断页面交互，保留游标和骨架槽位供后续重试。
    } finally {
      if (activeGeneration === generation) loadingMore.value = false
    }
  }

  const findFirstMissingBatch = (): { start: number; end: number } | null => {
    const visibleEnd = Math.min(displayCount.value, totalCount.value)
    for (let start = 0; start < visibleEnd; start += PREVIEW_BATCH) {
      const end = Math.min(start + PREVIEW_BATCH, visibleEnd)
      if (slots.value.slice(start, end).some((slot) => slot === null)) {
        return {start, end}
      }
    }
    return null
  }

  const isBatchFilled = (start: number, end: number): boolean =>
    slots.value.slice(start, end).every((slot) => slot !== null)

  const expandBatch = async (): Promise<boolean> => {
    if (allVisible.value || loadingMore.value) return false
    const activeGeneration = generation
    loadingMore.value = true

    try {
      await wait(400)
      if (activeGeneration !== generation) return false

      let missingBatch = findFirstMissingBatch()
      if (!missingBatch && displayCount.value < totalCount.value) {
        const newDisplayCount = Math.min(displayCount.value + PREVIEW_BATCH, totalCount.value)
        while (slots.value.length < newDisplayCount) {
          slots.value.push(null)
        }
        displayCount.value = newDisplayCount
        flushPendingSlots()
        missingBatch = findFirstMissingBatch()
        if (!missingBatch) {
          cursor.value = Math.max(cursor.value, newDisplayCount)
          return true
        }
      }

      if (!missingBatch) return false

      try {
        await loadBatch(
          missingBatch.start,
          missingBatch.end,
          createImageSlotSetterFor(activeGeneration),
        )
        if (activeGeneration !== generation) return false
        cursor.value = Math.max(cursor.value, missingBatch.end)
      } catch {
        // 保留当前游标，下次操作重试同一批次。
        return false
      }
      return isBatchFilled(missingBatch.start, missingBatch.end)
    } finally {
      if (activeGeneration === generation) loadingMore.value = false
    }
  }

  return {
    slots,
    displayCount,
    loadingMore,
    cursor,
    loadedCount,
    allVisible,
    createImageSlotSetter,
    reset,
    initialize,
    expandBatch,
  }
}
