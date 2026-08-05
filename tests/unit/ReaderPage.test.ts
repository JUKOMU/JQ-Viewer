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
  addImageReadyListener: vi.fn(),
  addVolumeKeyListener: vi.fn(),
  imageReadyHandler: null as null | ((sortOrder: number) => void),
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
  showToast: vi.fn(() => Promise.resolve()),
  JmcomicService: {
    getDownloadedPhoto: mocks.getDownloadedPhoto,
    getPhoto: mocks.getPhoto,
    getAlbum: mocks.getAlbum,
    preloadImages: mocks.preloadImages,
    addImageReadyListener: mocks.addImageReadyListener,
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
    totalCount: { type: Number, required: true },
    currentIndex: { type: Number, required: true },
  },
  emits: ['update:current-index', 'request-range', 'reached-bottom'],
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
    totalCount: { type: Number, required: true },
    currentIndex: { type: Number, required: true },
  },
  emits: ['update:current-index', 'toggle-toolbar'],
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
    mocks.getDownloadedPhoto.mockResolvedValue(makePhoto())
    mocks.getPhoto.mockResolvedValue(makePhoto())
    mocks.getAlbum.mockRejectedValue(new Error('skip history metadata'))
    mocks.preloadImages.mockResolvedValue({ cached: [], pending: [] } satisfies PreloadResult)
    mocks.addImageReadyListener.mockImplementation(
      (_photoId: string, handler: (sortOrder: number) => void) => {
        mocks.imageReadyHandler = handler
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
})
