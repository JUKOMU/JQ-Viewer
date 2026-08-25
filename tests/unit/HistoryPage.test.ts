/* eslint-disable vue/one-component-per-file -- test-only Ionic fixtures */
import { flushPromises, mount } from '@vue/test-utils'
import { KeepAlive, defineComponent, h, nextTick, onMounted, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import type { BrowseHistoryItem, ParseHistoryItem } from '@/services/JmcomicTypes'

const mocks = vi.hoisted(() => ({
  routerPush: vi.fn(),
  createAppAlert: vi.fn(),
  getBrowseHistory: vi.fn(),
  getParseHistory: vi.fn(),
  clearBrowseHistory: vi.fn(),
  clearParseHistory: vi.fn(),
  deleteBrowseItem: vi.fn(),
  deleteParseItem: vi.fn(),
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
    IonContent: defineComponent({
      name: 'IonContent',
      setup(_, { attrs, slots }) {
        const elementRef = ref<HTMLElement | null>(null)
        onMounted(() => {
          const element = elementRef.value as HTMLElement & {
            getScrollElement: () => Promise<HTMLElement | null>
          }
          element.getScrollElement = async () => element
        })
        return () => h('main', { ...attrs, ref: elementRef }, slots.default?.())
      },
    }),
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
    deleteBrowseItem: mocks.deleteBrowseItem,
    deleteParseItem: mocks.deleteParseItem,
    clearBrowseHistory: mocks.clearBrowseHistory,
    clearParseHistory: mocks.clearParseHistory,
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
    emits: ['select'],
    setup(props, { emit }) {
      return () =>
        h('div', {
          class: 'card-context-menu',
          'data-visible': props.visible ? 'true' : 'false',
          onClick: () => {
            if (props.visible) emit('select', 'delete')
          },
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

const makeParseItem = (id = 1, overrides: Partial<ParseHistoryItem> = {}): ParseHistoryItem => ({
  id,
  text: '12345',
  mode: 'single-mode',
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
  mocks.clearParseHistory.mockResolvedValue(undefined)
  mocks.deleteBrowseItem.mockResolvedValue(undefined)
  mocks.deleteParseItem.mockResolvedValue(undefined)
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

describe('HistoryPage Tab 生命周期', () => {
  test('每个 Tab 只在首次进入时加载并保留分页数据', async () => {
    const firstPage = Array.from({ length: 50 }, (_, index) =>
      makeBrowseItem('', { id: index + 1 }),
    )
    const secondPage = Array.from({ length: 50 }, (_, index) =>
      makeBrowseItem('', { id: index + 51 }),
    )
    mocks.getBrowseHistory.mockResolvedValueOnce(firstPage).mockResolvedValueOnce(secondPage)
    mocks.getParseHistory.mockResolvedValueOnce([makeParseItem()])

    const wrapper = mount(HistoryPage)
    await flushPromises()

    const scrollElement = wrapper.get('main').element as HTMLElement
    Object.defineProperties(scrollElement, {
      scrollHeight: { configurable: true, value: 1000 },
      clientHeight: { configurable: true, value: 600 },
    })
    scrollElement.scrollTop = 250
    scrollElement.dispatchEvent(new CustomEvent('ion-scroll', { detail: { scrollTop: 250 } }))
    await flushPromises()

    expect(mocks.getBrowseHistory).toHaveBeenNthCalledWith(2, 50, 50)
    expect(wrapper.findAll('.browse-card')).toHaveLength(100)

    const tabButtons = wrapper.findAll('.tab-btn')
    await tabButtons[1].trigger('click')
    await flushPromises()
    expect(mocks.getParseHistory).toHaveBeenCalledOnce()

    await tabButtons[0].trigger('click')
    await flushPromises()
    expect(mocks.getBrowseHistory).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('.browse-card')).toHaveLength(100)

    await tabButtons[1].trigger('click')
    await flushPromises()
    expect(mocks.getParseHistory).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  test('两个 Tab 分别恢复滚动位置，并在 KeepAlive 返回时保留状态', async () => {
    mocks.getBrowseHistory.mockResolvedValue([makeBrowseItem('', { id: 1 })])
    mocks.getParseHistory.mockResolvedValue([makeParseItem()])
    const showHistory = ref(true)
    const Host = defineComponent({
      setup() {
        return () =>
          h(KeepAlive, null, [
            showHistory.value
              ? h(HistoryPage, { key: 'history' })
              : h('div', { class: 'other-page' }),
          ])
      },
    })

    const wrapper = mount(Host)
    await flushPromises()
    const historyWrapper = wrapper.findComponent(HistoryPage)
    const scrollElement = historyWrapper.get('main').element as HTMLElement
    const tabButtons = historyWrapper.findAll('.tab-btn')

    scrollElement.scrollTop = 420
    await tabButtons[1].trigger('click')
    await flushPromises()
    expect(scrollElement.scrollTop).toBe(0)

    scrollElement.scrollTop = 180
    await tabButtons[0].trigger('click')
    await flushPromises()
    expect(scrollElement.scrollTop).toBe(420)

    await tabButtons[1].trigger('click')
    await flushPromises()
    expect(scrollElement.scrollTop).toBe(180)

    showHistory.value = false
    await nextTick()
    scrollElement.scrollTop = 0
    showHistory.value = true
    await nextTick()
    await flushPromises()

    expect(wrapper.findComponent(HistoryPage).get('.tab-btn.active').text()).toBe('解析历史')
    expect(scrollElement.scrollTop).toBe(180)
    expect(mocks.getBrowseHistory).toHaveBeenCalledOnce()
    expect(mocks.getParseHistory).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  test('删除记录即时更新列表且切换 Tab 不重新加载已缓存数据', async () => {
    const item = makeBrowseItem('', { id: 7 })
    const present = vi.fn().mockResolvedValue(undefined)
    mocks.getBrowseHistory.mockResolvedValue([item])
    mocks.createAppAlert.mockImplementation(
      async (options: { buttons: Array<{ handler?: () => void | Promise<void> }> }) => {
        await options.buttons[1]?.handler?.()
        return { present }
      },
    )
    const wrapper = await mountHistory([item])

    await wrapper.get('.card-more-btn').trigger('click')
    await wrapper.get('.card-context-menu').trigger('click')
    await flushPromises()

    expect(mocks.deleteBrowseItem).toHaveBeenCalledWith(item.id)
    expect(wrapper.findAll('.browse-card')).toHaveLength(0)

    const tabButtons = wrapper.findAll('.tab-btn')
    await tabButtons[1].trigger('click')
    await tabButtons[0].trigger('click')
    await flushPromises()
    expect(mocks.getBrowseHistory).toHaveBeenCalledOnce()
    expect(present).toHaveBeenCalledOnce()
    wrapper.unmount()
  })
})
