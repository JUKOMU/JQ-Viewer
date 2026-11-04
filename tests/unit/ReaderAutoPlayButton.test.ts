import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import ReaderAutoPlayButton from '@/components/reader/ReaderAutoPlayButton.vue'

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('ReaderAutoPlayButton', () => {
  test('保持键盘焦点直到用户离开按钮', async () => {
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

    expect(wrapper.find('button').exists()).toBe(true)
    await button.trigger('blur')
    expect(wrapper.find('button').exists()).toBe(false)
    wrapper.unmount()
  })
})
