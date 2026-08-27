/* eslint-disable vue/one-component-per-file -- test-only Ionic fixtures */
import { flushPromises, mount } from '@vue/test-utils'
import { KeepAlive, defineComponent, h, nextTick, onMounted, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import type {
  BrowseHistoryItem,
  BrowseHistoryOverview,
  BrowseHistoryRange,
  ParseHistoryItem,
} from '@/services/JmcomicTypes'
import { BROWSE_GROUP_DEFINITIONS, groupBrowseHistory } from '@/utils/historyDateGroups'

const mocks = vi.hoisted(() => ({
  routerPush: vi.fn(),
  createAppAlert: vi.fn(),
  getBrowseHistory: vi.fn(),
  getBrowseHistoryOverview: vi.fn(),
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
    IonSpinner: defineComponent({
      name: 'IonSpinner',
      props: { name: { type: String, default: '' } },
      setup(props) {
        return () => h('span', { class: 'ion-spinner', 'data-name': props.name })
      },
    }),
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
    getBrowseHistoryOverview: mocks.getBrowseHistoryOverview,
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
  id = 1,
  timestamp = Date.now(),
  overrides: Partial<BrowseHistoryItem> = {},
): BrowseHistoryItem => ({
  id,
  albumId: `${id}`,
  albumTitle: `测试本子 ${id}`,
  coverUrl: `https://example.test/${id}.jpg`,
  authors: '作者甲 / 作者乙',
  chapterId: '',
  chapterTitle: '',
  timestamp,
  ...overrides,
})

const makeParseItem = (id = 1, overrides: Partial<ParseHistoryItem> = {}): ParseHistoryItem => ({
  id,
  text: '12345',
  mode: 'single-mode',
  timestamp: Date.now(),
  ...overrides,
})

function matchesRange(timestamp: number, range?: BrowseHistoryRange): boolean {
  if (!range) return true
  if (range.startInclusive !== null && timestamp < range.startInclusive) return false
  if (range.endExclusive !== null && timestamp >= range.endExclusive) return false
  return true
}

function makeOverview(items: BrowseHistoryItem[]): BrowseHistoryOverview {
  const groups = groupBrowseHistory(items, Date.now())
  const groupCounts = Object.fromEntries(
    BROWSE_GROUP_DEFINITIONS.map(({ key }) => [
      key,
      groups.find((group) => group.key === key)?.items.length ?? 0,
    ]),
  ) as BrowseHistoryOverview['groupCounts']
  return { totalCount: items.length, groupCounts }
}

function mockBrowsePages(items: BrowseHistoryItem[]) {
  mocks.getBrowseHistory.mockImplementation(
    async (limit: number, offset: number, range?: BrowseHistoryRange) => {
      const matchingItems = items.filter((item) => matchesRange(item.timestamp, range))
      return {
        items: matchingItems.slice(offset, offset + limit),
        totalCount: matchingItems.length,
      }
    },
  )
}

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
  mocks.getBrowseHistoryOverview.mockResolvedValue(makeOverview([]))
  mocks.getBrowseHistory.mockResolvedValue({ items: [], totalCount: 0 })
  mocks.getParseHistory.mockResolvedValue([])
})

afterEach(() => {
  vi.useRealTimers()
})

const settle = async () => {
  await flushPromises()
  await nextTick()
  await flushPromises()
}

async function mountHistory(items: BrowseHistoryItem[]) {
  mocks.getBrowseHistoryOverview.mockResolvedValue(makeOverview(items))
  mockBrowsePages(items)
  const wrapper = mount(HistoryPage)
  await settle()
  return wrapper
}

function prepareNearBottom(wrapper: ReturnType<typeof mount>) {
  const scrollElement = wrapper.get('main').element as HTMLElement
  Object.defineProperties(scrollElement, {
    scrollHeight: { configurable: true, value: 1000 },
    clientHeight: { configurable: true, value: 600 },
  })
  scrollElement.scrollTop = 250
  return scrollElement
}

describe('HistoryPage 浏览历史概览与分组', () => {
  test('在未加载卡片前显示所有非空分组及全量计数', async () => {
    const today = new Date(2025, 6, 16, 10).getTime()
    const earlier = new Date(2024, 11, 31, 10).getTime()
    const items = [makeBrowseItem(1, today), makeBrowseItem(2, today), makeBrowseItem(3, earlier)]
    const wrapper = await mountHistory(items)

    expect(wrapper.findAll('.date-group-toggle').map((button) => button.text())).toEqual([
      '今天 (2)',
      '更早 (1)',
    ])
    expect(wrapper.findAll('.browse-card')).toHaveLength(2)
    expect(mocks.getBrowseHistoryOverview).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  test('概览失败时不显示为空，并可独立重试概览', async () => {
    const item = makeBrowseItem(1, new Date(2025, 6, 16, 10).getTime())
    mocks.getBrowseHistoryOverview
      .mockResolvedValueOnce(null)
      .mockResolvedValueOnce(makeOverview([item]))
    mockBrowsePages([item])
    const wrapper = mount(HistoryPage)
    await settle()

    expect(wrapper.get('.history-error').text()).toContain('加载失败')
    expect(wrapper.find('.empty-state').exists()).toBe(false)
    await wrapper.get('.retry-btn').trigger('click')
    await settle()
    expect(wrapper.findAll('.browse-card')).toHaveLength(1)
    wrapper.unmount()
  })

  test('展开尚未加载的较早分组只请求该分组范围', async () => {
    const today = new Date(2025, 6, 16, 10).getTime()
    const earlier = new Date(2024, 11, 31, 10).getTime()
    const items = [makeBrowseItem(1, today), makeBrowseItem(2, earlier)]
    const wrapper = await mountHistory(items)
    const earlierToggle = wrapper.get('#history-group-toggle-earlier')

    await earlierToggle.trigger('click')
    expect(wrapper.get('#history-group-content-earlier').attributes('style')).toContain(
      'display: none',
    )
    await earlierToggle.trigger('click')
    await settle()

    const earlierCall = mocks.getBrowseHistory.mock.calls.find(
      ([, , range]) => range?.startInclusive === null,
    )
    expect(earlierCall).toBeDefined()
    expect(earlierCall?.[0]).toBe(50)
    expect(earlierCall?.[1]).toBe(0)
    expect(earlierCall?.[2]).toEqual(expect.objectContaining({ startInclusive: null }))
    expect(earlierCall?.[2].endExclusive).toBeDefined()
    expect(wrapper.findAll('.browse-card')).toHaveLength(2)
    wrapper.unmount()
  })

  test('折叠使用 v-show 保留卡片和图片节点，不执行浏览列表过渡', async () => {
    const item = makeBrowseItem(1, new Date(2025, 6, 16, 10).getTime())
    const wrapper = await mountHistory([item])
    const content = wrapper.get('#history-group-content-today')
    const cardElement = wrapper.get('.browse-card').element
    const imageElement = wrapper.get('.card-cover').element

    await wrapper.get('#history-group-toggle-today').trigger('click')
    expect(content.exists()).toBe(true)
    expect(content.attributes('style')).toContain('display: none')
    expect(wrapper.get('.browse-card').element).toBe(cardElement)

    await wrapper.get('#history-group-toggle-today').trigger('click')
    await settle()
    expect(wrapper.get('.browse-card').element).toBe(cardElement)
    expect(wrapper.get('.card-cover').element).toBe(imageElement)
    expect(wrapper.findAll('.parse-history-list-enter-active')).toHaveLength(0)
    expect(wrapper.findAll('.history-list-enter-active')).toHaveLength(0)
    wrapper.unmount()
  })
})

describe('HistoryPage 分组分页', () => {
  test('各分组独立按范围分页并保持稳定 offset', async () => {
    const today = new Date(2025, 6, 16, 10).getTime()
    const items = Array.from({ length: 51 }, (_, index) => makeBrowseItem(index + 1, today))
    mocks.getBrowseHistoryOverview.mockResolvedValue(makeOverview(items))
    mockBrowsePages(items)
    const wrapper = mount(HistoryPage)
    await settle()

    expect(wrapper.findAll('.browse-card')).toHaveLength(50)
    const scrollElement = prepareNearBottom(wrapper)
    scrollElement.dispatchEvent(new CustomEvent('ion-scroll', { detail: { scrollTop: 250 } }))
    await settle()

    expect(mocks.getBrowseHistory).toHaveBeenCalledWith(
      50,
      50,
      expect.objectContaining({ startInclusive: expect.any(Number), endExclusive: null }),
    )
    expect(wrapper.findAll('.browse-card')).toHaveLength(51)
    wrapper.unmount()
  })

  test('分组分页失败后保留已加载内容并允许组尾重试', async () => {
    const item = makeBrowseItem(1, new Date(2025, 6, 16, 10).getTime())
    mocks.getBrowseHistoryOverview.mockResolvedValue(makeOverview([item]))
    mocks.getBrowseHistory.mockResolvedValueOnce(null).mockResolvedValueOnce({
      items: [item],
      totalCount: 1,
    })
    const wrapper = mount(HistoryPage)
    await settle()

    expect(wrapper.findAll('.browse-card')).toHaveLength(0)
    const retry = wrapper.get('.browse-group-retry')
    await retry.trigger('click')
    await settle()
    expect(wrapper.findAll('.browse-card')).toHaveLength(1)
    wrapper.unmount()
  })
})

describe('HistoryPage 详情和生命周期', () => {
  test('历史记录有章节 ID 时定位到对应章节', async () => {
    const wrapper = await mountHistory([
      makeBrowseItem(1, Date.now(), { albumId: '100', chapterId: '200', chapterTitle: '第二话' }),
    ])
    await wrapper.get('.browse-card').trigger('click')

    expect(mocks.routerPush).toHaveBeenCalledWith({
      path: '/album/100',
      query: {
        title: '测试本子 1',
        coverUrl: 'https://example.test/1.jpg',
        authors: '作者甲,作者乙',
        chapterId: '200',
      },
    })
    wrapper.unmount()
  })

  test('解析历史继续使用全局分页且不受浏览分组请求影响', async () => {
    const firstPage = Array.from({ length: 50 }, (_, index) => makeParseItem(index + 1))
    const lastItem = makeParseItem(51, { text: '最后一条解析' })
    mocks.getBrowseHistoryOverview.mockResolvedValue(makeOverview([]))
    mocks.getParseHistory.mockResolvedValueOnce(firstPage).mockResolvedValueOnce([lastItem])
    const wrapper = mount(HistoryPage)
    await settle()

    await wrapper.get('.tab-btn:nth-child(2)').trigger('click')
    await settle()
    expect(wrapper.findAll('.parse-card')).toHaveLength(50)

    const scrollElement = prepareNearBottom(wrapper)
    scrollElement.dispatchEvent(new CustomEvent('ion-scroll', { detail: { scrollTop: 250 } }))
    await settle()
    expect(mocks.getParseHistory).toHaveBeenNthCalledWith(2, 50, 50)
    expect(wrapper.findAll('.parse-card')).toHaveLength(51)
    wrapper.unmount()
  })

  test('删除浏览记录后刷新概览并重建当前已加载分组', async () => {
    const item = makeBrowseItem(7, new Date(2025, 6, 16, 10).getTime())
    mocks.getBrowseHistoryOverview
      .mockResolvedValueOnce(makeOverview([item]))
      .mockResolvedValueOnce(makeOverview([]))
    mocks.getBrowseHistory.mockResolvedValue({ items: [item], totalCount: 1 })
    mocks.createAppAlert.mockImplementation(
      async (options: { buttons: Array<{ handler?: () => void | Promise<void> }> }) => {
        await options.buttons[1]?.handler?.()
        return { present: vi.fn().mockResolvedValue(undefined) }
      },
    )
    const wrapper = mount(HistoryPage)
    await settle()

    await wrapper.get('.card-more-btn').trigger('click')
    await wrapper.get('.card-context-menu').trigger('click')
    await settle()

    expect(mocks.deleteBrowseItem).toHaveBeenCalledWith(item.id)
    expect(mocks.getBrowseHistoryOverview).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('.browse-card')).toHaveLength(0)
    wrapper.unmount()
  })

  test('KeepAlive 返回时保留 Tab 和各自滚动位置', async () => {
    const item = makeBrowseItem(1, new Date(2025, 6, 16, 10).getTime())
    mocks.getBrowseHistoryOverview.mockResolvedValue(makeOverview([item]))
    mockBrowsePages([item])
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
    await settle()
    const historyWrapper = wrapper.findComponent(HistoryPage)
    const scrollElement = historyWrapper.get('main').element as HTMLElement
    const tabButtons = historyWrapper.findAll('.tab-btn')

    scrollElement.scrollTop = 420
    await tabButtons[1].trigger('click')
    await settle()
    scrollElement.scrollTop = 180
    await tabButtons[0].trigger('click')
    await settle()
    expect(scrollElement.scrollTop).toBe(420)

    showHistory.value = false
    await nextTick()
    scrollElement.scrollTop = 0
    showHistory.value = true
    await nextTick()
    await settle()
    expect(wrapper.findComponent(HistoryPage).get('.tab-btn.active').text()).toBe('浏览历史')
    expect(scrollElement.scrollTop).toBe(420)
    wrapper.unmount()
  })
})
