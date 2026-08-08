/* eslint-disable vue/one-component-per-file -- test-only Ionic fixtures */
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  getCacheCapacityInfo: vi.fn(),
  getImageCacheContents: vi.fn(),
  getPhoto: vi.fn(),
}))

vi.mock('@ionic/vue', () => {
  const withSlot = (name: string, tag = 'div') =>
    defineComponent({
      name,
      setup(_, { slots }) {
        return () => h(tag, slots.default?.())
      },
    })

  return {
    IonBackButton: withSlot('IonBackButton', 'button'),
    IonButton: withSlot('IonButton', 'button'),
    IonButtons: withSlot('IonButtons'),
    IonContent: withSlot('IonContent', 'main'),
    IonHeader: withSlot('IonHeader', 'header'),
    IonIcon: withSlot('IonIcon', 'span'),
    IonModal: defineComponent({
      name: 'IonModal',
      props: {
        isOpen: Boolean,
      },
      emits: ['didDismiss'],
      setup(_, { slots }) {
        return () => h('div', { class: 'ion-modal-stub' }, slots.default?.())
      },
    }),
    IonPage: withSlot('IonPage'),
    IonTitle: withSlot('IonTitle'),
    IonToolbar: withSlot('IonToolbar'),
  }
})

vi.mock('ionicons/icons', () => ({
  chevronDownOutline: 'chevron-down',
  refreshOutline: 'refresh',
}))

vi.mock('@/services/JmcomicService', () => ({
  getImageUrl: (photoId: string, sortOrder: number, type: string) =>
    `https://cache.test/${type}/${photoId}/${sortOrder}`,
  JmcomicService: {
    getCacheCapacityInfo: mocks.getCacheCapacityInfo,
    getImageCacheContents: mocks.getImageCacheContents,
    getPhoto: mocks.getPhoto,
  },
}))

import CachePage from '@/views/CachePage.vue'

