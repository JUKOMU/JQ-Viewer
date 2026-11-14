/* eslint-disable vue/one-component-per-file -- 小型宿主分别覆盖播放状态和纵向视图协作 */
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
  SettingsStore: { getReaderAutoPlayInterval: () => 2 },
  persistReaderAutoPlayInterval: mocks.persistInterval,
}))

import { useReaderAutoPlay } from '@/composables/useReaderAutoPlay'
import VerticalScrollView from '@/components/reader/VerticalScrollView.vue'

// jsdom 不提供动画实现，测试只控制接口和时间，不替代真实 WebView 验收。
Object.defineProperty(Element.prototype, 'animate', { configurable: true, writable: true, value: () => {} })

/** 动画时钟与 rAF 独立推进，不模拟绘制或作视觉断言。 */
async function mountVerticalReader(round: (value: number) => number = (value) => value) {
  const frames = new Map<number, FrameRequestCallback>()
  let frameId = 0
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    frames.set(++frameId, callback)
    return frameId
  })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id))
  let resize!: () => void
  vi.stubGlobal('ResizeObserver', class {
    constructor(callback: () => void) { resize = callback }
    observe() {}
    disconnect() {}
  })
  const animations: Animation[] = []
  vi.spyOn(Element.prototype, 'animate').mockImplementation(() => {
    const animation = {
      currentTime: 0,
      effect: { setKeyframes: vi.fn(), updateTiming: vi.fn() },
      cancel: vi.fn(),
      pause: vi.fn(),
    } as unknown as Animation
    animations.push(animation)
    return animation
  })
  const view = ref<InstanceType<typeof VerticalScrollView> | null>(null)
  const currentIndex = ref(15)
  let autoPlay!: ReturnType<typeof useReaderAutoPlay>
  const wrapper = mount(defineComponent({
    setup() {
      autoPlay = useReaderAutoPlay({
        isVertical: ref(true), currentIndex, totalCount: ref(30),
        startScroll: (seconds) => view.value!.startAutoScroll(seconds),
        cancelScroll: () => view.value?.cancelAutoScroll(),
        goToIndex: () => {},
      })
      return () => h(VerticalScrollView, {
        ref: view,
        imageMap: new Map(Array.from({ length: 30 }, (_, i) => [i + 1, 'image-' + i])),
        totalCount: 30, currentIndex: currentIndex.value,
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
  async function frame() {
    const callbacks = [...frames.values()]
    frames.clear()
    callbacks.forEach((callback) => callback(performance.now()))
    await nextTick()
  }
  function loadImage(index: number, height: number) {
    const image = wrapper.get('[data-index="' + index + '"] img').element
    Object.defineProperties(image, {
      naturalWidth: { configurable: true, value: 600 },
      naturalHeight: { configurable: true, value: height },
    })
    image.dispatchEvent(new Event('load'))
  }
  await nextTick()
  await frame()
  await frame()
  view.value!.scrollToIndex(15)
  await nextTick()
  await frame()
  await frame()
  positions.length = 0
  return { wrapper, view, autoPlay, container, positions, animations, currentIndex, frames, frame, loadImage, resize }
}

afterEach(() => {
  vi.clearAllMocks()
  vi.restoreAllMocks()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('纵向播放的位置协作', () => {
  test.each([30, -30, 0.1, -0.1])('间隔 %s 时由动画时钟推进，rAF 不写原生位置', async (interval) => {
    const reader = await mountVerticalReader(Math.trunc)
    const start = reader.container.scrollTop
    reader.autoPlay.toggle()
    reader.autoPlay.updateInterval(interval)
    reader.positions.length = 0
    const animation = reader.animations[reader.animations.length - 1]
    // 没有执行中间的观察帧，动画时间仍是累计位移的唯一依据。
    animation.currentTime = 1000
    await reader.frame()
    expect(reader.positions).toHaveLength(0)
    reader.autoPlay.pauseForInteraction()
    expect(reader.container.scrollTop).toBeCloseTo(start + 600 / interval, 0)
    expect(animation.cancel).toHaveBeenCalledOnce()
    reader.wrapper.unmount()
  })

  test.each([[400, 30], [1200, 30], [400, -30], [1200, -30],
    [400, 0.1], [1200, 0.1], [400, -0.1], [1200, -0.1]])(
    '图片高度 %s、间隔 %s 时补偿不写原生位置，也不重启动画', async (height, interval) => {
      const reader = await mountVerticalReader()
      const start = reader.container.scrollTop
      reader.autoPlay.toggle()
      reader.autoPlay.updateInterval(interval)
      const animation = reader.animations[reader.animations.length - 1]
      const count = reader.animations.length
      reader.positions.length = 0
      animation.currentTime = 10
      reader.loadImage(12, height)
      await nextTick()
      expect(reader.positions).toHaveLength(0)
      expect(reader.animations).toHaveLength(count)
      expect(animation.currentTime).toBe(10)
      animation.currentTime = 20
      reader.autoPlay.pauseForInteraction()
      expect(reader.container.scrollTop).toBeCloseTo(start + height - 800 + 12 / interval)
      reader.wrapper.unmount()
    },
  )

  test('同批测高只补偿一次，暂停恢复保留小数位置', async () => {
    const reader = await mountVerticalReader(Math.trunc)
    const start = reader.container.scrollTop
    reader.autoPlay.toggle()
    reader.autoPlay.updateInterval(30)
    const animation = reader.animations[reader.animations.length - 1]
    animation.currentTime = 12.5
    reader.loadImage(12, 1200)
    reader.loadImage(13, 400)
    reader.autoPlay.pauseForInteraction()
    await nextTick()
    expect(reader.container.scrollTop).toBe(start)
    reader.autoPlay.resumeAfterInteraction()
    reader.animations[reader.animations.length - 1].currentTime = 37.5
    reader.autoPlay.pauseForInteraction()
    expect(reader.container.scrollTop).toBe(start + 1)
    reader.wrapper.unmount()
  })

  test('尺寸事务中调速按新方向从补偿后的位置继续', async () => {
    const reader = await mountVerticalReader()
    const start = reader.container.scrollTop
    reader.autoPlay.toggle()
    reader.animations[reader.animations.length - 1].currentTime = 100
    reader.loadImage(12, 400)
    reader.autoPlay.updateInterval(-30)
    await nextTick()
    reader.animations[reader.animations.length - 1].currentTime = 100
    reader.autoPlay.pauseForInteraction()
    expect(reader.container.scrollTop).toBeCloseTo(start + 30 - 400 - 2)
    reader.wrapper.unmount()
  })

  test('播放用逻辑位置更新页码，恢复不计入等待时间', async () => {
    const reader = await mountVerticalReader()
    const start = reader.container.scrollTop
    reader.autoPlay.toggle()
    reader.animations[reader.animations.length - 1].currentTime = 4000
    await reader.frame()
    expect(reader.currentIndex.value).toBe(16)
    expect(reader.container.scrollTop).toBe(start)
    reader.autoPlay.pauseForInteraction()
    expect(reader.container.scrollTop).toBe(start + 1200)
    reader.autoPlay.resumeAfterInteraction()
    reader.animations[reader.animations.length - 1].currentTime = 100
    reader.autoPlay.stop()
    expect(reader.container.scrollTop).toBe(start + 1230)
    reader.wrapper.unmount()
  })

  test('视口高度改变后按新屏高调速，改变前的距离不重算', async () => {
    const reader = await mountVerticalReader()
    const start = reader.container.scrollTop
    reader.autoPlay.toggle()
    const animation = reader.animations[reader.animations.length - 1]
    animation.currentTime = 100
    Object.defineProperty(reader.container, 'clientHeight', { configurable: true, value: 800 })
    reader.resize()
    await nextTick()
    animation.currentTime = 200
    reader.autoPlay.stop()
    expect(reader.container.scrollTop).toBeCloseTo(start + 30 + 40)
    reader.wrapper.unmount()
  })

  test('内容收缩时原生位置不被钳制，交接采用补偿后的逻辑位置', async () => {
    const reader = await mountVerticalReader()
    reader.view.value!.scrollToIndex(29)
    await nextTick()
    await reader.frame()
    await reader.frame()
    const start = reader.container.scrollTop
    reader.autoPlay.toggle()
    reader.autoPlay.updateInterval(-2)
    reader.animations[reader.animations.length - 1].currentTime = 100
    reader.loadImage(26, 400)
    await nextTick()
    expect(reader.container.scrollTop).toBe(start)
    reader.autoPlay.stop()
    await nextTick()
    expect(reader.container.scrollTop).toBe(start - 30 - 400)
    reader.wrapper.unmount()
  })

  test('跳页从新页继续，卸载撤销动画和观察帧', async () => {
    const reader = await mountVerticalReader()
    reader.autoPlay.toggle()
    const old = reader.animations[reader.animations.length - 1]
    old.currentTime = 1000
    reader.view.value!.scrollToIndex(20)
    await nextTick()
    await reader.frame()
    expect(old.cancel).toHaveBeenCalledOnce()
    const next = reader.animations[reader.animations.length - 1]
    expect(next).not.toBe(old)
    next.currentTime = 100
    reader.autoPlay.stop()
    expect(reader.container.scrollTop).toBe(16150)
    await reader.frame()
    reader.autoPlay.toggle()
    const last = reader.animations[reader.animations.length - 1]
    reader.wrapper.unmount()
    expect(last.cancel).toHaveBeenCalledOnce()
    expect(reader.frames.size).toBe(0)
  })
})

function mountHorizontalReader(index = 0, count = 4) {
  const currentIndex = ref(index)
  const goToIndex = vi.fn((next: number) => { currentIndex.value = next })
  let autoPlay!: ReturnType<typeof useReaderAutoPlay>
  const wrapper = mount(defineComponent({
    setup() {
      autoPlay = useReaderAutoPlay({
        isVertical: ref(false), currentIndex, totalCount: ref(count),
        startScroll: () => {}, cancelScroll: () => {}, goToIndex,
      })
      return () => h('div')
    },
  }))
  return { wrapper, currentIndex, goToIndex, autoPlay }
}

describe('useReaderAutoPlay', () => {
  test('横向自动播放按方向推进页面', () => {
    vi.useFakeTimers()
    const reader = mountHorizontalReader(1)
    reader.autoPlay.toggle()
    vi.advanceTimersByTime(2000)
    expect(reader.goToIndex).toHaveBeenCalledWith(2)
    reader.wrapper.unmount()
  })

  test('横向交互结束后恢复自动翻页计时', () => {
    vi.useFakeTimers()
    const reader = mountHorizontalReader()
    reader.autoPlay.toggle()
    vi.advanceTimersByTime(1000)
    reader.autoPlay.pauseForInteraction()
    vi.advanceTimersByTime(5000)
    reader.autoPlay.resumeAfterInteraction()
    vi.advanceTimersByTime(2000)
    expect(reader.goToIndex).toHaveBeenCalledWith(1)
    reader.wrapper.unmount()
  })

  test('停止后清除交互状态', () => {
    vi.useFakeTimers()
    const reader = mountHorizontalReader()
    reader.autoPlay.pauseForInteraction()
    reader.autoPlay.stop()
    reader.autoPlay.toggle()
    vi.advanceTimersByTime(2000)
    expect(reader.autoPlay.isPlaying.value).toBe(true)
    expect(reader.goToIndex).toHaveBeenCalledWith(1)
    reader.wrapper.unmount()
  })

  test('到达横向阅读末页后停止自动播放', () => {
    vi.useFakeTimers()
    const reader = mountHorizontalReader(1, 2)
    reader.autoPlay.toggle()
    vi.advanceTimersByTime(2000)
    expect(reader.autoPlay.isPlaying.value).toBe(false)
    expect(reader.goToIndex).not.toHaveBeenCalled()
    reader.wrapper.unmount()
  })

  test('方向键按音量键方向调速并保留两位小数', () => {
    const reader = mountHorizontalReader()
    const { autoPlay } = reader
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
    reader.wrapper.unmount()
  })
})
