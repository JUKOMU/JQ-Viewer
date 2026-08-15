/* eslint-disable vue/one-component-per-file -- test-only reader component fixtures */
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, nextTick, type PropType } from 'vue'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import type { ImageInfo, PhotoDetail, PreloadResult } from '@/services/JmcomicTypes'

const mocks = vi.hoisted(() => ({
  setRouteSource: undefined as undefined | ((source?: string) => void),
  router: {
    back: vi.fn(),
    push: vi.fn(),
    replace: vi.fn(),
  },
  getDownloadedPhoto: vi.fn(),
  getPhoto: vi.fn(),
  getAlbum: vi.fn(),
  preloadImages: vi.fn(),
  retryImage: vi.fn(),
  showToast: vi.fn(() => Promise.resolve()),
  addImageReadyListener: vi.fn(),
  addImageFailedListener: vi.fn(),
  addVolumeKeyListener: vi.fn(),
  imageReadyHandler: null as null | ((sortOrder: number) => void),
  imageFailedHandler: null as null | ((sortOrder: number) => void),
  listenerRemove: vi.fn(() => Promise.resolve()),
  getImageUrl: vi.fn(
    (photoId: string, sortOrder: number, type: string) =>
      `jqviewer.local/${photoId}/${type}/${sortOrder}`,
  ),
}))

vi.mock('vue-router', async () => {
  const { reactive } = await import('vue')
  const route = reactive({
    name: 'ReaderPage',
    params: { albumId: 'album-1', chapterId: 'chapter-1' },
    query: {} as Record<string, string>,
  })
  mocks.setRouteSource = (source) => {
    route.query = source ? { source } : {}
  }
  return {
    useRoute: () => route,
    useRouter: () => mocks.router,
  }
})

vi.mock('@ionic/vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    IonPage: defineComponent({
      name: 'IonPage',
      setup(_, { slots }) {
        return () => h('div', slots.default?.())
      },
    }),
  }
})

vi.mock('@/services/JmcomicService', () => ({
  getImageUrl: mocks.getImageUrl,
  showToast: mocks.showToast,
  JmcomicService: {
    getDownloadedPhoto: mocks.getDownloadedPhoto,
    getPhoto: mocks.getPhoto,
    getAlbum: mocks.getAlbum,
    preloadImages: mocks.preloadImages,
    retryImage: mocks.retryImage,
    addImageReadyListener: mocks.addImageReadyListener,
    addImageFailedListener: mocks.addImageFailedListener,
    addVolumeKeyListener: mocks.addVolumeKeyListener,
    setReaderBrightness: vi.fn(() => Promise.resolve()),
    setReaderScreenOrientation: vi.fn(() => Promise.resolve()),
    setReaderKeepScreenOn: vi.fn(() => Promise.resolve()),
    setReaderFullscreen: vi.fn(() => Promise.resolve()),
    setReaderState: vi.fn(() => Promise.resolve()),
  },
}))

vi.mock('@/services/SettingsService', () => ({
  SettingsStore: {
    getReaderPreloadPages: () => 1,
    getReaderDisplayMode: () => 'vertical',
    getReaderScreenOrientation: () => 'auto',
    getReaderBrightness: () => -1,
    getReaderKeepScreenOn: () => false,
    getReaderAutoShowToolbarAtEnd: () => false,
  },
}))

vi.mock('@/services/HistoryService', () => ({
  HistoryService: { recordBrowse: vi.fn() },
}))

vi.mock('@/services/ReadingProgressService', () => ({
  ReadingProgressService: {
    getInitialPage: () => 1,
    record: vi.fn(),
  },
}))

import ReaderPage from '@/views/ReaderPage.vue'

