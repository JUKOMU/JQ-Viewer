/* eslint-disable vue/one-component-per-file -- test-only Ionic and child-component fixtures */
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import { beforeEach, afterEach, describe, expect, test, vi } from 'vitest'
import type * as BatchParseUtils from '@/utils/batchParse'

const mocks = vi.hoisted(() => ({
  route: { query: { key: 'batch-test' } },
  getAlbum: vi.fn(),
  parseIdsFromText: vi.fn(),
  router: { push: vi.fn(() => Promise.resolve()) },
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => mocks.router,
}))

vi.mock('@/utils/batchParse', async (importOriginal) => {
  const actual = await importOriginal<BatchParseUtils>()
  return { ...actual, parseIdsFromText: mocks.parseIdsFromText }
})

vi.mock('@ionic/vue', () => ({
  IonContent: defineComponent({
    name: 'IonContent',
    emits: ['ion-scroll', 'pointerdown', 'touchstart', 'wheel', 'keydown', 'click'],
    setup(_, { slots }) {
      return () => h('main', slots.default?.())
    },
  }),
  IonHeader: defineComponent({
    name: 'IonHeader',
    setup:
      (_, { slots }) =>
      () =>
        h('header', slots.default?.()),
  }),
  IonIcon: defineComponent({ name: 'IonIcon', setup: () => () => h('span') }),
  IonPage: defineComponent({
    name: 'IonPage',
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  }),
  IonSpinner: defineComponent({ name: 'IonSpinner', setup: () => () => h('span') }),
  IonToolbar: defineComponent({
    name: 'IonToolbar',
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  }),
}))

vi.mock('ionicons/icons', () => ({
  bookmarkOutline: 'bookmark',
  bookOutline: 'book',
  chevronDownOutline: 'down',
  chevronUpOutline: 'up',
  createOutline: 'create',
  downloadOutline: 'download',
  ellipsisVertical: 'ellipsis',
  heartOutline: 'heart',
  informationCircleOutline: 'info',
  trashOutline: 'trash',
}))

vi.mock('@/components/common/MenuToggleButton.vue', () => ({
  default: defineComponent({ name: 'MenuToggleButton', setup: () => () => h('div') }),
}))

vi.mock('@/components/favorite/FavoriteFolderPicker.vue', () => ({
  default: defineComponent({ name: 'FavoriteFolderPicker', setup: () => () => h('div') }),
}))

vi.mock('@/components/search/SearchResultContainer.vue', () => ({
  default: defineComponent({
    name: 'SearchResultContainer',
    props: {
      items: { type: Array, default: () => [] },
      activeEntryKey: { type: String, default: null },
    },
    emits: ['ion-scroll'],
    setup(props, { expose, slots }) {
      const root = ref<HTMLElement | null>(null)
      const getEntryKey = (entry: { page: number; indexInPage: number; item: { id: string } }) =>
        `${entry.page}-${entry.indexInPage}-${entry.item.id}`
      expose({
        getRootElement: () => root.value,
        getEntryElement: (entryKey: string) =>
          root.value?.querySelector(`[data-entry-key="${entryKey}"]`) as HTMLElement | null,
      })
      return () =>
        h(
          'section',
          { ref: root },
          props.items.map((entry) =>
            h(
              'article',
              {
                key: getEntryKey(entry),
                class: { 'entry-highlighted': props.activeEntryKey === getEntryKey(entry) },
                'data-entry-key': getEntryKey(entry),
              },
              [
                entry.item.title,
                slots['item-actions']?.({
                  item: entry.item,
                  page: entry.page,
                  indexInPage: entry.indexInPage,
                }),
              ],
            ),
          ),
        )
    },
  }),
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    getAlbum: mocks.getAlbum,
    favorites: vi.fn(),
    toggleAlbumFavorite: vi.fn(),
    getPhoto: vi.fn(),
    downloadChapter: vi.fn(),
    manageFavoriteFolder: vi.fn(),
  },
  sanitizeError: vi.fn((_error: unknown, fallback: string) => fallback),
  showToast: vi.fn(() => Promise.resolve()),
}))

vi.mock('@/services/OfflineDownloadService', () => ({
  OfflineDownloadService: { getAll: vi.fn(() => []), addTask: vi.fn() },
}))

vi.mock('@/services/OfflineFavoriteService', () => ({
  OfflineFavoriteService: {
    ensureInit: vi.fn(() => Promise.resolve()),
    getFolders: vi.fn(() => []),
    getAllItemsMerged: vi.fn(() => Promise.resolve([])),
    addItem: vi.fn(),
  },
}))

