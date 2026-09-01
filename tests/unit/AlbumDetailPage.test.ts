/* eslint-disable vue/one-component-per-file -- test-only Ionic and keep-alive fixtures */
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { KeepAlive, defineComponent, h, nextTick, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import type {
  AlbumDetail,
  CommentItem,
  DownloadTask,
  ImportedPdf,
  PhotoDetail,
  PreloadResult,
} from '@/services/JmcomicTypes'

const mocks = vi.hoisted(() => ({
  route: {
    params: { id: '123' },
    query: {},
  },
  setRouteId: undefined as undefined | ((id: string | undefined) => void),
  setRouteChapterId: undefined as undefined | ((id: string | undefined) => void),
  router: {
    back: vi.fn(),
    push: vi.fn(),
  },
  getAlbum: vi.fn(() => new Promise(() => {})),
  getPhoto: vi.fn(() => new Promise(() => {})),
  getComments: vi.fn(),
  downloadChapter: vi.fn(),
  getDownloadedPhoto: vi.fn(),
  getDownloadTasks: vi.fn(),
  getImportedPdfs: vi.fn(),
  addDownloadProgressListener: vi.fn(),
  addImageReadyListener: vi.fn(),
  preloadImages: vi.fn(),
  listenerRemove: vi.fn(),
  imageReadyHandler: undefined as undefined | ((sortOrder: number) => void),
  getImageUrl: vi.fn(),
  showToast: vi.fn(),
  fetchPdfArrayBuffer: vi.fn(),
  buildPdfDocumentParams: vi.fn(),
  pdfGetDocument: vi.fn(),
  menuSwipeGesture: vi.fn(() => Promise.resolve()),
}))

vi.mock('vue-router', async () => {
  const { reactive } = await import('vue')
  const route = reactive<{ params: { id?: string }; query: Record<string, string> }>(mocks.route)
  mocks.setRouteId = (id) => {
    route.params.id = id
  }
  mocks.setRouteChapterId = (id) => {
    if (id) route.query.chapterId = id
    else delete route.query.chapterId
  }
  return {
    useRoute: () => route,
    useRouter: () => mocks.router,
  }
})

vi.mock('@ionic/vue', async () => {
  const { defineComponent, h, onMounted, ref } = await import('vue')
  return {
    IonContent: defineComponent({
      name: 'IonContent',
      setup(_, { slots }) {
        const elementRef = ref<HTMLElement | null>(null)
        onMounted(() => {
          const element = elementRef.value as HTMLElement & {
            getScrollElement: () => Promise<HTMLElement | null>
          }
          element.getScrollElement = async () => element
        })
        return () => h('div', { ref: elementRef, class: 'ion-content-stub' }, slots.default?.())
      },
    }),
    IonPage: defineComponent({
      name: 'IonPage',
      setup(_, { slots }) {
        return () => h('div', slots.default?.())
      },
    }),
    createGesture: vi.fn(() => ({
      destroy: vi.fn(),
      enable: vi.fn(),
    })),
    menuController: {
      close: vi.fn(() => Promise.resolve()),
      open: vi.fn(() => Promise.resolve()),
      swipeGesture: mocks.menuSwipeGesture,
    },
  }
})

vi.mock('pdfjs-dist', () => ({
  GlobalWorkerOptions: { workerSrc: '' },
  getDocument: mocks.pdfGetDocument,
}))

vi.mock('@/services/JmcomicService', () => ({
  getImageUrl: mocks.getImageUrl,
  sanitizeError: vi.fn((error: unknown, fallback: string) => String(error || fallback)),
  showToast: mocks.showToast,
  JmcomicService: {
    getAlbum: mocks.getAlbum,
    getPhoto: mocks.getPhoto,
    getComments: mocks.getComments,
    downloadChapter: mocks.downloadChapter,
    getDownloadedPhoto: mocks.getDownloadedPhoto,
    getDownloadTasks: mocks.getDownloadTasks,
    getImportedPdfs: mocks.getImportedPdfs,
    addDownloadProgressListener: mocks.addDownloadProgressListener,
    addImageReadyListener: mocks.addImageReadyListener,
    preloadImages: mocks.preloadImages,
  },
}))

vi.mock('@/services/PdfReaderService', () => ({
  buildPdfDocumentParams: mocks.buildPdfDocumentParams,
  fetchPdfArrayBuffer: mocks.fetchPdfArrayBuffer,
}))

vi.mock('@/services/OfflineDownloadService', () => ({
  OfflineDownloadService: {
    getAll: vi.fn(() => []),
    addTask: vi.fn(),
  },
}))

vi.mock('@/services/OfflineFavoriteService', () => ({
  OfflineFavoriteService: {},
}))

vi.mock('@/services/HistoryService', () => ({
  HistoryService: { recordBrowse: vi.fn() },
}))

vi.mock('@/composables/useAuth', async () => {
  const { ref } = await import('vue')
  return { useAuth: () => ({ isLoggedIn: ref(false) }) }
})

vi.mock('@/components/album/AlbumHeader.vue', () => ({
  default: {
    name: 'AlbumHeader',
    props: {
      pageCount: { type: Number, default: 0 },
      loading: { type: Boolean, default: false },
    },
    render: () => null,
  },
}))
vi.mock('@/components/album/AlbumInfoTab.vue', () => ({
  default: { name: 'AlbumInfoTab', render: () => null },
}))
vi.mock('@/components/album/AlbumChaptersTab.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'AlbumChaptersTab',
      inheritAttrs: false,
      props: {
        selectedChapterId: { type: String, default: '' },
      },
      emits: ['select-chapter', 'batch-download'],
      setup(props) {
        return () =>
          h('div', {
            class: 'album-chapters-tab-stub',
            'data-selected-chapter-id': props.selectedChapterId,
          })
      },
    }),
  }
})
vi.mock('@/components/album/AlbumPreviewTab.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'AlbumPreviewTab',
      inheritAttrs: false,
      props: {
        loadedCount: { type: Number, required: true },
      },
      setup(props) {
        return () =>
          h('div', {
            class: 'album-preview-tab-stub',
            'data-loaded-count': String(props.loadedCount),
          })
      },
    }),
  }
})
vi.mock('@/components/album/AlbumCommentsTab.vue', () => ({
  default: {
    name: 'AlbumCommentsTab',
    props: {
      comments: { type: Array, default: () => [] },
      loading: { type: Boolean, default: false },
    },
    render: () => null,
  },
}))
vi.mock('@/components/favorite/FavoriteFolderPicker.vue', () => ({
  default: { name: 'FavoriteFolderPicker', render: () => null },
}))

