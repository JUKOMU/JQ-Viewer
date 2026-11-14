/* eslint-disable vue/one-component-per-file -- 多个小型宿主分别覆盖自动播放状态 */
import { defineComponent, h, nextTick, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  persistInterval: vi.fn(() => Promise.resolve()),
  showToast: vi.fn(),
}))

vi.mock('@/runtime/runtimeContext', () => ({
  getRuntime: () => ({
    services: { reader: { volumeKeys: { available: false, reason: 'test' } } },
  }),
}))

vi.mock('@/services/JmcomicService', () => ({
  sanitizeError: (_error: unknown, fallback: string) => fallback,
  showToast: mocks.showToast,
}))

vi.mock('@/services/SettingsService', () => ({
  SettingsStore: {
    getReaderAutoPlayInterval: () => 2,
  },
  persistReaderAutoPlayInterval: mocks.persistInterval,
}))

import { useReaderAutoPlay } from '@/composables/useReaderAutoPlay'
import VerticalScrollView from '@/components/reader/VerticalScrollView.vue'

const horizontalScrollActions = {
  advanceScroll: async () => true,
  cancelScroll: () => {},
}

async function mountVerticalReader(round: (value: number) => number, totalCount = 6) {
  const frames = new Map<number, FrameRequestCallback>()
  let frameId = 0
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    frames.set(++frameId, callback)
    return frameId
  })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id))
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    disconnect() {}
  })
  const view = ref<InstanceType<typeof VerticalScrollView> | null>(null)
  let autoPlay!: ReturnType<typeof useReaderAutoPlay>
  const wrapper = mount(defineComponent({
    setup() {
      const currentIndex = ref(0)
      autoPlay = useReaderAutoPlay({
        isVertical: ref(true),
        currentIndex,
        totalCount: ref(totalCount),
        getScrollContainer: () => view.value?.containerRef ?? null,
        advanceScroll: (distance) => view.value!.advanceScroll(distance),
        cancelScroll: () => view.value?.cancelAutoScroll(),
        goToIndex: () => {},
      })
      return () => h(VerticalScrollView, {
        ref: view,
        imageMap: new Map(Array.from({ length: totalCount }, (_, i) => [i + 1, `image-${i}`])),
        totalCount,
        currentIndex: currentIndex.value,
        'onUpdate:currentIndex': (index: number) => { currentIndex.value = index },
        onGeometryChange: autoPlay.resumeAfterGeometryChange,
      })
    },
  }))
  const container = wrapper.get('.vertical-container').element as HTMLElement
  const inner = wrapper.get('.virtual-inner').element as HTMLElement
  let top = 0
  const positions: number[] = []
  const contentHeight = () => Number.parseFloat(inner.style.height)
  Object.defineProperties(container, {
    clientWidth: { configurable: true, value: 600 },
    clientHeight: { configurable: true, value: 600 },
    scrollHeight: { configurable: true, get: contentHeight },
    scrollTop: {
      configurable: true,
      get: () => Math.min(top, Math.max(0, contentHeight() - 600)),
      set: (value: number) => {
        top = Math.max(0, Math.min(contentHeight() - 600, round(value)))
        positions.push(top)
      },
    },
  })
  function fireFrame(now: number) {
    const callbacks = [...frames.values()]
    frames.clear()
    callbacks.forEach((callback) => callback(now))
  }
  async function flush() {
    for (let i = 0; i < 8; i++) await Promise.resolve()
  }
  async function frame(now: number) {
    fireFrame(now)
    await flush()
    container.dispatchEvent(new Event('scroll'))
    await flush()
  }
  function loadImage(index: number, height: number) {
    const image = wrapper.get(`[data-index="${index}"] img`).element
    Object.defineProperties(image, {
      naturalWidth: { configurable: true, value: 600 },
      naturalHeight: { configurable: true, value: height },
    })
    image.dispatchEvent(new Event('load'))
  }
  await nextTick()
  await frame(100)
  await frame(200)
  view.value!.scrollToIndex(1)
  await nextTick()
  await frame(300)
  await frame(400)
  return { wrapper, view, autoPlay, container, positions, frames, fireFrame, frame, flush, loadImage }
}

