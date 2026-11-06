import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import ReaderAutoPlayButton from '@/components/reader/ReaderAutoPlayButton.vue'

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('ReaderAutoPlayButton', () => {
  test('点击后仍在2秒后隐藏按钮', async () => {
    vi.useFakeTimers()
    const wrapper = mount(ReaderAutoPlayButton, {
      props: { isPlaying: false, isVertical: true, intervalSeconds: 10, speedAdjustmentVersion: 0 },
    })

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab' }))
    await wrapper.vm.$nextTick()
    const button = wrapper.get('button')
    await button.trigger('focus')
    vi.advanceTimersByTime(2000)
    await wrapper.vm.$nextTick()

    expect(wrapper.find('button').exists()).toBe(false)
    wrapper.unmount()
  })
})