import AlbumDetailPage from '@/views/AlbumDetailPage.vue'

const makePhoto = (): PhotoDetail => ({
  id: 'chapter-1',
  title: '第一章',
  albumId: '123',
  sortOrder: 1,
  author: '作者',
  tags: [],
  images: [
    {
      photoId: 'chapter-1',
      scrambleId: 'scramble-1',
      filename: '1.webp',
      url: 'https://example.test/1.webp',
      queryParams: '',
      sortOrder: 1,
    },
    {
      photoId: 'chapter-1',
      scrambleId: 'scramble-1',
      filename: '2.webp',
      url: 'https://example.test/2.webp',
      queryParams: '',
      sortOrder: 2,
    },
  ],
})

const makeSecondPhoto = (): PhotoDetail => ({
  ...makePhoto(),
  id: 'chapter-2',
  title: '第二章',
  sortOrder: 2,
  images: makePhoto().images.map((image) => ({ ...image, photoId: 'chapter-2' })),
})

const makeLongSecondPhoto = (): PhotoDetail => {
  const photo = makeSecondPhoto()
  return {
    ...photo,
    images: [...photo.images, { ...photo.images[0], sortOrder: 3, filename: '3.webp' }],
  }
}

const makeAlbum = (): AlbumDetail => ({
  id: '123',
  title: '测试本子',
  description: '',
  addTime: '',
  pageCount: 2,
  likes: '0',
  views: '0',
  commentCount: 0,
  image: '',
  category: null,
  subCategory: null,
  authors: ['作者'],
  works: [],
  actors: [],
  tags: [],
  relatedAlbums: [],
  photoMetas: [{ id: 'chapter-1', title: '第一章', sortOrder: 1 }],
  seriesId: '1',
  isFavorite: false,
  isLiked: false,
  price: '',
  purchased: '',
})