const VerticalScrollViewStub = defineComponent({
  name: 'VerticalScrollView',
  props: {
    imageMap: { type: Object as PropType<Map<number, string>>, required: true },
    failedSortOrders: { type: Object as PropType<Set<number>>, required: true },
    retryingSortOrders: { type: Object as PropType<Set<number>>, required: true },
    totalCount: { type: Number, required: true },
    currentIndex: { type: Number, required: true },
  },
  emits: ['update:current-index', 'request-range', 'reached-bottom', 'image-error', 'retry-images'],
  setup(_, { expose }) {
    expose({
      scrollToIndex: vi.fn(),
      isAtBottom: () => false,
    })
    return () => h('div', { class: 'vertical-reader-stub' })
  },
})

const HorizontalPageViewStub = defineComponent({
  name: 'HorizontalPageView',
  props: {
    imageMap: { type: Object as PropType<Map<number, string>>, required: true },
    failedSortOrders: { type: Object as PropType<Set<number>>, required: true },
    retryingSortOrders: { type: Object as PropType<Set<number>>, required: true },
    totalCount: { type: Number, required: true },
    currentIndex: { type: Number, required: true },
  },
  emits: ['update:current-index', 'toggle-toolbar', 'image-error', 'retry-images'],
  setup(_, { expose }) {
    expose({ scrollToIndex: vi.fn() })
    return () => h('div')
  },
})

const ReaderBottomToolbarStub = defineComponent({
  name: 'ReaderBottomToolbar',
  props: {
    current: { type: Number, required: true },
    total: { type: Number, required: true },
    chapters: { type: Array, required: true },
    currentChapterId: { type: String, required: true },
  },
  emits: [
    'open-settings',
    'select-chapter',
    'update:current',
    'update:current-input',
    'progress-drag-start',
    'progress-drag-end',
  ],
  setup() {
    return () => h('div', { class: 'reader-bottom-toolbar-stub' })
  },
})

const EmptyStub = defineComponent({
  setup() {
    return () => h('div')
  },
})

const makePhoto = (count = 100): PhotoDetail => ({
  id: 'chapter-1',
  title: '章节 1',
  albumId: 'album-1',
  sortOrder: 1,
  author: 'author',
  tags: [],
  images: Array.from({ length: count }, (_, index): ImageInfo => {
    const sortOrder = index + 1
    return {
      photoId: 'chapter-1',
      scrambleId: '0',
      filename: `${sortOrder}.jpg`,
      url: `https://example.com/${sortOrder}.jpg`,
      queryParams: '',
      sortOrder,
    }
  }),
})

