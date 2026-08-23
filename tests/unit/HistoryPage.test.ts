/* eslint-disable vue/one-component-per-file -- test-only Ionic fixtures */
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import type { BrowseHistoryItem } from '@/services/JmcomicTypes'

const mocks = vi.hoisted(() => ({
  routerPush: vi.fn(),
  createAppAlert: vi.fn(),
  getBrowseHistory: vi.fn(),
  getParseHistory: vi.fn(),
  clearBrowseHistory: vi.fn(),
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
  chevronDownOutline: 'chevron-down',
  copyOutline: 'copy',
  documentTextOutline: 'document-text',
  ellipsisVertical: 'ellipsis-vertical',
  informationCircleOutline: 'information-circle',
  timeOutline: 'time',
  trashOutline: 'trash',
}))

vi.mock('@/services/AppAlertService', () => ({
  createAppAlert: mocks.createAppAlert,
}))

vi.mock('@/services/HistoryService', () => ({
  HistoryService: {
    getBrowseHistory: mocks.getBrowseHistory,
    getParseHistory: mocks.getParseHistory,
    deleteBrowseItem: vi.fn(),
    deleteParseItem: vi.fn(),
    clearBrowseHistory: mocks.clearBrowseHistory,
    clearParseHistory: vi.fn(),
  },
}))

vi.mock('@/components/common/MenuToggleButton.vue', () => ({
  default: { name: 'MenuToggleButton', render: () => null },
}))

vi.mock('@/components/common/CardContextMenu.vue', () => ({
  default: defineComponent({
    name: 'CardContextMenu',
    props: {
      visible: { type: Boolean, default: false },
      anchor: { type: Object, default: null },
      actions: { type: Array, default: () => [] },
    },
    setup(props) {
      return () =>
        h('div', {
          class: 'card-context-menu',
          'data-visible': props.visible ? 'true' : 'false',
        })
    },
  }),
}))

import HistoryPage from '@/views/HistoryPage.vue'

const makeBrowseItem = (
  chapterId = '',
  overrides: Partial<BrowseHistoryItem> = {},
): BrowseHistoryItem => ({
  id: 1,
  albumId: '100',
  albumTitle: '测试本子',
  coverUrl: 'https://example.test/cover.jpg',
  authors: '作者甲 / 作者乙',
  chapterId,
  chapterTitle: chapterId ? '第二话' : '',
  timestamp: Date.now(),
  ...overrides,
})

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date(2025, 6, 16, 12))
  vi.clearAllMocks()
  mocks.routerPush.mockResolvedValue(undefined)
  mocks.createAppAlert.mockResolvedValue({ present: vi.fn().mockResolvedValue(undefined) })
  mocks.clearBrowseHistory.mockResolvedValue(undefined)
  mocks.getBrowseHistory.mockResolvedValue([])
  mocks.getParseHistory.mockResolvedValue([])
})

afterEach(() => {
  vi.useRealTimers()
})

async function mountHistory(items: BrowseHistoryItem[]) {
  mocks.getBrowseHistory.mockResolvedValue(items)
  const wrapper = mount(HistoryPage)
  await flushPromises()
  return wrapper
}

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

