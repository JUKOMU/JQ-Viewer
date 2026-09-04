/* eslint-disable vue/one-component-per-file -- test-only Ionic and child-component fixtures */
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, KeepAlive, nextTick, onMounted, reactive, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

import {
  clearSearchPageContextCache,
  getSearchPageContext,
} from '@/composables/searchPageContextCache'

const mocks = vi.hoisted(() => ({
  route: {
    name: 'SearchPage',
    query: { keyword: 'old', orderBy: 'mr', time: 'a', searchMainTag: '0', page: '1' },
  },
  router: {
    push: vi.fn(() => Promise.resolve()),
    replace: vi.fn(() => Promise.resolve()),
    back: vi.fn(),
  },
  search: vi.fn(),
  categories: vi.fn(),
  getAlbum: vi.fn(),
  showToast: vi.fn(() => Promise.resolve()),
  scrollElement: { scrollTop: 0, scrollHeight: 5000, clientHeight: 800 },
  anchorElement: { getBoundingClientRect: vi.fn(() => ({ top: 100 })) },
  resultRoot: { getBoundingClientRect: vi.fn(() => ({ top: 0 })) },
}))

mocks.route = reactive(mocks.route)

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => mocks.router,
}))

vi.mock('@ionic/vue', () => ({
  alertController: { create: vi.fn() },
  IonContent: defineComponent({
    name: 'IonContent',
    setup(_, { slots }) {
      const rootRef = ref<HTMLElement | null>(null)
      onMounted(() => {
        if (!rootRef.value) return
        Object.defineProperty(rootRef.value, 'getScrollElement', {
          configurable: true,
          value: () => Promise.resolve(mocks.scrollElement as unknown as HTMLElement),
        })
      })
      return () => h('main', { ref: rootRef }, slots.default?.())
    },
  }),
  IonIcon: defineComponent({ name: 'IonIcon', setup: () => () => h('span') }),
  IonPage: defineComponent({
    name: 'IonPage',
    setup(_, { slots }) {
      return () => h('div', slots.default?.())
    },
  }),
}))

vi.mock('ionicons/icons', () => ({
  bookOutline: 'book',
  downloadOutline: 'download',
  ellipsisVertical: 'ellipsis',
  heartOutline: 'heart',
  informationCircleOutline: 'information',
}))

vi.mock('@/components/search/SearchHeaderBar.vue', () => ({
  default: defineComponent({
    name: 'SearchHeaderBar',
    props: { query: { type: Object, required: true }, loading: Boolean },
    emits: ['search'],
    setup() {
      return () => h('div')
    },
  }),
}))

vi.mock('@/components/search/SearchResultContainer.vue', () => ({
  default: defineComponent({
    name: 'SearchResultContainer',
    props: {
      result: { type: Object, default: null },
      items: { type: Array, default: () => [] },
      loading: Boolean,
      errorMessage: { type: String, default: '' },
    },
    setup(_, { expose }) {
      expose({
        getRootElement: () => mocks.resultRoot,
        getEntryElement: () => mocks.anchorElement,
      })
      return () => h('div')
    },
  }),
}))

vi.mock('@/components/common/MenuToggleButton.vue', () => ({
  default: defineComponent({ setup: () => () => h('div') }),
}))

vi.mock('@/components/common/QuickActionFab.vue', () => ({
  default: defineComponent({ setup: () => () => h('div') }),
}))

vi.mock('@/components/favorite/FavoriteFolderPicker.vue', () => ({
  default: defineComponent({ setup: () => () => h('div') }),
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    search: mocks.search,
    categories: mocks.categories,
    getAlbum: mocks.getAlbum,
    getPhoto: vi.fn(),
    downloadChapter: vi.fn(),
    toggleAlbumFavorite: vi.fn(),
    favoriteToFolder: vi.fn(),
  },
  sanitizeError: vi.fn((error: unknown, fallback: string) => String(error || fallback)),
  showToast: mocks.showToast,
}))

vi.mock('@/services/OfflineDownloadService', () => ({
  OfflineDownloadService: { getAll: vi.fn(() => []), addTask: vi.fn() },
}))

vi.mock('@/services/OfflineFavoriteService', () => ({
  OfflineFavoriteService: {
    ensureInit: vi.fn(() => Promise.resolve()),
    getFolders: vi.fn(() => []),
    addItem: vi.fn(),
    createFolder: vi.fn(),
  },
}))

vi.mock('@/composables/useAuth', () => ({
  useAuth: () => ({ isLoggedIn: { value: false } }),
}))

import SearchPage from '@/views/SearchPage.vue'

const mountedWrappers: Array<{ unmount: () => void }> = []

const track = <T extends { unmount: () => void }>(wrapper: T) => {
  mountedWrappers.push(wrapper)
  return wrapper
}

afterEach(() => {
  for (const wrapper of mountedWrappers) wrapper.unmount()
  mountedWrappers.length = 0
})

const oldResult = {
  currentPage: 1,
  totalItems: 1,
  totalPages: 1,
  content: [{ id: '1', title: '旧结果', coverUrl: 'old.jpg', authors: ['作者'], tags: [] }],
}

const numericQuery = {
  keyword: '999',
  orderBy: 'mr',
  time: 'a',
  searchMainTag: 0,
  page: 1,
}