const deferred = <T>() => {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

const mountReader = () =>
  mount(ReaderPage, {
    global: {
      stubs: {
        ReaderTopToolbar: EmptyStub,
        ReaderBottomToolbar: ReaderBottomToolbarStub,
        VerticalScrollView: VerticalScrollViewStub,
        HorizontalPageView: HorizontalPageViewStub,
        ReaderSettingsPanel: EmptyStub,
      },
    },
  })

const settle = async () => {
  await flushPromises()
  await nextTick()
}

const currentImageMap = (wrapper: ReturnType<typeof mountReader>) =>
  wrapper.findComponent(VerticalScrollViewStub).props('imageMap') as Map<number, string>

describe('ReaderPage 在线/离线统一图片加载', () => {
  beforeEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
    mocks.setRouteSource?.('download')
    mocks.imageReadyHandler = null
    mocks.imageFailedHandler = null
    mocks.getDownloadedPhoto.mockResolvedValue(makePhoto())
    mocks.getPhoto.mockResolvedValue(makePhoto())
    mocks.getAlbum.mockRejectedValue(new Error('skip history metadata'))
    mocks.preloadImages.mockResolvedValue({ cached: [], pending: [] } satisfies PreloadResult)
    mocks.retryImage.mockResolvedValue({ success: true })
    mocks.addImageReadyListener.mockImplementation(
      (_photoId: string, handler: (sortOrder: number) => void) => {
        mocks.imageReadyHandler = handler
        return Promise.resolve({ remove: mocks.listenerRemove })
      },
    )
    mocks.addImageFailedListener.mockImplementation(
      (_photoId: string, handler: (sortOrder: number) => void) => {
        mocks.imageFailedHandler = handler
        return Promise.resolve({ remove: mocks.listenerRemove })
      },
    )
    mocks.addVolumeKeyListener.mockResolvedValue({ remove: vi.fn(() => Promise.resolve()) })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  test('离线章节等待 imageReady 监听注册后再预加载，并通过 cached 暴露图片', async () => {
    const listener = deferred<{ remove: () => Promise<void> }>()
    mocks.addImageReadyListener.mockImplementationOnce(
      (_photoId: string, handler: (sortOrder: number) => void) => {
        mocks.imageReadyHandler = handler
        return listener.promise
      },
    )
    mocks.preloadImages.mockResolvedValue({ cached: [1], pending: [2] } satisfies PreloadResult)

    const wrapper = mountReader()
    await nextTick()
    await Promise.resolve()
    await Promise.resolve()

    expect(mocks.addImageReadyListener).toHaveBeenCalledWith('chapter-1', expect.any(Function), {
      type: 'image',
    })
    expect(mocks.preloadImages).not.toHaveBeenCalled()
    expect(currentImageMap(wrapper).size).toBe(0)

    listener.resolve({ remove: mocks.listenerRemove })
    await settle()

    expect(mocks.preloadImages).toHaveBeenCalledWith(
      'chapter-1',
      expect.arrayContaining([
        expect.objectContaining({ sortOrder: 1 }),
        expect.objectContaining({ sortOrder: 2 }),
      ]),
      'image',
      { replacePending: false },
    )
    expect(currentImageMap(wrapper).get(1)).toBe('jqviewer.local/chapter-1/image/1')

    wrapper.unmount()
  })

  test('在线回退路径仍通过统一预加载入口显示图片', async () => {
    mocks.setRouteSource?.()
    mocks.getDownloadedPhoto.mockRejectedValue(new Error('not downloaded'))
    mocks.preloadImages.mockResolvedValue({ cached: [1], pending: [] } satisfies PreloadResult)

    const wrapper = mountReader()
    await settle()

    expect(mocks.getPhoto).toHaveBeenCalledWith('chapter-1')
    expect(mocks.preloadImages).toHaveBeenCalledWith('chapter-1', expect.any(Array), 'image', {
      replacePending: false,
    })
    expect(currentImageMap(wrapper).has(1)).toBe(true)

    wrapper.unmount()
  })

  test('预加载调用拒绝后可在同一窗口重新提交', async () => {
    mocks.preloadImages
      .mockRejectedValueOnce(new Error('bridge failure'))
      .mockResolvedValue({ cached: [], pending: [1, 2] } satisfies PreloadResult)

    const wrapper = mountReader()
    await settle()

    expect(mocks.preloadImages).toHaveBeenCalledTimes(1)
    const initialSortOrders = (mocks.preloadImages.mock.calls[0][1] as ImageInfo[]).map(
      (image) => image.sortOrder,
    )

    wrapper.findComponent(VerticalScrollViewStub).vm.$emit('request-range', {
      start: 0,
      end: 2,
      center: 0,
    })
    await settle()

    expect(mocks.preloadImages).toHaveBeenCalledTimes(2)
    expect(mocks.preloadImages.mock.calls[1][3]).toEqual({ replacePending: false })
    expect(
      (mocks.preloadImages.mock.calls[1][1] as ImageInfo[]).map((image) => image.sortOrder),
    ).toEqual(initialSortOrders)

    wrapper.unmount()
  })

  test('旧批次晚到拒绝不会清除新批次请求标记', async () => {
    const initialPreload = deferred<PreloadResult>()
    mocks.preloadImages
      .mockImplementationOnce(() => initialPreload.promise)
      .mockResolvedValue({ cached: [], pending: [1, 2] } satisfies PreloadResult)

    const wrapper = mountReader()
    await settle()
    expect(mocks.preloadImages).toHaveBeenCalledTimes(1)

    wrapper.findComponent(ReaderBottomToolbarStub).vm.$emit('update:current', 1)
    await settle()
    expect(mocks.preloadImages).toHaveBeenCalledTimes(2)
    expect(mocks.preloadImages.mock.calls[1][3]).toEqual({ replacePending: true })

    initialPreload.reject(new Error('stale batch failure'))
    await settle()

    wrapper.findComponent(VerticalScrollViewStub).vm.$emit('request-range', {
      start: 0,
      end: 2,
      center: 0,
    })
    await settle()

    expect(mocks.preloadImages).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  test('监听注册晚到时安全处理 remove 拒绝', async () => {
    const listener = deferred<{ remove: () => Promise<void> }>()
    const remove = vi.fn(() => Promise.reject(new Error('remove failed')))
    mocks.addImageReadyListener.mockImplementationOnce(
      (_photoId: string, handler: (sortOrder: number) => void) => {
        mocks.imageReadyHandler = handler
        return listener.promise
      },
    )

    const wrapper = mountReader()
    await vi.waitFor(() => expect(mocks.addImageReadyListener).toHaveBeenCalledTimes(1))
    wrapper.unmount()

    listener.resolve({ remove })
    await settle()

    expect(remove).toHaveBeenCalledTimes(1)
  })

  test('窗口外晚到结果不写入，返回窗口后可重新探测 native cache', async () => {
    const initialPreload = deferred<PreloadResult>()
    mocks.preloadImages
      .mockImplementationOnce(() => initialPreload.promise)
      .mockResolvedValue({ cached: [], pending: [] } satisfies PreloadResult)

    const wrapper = mountReader()
    await settle()
    expect(mocks.preloadImages).toHaveBeenCalledTimes(1)

    wrapper.findComponent(VerticalScrollViewStub).vm.$emit('update:current-index', 60)
    await settle()
    expect(mocks.preloadImages).toHaveBeenCalledTimes(2)

    initialPreload.resolve({ cached: [1], pending: [2] })
    await settle()
    mocks.imageReadyHandler?.(2)
    await nextTick()
    expect(currentImageMap(wrapper).has(1)).toBe(false)
    expect(currentImageMap(wrapper).has(2)).toBe(false)

    wrapper.findComponent(VerticalScrollViewStub).vm.$emit('update:current-index', 0)
    await settle()

    expect(mocks.preloadImages).toHaveBeenCalledTimes(3)
    const returnImages = mocks.preloadImages.mock.calls[2][1] as ImageInfo[]
    expect(returnImages.map((image) => image.sortOrder)).toEqual([1, 2])

    wrapper.unmount()
  })

  test('拖动预览可绕过 requested 标记，旧目标晚到事件被拒绝', async () => {
    mocks.preloadImages.mockResolvedValue({ cached: [], pending: [1, 2] } satisfies PreloadResult)
    const wrapper = mountReader()
    await settle()
    vi.useFakeTimers()

    const toolbar = wrapper.findComponent(ReaderBottomToolbarStub)
    toolbar.vm.$emit('progress-drag-start')
    toolbar.vm.$emit('update:current-input', 2)
    await nextTick()
    vi.advanceTimersByTime(500)
    await settle()

    expect(mocks.preloadImages).toHaveBeenCalledTimes(2)
    expect(mocks.preloadImages.mock.calls[1][1]).toEqual([
      expect.objectContaining({ sortOrder: 2 }),
    ])
    expect(mocks.preloadImages.mock.calls[1][3]).toEqual({ replacePending: true })

    toolbar.vm.$emit('update:current-input', 50)
    await nextTick()
    vi.advanceTimersByTime(500)
    await settle()
    mocks.imageReadyHandler?.(2)
    await nextTick()

    expect(currentImageMap(wrapper).has(2)).toBe(false)

    wrapper.unmount()
  })

  test('纯在线章节一次重试当前全部失败图片并保留部分失败状态', async () => {
    mocks.setRouteSource?.()
    mocks.getDownloadedPhoto.mockRejectedValue(new Error('not downloaded'))
    const initialPhoto = makePhoto(2)
    const latestPhoto = makePhoto(2)
    latestPhoto.images[0].url = 'https://latest.example.com/1.jpg'
    latestPhoto.images[1].url = 'https://latest.example.com/2.jpg'
    mocks.getPhoto.mockResolvedValueOnce(initialPhoto).mockResolvedValueOnce(latestPhoto)
    mocks.preloadImages.mockResolvedValue({ cached: [1, 2], pending: [] } satisfies PreloadResult)
    mocks.retryImage
      .mockResolvedValueOnce({ success: true })
      .mockRejectedValueOnce(new Error('retry failed'))

    const wrapper = mountReader()
    await settle()
    const view = wrapper.findComponent(VerticalScrollViewStub)
    const firstUrl = currentImageMap(wrapper).get(1)!
    const secondUrl = currentImageMap(wrapper).get(2)!

    view.vm.$emit('image-error', 1, firstUrl)
    view.vm.$emit('image-error', 2, secondUrl)
    await nextTick()
    expect(view.props('failedSortOrders')).toEqual(new Set([1, 2]))

    view.vm.$emit('retry-images')
    await settle()

    expect(mocks.getPhoto).toHaveBeenCalledTimes(2)
    expect(mocks.getPhoto).toHaveBeenLastCalledWith('chapter-1')
    expect(mocks.retryImage.mock.calls).toEqual([
      ['chapter-1', expect.objectContaining({ sortOrder: 1, url: latestPhoto.images[0].url })],
      ['chapter-1', expect.objectContaining({ sortOrder: 2, url: latestPhoto.images[1].url })],
    ])
    expect(currentImageMap(wrapper).get(1)).toContain('?retry=')
    expect(currentImageMap(wrapper).has(2)).toBe(false)
    expect(view.props('failedSortOrders')).toEqual(new Set([2]))
    expect(mocks.showToast).toHaveBeenCalledWith('1 张图片重试失败', 'danger')

    wrapper.unmount()
  })

  test('普通预载网络失败进入同一失败集合并可直接批量重试', async () => {
    mocks.preloadImages.mockResolvedValue({ cached: [], pending: [1, 2] } satisfies PreloadResult)
    const wrapper = mountReader()
    await settle()

    mocks.imageFailedHandler?.(1)
    mocks.imageFailedHandler?.(2)
    await nextTick()

    const view = wrapper.findComponent(VerticalScrollViewStub)
    expect(view.props('failedSortOrders')).toEqual(new Set([1, 2]))

    view.vm.$emit('retry-images')
    await settle()

    expect(mocks.getPhoto).toHaveBeenCalledWith('chapter-1')
    expect(mocks.retryImage.mock.calls).toEqual([
      ['chapter-1', expect.objectContaining({ sortOrder: 1 })],
      ['chapter-1', expect.objectContaining({ sortOrder: 2 })],
    ])
    expect(view.props('failedSortOrders')).toEqual(new Set())
    wrapper.unmount()
  })

  test('旧图片 URL 晚到的错误事件不会覆盖当前图片', async () => {
    mocks.preloadImages.mockResolvedValue({ cached: [1], pending: [] } satisfies PreloadResult)
    const wrapper = mountReader()
    await settle()

    const view = wrapper.findComponent(VerticalScrollViewStub)
    view.vm.$emit('image-error', 1, 'jqviewer.local/chapter-1/image/stale')
    await nextTick()

    expect(currentImageMap(wrapper).has(1)).toBe(true)
    expect(view.props('failedSortOrders')).toEqual(new Set())
    wrapper.unmount()
  })
})
