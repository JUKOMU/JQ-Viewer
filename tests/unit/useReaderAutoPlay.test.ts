/* eslint-disable vue/one-component-per-file -- 多个小型宿主分别覆盖自动播放状态 */
import { defineComponent, h, ref } from 'vue'
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

afterEach(() => {
  vi.clearAllMocks()
})

describe('useReaderAutoPlay', () => {
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
    const second = frames.entries().next().value!
    frames.delete(second[0])
    second[1](2000)
    const third = frames.entries().next().value!
    frames.delete(third[0])
    third[1](3000)
    const fourth = frames.entries().next().value!
    frames.delete(fourth[0])
    fourth[1](4000)
    const fifth = frames.entries().next().value!
    frames.delete(fifth[0])
    fifth[1](5000)
    const sixth = frames.entries().next().value!
    frames.delete(sixth[0])
    sixth[1](6000)
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