describe('HistoryPage 浏览历史分组', () => {
  test('渲染八组中的非空分组并保持稳定顺序', async () => {
    const wrapper = await mountHistory([
      makeBrowseItem('', { id: 1, timestamp: new Date(2025, 6, 16, 10).getTime() }),
      makeBrowseItem('', { id: 2, timestamp: new Date(2025, 6, 15, 10).getTime() }),
      makeBrowseItem('', { id: 3, timestamp: new Date(2025, 6, 14, 10).getTime() }),
      makeBrowseItem('', { id: 4, timestamp: new Date(2025, 6, 1, 10).getTime() }),
      makeBrowseItem('', { id: 5, timestamp: new Date(2025, 3, 16, 10).getTime() }),
      makeBrowseItem('', { id: 6, timestamp: new Date(2025, 0, 16, 10).getTime() }),
      makeBrowseItem('', { id: 7, timestamp: new Date(2024, 11, 31, 10).getTime() }),
    ])

    expect(wrapper.findAll('.date-group-toggle').map((button) => button.text())).toEqual([
      '今天',
      '昨天',
      '本周',
      '本月',
      '3个月内',
      '6个月内',
      '更早',
    ])
    expect(wrapper.findAll('.browse-card')).toHaveLength(7)
    wrapper.unmount()
  })

  test('文本和图标共享可访问按钮，独立切换并同步 region 状态', async () => {
    const wrapper = await mountHistory([
      makeBrowseItem('', { id: 1, timestamp: new Date(2025, 6, 16, 10).getTime() }),
      makeBrowseItem('', { id: 2, timestamp: new Date(2025, 6, 15, 10).getTime() }),
    ])
    const todayToggle = wrapper.get('#history-group-toggle-today')
    const todayContentId = 'history-group-content-today'

    expect(todayToggle.element.tagName).toBe('BUTTON')
    expect(todayToggle.text()).toBe('今天')
    expect(todayToggle.find('.date-group-toggle-icon').attributes('aria-hidden')).toBe('true')
    expect(todayToggle.attributes('aria-expanded')).toBe('true')
    expect(todayToggle.attributes('aria-controls')).toBe(todayContentId)
    expect(wrapper.get(`#${todayContentId}`).attributes('role')).toBe('region')
    expect(wrapper.get(`#${todayContentId}`).attributes('aria-labelledby')).toBe(
      'history-group-toggle-today',
    )

    await todayToggle.trigger('click')

    expect(todayToggle.attributes('aria-expanded')).toBe('false')
    expect(todayToggle.find('.date-group-toggle-icon').classes()).toContain('collapsed')
    expect(wrapper.find(`#${todayContentId}`).exists()).toBe(false)
    expect(wrapper.get('#history-group-toggle-yesterday').attributes('aria-expanded')).toBe('true')

    await todayToggle.trigger('click')
    await todayToggle.trigger('click')
    await todayToggle.trigger('click')

    expect(todayToggle.attributes('aria-expanded')).toBe('true')
    expect(wrapper.find(`#${todayContentId}`).exists()).toBe(true)
    wrapper.unmount()
  })

  test('折叠包含上下文菜单的分组时先关闭菜单', async () => {
    const wrapper = await mountHistory([
      makeBrowseItem('', { id: 1, timestamp: new Date(2025, 6, 16, 10).getTime() }),
    ])

    await wrapper.get('.card-more-btn').trigger('click')
    expect(wrapper.get('.card-context-menu').attributes('data-visible')).toBe('true')

    await wrapper.get('#history-group-toggle-today').trigger('click')

    expect(wrapper.get('.card-context-menu').attributes('data-visible')).toBe('false')
    wrapper.unmount()
  })

  test('切换解析历史再返回时保留分组折叠状态', async () => {
    const wrapper = await mountHistory([
      makeBrowseItem('', { id: 1, timestamp: new Date(2025, 6, 16, 10).getTime() }),
    ])

    await wrapper.get('#history-group-toggle-today').trigger('click')
    const tabButtons = wrapper.findAll('.tab-btn')
    await tabButtons[1].trigger('click')
    await tabButtons[0].trigger('click')
    await flushPromises()

    expect(wrapper.get('#history-group-toggle-today').attributes('aria-expanded')).toBe('false')
    wrapper.unmount()
  })

  test('清空浏览历史后重新出现的分组默认展开', async () => {
    const items = [makeBrowseItem('', { id: 1, timestamp: new Date(2025, 6, 16, 10).getTime() })]
    const present = vi.fn().mockResolvedValue(undefined)
    mocks.createAppAlert.mockImplementation(
      async (options: { buttons: Array<{ handler?: () => void | Promise<void> }> }) => {
        await options.buttons[1]?.handler?.()
        return { present }
      },
    )
    const wrapper = await mountHistory(items)

    await wrapper.get('#history-group-toggle-today').trigger('click')
    await wrapper.get('.clear-btn').trigger('click')
    await flushPromises()
    expect(mocks.clearBrowseHistory).toHaveBeenCalledOnce()

    mocks.getBrowseHistory.mockResolvedValue(items)
    const tabButtons = wrapper.findAll('.tab-btn')
    await tabButtons[1].trigger('click')
    await tabButtons[0].trigger('click')
    await flushPromises()

    expect(wrapper.get('#history-group-toggle-today').attributes('aria-expanded')).toBe('true')
    expect(present).toHaveBeenCalledOnce()
    wrapper.unmount()
  })
})
