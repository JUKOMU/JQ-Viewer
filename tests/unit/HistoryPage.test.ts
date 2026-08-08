/* eslint-disable vue/one-component-per-file -- test-only Ionic fixtures */
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import type { BrowseHistoryItem } from '@/services/JmcomicTypes'

const mocks = vi.hoisted(() => ({
  routerPush: vi.fn(),
  getBrowseHistory: vi.fn(),
  getParseHistory: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.routerPush }),
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
    alertController: { create: vi.fn() },
    IonContent: withSlot('IonContent', 'main'),
    IonHeader: withSlot('IonHeader', 'header'),
    IonIcon: withSlot('IonIcon', 'span'),
    IonPage: withSlot('IonPage'),
    IonToolbar: withSlot('IonToolbar'),
  }
})

vi.mock('ionicons/icons', () => ({
  bookOutline: 'book',
  copyOutline: 'copy',
  documentTextOutline: 'document-text',
  ellipsisVertical: 'ellipsis-vertical',
  timeOutline: 'time',
  trashOutline: 'trash',
}))

vi.mock('@/services/HistoryService', () => ({
  HistoryService: {
    getBrowseHistory: mocks.getBrowseHistory,
    getParseHistory: mocks.getParseHistory,
    deleteBrowseItem: vi.fn(),
    deleteParseItem: vi.fn(),
    clearBrowseHistory: vi.fn(),
    clearParseHistory: vi.fn(),
  },
}))

vi.mock('@/components/common/MenuToggleButton.vue', () => ({
  default: { name: 'MenuToggleButton', render: () => null },
}))

import HistoryPage from '@/views/HistoryPage.vue'

const makeBrowseItem = (chapterId: string): BrowseHistoryItem => ({
  id: 1,
  albumId: '100',
  albumTitle: '测试本子',
  coverUrl: 'https://example.test/cover.jpg',
  authors: '作者甲 / 作者乙',
  chapterId,
  chapterTitle: chapterId ? '第二话' : '',
  timestamp: Date.now(),
})

beforeEach(() => {
  vi.clearAllMocks()
  mocks.routerPush.mockResolvedValue(undefined)
  mocks.getParseHistory.mockResolvedValue([])
})

describe('HistoryPage 详情跳转', () => {
  test('历史记录有章节 ID 时定位到对应章节', async () => {
    mocks.getBrowseHistory.mockResolvedValue([makeBrowseItem('200')])
    const wrapper = mount(HistoryPage)
    await flushPromises()

    await wrapper.get('.browse-card').trigger('click')

    expect(mocks.routerPush).toHaveBeenCalledWith({
      path: '/album/100',
      query: {
        title: '测试本子',
        coverUrl: 'https://example.test/cover.jpg',
        authors: '作者甲,作者乙',
        chapterId: '200',
      },
    })
    wrapper.unmount()
  })

  test('历史记录无章节 ID 时保持本子级进入', async () => {
    mocks.getBrowseHistory.mockResolvedValue([makeBrowseItem('')])
    const wrapper = mount(HistoryPage)
    await flushPromises()

    await wrapper.get('.browse-card').trigger('click')

    expect(mocks.routerPush).toHaveBeenCalledWith({
      path: '/album/100',
      query: {
        title: '测试本子',
        coverUrl: 'https://example.test/cover.jpg',
        authors: '作者甲,作者乙',
      },
    })
    wrapper.unmount()
  })
})
