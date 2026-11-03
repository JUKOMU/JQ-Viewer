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
