import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import VerticalScrollView from '@/components/reader/VerticalScrollView.vue'

class ResizeObserverMock {
  observe() {}

  disconnect() {}
}

let frameId = 0

beforeEach(() => {
  frameId = 0
  vi.stubGlobal('ResizeObserver', ResizeObserverMock)
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    const id = ++frameId
    Promise.resolve().then(() => callback(performance.now()))
    return id
  })
  vi.stubGlobal('cancelAnimationFrame', () => {})
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

async function flushAnimationFrames() {
  for (let i = 0; i < 6; i++) {
    await Promise.resolve()
  }
}

describe('VerticalScrollView', () => {
  test('双指缩放上限为 5 倍', async () => {
    const wrapper = mount(VerticalScrollView, {
      props: {
        imageMap: new Map([[1, 'image-1']]),
        totalCount: 1,
        currentIndex: 0,
      },
    })
    await flushAnimationFrames()

    const container = wrapper.get('.vertical-container')
    Object.defineProperties(container.element, {
      clientHeight: { configurable: true, value: 400 },
      clientWidth: { configurable: true, value: 300 },
      scrollTop: { configurable: true, value: 0, writable: true },
    })
    await container.trigger('touchstart', {
      touches: [
        { clientX: 100, clientY: 100 },
        { clientX: 200, clientY: 100 },
      ],
    })
    await container.trigger('touchmove', {
      touches: [
        { clientX: -200, clientY: 100 },
        { clientX: 500, clientY: 100 },
      ],
    })

    expect(wrapper.get('.zoom-wrapper').attributes('style')).toContain('scale(5)')
    wrapper.unmount()
  })

  test('双击按 1 倍、2 倍、3 倍、5 倍循环缩放', async () => {
    let now = 1000
    vi.spyOn(Date, 'now').mockImplementation(() => now)
    const wrapper = mount(VerticalScrollView, {
      props: {
        imageMap: new Map([[1, 'image-1']]),
        totalCount: 1,
        currentIndex: 0,
      },
    })
    await flushAnimationFrames()

    const container = wrapper.get('.vertical-container')
    Object.defineProperties(container.element, {
      clientHeight: { configurable: true, value: 400 },
      clientWidth: { configurable: true, value: 300 },
      scrollTop: { configurable: true, value: 0, writable: true },
    })
    const tap = async () => {
      await container.trigger('touchstart', { touches: [{ clientX: 150, clientY: 200 }] })
      await container.trigger('touchend', {
        touches: [],
        changedTouches: [{ clientX: 150, clientY: 200 }],
      })
    }
    const doubleTap = async () => {
      await tap()
      now += 100
      await tap()
      now += 400
    }

    await doubleTap()
    expect(wrapper.get('.zoom-wrapper').attributes('style')).toContain('scale(2)')
    await doubleTap()
    expect(wrapper.get('.zoom-wrapper').attributes('style')).toContain('scale(3)')
    await doubleTap()
    expect(wrapper.get('.zoom-wrapper').attributes('style')).toContain('scale(5)')
    await doubleTap()
    expect(wrapper.get('.zoom-wrapper').attributes('style') ?? '').not.toContain('scale(')

    wrapper.unmount()
  })

  test('每次进入章节底部时只触发一次 reached-bottom', async () => {
    const wrapper = mount(VerticalScrollView, {
      props: {
        imageMap: new Map<number, string>(),
        totalCount: 2,
        currentIndex: 0,
      },
    })
    await flushAnimationFrames()

    const container = wrapper.get('.vertical-container')
    Object.defineProperties(container.element, {
      scrollHeight: { configurable: true, value: 1000 },
      clientHeight: { configurable: true, value: 400 },
      clientWidth: { configurable: true, value: 300 },
      scrollTop: { configurable: true, value: 500, writable: true },
    })

    await container.trigger('scroll')
    await flushAnimationFrames()
    expect(wrapper.vm.isAtBottom()).toBe(false)
    expect(wrapper.emitted('reached-bottom')).toBeUndefined()

    container.element.scrollTop = 600
    await container.trigger('scroll')
    await flushAnimationFrames()
    expect(wrapper.vm.isAtBottom()).toBe(true)
    expect(wrapper.emitted('reached-bottom')).toHaveLength(1)

    await container.trigger('scroll')
    await flushAnimationFrames()
    expect(wrapper.emitted('reached-bottom')).toHaveLength(1)

    container.element.scrollTop = 500
    await container.trigger('scroll')
    await flushAnimationFrames()
    expect(wrapper.vm.isAtBottom()).toBe(false)

    container.element.scrollTop = 599
    await container.trigger('scroll')
    await flushAnimationFrames()
    expect(wrapper.vm.isAtBottom()).toBe(true)
    expect(wrapper.emitted('reached-bottom')).toHaveLength(2)

    wrapper.unmount()
  })

  test('失败图片保持槽位并触发聚合重试', async () => {
    const wrapper = mount(VerticalScrollView, {
      props: {
        imageMap: new Map([[1, 'image-1']]),
        failedSortOrders: new Set<number>(),
        retryingSortOrders: new Set<number>(),
        totalCount: 1,
        currentIndex: 0,
      },
    })
    await flushAnimationFrames()

    await wrapper.get('.reader-image').trigger('error')
    expect(wrapper.emitted('image-error')).toEqual([[1, 'image-1']])

    await wrapper.setProps({ failedSortOrders: new Set([1]) })
    expect(wrapper.get('.image-error-state').text()).toContain('图片加载失败')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry-images')).toEqual([[]])
    wrapper.unmount()
  })
})
