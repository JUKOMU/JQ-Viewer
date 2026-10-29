/* eslint-disable vue/one-component-per-file -- test-only Ionic and child-component fixtures */
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, KeepAlive, onMounted, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  auth: null as null | { isLoggedIn: ReturnType<typeof ref>; userInfo: ReturnType<typeof ref> },
  favorites: vi.fn(),
  getCompletedDownloadAlbumIds: vi.fn(),
  getImportedLocalFiles: vi.fn(),
  addDownloadProgressListener: vi.fn(),
  getPhoto: vi.fn(),
  downloadChapter: vi.fn(),
  addDownloadTask: vi.fn(),
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
  useBackButton: vi.fn(),
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
  IonSpinner: defineComponent({ name: 'IonSpinner', setup: () => () => h('span') }),
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
  checkmarkDoneOutline: 'checkmark-done',
  closeCircleOutline: 'close-circle',
  closeOutline: 'close',
  documentLockOutline: 'document-lock',
  downloadOutline: 'download',
  ellipsisVertical: 'ellipsis',
  folderOpenOutline: 'folder',
  heartOutline: 'heart',
  pauseOutline: 'pause',
  playOutline: 'play',
  refreshOutline: 'refresh',
  swapHorizontalOutline: 'swap',
  swapVerticalOutline: 'swap-vertical',
  trashOutline: 'trash',
  listOutline: 'list',
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
      downloadedAlbumIds: { type: Set, default: () => new Set() },
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
    getCompletedDownloadAlbumIds: mocks.getCompletedDownloadAlbumIds,
    getImportedLocalFiles: mocks.getImportedLocalFiles,
    addDownloadProgressListener: mocks.addDownloadProgressListener,
    getPhoto: mocks.getPhoto,
    downloadChapter: mocks.downloadChapter,
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
  OfflineDownloadService: { getAll: vi.fn(() => []), addTask: mocks.addDownloadTask },
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
import { cachedState, clearFavoritePageCache } from '@/composables/favoritePageCache'

const item = {
  id: 'album-1',
  title: '在线收藏',
  coverUrl: 'cover.jpg',
  authors: [],
  tags: [],
}

const secondItem = {
  id: 'album-2',
  title: '第二页旧内容',
  coverUrl: 'cover-2.jpg',
  authors: [],
  tags: [],
}

const refreshedSecondItem = {
  ...secondItem,
  title: '第二页新内容',
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
  mocks.getCompletedDownloadAlbumIds.mockReset()
  mocks.getCompletedDownloadAlbumIds.mockResolvedValue({ albumIds: [] })
  mocks.getImportedLocalFiles.mockReset()
  mocks.getImportedLocalFiles.mockResolvedValue({ files: [] })
  mocks.addDownloadProgressListener.mockReset()
  mocks.addDownloadProgressListener.mockResolvedValue({ remove: vi.fn(() => Promise.resolve()) })
  mocks.getPhoto.mockReset()
  mocks.getPhoto.mockResolvedValue({ id: 'chapter-1', title: '第一话' })
  mocks.downloadChapter.mockReset()
  mocks.downloadChapter.mockResolvedValue({ taskId: 'download-1' })
  mocks.addDownloadTask.mockClear()
  mocks.showToast.mockClear()
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

  test('静默刷新会请求并原子替换全部已缓存页后清除 stale', async () => {
    const pageOneResult = {
      ...onlineResult,
      totalItems: 40,
      totalPages: 2,
    }
    const pageTwoResult = {
      ...pageOneResult,
      currentPage: 2,
      content: [refreshedSecondItem],
    }
    cachedState.value = {
      accountId: 'account-a',
      keyword: '',
      stale: true,
      folderSource: 'online',
      currentFolderId: '0',
      onlineFolderMap: { '0': '全部' },
      onlineFolderCounts: { '0': 40 },
      resultMeta: pageOneResult,
      pageCache: { 1: [item], 2: [secondItem] },
      displayMode: 'list',
    }

    const requestedPages: number[] = []
    mocks.favorites.mockImplementation(({ folderId, page }: { folderId: string; page: number }) => {
      if (folderId === '0') {
        requestedPages.push(page)
        return Promise.resolve(page === 2 ? pageTwoResult : pageOneResult)
      }
      return Promise.resolve({ totalItems: 40 })
    })

    wrapper = mount(FavoritePage)
    await flushPromises()

    const results = wrapper.findComponent({ name: 'SearchResultContainer' })
    expect(requestedPages).toContain(2)
    expect(results.props('items').map((entry: { item: { id: string } }) => entry.item.id)).toEqual([
      'album-1',
      'album-2',
    ])
    expect(
      results.props('items').map((entry: { item: { title: string } }) => entry.item.title),
    ).toEqual(['在线收藏', '第二页新内容'])
    expect(cachedState.value?.stale).toBe(false)
  })

  test('静默刷新任一缓存页失败时保留原快照并继续标记 stale', async () => {
    const pageOneResult = { ...onlineResult, totalItems: 40, totalPages: 2 }
    cachedState.value = {
      accountId: 'account-a',
      keyword: '',
      stale: true,
      folderSource: 'online',
      currentFolderId: '0',
      onlineFolderMap: { '0': '全部' },
      onlineFolderCounts: { '0': 40 },
      resultMeta: pageOneResult,
      pageCache: { 1: [item], 2: [secondItem] },
      displayMode: 'list',
    }

    mocks.favorites.mockImplementation(({ folderId, page }: { folderId: string; page: number }) => {
      if (folderId !== '0') return Promise.resolve({ totalItems: 40 })
      if (page === 2) return Promise.reject(new Error('page 2 failed'))
      return Promise.resolve(pageOneResult)
    })

    wrapper = mount(FavoritePage)
    await flushPromises()

    const results = wrapper.findComponent({ name: 'SearchResultContainer' })
    expect(results.props('items').map((entry: { item: { id: string } }) => entry.item.id)).toEqual([
      'album-1',
      'album-2',
    ])
    expect(cachedState.value?.stale).toBe(true)
  })
})

