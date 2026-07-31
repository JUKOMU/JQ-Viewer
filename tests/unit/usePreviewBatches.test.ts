import {ref} from 'vue'
import {afterEach, describe, expect, test, vi} from 'vitest'
import {usePreviewBatches} from '@/composables/usePreviewBatches'

type ImageSlotSetter = (sortOrder: number, dataUrl: string) => void
type BatchLoader = (start: number, end: number, setImageSlot: ImageSlotSetter) => Promise<void>

afterEach(() => {
  vi.useRealTimers()
})

describe('usePreviewBatches', () => {
  test('首批槽位创建前到达的图片事件会在初始化后回填', async () => {
    vi.useFakeTimers()
    const totalCount = ref(20)
    const loadBatch = vi.fn(async () => {
    })
    const previewBatches = usePreviewBatches(totalCount, loadBatch)
    const setImageSlot = previewBatches.createImageSlotSetter()

    setImageSlot(3, 'early-image')
    const initializePromise = previewBatches.initialize()
    await vi.runAllTimersAsync()
    await initializePromise

    expect(previewBatches.slots.value[2]).toEqual({
      sortOrder: 3,
      dataUrl: 'early-image',
    })
    expect(previewBatches.loadedCount.value).toBe(1)
  })

  test('扩展时下一批已提前到达则直接视为加载完成', async () => {
    vi.useFakeTimers()
    const totalCount = ref(40)
    const loadBatch = vi.fn<BatchLoader>()
    loadBatch.mockImplementation(async (start, end, setImageSlot) => {
      for (let index = start; index < end; index++) {
        setImageSlot(index + 1, `image-${index + 1}`)
      }
    })
    const previewBatches = usePreviewBatches(totalCount, loadBatch)
    const setImageSlot = previewBatches.createImageSlotSetter()

    for (let sortOrder = 21; sortOrder <= 40; sortOrder++) {
      setImageSlot(sortOrder, `early-image-${sortOrder}`)
    }

    const initializePromise = previewBatches.initialize()
    await vi.runAllTimersAsync()
    await initializePromise

    const expandPromise = previewBatches.expandBatch()
    await vi.runAllTimersAsync()

    expect(await expandPromise).toBe(true)
    expect(loadBatch).toHaveBeenCalledTimes(1)
    expect(previewBatches.cursor.value).toBe(40)
    expect(previewBatches.loadedCount.value).toBe(40)
    expect(previewBatches.allVisible.value).toBe(true)
  })

  test('首批加载 20 张并继续按批次追加到总数', async () => {
    vi.useFakeTimers()
    const totalCount = ref(45)
    const loadBatch = vi.fn<BatchLoader>()
    const previewBatches = usePreviewBatches(totalCount, loadBatch)
    loadBatch.mockImplementation(async (start, end, setImageSlot) => {
      for (let index = start; index < end; index++) {
        setImageSlot(index + 1, `image-${index + 1}`)
      }
    })

    const initializePromise = previewBatches.initialize()
    await vi.runAllTimersAsync()
    await initializePromise

    expect(loadBatch).toHaveBeenNthCalledWith(1, 0, 20, expect.any(Function))
    expect(previewBatches.displayCount.value).toBe(20)
    expect(previewBatches.loadedCount.value).toBe(20)

    const secondBatchPromise = previewBatches.expandBatch()
    const duplicatePromise = previewBatches.expandBatch()
    await vi.runAllTimersAsync()
    await Promise.all([secondBatchPromise, duplicatePromise])

    expect(loadBatch).toHaveBeenNthCalledWith(2, 20, 40, expect.any(Function))
    expect(loadBatch).toHaveBeenCalledTimes(2)
    expect(previewBatches.displayCount.value).toBe(40)
    expect(previewBatches.loadedCount.value).toBe(40)

    const finalBatchPromise = previewBatches.expandBatch()
    await vi.runAllTimersAsync()
    await finalBatchPromise

    expect(loadBatch).toHaveBeenNthCalledWith(3, 40, 45, expect.any(Function))
    expect(previewBatches.displayCount.value).toBe(45)
    expect(previewBatches.loadedCount.value).toBe(45)
    expect(previewBatches.allVisible.value).toBe(true)
  })

  test('首批加载失败后保留游标并重试同一批次', async () => {
    vi.useFakeTimers()
    const totalCount = ref(20)
    const loadBatch = vi.fn<BatchLoader>()
    const previewBatches = usePreviewBatches(totalCount, loadBatch)
    loadBatch.mockRejectedValueOnce(new Error('load failed'))
    loadBatch.mockImplementation(async (start, end, setImageSlot) => {
      for (let index = start; index < end; index++) {
        setImageSlot(index + 1, `image-${index + 1}`)
      }
    })

    await previewBatches.initialize()

    expect(loadBatch).toHaveBeenNthCalledWith(1, 0, 20, expect.any(Function))
    expect(previewBatches.cursor.value).toBe(0)
    expect(previewBatches.displayCount.value).toBe(20)
    expect(previewBatches.allVisible.value).toBe(false)

    const retryPromise = previewBatches.expandBatch()
    await vi.runAllTimersAsync()
    expect(await retryPromise).toBe(true)

    expect(loadBatch).toHaveBeenNthCalledWith(2, 0, 20, expect.any(Function))
    expect(previewBatches.cursor.value).toBe(20)
    expect(previewBatches.loadedCount.value).toBe(20)
    expect(previewBatches.allVisible.value).toBe(true)
  })

  test('后续批次失败时不会跳过该批次或继续扩展槽位', async () => {
    vi.useFakeTimers()
    const totalCount = ref(40)
    const loadBatch = vi.fn<BatchLoader>()
    const previewBatches = usePreviewBatches(totalCount, loadBatch)
    loadBatch.mockImplementation(async (start, end, setImageSlot) => {
      for (let index = start; index < end; index++) {
        setImageSlot(index + 1, `image-${index + 1}`)
      }
    })
    loadBatch.mockImplementationOnce(async (start, end, setImageSlot) => {
      for (let index = start; index < end; index++) {
        setImageSlot(index + 1, `image-${index + 1}`)
      }
    })
    loadBatch.mockRejectedValueOnce(new Error('load failed'))

    const initializePromise = previewBatches.initialize()
    await vi.runAllTimersAsync()
    await initializePromise

    const failedBatchPromise = previewBatches.expandBatch()
    await vi.runAllTimersAsync()
    expect(await failedBatchPromise).toBe(false)
    expect(loadBatch).toHaveBeenNthCalledWith(2, 20, 40, expect.any(Function))
    expect(previewBatches.cursor.value).toBe(20)
    expect(previewBatches.displayCount.value).toBe(40)
    expect(previewBatches.allVisible.value).toBe(false)

    const retryPromise = previewBatches.expandBatch()
    await vi.runAllTimersAsync()
    expect(await retryPromise).toBe(true)
    expect(loadBatch).toHaveBeenNthCalledWith(3, 20, 40, expect.any(Function))
    expect(previewBatches.cursor.value).toBe(40)
    expect(previewBatches.displayCount.value).toBe(40)
    expect(previewBatches.allVisible.value).toBe(true)
  })

  test('批次提交完成但没有图片填槽时不会误判为全部可见', async () => {
    vi.useFakeTimers()
    const totalCount = ref(20)
    const loadBatch = vi.fn<BatchLoader>(async () => {
    })
    const previewBatches = usePreviewBatches(totalCount, loadBatch)

    const initializePromise = previewBatches.initialize()
    await vi.runAllTimersAsync()
    await initializePromise

    expect(loadBatch).toHaveBeenCalledWith(0, 20, expect.any(Function))
    expect(previewBatches.cursor.value).toBe(20)
    expect(previewBatches.loadedCount.value).toBe(0)
    expect(previewBatches.allVisible.value).toBe(false)
  })

  test('可见批次存在缺失槽位时会重新提交同一批次而不扩展下一批', async () => {
    vi.useFakeTimers()
    const totalCount = ref(40)
    const loadBatch = vi.fn<BatchLoader>()
    const previewBatches = usePreviewBatches(totalCount, loadBatch)
    loadBatch.mockImplementationOnce(async (start, end, setImageSlot) => {
      for (let index = start; index < end - 1; index++) {
        setImageSlot(index + 1, `image-${index + 1}`)
      }
    })
    loadBatch.mockImplementationOnce(async (start, end, setImageSlot) => {
      for (let index = start; index < end; index++) {
        setImageSlot(index + 1, `retry-image-${index + 1}`)
      }
    })

    const initializePromise = previewBatches.initialize()
    await vi.runAllTimersAsync()
    await initializePromise

    expect(previewBatches.displayCount.value).toBe(20)
    expect(previewBatches.loadedCount.value).toBe(19)

    const retryPromise = previewBatches.expandBatch()
    const duplicatePromise = previewBatches.expandBatch()
    await vi.runAllTimersAsync()

    expect(await retryPromise).toBe(true)
    expect(await duplicatePromise).toBe(false)
    expect(loadBatch).toHaveBeenNthCalledWith(2, 0, 20, expect.any(Function))
    expect(loadBatch).toHaveBeenCalledTimes(2)
    expect(previewBatches.displayCount.value).toBe(20)
    expect(previewBatches.loadedCount.value).toBe(20)
    expect(previewBatches.allVisible.value).toBe(false)
  })

  test('reset 后旧代次的图片 setter 不会写入新槽位', async () => {
    vi.useFakeTimers()
    const totalCount = ref(20)
    const loadBatch = vi.fn<BatchLoader>(async () => {
    })
    const previewBatches = usePreviewBatches(totalCount, loadBatch)
    const staleSetImageSlot = previewBatches.createImageSlotSetter()

    previewBatches.reset()
    const currentSetImageSlot = previewBatches.createImageSlotSetter()
    staleSetImageSlot(1, 'stale-image')
    currentSetImageSlot(2, 'current-image')

    const initializePromise = previewBatches.initialize()
    await vi.runAllTimersAsync()
    await initializePromise

    expect(previewBatches.slots.value[0]).toBeNull()
    expect(previewBatches.slots.value[1]).toEqual({
      sortOrder: 2,
      dataUrl: 'current-image',
    })
  })

  test('reset 后旧 initialize 的完成和 finally 不会覆盖新代次状态', async () => {
    vi.useFakeTimers()
    const totalCount = ref(20)
    let resolveStaleLoad!: () => void
    let resolveCurrentLoad!: () => void
    let staleSetImageSlot!: ImageSlotSetter
    const loadBatch = vi.fn<BatchLoader>()
    loadBatch.mockImplementationOnce(async (_start, _end, setImageSlot) => {
      staleSetImageSlot = setImageSlot
      await new Promise<void>((resolve) => {
        resolveStaleLoad = resolve
      })
    })
    loadBatch.mockImplementationOnce(async () => {
      await new Promise<void>((resolve) => {
        resolveCurrentLoad = resolve
      })
    })
    const previewBatches = usePreviewBatches(totalCount, loadBatch)

    const staleInitialize = previewBatches.initialize()
    previewBatches.reset()
    const currentInitialize = previewBatches.initialize()
    staleSetImageSlot(1, 'stale-image')
    resolveStaleLoad()
    await staleInitialize

    expect(previewBatches.slots.value[0]).toBeNull()
    expect(previewBatches.cursor.value).toBe(0)
    expect(previewBatches.loadingMore.value).toBe(true)

    resolveCurrentLoad()
    await vi.runAllTimersAsync()
    await currentInitialize
    expect(previewBatches.cursor.value).toBe(20)
    expect(previewBatches.loadingMore.value).toBe(false)
  })

  test('reset 后旧 expand 的完成和 finally 不会覆盖新代次状态', async () => {
    vi.useFakeTimers()
    const totalCount = ref(40)
    let resolveStaleLoad!: () => void
    let resolveCurrentLoad!: () => void
    let staleSetImageSlot!: ImageSlotSetter
    const loadBatch = vi.fn<BatchLoader>()
    loadBatch.mockImplementationOnce(async (start, end, setImageSlot) => {
      for (let index = start; index < end; index++) {
        setImageSlot(index + 1, `image-${index + 1}`)
      }
    })
    loadBatch.mockImplementationOnce(async (_start, _end, setImageSlot) => {
      staleSetImageSlot = setImageSlot
      await new Promise<void>((resolve) => {
        resolveStaleLoad = resolve
      })
    })
    loadBatch.mockImplementationOnce(async () => {
      await new Promise<void>((resolve) => {
        resolveCurrentLoad = resolve
      })
    })
    const previewBatches = usePreviewBatches(totalCount, loadBatch)

    const initializePromise = previewBatches.initialize()
    await vi.runAllTimersAsync()
    await initializePromise

    const staleExpand = previewBatches.expandBatch()
    await vi.advanceTimersByTimeAsync(400)
    previewBatches.reset()
    const currentInitialize = previewBatches.initialize()
    staleSetImageSlot(1, 'stale-image')
    resolveStaleLoad()

    expect(await staleExpand).toBe(false)
    expect(previewBatches.slots.value[0]).toBeNull()
    expect(previewBatches.cursor.value).toBe(0)
    expect(previewBatches.loadingMore.value).toBe(true)

    resolveCurrentLoad()
    await vi.runAllTimersAsync()
    await currentInitialize
    expect(previewBatches.cursor.value).toBe(20)
    expect(previewBatches.loadingMore.value).toBe(false)
  })
})