describe('CachePage 章节标题', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  beforeEach(() => {
    mocks.getCacheCapacityInfo.mockReset()
    mocks.getCacheCapacityInfo.mockResolvedValue({ capacityMb: 100, usedMb: 1 })
    mocks.getImageCacheContents.mockReset()
    mocks.getImageCacheContents.mockResolvedValue({
      entries: [
        { photoId: '10', sortOrder: 1, type: 'image', sizeBytes: 100, mimeType: 'image/jpeg' },
        { photoId: '20', sortOrder: 1, type: 'thumb', sizeBytes: 20, mimeType: 'image/jpeg' },
      ],
    })
    mocks.getPhoto.mockReset()
  })

  test('单章节显示标题，多章节追加话数，并随图片一起收起', async () => {
    mocks.getPhoto.mockImplementation((photoId: string) =>
      Promise.resolve(
        photoId === '10'
          ? {
              id: '10',
              title: '单章节标题',
              albumId: '10',
              sortOrder: 1,
              author: '',
              tags: [],
              images: [],
              isSingleEpisode: true,
            }
          : {
              id: '20',
              title: '多章节标题',
              albumId: '2',
              sortOrder: 3,
              author: '',
              tags: [],
              images: [],
              isSingleEpisode: false,
            },
      ),
    )

    const wrapper = mount(CachePage)
    await flushPromises()

    expect(wrapper.findAll('.group-title').map((item) => item.text())).toEqual([
      '单章节标题',
      '多章节标题 · 第3话',
    ])

    await wrapper.findAll('.group-header')[0].trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.cache-group')[0].find('.group-title').exists()).toBe(false)
    expect(wrapper.findAll('.cache-group')[0].find('.cache-card').exists()).toBe(false)
    expect(wrapper.findAll('.cache-group')[0].find('.group-header').exists()).toBe(true)
    wrapper.unmount()
  })

  test('标题接口失败时不影响缓存图片显示', async () => {
    mocks.getPhoto.mockRejectedValue(new Error('offline'))

    const wrapper = mount(CachePage)
    await flushPromises()

    expect(wrapper.findAll('.group-title').every((item) => item.text() === '标题获取失败')).toBe(
      true,
    )
    expect(wrapper.findAll('.cache-card')).toHaveLength(2)
    wrapper.unmount()
  })

  test('点击图片打开无标题栏预览，优先使用原图并可点击预览层关闭', async () => {
    mocks.getImageCacheContents.mockResolvedValue({
      entries: [
        { photoId: '10', sortOrder: 1, type: 'thumb', sizeBytes: 20, mimeType: 'image/jpeg' },
        { photoId: '10', sortOrder: 1, type: 'image', sizeBytes: 100, mimeType: 'image/jpeg' },
      ],
    })
    mocks.getPhoto.mockResolvedValue({
      id: '10',
      title: '标题',
      albumId: '10',
      sortOrder: 1,
      author: '',
      tags: [],
      images: [],
      isSingleEpisode: true,
    })

    const wrapper = mount(CachePage)
    await flushPromises()

    await wrapper.find('.image-frame').trigger('click')

    expect(wrapper.findComponent({ name: 'IonModal' }).props('isOpen')).toBe(true)
    expect(wrapper.find('.preview-stage img').attributes('src')).toBe(
      'https://cache.test/image/10/1',
    )
    expect(wrapper.find('.preview-title').exists()).toBe(false)

    await wrapper.find('.preview-stage').trigger('click')

    expect(wrapper.findComponent({ name: 'IonModal' }).props('isOpen')).toBe(false)
    wrapper.unmount()
  })

  test('没有原图时预览缩略图', async () => {
    mocks.getPhoto.mockRejectedValue(new Error('offline'))

    const wrapper = mount(CachePage)
    await flushPromises()

    await wrapper.findAll('.image-frame')[1].trigger('click')

    expect(wrapper.find('.preview-stage img').attributes('src')).toBe(
      'https://cache.test/thumb/20/1',
    )
    wrapper.unmount()
  })

  test('支持最大 5 倍双指缩放和单指平移，手势结束后不会误关闭并在重新打开时复位', async () => {
    mocks.getPhoto.mockRejectedValue(new Error('offline'))

    const wrapper = mount(CachePage)
    await flushPromises()
    await wrapper.find('.image-frame').trigger('click')

    const stage = wrapper.find('.preview-stage')
    Object.defineProperties(stage.element, {
      clientWidth: { value: 300 },
      clientHeight: { value: 400 },
    })

    await stage.trigger('touchstart', {
      touches: [
        { clientX: 100, clientY: 100 },
        { clientX: 200, clientY: 100 },
      ],
    })
    await stage.trigger('touchmove', {
      touches: [
        { clientX: -200, clientY: 100 },
        { clientX: 500, clientY: 100 },
      ],
    })
    await stage.trigger('touchend', { touches: [], changedTouches: [] })

    expect(stage.find('img').attributes('style')).toContain(
      'translate3d(-600px, -400px, 0) scale(5)',
    )
    expect(wrapper.findComponent({ name: 'IonModal' }).props('isOpen')).toBe(true)

    await stage.trigger('touchstart', { touches: [{ clientX: 150, clientY: 100 }] })
    await stage.trigger('touchmove', { touches: [{ clientX: 100, clientY: 50 }] })
    await stage.trigger('touchend', { touches: [], changedTouches: [] })
    await stage.trigger('click')

    expect(stage.find('img').attributes('style')).toContain(
      'translate3d(-650px, -450px, 0) scale(5)',
    )
    expect(wrapper.findComponent({ name: 'IonModal' }).props('isOpen')).toBe(true)

    wrapper.findComponent({ name: 'IonModal' }).vm.$emit('didDismiss')
    await wrapper.find('.image-frame').trigger('click')

    expect(wrapper.find('.preview-stage img').attributes('style')).toContain(
      'translate3d(0px, 0px, 0) scale(1)',
    )
    wrapper.unmount()
  })

  test('双击按 1 倍、2 倍、3 倍、5 倍循环缩放', async () => {
    mocks.getPhoto.mockRejectedValue(new Error('offline'))
    let now = 1_000
    vi.spyOn(Date, 'now').mockImplementation(() => now)

    const wrapper = mount(CachePage)
    await flushPromises()
    await wrapper.find('.image-frame').trigger('click')

    const stage = wrapper.find('.preview-stage')
    Object.defineProperties(stage.element, {
      clientWidth: { value: 300 },
      clientHeight: { value: 400 },
    })
    const tap = async () => {
      await stage.trigger('touchstart', { touches: [{ clientX: 150, clientY: 200 }] })
      await stage.trigger('touchend', {
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
    expect(stage.find('img').attributes('style')).toContain('scale(2)')
    await doubleTap()
    expect(stage.find('img').attributes('style')).toContain('scale(3)')
    await doubleTap()
    expect(stage.find('img').attributes('style')).toContain('scale(5)')
    await doubleTap()
    expect(stage.find('img').attributes('style')).toContain('translate3d(0px, 0px, 0) scale(1)')
    expect(wrapper.findComponent({ name: 'IonModal' }).props('isOpen')).toBe(true)
    wrapper.unmount()
  })

  test('触摸单击等待双击判定后关闭预览', async () => {
    mocks.getPhoto.mockRejectedValue(new Error('offline'))

    const wrapper = mount(CachePage)
    await flushPromises()
    await wrapper.find('.image-frame').trigger('click')
    vi.useFakeTimers()

    const stage = wrapper.find('.preview-stage')
    await stage.trigger('touchstart', { touches: [{ clientX: 100, clientY: 100 }] })
    await stage.trigger('touchend', {
      touches: [],
      changedTouches: [{ clientX: 100, clientY: 100 }],
    })

    expect(wrapper.findComponent({ name: 'IonModal' }).props('isOpen')).toBe(true)
    vi.advanceTimersByTime(280)
    await flushPromises()
    expect(wrapper.findComponent({ name: 'IonModal' }).props('isOpen')).toBe(false)
    wrapper.unmount()
  })
})