vi.mock('@/composables/useAuth', () => ({
  useAuth: () => ({ isLoggedIn: ref(false) }),
}))

import BatchParsePage from '@/views/BatchParsePage.vue'

const makeAlbum = (id: string) => ({
  id,
  title: `本子 ${id}`,
  image: `${id}.jpg`,
  authors: [],
  tags: [],
})

const mountPage = async (text = 'jm111111 jm222222\njm333333') => {
  sessionStorage.setItem('batch-parse-text:batch-test', text)
  const wrapper = mount(BatchParsePage)
  await flushPromises()
  return wrapper
}

const setCardRect = (element: Element, top: number, bottom: number) => {
  vi.spyOn(element, 'getBoundingClientRect').mockReturnValue({
    top,
    bottom,
    height: bottom - top,
    left: 0,
    right: 100,
    width: 100,
    x: 0,
    y: top,
    toJSON: () => ({}),
  } as DOMRect)
}

describe('BatchParsePage 原文与结果联动', () => {
  beforeEach(async () => {
    mocks.route.query = { key: 'batch-test' }
    mocks.getAlbum.mockReset()
    mocks.getAlbum.mockImplementation(async (id: string) => makeAlbum(id))
    const actualBatchParse = await vi.importActual<BatchParseUtils>('@/utils/batchParse')
    mocks.parseIdsFromText.mockReset()
    mocks.parseIdsFromText.mockImplementation(actualBatchParse.parseIdsFromText)
    mocks.router.push.mockClear()
    sessionStorage.clear()
    Element.prototype.scrollIntoView = vi.fn()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  test('同一行的每个 JM 号都是独立可点击目标', async () => {
    const wrapper = await mountPage()
    const buttons = wrapper.findAll('button.source-id')
    expect(buttons).toHaveLength(3)

    const target = wrapper.find('[data-entry-key="1-1-222222"]')
    const scrollIntoView = vi.fn()
    target.element.scrollIntoView = scrollIntoView

    await buttons[1].trigger('click')

    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'center' })
    expect(wrapper.findComponent({ name: 'SearchResultContainer' }).props('activeEntryKey')).toBe(
      '1-1-222222',
    )
    expect(wrapper.find('.source-line.current-line').exists()).toBe(true)
    expect(target.classes()).toContain('entry-highlighted')
  })

  test('同一行首项失败时，点击行空白可定位后续成功项', async () => {
    mocks.getAlbum.mockImplementation(async (id: string) => {
      if (id === '111111') throw new Error('not found')
      return makeAlbum(id)
    })

    const wrapper = await mountPage('jm111111 jm222222')
    const line = wrapper.find('.source-line')
    const target = wrapper.find('[data-entry-key="1-1-222222"]')
    const scrollIntoView = vi.fn()
    target.element.scrollIntoView = scrollIntoView

    await line.trigger('click')

    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'center' })
    expect(wrapper.findComponent({ name: 'SearchResultContainer' }).props('activeEntryKey')).toBe(
      '1-1-222222',
    )
  })

  test('编辑模式可以将单行多个 ID 整理为单行单 ID', async () => {
    const wrapper = await mountPage()
    await wrapper.findAll('button.header-btn')[1].trigger('click')

    const textarea = wrapper.find('textarea.source-textarea')
    await textarea.setValue('jm1199508 jm1199507\n备注 jm1199508')
    const normalizeButton = wrapper
      .findAll('button.edit-btn')
      .find((button) => button.text() === '按 ID 分行')
    expect(normalizeButton).toBeDefined()
    await normalizeButton!.trigger('click')

    expect(textarea.element.value).toBe('1199508\n1199507')
  })

  test('首尾卡片点击后保持高亮，直到用户再次操作内容区', async () => {
    vi.useFakeTimers()
    const wrapper = await mountPage()
    const content = wrapper.findComponent({ name: 'IonContent' })
    const result = wrapper.findComponent({ name: 'SearchResultContainer' })
    const cards = result.findAll('[data-entry-key]')
    setCardRect(cards[0].element, 100, 200)
    setCardRect(cards[1].element, 334, 434)
    setCardRect(cards[2].element, 600, 700)

    const buttons = wrapper.findAll('button.source-id')
    await buttons[0].trigger('click')
    content.vm.$emit('ion-scroll', { detail: { scrollTop: 0 } })
    vi.advanceTimersByTime(150)
    await wrapper.vm.$nextTick()

    expect(result.props('activeEntryKey')).toBe('1-0-111111')

    content.vm.$emit('pointerdown')
    content.vm.$emit('ion-scroll', { detail: { scrollTop: 200 } })
    vi.advanceTimersByTime(150)
    await wrapper.vm.$nextTick()
    expect(result.props('activeEntryKey')).toBe('1-1-222222')

    await buttons[2].trigger('click')
    content.vm.$emit('ion-scroll', { detail: { scrollTop: 600 } })
    vi.advanceTimersByTime(150)
    await wrapper.vm.$nextTick()

    expect(result.props('activeEntryKey')).toBe('1-2-333333')
  })

  test('滚动时选择中心最近卡片，而不是最后一个可见卡片', async () => {
    vi.useFakeTimers()
    const wrapper = await mountPage()
    const result = wrapper.findComponent({ name: 'SearchResultContainer' })
    const cards = result.findAll('[data-entry-key]')
    setCardRect(cards[0].element, 100, 200)
    setCardRect(cards[1].element, 334, 434)
    setCardRect(cards[2].element, 600, 700)

    wrapper.findComponent({ name: 'IonContent' }).vm.$emit('ion-scroll', {
      detail: { scrollTop: 200 },
    })
    vi.advanceTimersByTime(150)
    await wrapper.vm.$nextTick()

    expect(result.props('activeEntryKey')).toBe('1-1-222222')
  })

  test('失败结果过滤后仍按原始解析索引高亮', async () => {
    mocks.getAlbum.mockImplementation(async (id: string) => {
      if (id === '222222') throw new Error('not found')
      return makeAlbum(id)
    })

    vi.useFakeTimers()
    const wrapper = await mountPage()
    const result = wrapper.findComponent({ name: 'SearchResultContainer' })
    const cards = result.findAll('[data-entry-key]')
    expect(cards.map((card) => card.attributes('data-entry-key'))).toEqual([
      '1-0-111111',
      '1-2-333333',
    ])
    setCardRect(cards[0].element, 100, 200)
    setCardRect(cards[1].element, 334, 434)

    wrapper.findComponent({ name: 'IonContent' }).vm.$emit('ion-scroll', {
      detail: { scrollTop: 200 },
    })
    vi.advanceTimersByTime(150)
    await wrapper.vm.$nextTick()

    expect(result.props('activeEntryKey')).toBe('1-2-333333')
    expect(wrapper.findAll('.source-line')[1].classes()).toContain('current-line')
  })

  test('重复 JM 号的失败状态按解析项索引隔离', async () => {
    mocks.parseIdsFromText.mockReturnValue({
      items: [
        {
          id: '111111',
          lineText: 'jm111111 jm111111',
          lineIndex: 0,
          startIndex: 2,
          endIndex: 8,
        },
        {
          id: '111111',
          lineText: 'jm111111 jm111111',
          lineIndex: 0,
          startIndex: 11,
          endIndex: 17,
        },
      ],
      invalidIds: [],
    })
    let requestCount = 0
    mocks.getAlbum.mockImplementation(async (id: string) => {
      if (requestCount++ === 0) throw new Error('not found')
      return makeAlbum(id)
    })

    const wrapper = await mountPage('jm111111 jm111111')
    const line = wrapper.find('.source-line')

    expect(line.findAll('.hl-failed')).toHaveLength(1)
    expect(line.findAll('button.source-id')).toHaveLength(1)
    expect(wrapper.find('[data-entry-key="1-1-111111"]').exists()).toBe(true)
  })

  test('移除当前高亮卡片后清理状态并重新选择现存卡片', async () => {
    vi.useFakeTimers()
    const wrapper = await mountPage()
    const result = wrapper.findComponent({ name: 'SearchResultContainer' })
    const cards = result.findAll('[data-entry-key]')
    setCardRect(cards[0].element, 100, 200)
    setCardRect(cards[1].element, 334, 434)
    setCardRect(cards[2].element, 600, 700)

    await wrapper.findAll('button.source-id')[1].trigger('click')
    expect(result.props('activeEntryKey')).toBe('1-1-222222')

    await wrapper.findAll('.card-more-btn')[1].trigger('click')
    await wrapper.find('.card-menu-item--danger').trigger('click')
    await flushPromises()

    expect(result.find('[data-entry-key="1-1-222222"]').exists()).toBe(false)
    expect(result.props('activeEntryKey')).toBe('1-0-111111')
  })
})