describe('FavoritePage 多选下载', () => {
  test('立即退出多选且无遮罩，提交结束不清理新选择或重置收藏列表', async () => {
    let finishPhoto: ((photo: { id: string; title: string }) => void) | undefined
    mocks.getPhoto.mockReturnValue(new Promise((resolve) => { finishPhoto = resolve }))
    wrapper = mount(FavoritePage)
    await flushPromises()
    const state = (wrapper.vm as any).$.setupState
    const requestsBeforeDownload = mocks.favorites.mock.calls.length

    state.enterMultiSelect(item.id)
    const submission = state.executeFavoriteMultiAction('download')
    expect(state.multiSelectActive).toBe(false)
    expect(mocks.showToast).toHaveBeenCalledWith('批量下载已开始', 'success')
    await flushPromises()
    expect(state.downloadSubmitting).toBe(true)
    expect(wrapper.find('.progress-overlay').exists()).toBe(false)

    state.enterMultiSelect(secondItem.id)
    await flushPromises()
    finishPhoto?.({ id: 'chapter-1', title: '第一话' })
    await submission
    await flushPromises()
    expect(state.multiSelectActive).toBe(true)
    expect(state.multiSelectedIds.has(secondItem.id)).toBe(true)
    expect(state.downloadSubmitting).toBe(false)
    expect(wrapper.find('.progress-overlay').exists()).toBe(false)
    expect(mocks.favorites).toHaveBeenCalledTimes(requestsBeforeDownload)
    expect(mocks.addDownloadTask).toHaveBeenCalledWith(expect.objectContaining({
      albumId: item.id, chapterId: 'chapter-1', status: 'queued',
    }))
  })

  test('跨页选择在补读条目前就退出，仍按原选择提交且不影响新多选', async () => {
    wrapper = mount(FavoritePage)
    await flushPromises()
    const state = (wrapper.vm as any).$.setupState
    let finishPage: ((page: typeof onlineResult) => void) | undefined
    mocks.favorites.mockImplementation(({ page }: { page: number }) => {
      if (page === 1) return new Promise((resolve) => { finishPage = resolve })
      return Promise.resolve({ ...onlineResult, currentPage: 2, totalPages: 2, content: [secondItem] })
    })

    state.enterMultiSelect(secondItem.id)
    const submission = state.executeFavoriteMultiAction('download')
    expect(state.multiSelectActive).toBe(false)
    await flushPromises()
    expect(mocks.getPhoto).not.toHaveBeenCalled()
    expect(state.multiSelectPreparing).toBe(false)

    state.enterMultiSelect(item.id)
    finishPage?.({ ...onlineResult, totalItems: 2, totalPages: 2 })
    await submission
    await flushPromises()
    expect(mocks.getPhoto).toHaveBeenCalledWith(secondItem.id)
    expect(state.multiSelectActive).toBe(true)
    expect(state.multiSelectedIds.has(item.id)).toBe(true)
    expect(state.downloadSubmitting).toBe(false)
    expect(wrapper.find('.progress-overlay').exists()).toBe(false)
  })
})

