import {mount} from '@vue/test-utils'
import {afterEach, describe, expect, test, vi} from 'vitest'
import HorizontalPageView from '@/components/reader/HorizontalPageView.vue'

afterEach(() => {
  vi.restoreAllMocks()
})

function mountView(currentIndex = 1) {
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
    clientHeight: {configurable: true, value: 400},
    clientWidth: {configurable: true, value: 300},
  })
  return {wrapper, container}
}

describe('HorizontalPageView', () => {
  test('双指缩放上限为 5 倍', async () => {
    const {wrapper, container} = mountView(0)

    await container.trigger('touchstart', {
      touches: [
        {clientX: 100, clientY: 100},
        {clientX: 200, clientY: 100},
      ],
    })
    await container.trigger('touchmove', {
      touches: [
        {clientX: -200, clientY: 100},
        {clientX: 500, clientY: 100},
      ],
    })

    expect(wrapper.get('.page-content').attributes('style')).toContain('scale(5)')
    wrapper.unmount()
  })

  test('中间区域双击按 1 倍、2 倍、3 倍、5 倍循环缩放', async () => {
    let now = 1000
    vi.spyOn(Date, 'now').mockImplementation(() => now)
    const {wrapper, container} = mountView()
    const tap = async () => {
      await container.trigger('touchstart', {touches: [{clientX: 150, clientY: 200}]})
      await container.trigger('touchend', {
        touches: [],
        changedTouches: [{clientX: 150, clientY: 200}],
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
    const {wrapper, container} = mountView()

    await container.trigger('touchstart', {touches: [{clientX: 30, clientY: 200}]})
    await container.trigger('touchend', {
      touches: [],
      changedTouches: [{clientX: 30, clientY: 200}],
    })
    await container.trigger('touchstart', {touches: [{clientX: 270, clientY: 200}]})
    await container.trigger('touchend', {
      touches: [],
      changedTouches: [{clientX: 270, clientY: 200}],
    })

    expect(wrapper.emitted('update:currentIndex')).toEqual([[0], [1]])
    wrapper.unmount()
  })
})