const makeMultiChapterAlbum = (): AlbumDetail => ({
  ...makeAlbum(),
  photoMetas: [
    { id: 'chapter-1', title: '第一章', sortOrder: 1 },
    { id: 'chapter-2', title: '第二章', sortOrder: 2 },
  ],
})

const makeComment = (commentId: string): CommentItem => ({
  commentId,
  userId: 'user-1',
  username: '用户',
  nickname: '用户',
  content: commentId,
  postDate: '',
  photo: '',
  expinfo: '',
  aid: '123',
  name: '测试本子',
  likes: 0,
  voteUp: 0,
  voteDown: 0,
  replys: [],
})

const makeDownloadTask = (): DownloadTask => ({
  taskId: '123_chapter-1',
  albumId: '123',
  chapterId: 'chapter-1',
  albumTitle: '测试本子',
  chapterTitle: '第一章',
  coverUrl: '',
  totalPages: 2,
  downloadedPages: 2,
  status: 'completed',
  createdAt: 1,
})

const makeImportedPdf = (): ImportedPdf => ({
  id: 1,
  filePath: '/imports/chapter-1.pdf',
  fileName: 'chapter-1.pdf',
  albumId: '123',
  albumTitle: '测试本子',
  coverUrl: '',
  authors: '作者',
  chapterId: 'chapter-1',
  chapterTitle: '第一章',
  chapterSortOrder: 1,
  createdAt: 1,
})

class ResizeObserverStub {
  observe() {}
  disconnect() {}
}

const settle = async () => {
  await flushPromises()
  await nextTick()
  await flushPromises()
  await nextTick()
}

