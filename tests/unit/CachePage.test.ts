/* eslint-disable vue/one-component-per-file -- test-only Ionic fixtures */
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { beforeEach, describe, expect, test, vi } from 'vitest'

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
})
