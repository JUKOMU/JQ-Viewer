import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import RollingNumber from '@/components/update/RollingNumber.vue'

describe('RollingNumber', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  test('按一位小数逐步变化，不使用数字滚轮', async () => {
    vi.useFakeTimers()
    const wrapper = mount(RollingNumber, { props: { value: 1.2 } })

    expect(wrapper.attributes('aria-label')).toBe('1.2')
    expect(wrapper.text()).toBe('1.2')
    expect(wrapper.find('.rolling-strip').exists()).toBe(false)

    await wrapper.setProps({ value: 1.8 })

    expect(wrapper.attributes('aria-label')).toBe('1.8')
    expect(wrapper.text()).toBe('1.2')

    await vi.advanceTimersByTimeAsync(32 * 6)
    expect(wrapper.text()).toBe('1.8')
  })

  test('支持反向变化和两端数字', async () => {
    vi.useFakeTimers()
    const wrapper = mount(RollingNumber, { props: { value: 3, decimals: 0 } })

    expect(wrapper.attributes('aria-label')).toBe('3')

    await wrapper.setProps({ value: 2 })
    await vi.advanceTimersByTimeAsync(32)
    expect(wrapper.text()).toBe('2')

    await wrapper.setProps({ value: 9 })
    await vi.advanceTimersByTimeAsync(32 * 7)
    expect(wrapper.text()).toBe('9')

    await wrapper.setProps({ value: 0 })
    await vi.advanceTimersByTimeAsync(32 * 9)
    expect(wrapper.text()).toBe('0')

    await wrapper.setProps({ value: 9 })
    await vi.advanceTimersByTimeAsync(32 * 9)
    expect(wrapper.text()).toBe('9')
  })

  test('从两位数降为一位数时不保留前导零', async () => {
    vi.useFakeTimers()
    const wrapper = mount(RollingNumber, { props: { value: 96, decimals: 0 } })

    await wrapper.setProps({ value: 6 })
    await vi.advanceTimersByTimeAsync(32 * 90)

    expect(wrapper.text()).toBe('6')
    expect(wrapper.text()).not.toBe('06')
  })

  test('大幅变化按数字位追赶并在短时间内完成', async () => {
    vi.useFakeTimers()
    const wrapper = mount(RollingNumber, { props: { value: 0 } })

    await wrapper.setProps({ value: 57.4 })
    await vi.advanceTimersByTimeAsync(16 * 9)

    expect(wrapper.text()).toBe('57.4')
  })

  test('连续高频更新时不会反复重置追赶过程', async () => {
    vi.useFakeTimers()
    const wrapper = mount(RollingNumber, { props: { value: 0, decimals: 0 } })

    for (let value = 1; value <= 50; value += 1) {
      await wrapper.setProps({ value })
      await vi.advanceTimersByTimeAsync(1)
    }

    expect(Number(wrapper.text())).toBeGreaterThan(0)

    await vi.advanceTimersByTimeAsync(500)
    expect(wrapper.text()).toBe('50')
  })
})
