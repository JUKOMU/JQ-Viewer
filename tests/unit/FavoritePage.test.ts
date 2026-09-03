/* eslint-disable vue/one-component-per-file -- test-only Ionic and child-component fixtures */
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, onMounted, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  auth: null as null | { isLoggedIn: ReturnType<typeof ref>; userInfo: ReturnType<typeof ref> },
  favorites: vi.fn(),
  getDownloadTasks: vi.fn(),
  getImportedPdfs: vi.fn(),
  addDownloadProgressListener: vi.fn(),
  showToast: vi.fn(() => Promise.resolve()),
  router: {
    push: vi.fn(() => Promise.resolve()),
    back: vi.fn(),
  },
}))

vi.mock('vue-router', () => ({
  useRouter: () => mocks.router,
}))

vi.mock('@ionic/vue', () => ({
  IonContent: defineComponent({
    name: 'IonContent',
    setup(_, { slots }) {
      const elementRef = ref<HTMLElement | null>(null)
      onMounted(() => {
        const element = elementRef.value as HTMLElement & {
          getScrollElement: () => Promise<HTMLElement>
          scrollToTop: ReturnType<typeof vi.fn>
        }
        element.getScrollElement = async () => element
        element.scrollToTop = vi.fn()
      })
      return () => h('main', { ref: elementRef }, slots.default?.())
    },
  }),
  IonIcon: defineComponent({ name: 'IonIcon', setup: () => () => h('span') }),
  IonPage: defineComponent({
    name: 'IonPage',
    setup(_, { slots }) {
      return () => h('div', slots.default?.())
    },
  }),
  IonRefresher: defineComponent({
    name: 'IonRefresher',
    setup(_, { slots }) {
      return () => h('div', slots.default?.())
    },
  }),
  IonRefresherContent: defineComponent({
    name: 'IonRefresherContent',
    setup: () => () => h('div'),
  }),
  createGesture: vi.fn(() => ({ destroy: vi.fn(), enable: vi.fn() })),
}))

vi.mock('ionicons/icons', () => ({
  bookOutline: 'book',
  downloadOutline: 'download',
  ellipsisVertical: 'ellipsis',
  folderOpenOutline: 'folder',
  swapHorizontalOutline: 'swap',
  trashOutline: 'trash',
}))

vi.mock('@/components/common/MenuToggleButton.vue', () => ({
  default: defineComponent({ name: 'MenuToggleButton', setup: () => () => h('div') }),
}))

vi.mock('@/components/common/QuickActionFab.vue', () => ({
  default: defineComponent({ name: 'QuickActionFab', setup: () => () => h('div') }),
}))

vi.mock('@/components/favorite/FavoriteSearchBar.vue', () => ({
  default: defineComponent({
    name: 'FavoriteSearchBar',
    props: { query: { type: Object, required: true }, loading: Boolean },
    setup: () => () => h('div'),
  }),
}))

vi.mock('@/components/favorite/FavoriteSideMenu.vue', () => ({
  default: defineComponent({
    name: 'FavoriteSideMenu',
    setup(_, { expose }) {
      expose({ panelRef: null })
      return () => h('aside')
    },
  }),
}))

vi.mock('@/components/common/CardContextMenu.vue', () => ({
  default: defineComponent({ name: 'CardContextMenu', setup: () => () => h('div') }),
}))

vi.mock('@/components/search/SearchResultContainer.vue', () => ({
  default: defineComponent({
    name: 'SearchResultContainer',
    props: {
      result: { type: Object, default: null },
      items: { type: Array, default: () => [] },
      loading: Boolean,
    },
    setup(_, { expose }) {
      expose({
        getEntryElement: () => null,
        getRootElement: () => null,
      })
      return () => h('section')
    },
  }),
}))

vi.mock('@/services/AppAlertService', () => ({
  createAppAlert: vi.fn(),
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    favorites: mocks.favorites,
    getDownloadTasks: mocks.getDownloadTasks,
    getImportedPdfs: mocks.getImportedPdfs,
    addDownloadProgressListener: mocks.addDownloadProgressListener,
  },
  sanitizeError: (_error: unknown, fallback: string) => fallback,
  showToast: mocks.showToast,
}))

vi.mock('@/services/OfflineFavoriteService', () => ({
  OfflineFavoriteService: {
    ensureInit: vi.fn(() => Promise.resolve()),
    getFolders: vi.fn(() => []),
  },
  offlineFolderCache: ref([]),
  offlineTotalCount: ref(0),
}))

vi.mock('@/services/OfflineDownloadService', () => ({
  OfflineDownloadService: { getAll: vi.fn(() => []) },
}))

vi.mock('@/services/ExportFormatService', () => ({
  ExportFormatService: {},
}))

vi.mock('@/composables/useAuth', async () => {
  const { ref } = await import('vue')
  const isLoggedIn = ref(true)
  const userInfo = ref({ uid: 'account-a' })
  mocks.auth = { isLoggedIn, userInfo }
  return { useAuth: () => mocks.auth }
})

import FavoritePage from '@/views/FavoritePage.vue'
import { clearFavoriteFolderStore } from '@/composables/favoriteFolderStore'
import { clearFavoritePageCache } from '@/composables/favoritePageCache'

const item = {
  id: 'album-1',
  title: '在线收藏',
  coverUrl: 'cover.jpg',
  authors: [],
  tags: [],
}

const onlineResult = {
  folderName: '全部',
  folderId: '0',
  currentPage: 1,
  totalItems: 1,
  totalPages: 1,
  content: [item],
  folderList: { '0': '全部' },
}

let wrapper: VueWrapper | null = null

beforeEach(() => {
  clearFavoriteFolderStore()
  clearFavoritePageCache()
  mocks.auth!.isLoggedIn.value = true
  mocks.auth!.userInfo.value = { uid: 'account-a' }
  mocks.favorites.mockReset()
  mocks.favorites.mockResolvedValue(onlineResult)
  mocks.getDownloadTasks.mockReset()
  mocks.getDownloadTasks.mockResolvedValue({ tasks: [] })
  mocks.getImportedPdfs.mockReset()
  mocks.getImportedPdfs.mockResolvedValue({ pdfs: [] })
  mocks.addDownloadProgressListener.mockReset()
  mocks.addDownloadProgressListener.mockResolvedValue({ remove: vi.fn(() => Promise.resolve()) })
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

describe('FavoritePage 账号切换', () => {
  test('登出后立即清空在线结果并切换到无离线内容状态', async () => {
    wrapper = mount(FavoritePage)
    await flushPromises()

    const results = wrapper.findComponent({ name: 'SearchResultContainer' })
    expect(results.props('items')).toHaveLength(1)
    expect(results.props('result')).not.toBeNull()

    mocks.auth!.isLoggedIn.value = false
    mocks.auth!.userInfo.value = null
    await flushPromises()

    expect(results.props('items')).toHaveLength(0)
    expect(results.props('result')).toBeNull()
  })
})