describe('SearchPage 数字 ID 搜索', () => {
  beforeEach(() => {
    clearSearchPageContextCache()
    mocks.route.name = 'SearchPage'
    mocks.route.query = {
      keyword: 'old',
      orderBy: 'mr',
      time: 'a',
      searchMainTag: '0',
      page: '1',
    }
    mocks.router.push.mockReset()
    mocks.router.push.mockResolvedValue(undefined)
    mocks.router.replace.mockReset()
    mocks.router.replace.mockResolvedValue(undefined)
    mocks.search.mockReset()
    mocks.search.mockResolvedValue(oldResult)
    mocks.categories.mockReset()
    mocks.getAlbum.mockReset()
    mocks.showToast.mockClear()
    mocks.scrollElement.scrollTop = 0
    mocks.anchorElement.getBoundingClientRect.mockReset()
    mocks.anchorElement.getBoundingClientRect.mockReturnValue({ top: 100 })
    mocks.resultRoot.getBoundingClientRect.mockReset()
    mocks.resultRoot.getBoundingClientRect.mockReturnValue({ top: 0 })
  })

  test('无效数字 ID 会清除普通搜索的旧结果', async () => {
    const wrapper = track(mount(SearchPage))
    await flushPromises()
    const results = wrapper.findComponent({ name: 'SearchResultContainer' })
    expect(results.props('items')).toHaveLength(1)

    mocks.getAlbum.mockRejectedValue(new Error('not found'))
    wrapper.findComponent({ name: 'SearchHeaderBar' }).vm.$emit('search', numericQuery)
    await flushPromises()

    expect(results.props('items')).toHaveLength(0)
    expect(results.props('result')).toBeNull()
    expect(mocks.showToast).toHaveBeenCalledWith('本子不存在', 'danger')
  })

  test('详情缺少作者字段时仍然导航', async () => {
    mocks.route.query = { ...mocks.route.query, keyword: '999' }
    mocks.getAlbum.mockResolvedValue({ id: '999', title: '数字本子', image: 'cover.jpg' })

    track(mount(SearchPage))
    await flushPromises()

    expect(mocks.router.replace).toHaveBeenCalledWith({
      path: '/album/999',
      query: { title: '数字本子', coverUrl: 'cover.jpg', authors: '' },
    })
    expect(mocks.showToast).not.toHaveBeenCalled()
  })

  test('导航失败不会误报本子不存在', async () => {
    mocks.route.query = { ...mocks.route.query, keyword: '999' }
    mocks.getAlbum.mockResolvedValue({
      id: '999',
      title: '数字本子',
      image: 'cover.jpg',
      authors: ['作者'],
    })
    mocks.router.replace.mockRejectedValue(new Error('navigation failed'))

    track(mount(SearchPage))
    await flushPromises()

    expect(mocks.showToast).toHaveBeenCalledWith('Error: navigation failed', 'danger')
    expect(mocks.showToast).not.toHaveBeenCalledWith('本子不存在', 'danger')
  })

  test('停用后销毁不会用失真的锚点覆盖有效快照', async () => {
    const active = ref(true)
    const SearchPageHost = defineComponent({
      setup() {
        return () =>
          h(KeepAlive, null, {
            default: () => (active.value ? h(SearchPage) : null),
          })
      },
    })
    const wrapper = mount(SearchPageHost)
    await flushPromises()

    mocks.scrollElement.scrollTop = 640
    let captureCount = 0
    mocks.anchorElement.getBoundingClientRect.mockImplementation(() => {
      captureCount += 1
      return { top: captureCount === 1 ? 180 : 0 }
    })

    active.value = false
    await nextTick()
    wrapper.unmount()

    expect(captureCount).toBe(1)
    expect(
      getSearchPageContext({
        keyword: 'old',
        orderBy: 'mr',
        time: 'a',
        searchMainTag: 0,
      }),
    ).toMatchObject({ scrollTop: 640, anchorOffset: 180 })
  })

  test('切换搜索上下文前保存最新窗口并在返回时恢复滚动位置', async () => {
    const queryA = {
      keyword: 'A',
      orderBy: 'mr',
      time: 'a',
      searchMainTag: 0,
      page: 3,
    }
    const queryB = { ...queryA, keyword: 'B', page: 1 }
    mocks.route.query = {
      keyword: 'A',
      orderBy: 'mr',
      time: 'a',
      searchMainTag: '0',
      page: '3',
    }
    mocks.search.mockImplementation((query: { keyword?: string; page?: number }) =>
      Promise.resolve({
        currentPage: query.page ?? 1,
        totalItems: 400,
        totalPages: 5,
        content: [
          {
            id: `${query.keyword ?? ''}-${query.page ?? 1}`,
            title: `${query.keyword ?? ''} 结果`,
            coverUrl: '',
            authors: [],
            tags: [],
          },
        ],
      }),
    )

    const wrapper = track(mount(SearchPage))
    await flushPromises()
    mocks.scrollElement.scrollTop = 420

    wrapper.findComponent({ name: 'SearchHeaderBar' }).vm.$emit('search', queryB)
    await flushPromises()

    mocks.route.query = {
      keyword: 'B',
      orderBy: 'mr',
      time: 'a',
      searchMainTag: '0',
      page: '1',
    }
    await nextTick()
    mocks.route.query = {
      keyword: 'A',
      orderBy: 'mr',
      time: 'a',
      searchMainTag: '0',
      page: '3',
    }
    await flushPromises()
    await nextTick()
    await flushPromises()

    expect(mocks.search).toHaveBeenCalledTimes(2)
    expect(mocks.scrollElement.scrollTop).toBe(420)
    expect(wrapper.findComponent({ name: 'SearchResultContainer' }).props('items')).toMatchObject([
      { item: { id: 'A-3' } },
    ])
    expect(
      getSearchPageContext({
        keyword: 'A',
        orderBy: 'mr',
        time: 'a',
        searchMainTag: 0,
      }),
    ).toMatchObject({ routePage: 3, scrollTop: 420 })
  })
})