describe('FavoritePage 全选取消', () => {
  test('退出多选后停止发起后续收藏页请求', async () => {
    wrapper = mount(FavoritePage)
    await flushPromises()

    const requestedPages: number[] = []
    const pageOne = { ...onlineResult, totalItems: 50, totalPages: 50 }
    let finishPageTwo: ((result: typeof pageOne) => void) | undefined
    mocks.favorites.mockReset()
    mocks.favorites.mockImplementation(({ page }: { page: number }) => {
      requestedPages.push(page)
      if (page === 1) return Promise.resolve(pageOne)
      return new Promise((resolve) => {
        finishPageTwo = resolve
      })
    })

    const state = (wrapper.vm as any).$.setupState
    state.enterMultiSelect('album-1')
    const preparation = state.prepareFavoriteSelection(false)
    await flushPromises()

    expect(requestedPages).toEqual([1, 2])
    state.exitMultiSelect()
    finishPageTwo?.({ ...pageOne, currentPage: 2, content: [secondItem] })
    await preparation
    await flushPromises()

    expect(requestedPages).toEqual([1, 2])
    expect(state.multiSelectActive).toBe(false)
  })
})


describe('收藏下载标记按可见窗口刷新', () => {
  test('首次异步加载及追加页面后查询当前本子 ID', async () => {
    const second = { ...item, id: 'album-2', title: 'Second' }
    mocks.favorites.mockImplementation(async ({ page }: any) => ({ ...onlineResult,
      totalItems: 40, totalPages: 2, currentPage: page, content: page === 2 ? [second] : [item] }))
    mocks.getCompletedDownloadAlbumIds.mockImplementation(async (ids: string[]) => ({ albumIds: ids }))
    wrapper = mount(FavoritePage)
    await flushPromises()
    expect(mocks.getCompletedDownloadAlbumIds).toHaveBeenCalledWith(['album-1'])
    const state = (wrapper.vm as any).$.setupState
    await state.appendPage(2)
    await flushPromises()
    expect(mocks.getCompletedDownloadAlbumIds).toHaveBeenLastCalledWith(['album-1', 'album-2'])
    expect(Array.from(state.downloadedAlbumIds)).toEqual(['album-1', 'album-2'])
  })

  test('旧窗口的标记响应不能覆盖翻页后的结果', async () => {
    let finishOld: ((value: { albumIds: string[] }) => void) | undefined
    const second = { ...item, id: 'album-2', title: 'Second' }
    mocks.favorites.mockImplementation(async ({ page }: any) => ({ ...onlineResult,
      totalItems: 40, totalPages: 2, currentPage: page, content: page === 2 ? [second] : [item] }))
    mocks.getCompletedDownloadAlbumIds.mockImplementationOnce(() => new Promise((resolve) => { finishOld = resolve }))
    wrapper = mount(FavoritePage)
    await flushPromises()
    const state = (wrapper.vm as any).$.setupState
    mocks.getCompletedDownloadAlbumIds.mockResolvedValue({ albumIds: ['album-2'] })
    await state.appendPage(2)
    await flushPromises()
    finishOld?.({ albumIds: ['album-1'] })
    await flushPromises()
    expect(Array.from(state.downloadedAlbumIds)).toEqual(['album-2'])
  })

  test('同一本子的其他章节暂停不移除已完成标记，终态按后端重新核对', async () => {
    let progress: ((event: any) => void) | undefined
    mocks.addDownloadProgressListener.mockImplementation(async (handler: (event: any) => void) => {
      progress = handler
      return { remove: vi.fn() }
    })
    mocks.getCompletedDownloadAlbumIds.mockResolvedValue({ albumIds: ['album-1'] })
    wrapper = mount(FavoritePage)
    await flushPromises()
    const state = (wrapper.vm as any).$.setupState
    progress?.({ albumId: 'album-1', status: 'paused' })
    await flushPromises()
    expect(state.downloadedAlbumIds.has('album-1')).toBe(true)
    progress?.({ albumId: 'album-1', status: 'failed' })
    await flushPromises()
    expect(mocks.getCompletedDownloadAlbumIds).toHaveBeenCalledTimes(2)
    expect(state.downloadedAlbumIds.has('album-1')).toBe(true)
  })

  test('重新激活时即使 ID 未变也会重新核对下载标记', async () => {
    const show = ref(true)
    mocks.getCompletedDownloadAlbumIds.mockResolvedValue({ albumIds: ['album-1'] })
    const host = defineComponent({
      setup: () => () => h(KeepAlive, () => show.value ? h(FavoritePage) : null),
    })
    wrapper = mount(host)
    await flushPromises()
    expect((wrapper.findComponent(FavoritePage).vm as any).$.setupState.downloadedAlbumIds.has('album-1')).toBe(true)
    show.value = false
    await flushPromises()
    mocks.getCompletedDownloadAlbumIds.mockResolvedValue({ albumIds: [] })
    show.value = true
    await flushPromises()
    expect((wrapper.findComponent(FavoritePage).vm as any).$.setupState.downloadedAlbumIds.size).toBe(0)
  })

})