afterEach(() => {
  vi.clearAllMocks()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('useReaderAutoPlay', () => {
  test.each([
    ['小数', (value: number) => value],
    ['取整', Math.trunc],
  ] as const)('%s scrollTop 下布局稳定时逐帧推进，回读不产生重复刷新', async (_label, round) => {
    for (const interval of [0.1, -0.1, 30, -30]) {
      const reader = await mountVerticalReader(round, 30)
      reader.view.value!.scrollToIndex(15)
      await nextTick()
      await reader.frame(500)
      await reader.frame(600)
      const start = reader.container.scrollTop
      reader.autoPlay.toggle()
      reader.autoPlay.updateInterval(interval)
      await reader.frame(1000)
      for (let i = 1; i <= 60; i++) {
        const writes = reader.positions.length
        reader.fireFrame(1000 + i * 1000 / 60)
        const expected = start + Math.sign(interval) * i * 10 / Math.abs(interval)
        // 检查每帧调用返回时的位置，不能只检查所有微任务结束后的累计值。
        expect(Math.abs(reader.container.scrollTop - expected)).toBeLessThan(1.000001)
        expect(reader.positions).toHaveLength(writes + 1)
        await reader.flush()
        reader.container.dispatchEvent(new Event('scroll'))
        await reader.flush()
        expect(reader.positions).toHaveLength(writes + 1)
        expect(reader.frames.size).toBe(1)
      }
      reader.wrapper.unmount()
    }
  })

  test.each([
    ['小数', (value: number) => value],
    ['取整', Math.trunc],
  ] as const)('%s scrollTop 下双向低速位移在 120Hz 下累计', async (_label, round) => {
    for (const direction of [1, -1]) {
      const reader = await mountVerticalReader(round)
      const start = reader.container.scrollTop
      reader.autoPlay.toggle()
      reader.autoPlay.updateInterval(direction * 30)
      await reader.frame(1000)
      for (let i = 1; i <= 120; i++) await reader.frame(1000 + i * 1000 / 120)
      expect(Math.abs(reader.container.scrollTop - start - direction * 20)).toBeLessThan(1.000001)
      reader.wrapper.unmount()
    }
  })

  test.each([[1, 1200], [1, 400], [-1, 1200], [-1, 400]])(
    '方向 %s、图片高度 %s 时补偿与低速播放同批提交', async (direction, height) => {
    const reader = await mountVerticalReader(Math.trunc)
    const start = reader.container.scrollTop
    reader.autoPlay.toggle()
    reader.autoPlay.updateInterval(direction * 30)
    await reader.frame(1000)
    reader.fireFrame(1000 + 1000 / 120)
    reader.loadImage(0, height)
    await reader.flush()
    for (let i = 2; i <= 120; i++) await reader.frame(1000 + i * 1000 / 120)
    expect(Math.abs(reader.container.scrollTop - start - (height - 800) - direction * 20))
      .toBeLessThan(1.000001)
    reader.wrapper.unmount()
  })

  test('同批多张图片增减高度时，播放增量只应用一次', async () => {
    const reader = await mountVerticalReader((value) => value)
    reader.view.value!.scrollToIndex(3)
    await nextTick()
    await reader.frame(1000)
    const start = reader.container.scrollTop
    const scroll = reader.view.value!.advanceScroll(0.25)
    reader.loadImage(0, 1200)
    reader.loadImage(1, 400)
    await scroll
    expect(reader.container.scrollTop).toBe(start + 0.25)
    reader.wrapper.unmount()
  })

  test('暂停撤销待提交播放量，保留图片补偿；恢复后从新时间起点推进', async () => {
    const reader = await mountVerticalReader((value) => value)
    const start = reader.container.scrollTop
    reader.autoPlay.toggle()
    reader.autoPlay.updateInterval(30)
    await reader.frame(1000)
    reader.loadImage(0, 1200)
    reader.fireFrame(1500)
    reader.autoPlay.pauseForInteraction()
    await reader.flush()
    expect(reader.container.scrollTop).toBe(start + 400)
    reader.autoPlay.resumeAfterInteraction()
    await reader.frame(9000)
    expect(reader.container.scrollTop).toBe(start + 400)
    await reader.frame(9050)
    expect(reader.container.scrollTop).toBeCloseTo(start + 401)
    reader.wrapper.unmount()
  })

  test('图片补偿在新内容高度提交后写入，不被旧滚动边界裁剪', async () => {
    const reader = await mountVerticalReader((value) => value)
    reader.container.scrollTop = 4400
    reader.container.dispatchEvent(new Event('scroll'))
    await reader.frame(1000)
    reader.loadImage(3, 1200)
    await reader.flush()
    expect(reader.container.scrollTop).toBe(4800)
    reader.wrapper.unmount()
  })

  test('跳页和卸载使已排队的位置提交失效', async () => {
    const reader = await mountVerticalReader((value) => value)
    reader.loadImage(0, 1200)
    const pending = reader.view.value!.advanceScroll(10)
    reader.view.value!.scrollToIndex(2)
    await pending
    await reader.frame(1000)
    expect(reader.container.scrollTop).toBe(2120)
    const position = reader.container.scrollTop
    reader.loadImage(0, 1600)
    const beforeUnmount = reader.view.value!.advanceScroll(100)
    reader.wrapper.unmount()
    await beforeUnmount
    expect(reader.container.scrollTop).toBe(position)
  })

  test('横向自动播放按方向推进页面', () => {
    vi.useFakeTimers()
    let autoPlay!: ReturnType<typeof useReaderAutoPlay>
    const currentIndex = ref(1)
    const goToIndex = vi.fn((index: number) => currentIndex.value = index)
    const wrapper = mount(
      defineComponent({
        setup() {
          autoPlay = useReaderAutoPlay({
            isVertical: ref(false),
            currentIndex,
            totalCount: ref(4),
            getScrollContainer: () => null,
            ...horizontalScrollActions,
            goToIndex,
          })
          return () => h('div')
        },
      }),
    )

    autoPlay.toggle()
    vi.advanceTimersByTime(2000)

    expect(goToIndex).toHaveBeenCalledWith(2)
    expect(currentIndex.value).toBe(2)
    wrapper.unmount()
  })

  test('横向交互结束后恢复自动翻页计时', () => {
    vi.useFakeTimers()
    let autoPlay!: ReturnType<typeof useReaderAutoPlay>
    const currentIndex = ref(0)
    const goToIndex = vi.fn((index: number) => currentIndex.value = index)
    const wrapper = mount(
      defineComponent({
        setup() {
          autoPlay = useReaderAutoPlay({
            isVertical: ref(false),
            currentIndex,
            totalCount: ref(3),
            getScrollContainer: () => null,
            ...horizontalScrollActions,
            goToIndex,
          })
          return () => h('div')
        },
      }),
    )

    autoPlay.toggle()
    vi.advanceTimersByTime(1000)
    autoPlay.pauseForInteraction()
    vi.advanceTimersByTime(5000)
    autoPlay.resumeAfterInteraction()
    vi.advanceTimersByTime(2000)

    expect(goToIndex).toHaveBeenCalledWith(1)
    wrapper.unmount()
  })

  test('停止后清除交互状态', () => {
    vi.useFakeTimers()
    let autoPlay!: ReturnType<typeof useReaderAutoPlay>
    const goToIndex = vi.fn()
    const wrapper = mount(
      defineComponent({
        setup() {
          autoPlay = useReaderAutoPlay({
            isVertical: ref(false),
            currentIndex: ref(0),
            totalCount: ref(3),
            getScrollContainer: () => null,
            ...horizontalScrollActions,
            goToIndex,
          })
          return () => h('div')
        },
      }),
    )

    autoPlay.pauseForInteraction()
    autoPlay.stop()
    autoPlay.toggle()
    vi.advanceTimersByTime(2000)

    expect(autoPlay.isPlaying.value).toBe(true)
    expect(goToIndex).toHaveBeenCalledWith(1)
    wrapper.unmount()
  })

  test('纵向播放到边界后在布局增长时恢复滚动', async () => {
    let autoPlay!: ReturnType<typeof useReaderAutoPlay>
    const container = { clientHeight: 100, scrollHeight: 300, scrollTop: 0 } as HTMLElement
    const frames = new Map<number, FrameRequestCallback>()
    let frameId = 0
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      frames.set(++frameId, callback)
      return frameId
    })
    vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id))
    const wrapper = mount(
      defineComponent({
        setup() {
          autoPlay = useReaderAutoPlay({
            isVertical: ref(true),
            currentIndex: ref(0),
            totalCount: ref(3),
            getScrollContainer: () => container,
            advanceScroll: async (distance) => {
              const max = container.scrollHeight - container.clientHeight
              container.scrollTop = Math.max(0, Math.min(max, container.scrollTop + distance))
              return distance === 0 || container.scrollTop < max
            },
            cancelScroll: () => {},
            goToIndex: () => {},
          })
          return () => h('div')
        },
      }),
    )

    autoPlay.toggle()
    const first = frames.entries().next().value!
    frames.delete(first[0])
    first[1](1000)
    await Promise.resolve()
    const second = frames.entries().next().value!
    frames.delete(second[0])
    second[1](2000)
    await Promise.resolve()
    const third = frames.entries().next().value!
    frames.delete(third[0])
    third[1](3000)
    await Promise.resolve()
    const fourth = frames.entries().next().value!
    frames.delete(fourth[0])
    fourth[1](4000)
    await Promise.resolve()
    const fifth = frames.entries().next().value!
    frames.delete(fifth[0])
    fifth[1](5000)
    await Promise.resolve()
    expect(container.scrollTop).toBe(200)
    expect(frames.size).toBe(0)

    container.scrollHeight = 400
    autoPlay.pauseForInteraction()
    autoPlay.resumeAfterGeometryChange()
    expect(frames.size).toBe(0)
    autoPlay.resumeAfterInteraction()
    expect(frames.size).toBe(1)
    autoPlay.stop()
    wrapper.unmount()
  })

  test('到达横向阅读末页后停止自动播放', () => {
    vi.useFakeTimers()
    let autoPlay!: ReturnType<typeof useReaderAutoPlay>
    const goToIndex = vi.fn()
    const wrapper = mount(
      defineComponent({
        setup() {
          autoPlay = useReaderAutoPlay({
            isVertical: ref(false),
            currentIndex: ref(1),
            totalCount: ref(2),
            getScrollContainer: () => null,
            ...horizontalScrollActions,
            goToIndex,
          })
          return () => h('div')
        },
      }),
    )

    autoPlay.toggle()
    vi.advanceTimersByTime(2000)

    expect(autoPlay.isPlaying.value).toBe(false)
    expect(goToIndex).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  test('方向键按音量键方向调速并保留两位小数', () => {
    let autoPlay!: ReturnType<typeof useReaderAutoPlay>
    const wrapper = mount(
      defineComponent({
        setup() {
          const isVertical = ref(false)
          const currentIndex = ref(2)
          const totalCount = ref(5)
          autoPlay = useReaderAutoPlay({
            isVertical,
            currentIndex,
            totalCount,
            getScrollContainer: () => null,
            ...horizontalScrollActions,
            goToIndex: () => {},
          })
          return () => h('div')
        },
      }),
    )

    autoPlay.toggle()
    expect(autoPlay.handleDirectionKey('ArrowDown')).toBe(true)
    expect(autoPlay.intervalSeconds.value).toBe(1.8)
    expect(autoPlay.speedAdjustmentVersion.value).toBe(1)
    expect(autoPlay.handleDirectionKey('ArrowUp')).toBe(true)
    expect(autoPlay.intervalSeconds.value).toBe(1.98)
    expect(autoPlay.speedAdjustmentVersion.value).toBe(2)
    expect(mocks.persistInterval).toHaveBeenNthCalledWith(1, 1.8)
    expect(mocks.persistInterval).toHaveBeenNthCalledWith(2, 1.98)

    autoPlay.updateInterval(-2)
    autoPlay.handleDirectionKey('ArrowUp')
    expect(autoPlay.intervalSeconds.value).toBe(-1.8)
    autoPlay.handleDirectionKey('ArrowDown')
    expect(autoPlay.intervalSeconds.value).toBe(-1.98)

    autoPlay.updateInterval(29)
    autoPlay.handleDirectionKey('ArrowUp')
    expect(autoPlay.intervalSeconds.value).toBe(-30)
    autoPlay.updateInterval(-29)
    autoPlay.handleDirectionKey('ArrowDown')
    expect(autoPlay.intervalSeconds.value).toBe(30)
    autoPlay.updateInterval(-0.1)
    autoPlay.handleDirectionKey('ArrowUp')
    expect(autoPlay.intervalSeconds.value).toBe(-0.1)
    wrapper.unmount()
  })
})