const deferred = <T>() => {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

const clickTab = async (wrapper: VueWrapper, label: string) => {
  const button = wrapper.findAll('.tab-btn').find((item) => item.text().startsWith(label))
  if (!button) throw new Error(`找不到 tab: ${label}`)
  await button.trigger('click')
  await settle()
}

const mountLoadedPage = async ({ downloaded = false, pdf = false, album = makeAlbum() } = {}) => {
  mocks.getAlbum.mockResolvedValue(album)
  mocks.getPhoto.mockResolvedValue(makePhoto())
  mocks.getDownloadTasks.mockResolvedValue({
    tasks: downloaded ? [makeDownloadTask()] : [],
    usedBytes: 0,
    availableBytes: 0,
  })
  mocks.getImportedPdfs.mockResolvedValue({ pdfs: pdf ? [makeImportedPdf()] : [] })

  const wrapper = mount(AlbumDetailPage)
  await settle()

  mocks.getPhoto.mockClear()
  mocks.downloadChapter.mockClear()
  mocks.getDownloadedPhoto.mockClear()
  mocks.addImageReadyListener.mockClear()
  mocks.preloadImages.mockClear()
  mocks.fetchPdfArrayBuffer.mockClear()
  mocks.showToast.mockClear()
  return wrapper
}

const openPreview = async (wrapper: VueWrapper) => {
  await clickTab(wrapper, '预览')
  await vi.runAllTimersAsync()
  await settle()
}

beforeEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
  vi.stubGlobal('ResizeObserver', ResizeObserverStub)
  mocks.setRouteId?.('123')
  mocks.setRouteChapterId?.(undefined)
  mocks.getAlbum.mockImplementation(() => new Promise(() => {}))
  mocks.getPhoto.mockImplementation(() => new Promise(() => {}))
  mocks.getComments.mockResolvedValue({ total: 0, list: [] })
  mocks.downloadChapter.mockResolvedValue({ taskId: '123_chapter-1' })
  mocks.getDownloadedPhoto.mockResolvedValue(makePhoto())
  mocks.getDownloadTasks.mockResolvedValue({ tasks: [], usedBytes: 0, availableBytes: 0 })
  mocks.getImportedPdfs.mockResolvedValue({ pdfs: [] })
  mocks.listenerRemove.mockResolvedValue(undefined)
  mocks.addDownloadProgressListener.mockResolvedValue({ remove: mocks.listenerRemove })
  mocks.addImageReadyListener.mockImplementation(
    (_photoId: string, handler: (sortOrder: number) => void) => {
      mocks.imageReadyHandler = handler
      return Promise.resolve({ remove: mocks.listenerRemove })
    },
  )
  mocks.preloadImages.mockResolvedValue({ cached: [1], pending: [2] } satisfies PreloadResult)
  mocks.getImageUrl.mockImplementation(
    (photoId: string, sortOrder: number, type: string) =>
      `https://jqviewer.local/${type}/${photoId}/${sortOrder}`,
  )
  mocks.showToast.mockResolvedValue(undefined)
  mocks.fetchPdfArrayBuffer.mockResolvedValue(new ArrayBuffer(0))
  mocks.buildPdfDocumentParams.mockReturnValue({ data: new Uint8Array() })
  mocks.pdfGetDocument.mockReturnValue({
    promise: Promise.resolve({
      numPages: 0,
      destroy: vi.fn(() => Promise.resolve()),
    }),
  })
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('AlbumDetailPage 章节路由定位', () => {
  test('初始进入时选中 chapterId 对应的章节', async () => {
    mocks.setRouteChapterId?.('chapter-2')
    mocks.getAlbum.mockResolvedValue(makeMultiChapterAlbum())
    mocks.getPhoto.mockResolvedValue(makeSecondPhoto())

    const wrapper = mount(AlbumDetailPage)
    await settle()

    expect(mocks.getPhoto).toHaveBeenCalledWith('chapter-2')
    expect(wrapper.findComponent({ name: 'AlbumChaptersTab' }).props('selectedChapterId')).toBe(
      'chapter-2',
    )
    wrapper.unmount()
  })

  test('同一详情页变更 chapterId 时同步切换章节', async () => {
    mocks.getAlbum.mockResolvedValue(makeMultiChapterAlbum())
    mocks.getPhoto.mockImplementation((chapterId: string) =>
      Promise.resolve(chapterId === 'chapter-2' ? makeSecondPhoto() : makePhoto()),
    )

    const wrapper = mount(AlbumDetailPage)
    await settle()
    mocks.getPhoto.mockClear()

    mocks.setRouteChapterId?.('chapter-2')
    await settle()

    expect(mocks.getPhoto).toHaveBeenCalledWith('chapter-2')
    expect(wrapper.findComponent({ name: 'AlbumChaptersTab' }).props('selectedChapterId')).toBe(
      'chapter-2',
    )
    wrapper.unmount()
  })

  test('无效 chapterId 回退到第一章', async () => {
    mocks.setRouteChapterId?.('missing-chapter')
    mocks.getAlbum.mockResolvedValue(makeMultiChapterAlbum())
    mocks.getPhoto.mockResolvedValue(makePhoto())

    const wrapper = mount(AlbumDetailPage)
    await settle()

    expect(mocks.getPhoto).toHaveBeenCalledWith('chapter-1')
    expect(mocks.getPhoto).not.toHaveBeenCalledWith('missing-chapter')
    expect(wrapper.findComponent({ name: 'AlbumChaptersTab' }).props('selectedChapterId')).toBe(
      'chapter-1',
    )
    wrapper.unmount()
  })

  test('初始加载期间切换 chapterId 时忽略旧请求结果', async () => {
    const firstAlbum = deferred<AlbumDetail>()
    const firstPhoto = deferred<PhotoDetail>()
    const latestAlbum = deferred<AlbumDetail>()
    const latestPhoto = deferred<PhotoDetail>()
    mocks.getAlbum.mockReturnValueOnce(firstAlbum.promise).mockReturnValueOnce(latestAlbum.promise)
    mocks.getPhoto.mockReturnValueOnce(firstPhoto.promise).mockReturnValueOnce(latestPhoto.promise)

    const wrapper = mount(AlbumDetailPage)
    await nextTick()

    mocks.setRouteChapterId?.('chapter-2')
    await nextTick()
    latestAlbum.resolve(makeMultiChapterAlbum())
    await settle()
    latestPhoto.resolve(makeSecondPhoto())
    await settle()

    expect(wrapper.findComponent({ name: 'AlbumChaptersTab' }).props('selectedChapterId')).toBe(
      'chapter-2',
    )

    firstAlbum.resolve(makeAlbum())
    firstPhoto.resolve(makePhoto())
    await settle()

    expect(wrapper.findComponent({ name: 'AlbumChaptersTab' }).props('selectedChapterId')).toBe(
      'chapter-2',
    )
    wrapper.unmount()
  })

  test('快速切换章节时旧章节图片不会覆盖当前章节', async () => {
    const chapterTwo = deferred<PhotoDetail>()
    const chapterOne = deferred<PhotoDetail>()
    mocks.getAlbum.mockResolvedValue(makeMultiChapterAlbum())
    mocks.getPhoto.mockImplementation((id: string) => {
      if (id === 'chapter-2') return chapterTwo.promise
      if (id === 'chapter-1') return chapterOne.promise
      return Promise.resolve(makePhoto())
    })

    const wrapper = mount(AlbumDetailPage)
    await settle()

    mocks.setRouteChapterId?.('chapter-2')
    await nextTick()
    mocks.setRouteChapterId?.('chapter-1')
    await nextTick()

    chapterOne.resolve(makePhoto())
    await settle()
    chapterTwo.resolve(makeLongSecondPhoto())
    await settle()

    expect(wrapper.findComponent({ name: 'AlbumChaptersTab' }).props('selectedChapterId')).toBe(
      'chapter-1',
    )
    expect(wrapper.findComponent({ name: 'AlbumHeader' }).props('pageCount')).toBe(2)
    wrapper.unmount()
  })

  test('初始章节图片请求期间选择其他章节时忽略旧响应', async () => {
    const initialPhoto = deferred<PhotoDetail>()
    const selectedPhoto = deferred<PhotoDetail>()
    mocks.setRouteChapterId?.('chapter-1')
    mocks.getAlbum.mockResolvedValue(makeMultiChapterAlbum())
    mocks.getPhoto.mockImplementation((chapterId: string) =>
      chapterId === 'chapter-1' ? initialPhoto.promise : selectedPhoto.promise,
    )

    const wrapper = mount(AlbumDetailPage)
    await settle()
    await clickTab(wrapper, '章节')

    wrapper.findComponent({ name: 'AlbumChaptersTab' }).vm.$emit('select-chapter', 'chapter-2')
    await nextTick()

    selectedPhoto.resolve(makeLongSecondPhoto())
    await settle()
    expect(wrapper.findComponent({ name: 'AlbumHeader' }).props('pageCount')).toBe(3)

    initialPhoto.resolve(makePhoto())
    await settle()

    expect(wrapper.findComponent({ name: 'AlbumChaptersTab' }).props('selectedChapterId')).toBe(
      'chapter-2',
    )
    expect(wrapper.findComponent({ name: 'AlbumHeader' }).props('pageCount')).toBe(3)
    wrapper.unmount()
  })
})

describe('AlbumDetailPage 阅读入口', () => {
  test('navigator.onLine 为 false 时主按钮和网络来源仍进入阅读路由', async () => {
    const onLineSpy = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    const wrapper = await mountLoadedPage()

    try {
      const header = wrapper.findComponent({ name: 'AlbumHeader' })
      header.vm.$emit('start-reading')
      header.vm.$emit('select-source', 'network')
      await nextTick()

      const expectedRoute = {
        path: '/album/123/read/chapter-1',
        query: { title: '测试本子', total: '2' },
      }
      expect(mocks.router.push).toHaveBeenNthCalledWith(1, expectedRoute)
      expect(mocks.router.push).toHaveBeenNthCalledWith(2, expectedRoute)
      expect(mocks.showToast).not.toHaveBeenCalledWith('当前网络不可用', 'medium')
    } finally {
      wrapper.unmount()
      onLineSpy.mockRestore()
    }
  })
})

describe('AlbumDetailPage 批量下载', () => {
  test('离开详情页后继续使用提交时的专辑上下文', async () => {
    const firstSubmit = deferred<{ taskId: string }>()
    mocks.downloadChapter
      .mockReturnValueOnce(firstSubmit.promise)
      .mockResolvedValueOnce({ taskId: '123_chapter-2' })
    const wrapper = await mountLoadedPage({ album: makeMultiChapterAlbum() })

    wrapper
      .findComponent({ name: 'AlbumChaptersTab' })
      .vm.$emit('batch-download', ['chapter-1', 'chapter-2'])
    await nextTick()

    expect(mocks.downloadChapter).toHaveBeenNthCalledWith(
      1,
      '123',
      'chapter-1',
      '测试本子',
      '第一章',
      '',
    )

    mocks.setRouteId?.(undefined)
    await nextTick()
    firstSubmit.resolve({ taskId: '123_chapter-1' })
    await settle()

    expect(mocks.downloadChapter).toHaveBeenNthCalledWith(
      2,
      '123',
      'chapter-2',
      '测试本子',
      '第二章',
      '',
    )
    expect(mocks.showToast).toHaveBeenCalledWith('已加入 2 个下载任务', 'success')

    wrapper.unmount()
  })
})

describe('AlbumDetailPage tab 状态', () => {
  beforeEach(() => {
    mocks.menuSwipeGesture.mockClear()
  })

  test('每个 tab 切换后恢复各自的滚动位置', async () => {
    const wrapper = mount(AlbumDetailPage)
    await settle()

    const scrollElement = wrapper.get('.ion-content-stub').element as HTMLElement
    scrollElement.scrollTop = 420

    await clickTab(wrapper, '章节')
    expect(scrollElement.scrollTop).toBe(0)
    scrollElement.scrollTop = 180

    await clickTab(wrapper, '预览')
    expect(scrollElement.scrollTop).toBe(0)
    scrollElement.scrollTop = 960

    await clickTab(wrapper, '章节')
    expect(scrollElement.scrollTop).toBe(180)

    await clickTab(wrapper, '预览')
    expect(scrollElement.scrollTop).toBe(960)

    await clickTab(wrapper, '本子信息')
    expect(scrollElement.scrollTop).toBe(420)

    wrapper.unmount()
  })

  test('缓存恢复后保留当前 tab 和滚动位置', async () => {
    const showDetail = ref(true)
    const Host = defineComponent({
      setup() {
        return () =>
          h(KeepAlive, null, [
            showDetail.value
              ? h(AlbumDetailPage, { key: 'detail' })
              : h('div', { class: 'reader-stub' }),
          ])
      },
    })
    const wrapper = mount(Host)
    await settle()

    const detailWrapper = wrapper.findComponent(AlbumDetailPage)
    const scrollElement = detailWrapper.get('.ion-content-stub').element as HTMLElement
    await clickTab(detailWrapper, '预览')
    scrollElement.scrollTop = 720

    showDetail.value = false
    mocks.setRouteId?.(undefined)
    await settle()
    scrollElement.scrollTop = 0

    mocks.setRouteId?.('123')
    showDetail.value = true
    await settle()

    expect(wrapper.findComponent(AlbumDetailPage).get('.tab-btn.active').text()).toBe('预览')
    expect(scrollElement.scrollTop).toBe(720)

    wrapper.unmount()
  })

  test('详情加载未完成时返回页面会强制重新加载', async () => {
    const firstAlbum = deferred<AlbumDetail>()
    const firstPhoto = deferred<PhotoDetail>()
    const reloadedAlbum = { ...makeAlbum(), title: '重载本子' }
    const reloadedPhoto = {
      ...makePhoto(),
      title: '重载章节',
      images: [
        ...makePhoto().images,
        { ...makePhoto().images[0], sortOrder: 3, filename: '3.webp' },
      ],
    }
    mocks.getAlbum.mockReturnValueOnce(firstAlbum.promise).mockResolvedValueOnce(reloadedAlbum)
    mocks.getPhoto.mockReturnValueOnce(firstPhoto.promise).mockResolvedValueOnce(reloadedPhoto)
    mocks.getDownloadTasks.mockResolvedValue({ tasks: [], usedBytes: 0, availableBytes: 0 })
    mocks.getImportedPdfs.mockResolvedValue({ pdfs: [] })

    const showDetail = ref(true)
    const Host = defineComponent({
      setup() {
        return () =>
          h(KeepAlive, null, [
            showDetail.value
              ? h(AlbumDetailPage, { key: 'detail' })
              : h('div', { class: 'reader-stub' }),
          ])
      },
    })
    const wrapper = mount(Host)
    await nextTick()
    expect(wrapper.findComponent({ name: 'AlbumHeader' }).props('loading')).toBe(true)

    showDetail.value = false
    mocks.setRouteId?.(undefined)
    await settle()

    mocks.setRouteId?.('123')
    showDetail.value = true
    await settle()

    expect(mocks.getAlbum).toHaveBeenCalledTimes(2)
    expect(mocks.getPhoto).toHaveBeenCalledTimes(2)
    expect(wrapper.findComponent({ name: 'AlbumHeader' }).props('loading')).toBe(false)

    firstAlbum.resolve(makeAlbum())
    firstPhoto.resolve(makePhoto())
    await settle()
    expect(wrapper.findComponent({ name: 'AlbumHeader' }).props('pageCount')).toBe(
      reloadedPhoto.images.length,
    )
    wrapper.unmount()
  })

  test('强制重载时丢弃旧评论请求并重新加载当前评论 tab', async () => {
    const firstAlbum = deferred<AlbumDetail>()
    const firstPhoto = deferred<PhotoDetail>()
    const firstComments = deferred<{ total: number; list: CommentItem[] }>()
    const reloadedComments = deferred<{ total: number; list: CommentItem[] }>()
    mocks.getAlbum.mockReturnValueOnce(firstAlbum.promise).mockResolvedValueOnce(makeAlbum())
    mocks.getPhoto.mockReturnValueOnce(firstPhoto.promise).mockResolvedValueOnce(makePhoto())
    mocks.getComments
      .mockReturnValueOnce(firstComments.promise)
      .mockReturnValueOnce(reloadedComments.promise)

    const showDetail = ref(true)
    const Host = defineComponent({
      setup() {
        return () =>
          h(KeepAlive, null, [
            showDetail.value
              ? h(AlbumDetailPage, { key: 'detail' })
              : h('div', { class: 'reader-stub' }),
          ])
      },
    })
    const wrapper = mount(Host)
    await nextTick()
    await clickTab(wrapper.findComponent(AlbumDetailPage), '评论')

    showDetail.value = false
    mocks.setRouteId?.(undefined)
    await settle()
    mocks.setRouteId?.('123')
    showDetail.value = true
    await settle()

    reloadedComments.resolve({ total: 1, list: [makeComment('new')] })
    await settle()
    firstComments.resolve({ total: 1, list: [makeComment('old')] })
    firstAlbum.resolve(makeAlbum())
    firstPhoto.resolve(makePhoto())
    await settle()

    expect(mocks.getComments).toHaveBeenCalledTimes(2)
    expect(wrapper.findComponent({ name: 'AlbumCommentsTab' }).props('comments')).toEqual([
      makeComment('new'),
    ])
    wrapper.unmount()
  })
})

describe('AlbumDetailPage 预览来源', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  test.each([
    { pdf: true, label: '已下载且存在 PDF' },
    { pdf: false, label: '仅已下载' },
  ])('$label 时优先进入下载分支', async ({ pdf }) => {
    const wrapper = await mountLoadedPage({ downloaded: true, pdf })

    await openPreview(wrapper)

    expect(mocks.getPhoto).toHaveBeenCalledWith('chapter-1')
    expect(mocks.getDownloadedPhoto).not.toHaveBeenCalled()
    expect(mocks.fetchPdfArrayBuffer).not.toHaveBeenCalled()
    expect(mocks.addImageReadyListener).toHaveBeenCalledWith('chapter-1', expect.any(Function), {
      type: 'thumb',
    })
    expect(mocks.preloadImages).toHaveBeenCalledWith('chapter-1', makePhoto().images, 'thumb')

    const preview = wrapper.findComponent({ name: 'AlbumPreviewTab' })
    expect(preview.props('loadedCount')).toBe(1)
    mocks.imageReadyHandler?.(2)
    await settle()
    expect(preview.props('loadedCount')).toBe(2)

    wrapper.unmount()
  })

  test('仅存在 PDF 时只进入 PDF 分支', async () => {
    const wrapper = await mountLoadedPage({ pdf: true })

    await openPreview(wrapper)

    expect(mocks.fetchPdfArrayBuffer).toHaveBeenCalledWith('/imports/chapter-1.pdf')
    expect(mocks.getPhoto).not.toHaveBeenCalled()
    expect(mocks.getDownloadedPhoto).not.toHaveBeenCalled()
    expect(mocks.addImageReadyListener).not.toHaveBeenCalled()
    expect(mocks.preloadImages).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  test('没有任何本地来源时只进入网络分支', async () => {
    const wrapper = await mountLoadedPage()

    await openPreview(wrapper)

    expect(mocks.getPhoto).toHaveBeenCalledWith('chapter-1')
    expect(mocks.getDownloadedPhoto).not.toHaveBeenCalled()
    expect(mocks.fetchPdfArrayBuffer).not.toHaveBeenCalled()
    expect(mocks.preloadImages).toHaveBeenCalledWith('chapter-1', makePhoto().images, 'thumb')

    wrapper.unmount()
  })

  test('下载分支在线元数据失败后只回退下载元数据', async () => {
    const wrapper = await mountLoadedPage({ downloaded: true, pdf: true })
    mocks.getPhoto.mockRejectedValueOnce(new Error('online metadata failed'))

    await openPreview(wrapper)

    expect(mocks.getDownloadedPhoto).toHaveBeenCalledWith('123', 'chapter-1')
    expect(mocks.fetchPdfArrayBuffer).not.toHaveBeenCalled()
    expect(mocks.preloadImages).toHaveBeenCalledWith('chapter-1', makePhoto().images, 'thumb')

    wrapper.unmount()
  })

  test('下载分支完全失败后不跨到 PDF 分支', async () => {
    const wrapper = await mountLoadedPage({ downloaded: true, pdf: true })
    mocks.getPhoto.mockRejectedValueOnce(new Error('online metadata failed'))
    mocks.getDownloadedPhoto.mockRejectedValueOnce(new Error('download metadata failed'))

    await openPreview(wrapper)

    expect(mocks.fetchPdfArrayBuffer).not.toHaveBeenCalled()
    expect(mocks.preloadImages).not.toHaveBeenCalled()
    expect(mocks.showToast).toHaveBeenCalled()

    wrapper.unmount()
  })

  test('PDF 分支失败后不跨到图片分支', async () => {
    const wrapper = await mountLoadedPage({ pdf: true })
    mocks.fetchPdfArrayBuffer.mockRejectedValueOnce(new Error('pdf failed'))

    await openPreview(wrapper)

    expect(mocks.getPhoto).not.toHaveBeenCalled()
    expect(mocks.getDownloadedPhoto).not.toHaveBeenCalled()
    expect(mocks.preloadImages).not.toHaveBeenCalled()
    expect(mocks.showToast).toHaveBeenCalled()

    wrapper.unmount()
  })

  test('网络分支失败后不查询下载或 PDF 来源', async () => {
    const wrapper = await mountLoadedPage()
    mocks.getPhoto.mockRejectedValueOnce(new Error('network failed'))

    await openPreview(wrapper)

    expect(mocks.getDownloadedPhoto).not.toHaveBeenCalled()
    expect(mocks.fetchPdfArrayBuffer).not.toHaveBeenCalled()
    expect(mocks.preloadImages).not.toHaveBeenCalled()
    expect(mocks.showToast).toHaveBeenCalled()

    wrapper.unmount()
  })

  test('来源失效后的旧 imageReady 事件不能写回预览槽位', async () => {
    const wrapper = await mountLoadedPage({ downloaded: true })
    await openPreview(wrapper)
    const staleImageReadyHandler = mocks.imageReadyHandler
    expect(staleImageReadyHandler).toBeTypeOf('function')

    mocks.setRouteId?.('456')
    await settle()
    staleImageReadyHandler?.(2)
    await settle()

    const preview = wrapper.findComponent({ name: 'AlbumPreviewTab' })
    expect(preview.props('loadedCount')).toBe(0)

    wrapper.unmount()
  })
})
