import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import HorizontalPageView from '@/components/reader/HorizontalPageView.vue'

let resizeObserverTrigger: (() => void) | null = null
let lastResizeObserver: { disconnected: boolean } | null = null

class ResizeObserverMock {
  constructor(callback: () => void) {
    lastResizeObserver = { disconnected: false }
    resizeObserverTrigger = callback
  }

  observe() {}

  disconnect() {
    if (lastResizeObserver) lastResizeObserver.disconnected = true
  }
}

beforeEach(() => {
  resizeObserverTrigger = null
  lastResizeObserver = null
  vi.stubGlobal('ResizeObserver', ResizeObserverMock)
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  resizeObserverTrigger = null
  lastResizeObserver = null
})

function mountView(currentIndex = 1, width = 300) {
  const wrapper = mount(HorizontalPageView, {
    props: {
      imageMap: new Map([
        [1, 'image-1'],
        [2, 'image-2'],
        [3, 'image-3'],
      ]),
      totalCount: 3,
      currentIndex,
    },
  })
  const container = wrapper.get('.horizontal-container')
  Object.defineProperties(container.element, {
    clientHeight: { configurable: true, value: 400 },
    clientWidth: { configurable: true, value: width },
  })
  return { wrapper, container }
}

describe('HorizontalPageView', () => {
  test('页面槽位统一使用容器实际宽度', async () => {
    const { wrapper } = mountView(1, 1280)
    resizeObserverTrigger?.()
    await wrapper.vm.$nextTick()

    const slots = wrapper.findAll('.page-slot')
    expect(slots[0].attributes('style')).toContain('left: 0px')
    expect(slots[0].attributes('style')).toContain('width: 1280px')
    expect(slots[1].attributes('style')).toContain('left: 1280px')
    expect(slots[2].attributes('style')).toContain('left: 2560px')
    expect(wrapper.get('.strip').attributes('style')).toContain('width: 3840px')
    wrapper.unmount()
  })

  test('容器尺寸变化后重新对齐当前页并断开观察器', async () => {
    const { wrapper, container } = mountView(1, 300)
    resizeObserverTrigger?.()
    await wrapper.vm.$nextTick()
    expect(wrapper.get('.strip').attributes('style')).toContain('translate3d(-300px, 0, 0)')

    Object.defineProperty(container.element, 'clientWidth', {
      configurable: true,
      value: 800,
    })
    resizeObserverTrigger?.()
    await wrapper.vm.$nextTick()

    expect(wrapper.get('.strip').attributes('style')).toContain('translate3d(-800px, 0, 0)')
    expect(wrapper.findAll('.page-slot')[1].attributes('style')).toContain('width: 800px')
    expect(wrapper.props('currentIndex')).toBe(1)
    expect(wrapper.emitted('update:currentIndex')).toBeUndefined()

    wrapper.unmount()
    expect(lastResizeObserver?.disconnected).toBe(true)
  })

  test('双指缩放上限为 5 倍', async () => {
    const { wrapper, container } = mountView(0)

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

    expect(wrapper.get('.page-content').attributes('style')).toContain('scale(5)')
    wrapper.unmount()
  })

  test('中间区域双击按 1 倍、2 倍、3 倍、5 倍循环缩放', async () => {
    let now = 1000
    vi.spyOn(Date, 'now').mockImplementation(() => now)
    const { wrapper, container } = mountView()
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
    const currentPageStyle = () => wrapper.findAll('.page-content')[1].attributes('style') ?? ''

    await doubleTap()
    expect(currentPageStyle()).toContain('scale(2)')
    await doubleTap()
    expect(currentPageStyle()).toContain('scale(3)')
    await doubleTap()
    expect(currentPageStyle()).toContain('scale(5)')
    await doubleTap()
    expect(currentPageStyle()).not.toContain('scale(')

    wrapper.unmount()
  })

  test('左右区域第一次点击仍立即翻页', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(1000)
    const { wrapper, container } = mountView()

    await container.trigger('touchstart', { touches: [{ clientX: 30, clientY: 200 }] })
    await container.trigger('touchend', {
      touches: [],
      changedTouches: [{ clientX: 30, clientY: 200 }],
    })
    await container.trigger('touchstart', { touches: [{ clientX: 270, clientY: 200 }] })
    await container.trigger('touchend', {
      touches: [],
      changedTouches: [{ clientX: 270, clientY: 200 }],
    })

    expect(wrapper.emitted('update:currentIndex')).toEqual([[0], [1]])
    wrapper.unmount()
  })

  test('图片错误上报原 URL，多个失败页显示聚合重试', async () => {
    const wrapper = mount(HorizontalPageView, {
      props: {
        imageMap: new Map([
          [1, 'image-1'],
          [2, 'image-2'],
        ]),
        failedSortOrders: new Set<number>(),
        retryingSortOrders: new Set<number>(),
        totalCount: 2,
        currentIndex: 0,
      },
    })

    await wrapper.get('.page-image').trigger('error')
    expect(wrapper.emitted('image-error')).toEqual([[1, 'image-1']])

    await wrapper.setProps({ failedSortOrders: new Set([1, 2]) })
    const retryButton = wrapper.get('button')
    expect(retryButton.text()).toContain('重试全部（2）')
    await retryButton.trigger('click')
    expect(wrapper.emitted('retry-images')).toEqual([[]])

    await wrapper.setProps({ retryingSortOrders: new Set([1, 2]) })
    expect(wrapper.get('button').attributes()).toHaveProperty('disabled')
    expect(wrapper.get('button').text()).toContain('重试中')
    wrapper.unmount()
  })
})
