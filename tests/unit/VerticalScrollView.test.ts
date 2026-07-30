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
  vi.unstubAllGlobals()
})

async function flushAnimationFrames() {
  for (let i = 0; i < 6; i++) {
    await Promise.resolve()
  }
}

describe('VerticalScrollView', () => {
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
})
