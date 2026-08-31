/* eslint-disable vue/one-component-per-file -- test-only reader fixtures */
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, nextTick, onMounted, ref, type PropType } from 'vue'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

type Deferred<T> = {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason?: unknown) => void
}

const deferred = <T>(): Deferred<T> => {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

const mocks = vi.hoisted(() => ({
  route: {
    query: {
      path: '/books/test.pdf',
      title: '测试 PDF',
      albumId: 'album-1',
      chapterId: 'chapter-1',
      page: '1',
    } as Record<string, string>,
  },
  setRouteQuery: undefined as undefined | ((query: Record<string, string>) => void),
  router: {
    back: vi.fn(),
    push: vi.fn(),
  },
  displayMode: 'vertical',
  fetchPdfArrayBuffer: vi.fn(),
  buildPdfDocumentParams: vi.fn(),
  getDocument: vi.fn(),
  getPdfInfo: vi.fn(),
  renderPdfPage: vi.fn(),
  createObjectURL: vi.fn(),
  revokeObjectURL: vi.fn(),
  showToast: vi.fn(),
  addVolumeKeyListener: vi.fn(),
  getAlbum: vi.fn(),
  getInitialPage: vi.fn(),
  recordProgress: vi.fn(),
  recordBrowse: vi.fn(),
}))

const originalCreateObjectURL = Object.getOwnPropertyDescriptor(URL, 'createObjectURL')
const originalRevokeObjectURL = Object.getOwnPropertyDescriptor(URL, 'revokeObjectURL')

vi.mock('vue-router', async () => {
  const { reactive } = await import('vue')
  const route = reactive(mocks.route)
  mocks.setRouteQuery = (query) => {
    for (const key of Object.keys(route.query)) delete route.query[key]
    Object.assign(route.query, query)
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

vi.mock('pdfjs-dist', () => ({
  GlobalWorkerOptions: { workerSrc: '' },
  getDocument: mocks.getDocument,
}))

vi.mock('@/services/PdfReaderService', () => ({
  buildPdfDocumentParams: mocks.buildPdfDocumentParams,
  fetchPdfArrayBuffer: mocks.fetchPdfArrayBuffer,
  PdfLoadError: class PdfLoadError extends Error {},
}))

vi.mock('@/services/JmcomicService', () => ({
  showToast: mocks.showToast,
  JmcomicService: {
    addVolumeKeyListener: mocks.addVolumeKeyListener,
    getAlbum: mocks.getAlbum,
    getPdfInfo: mocks.getPdfInfo,
    renderPdfPage: mocks.renderPdfPage,
    setReaderBrightness: vi.fn(() => Promise.resolve()),
    setReaderFullscreen: vi.fn(() => Promise.resolve()),
    setReaderKeepScreenOn: vi.fn(() => Promise.resolve()),
    setReaderScreenOrientation: vi.fn(() => Promise.resolve()),
    setReaderState: vi.fn(() => Promise.resolve()),
  },
}))

vi.mock('@/services/SettingsService', () => ({
  SettingsStore: {
    getReaderDisplayMode: () => mocks.displayMode,
    getReaderPreloadPages: () => 1,
    getReaderScreenOrientation: () => 'auto',
    getReaderBrightness: () => -1,
    getReaderKeepScreenOn: () => false,
  },
}))

vi.mock('@/services/HistoryService', () => ({
  HistoryService: { recordBrowse: mocks.recordBrowse },
}))

vi.mock('@/services/ReadingProgressService', () => ({
  ReadingProgressService: {
    getInitialPage: mocks.getInitialPage,
    record: mocks.recordProgress,
  },
}))

type RenderCall = {
  pageNum: number
  canvas: HTMLCanvasElement
}

let renderCalls: RenderCall[] = []
let pendingRenders: Array<Deferred<void>> = []
let renderBehavior: 'resolved' | 'deferred' = 'resolved'
let objectUrlIndex = 0
let pdfDocument: ReturnType<typeof createPdfDocument>
const viewWidths = { vertical: 400, horizontal: 900 }
const mountedViews: { vertical: HTMLElement | null; horizontal: HTMLElement | null } = {
  vertical: null,
  horizontal: null,
}

function createPdfDocument(pageCount = 3) {
  const pages = new Map<
    number,
    { getViewport: ReturnType<typeof vi.fn>; render: ReturnType<typeof vi.fn> }
  >()
  const document = {
    numPages: pageCount,
    getPage: vi.fn((pageNum: number) => {
      let page = pages.get(pageNum)
      if (!page) {
        page = {
          getViewport: vi.fn(({ scale }: { scale: number }) => ({
            width: 600 * scale,
            height: 800 * scale,
          })),
          render: vi.fn(({ canvas }: { canvas: HTMLCanvasElement }) => {
            renderCalls.push({ pageNum, canvas })
            const renderDeferred = deferred<void>()
            pendingRenders.push(renderDeferred)
            return {
              promise: renderBehavior === 'deferred' ? renderDeferred.promise : Promise.resolve(),
              cancel: vi.fn(),
            }
          }),
        }
        pages.set(pageNum, page)
      }
      return Promise.resolve({ ...page, cleanup: vi.fn() })
    }),
    destroy: vi.fn(() => Promise.resolve()),
  }
  return document
}

class ResizeObserverMock {
  static active: ResizeObserverMock | null = null

  private readonly callback: ResizeObserverCallback
  disconnected = false

  constructor(callback: ResizeObserverCallback) {
    this.callback = callback
    ResizeObserverMock.active = this
  }

  observe() {}

  disconnect() {
    this.disconnected = true
    if (ResizeObserverMock.active === this) ResizeObserverMock.active = null
  }

  trigger() {
    this.callback([], this as unknown as ResizeObserver)
  }
}

function setClientWidth(element: HTMLElement, width: number) {
  Object.defineProperty(element, 'clientWidth', {
    configurable: true,
    value: width,
  })
}

const VerticalViewStub = defineComponent({
  name: 'VerticalScrollView',
  props: {
    imageMap: { type: Object as PropType<Map<number, string>>, required: true },
    totalCount: { type: Number, required: true },
    currentIndex: { type: Number, required: true },
  },
  emits: ['update:current-index', 'request-range'],
  setup(_, { expose }) {
    const elementRef = ref<HTMLElement | null>(null)
    const scrollToIndex = vi.fn()
    expose({ scrollToIndex })
    onMounted(() => {
      if (!elementRef.value) return
      setClientWidth(elementRef.value, viewWidths.vertical)
      mountedViews.vertical = elementRef.value
    })
    return () => h('div', { ref: elementRef, class: 'vertical-container-stub' })
  },
})

const HorizontalViewStub = defineComponent({
  name: 'HorizontalPageView',
  props: {
    imageMap: { type: Object as PropType<Map<number, string>>, required: true },
    totalCount: { type: Number, required: true },
    currentIndex: { type: Number, required: true },
  },
  emits: ['update:current-index', 'toggle-toolbar'],
  setup(_, { expose }) {
    const elementRef = ref<HTMLElement | null>(null)
    const scrollToIndex = vi.fn()
    expose({ scrollToIndex })
    onMounted(() => {
      if (!elementRef.value) return
      setClientWidth(elementRef.value, viewWidths.horizontal)
      mountedViews.horizontal = elementRef.value
    })
    return () => h('div', { ref: elementRef, class: 'horizontal-container-stub' })
  },
})

const ReaderBottomToolbarStub = defineComponent({
  name: 'ReaderBottomToolbar',
  props: {
    current: { type: Number, required: true },
    total: { type: Number, required: true },
  },
  emits: [
    'open-settings',
    'update:current',
    'update:current-input',
    'progress-drag-start',
    'progress-drag-end',
  ],
  setup() {
    return () => h('div', { class: 'reader-bottom-toolbar-stub' })
  },
})

const ReaderSettingsPanelStub = defineComponent({
  name: 'ReaderSettingsPanel',
  props: { isVertical: { type: Boolean, required: true } },
  emits: ['close', 'update:display-mode'],
  setup() {
    return () => h('div', { class: 'reader-settings-panel-stub' })
  },
})

const EmptyStub = defineComponent({
  setup() {
    return () => h('div')
  },
})

import PdfReaderPage from '@/views/PdfReaderPage.vue'

const mountPage = () =>
  mount(PdfReaderPage, {
    global: {
      stubs: {
        ReaderTopToolbar: EmptyStub,
        ReaderBottomToolbar: ReaderBottomToolbarStub,
        ReaderSettingsPanel: ReaderSettingsPanelStub,
        VerticalScrollView: VerticalViewStub,
        HorizontalPageView: HorizontalViewStub,
      },
    },
  })

async function settle() {
  for (let i = 0; i < 8; i++) {
    await flushPromises()
    await nextTick()
  }
}

function currentView(wrapper: ReturnType<typeof mountPage>) {
  if (wrapper.findComponent(VerticalViewStub).exists()) {
    return wrapper.findComponent(VerticalViewStub)
  }
  return wrapper.findComponent(HorizontalViewStub)
}

function triggerResize() {
  ResizeObserverMock.active?.trigger()
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.displayMode = 'vertical'
  mocks.setRouteQuery?.({
    path: '/books/test.pdf',
    title: '测试 PDF',
    albumId: 'album-1',
    chapterId: 'chapter-1',
    page: '1',
  })
  mocks.fetchPdfArrayBuffer.mockResolvedValue(new ArrayBuffer(8))
  mocks.buildPdfDocumentParams.mockImplementation((data: ArrayBuffer) => ({ data }))
  mocks.getPdfInfo.mockResolvedValue({ pageCount: 3 })
  mocks.renderPdfPage.mockImplementation(
    (_filePath: string, pageNum: number, targetWidth: number) =>
      Promise.resolve({ imageUrl: `native:${pageNum}:${targetWidth}` }),
  )
  mocks.getAlbum.mockResolvedValue({ title: '测试专辑', image: '', authors: [] })
  mocks.getInitialPage.mockImplementation((page: string | undefined) => Number(page) || 1)
  mocks.addVolumeKeyListener.mockResolvedValue({ remove: vi.fn(() => Promise.resolve()) })
  mocks.showToast.mockResolvedValue(undefined)

  renderCalls = []
  pendingRenders = []
  renderBehavior = 'resolved'
  objectUrlIndex = 0
  pdfDocument = createPdfDocument()
  mocks.getDocument.mockReturnValue({ promise: Promise.resolve(pdfDocument) })
  mocks.createObjectURL.mockImplementation(() => `blob:${++objectUrlIndex}`)
  mocks.revokeObjectURL.mockImplementation(() => {})

  viewWidths.vertical = 400
  viewWidths.horizontal = 900
  mountedViews.vertical = null
  mountedViews.horizontal = null

  vi.stubGlobal('ResizeObserver', ResizeObserverMock)
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    queueMicrotask(() => callback(performance.now()))
    return 1
  })
  vi.stubGlobal('cancelAnimationFrame', () => {})
  Object.defineProperty(URL, 'createObjectURL', {
    configurable: true,
    value: mocks.createObjectURL,
  })
  Object.defineProperty(URL, 'revokeObjectURL', {
    configurable: true,
    value: mocks.revokeObjectURL,
  })
  vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation((callback) => {
    callback(new Blob(['rendered'], { type: 'image/png' }))
  })
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  if (originalCreateObjectURL) {
    Object.defineProperty(URL, 'createObjectURL', originalCreateObjectURL)
  } else {
    delete (URL as unknown as Record<string, unknown>).createObjectURL
  }
  if (originalRevokeObjectURL) {
    Object.defineProperty(URL, 'revokeObjectURL', originalRevokeObjectURL)
  } else {
    delete (URL as unknown as Record<string, unknown>).revokeObjectURL
  }
  ResizeObserverMock.active = null
})

describe('PdfReaderPage PDF 专属渲染尺寸', () => {
  test('pdf.js 纵向渲染使用实际 720px 阅读轨道并保持 2 倍密度', async () => {
    viewWidths.vertical = 1440
    const wrapper = mountPage()
    await settle()

    expect(renderCalls.length).toBeGreaterThan(0)
    expect(renderCalls.map(({ canvas }) => canvas.width)).toEqual(expect.arrayContaining([1440]))
    expect(renderCalls.every(({ canvas }) => canvas.width === 1440)).toBe(true)
    expect(mocks.renderPdfPage).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  test('pdf.js 横向渲染使用实际 reader 容器宽度', async () => {
    mocks.displayMode = 'horizontal'
    viewWidths.horizontal = 900
    const wrapper = mountPage()
    await settle()

    expect(renderCalls.length).toBeGreaterThan(0)
    expect(renderCalls.every(({ canvas }) => canvas.width === 1800)).toBe(true)
    expect(wrapper.findComponent(HorizontalViewStub).props('currentIndex')).toBe(0)
    wrapper.unmount()
  })

  test('pdf.js 失败后 native renderer 使用实际宽度并保留最大像素限制', async () => {
    mocks.displayMode = 'horizontal'
    viewWidths.horizontal = 1000
    const loadFailure = deferred<never>()
    mocks.getDocument.mockReturnValue({ promise: loadFailure.promise })
    const wrapper = mountPage()
    loadFailure.reject(new Error('unsupported'))
    await settle()

    expect(mocks.getPdfInfo).toHaveBeenCalledWith('/books/test.pdf')
    expect(mocks.renderPdfPage).toHaveBeenCalled()
    expect(mocks.renderPdfPage.mock.calls.every((call) => call[2] === 2400)).toBe(true)
    expect(renderCalls).toHaveLength(0)
    wrapper.unmount()
  })

  test('容器变窄不重渲染，变宽只失效并重渲染活动窗口', async () => {
    pdfDocument = createPdfDocument(4)
    mocks.getDocument.mockReturnValue({ promise: Promise.resolve(pdfDocument) })
    viewWidths.vertical = 400
    const wrapper = mountPage()
    await settle()
    const initialCount = renderCalls.length

    setClientWidth(mountedViews.vertical!, 300)
    triggerResize()
    await settle()
    expect(renderCalls).toHaveLength(initialCount)

    wrapper.findComponent(ReaderBottomToolbarStub).vm.$emit('update:current', 4)
    await settle()
    wrapper.findComponent(ReaderBottomToolbarStub).vm.$emit('update:current', 2)
    await settle()
    const beforeWidening = renderCalls.length
    const page4BeforeWidening = currentView(wrapper).props('imageMap').get(4)

    setClientWidth(mountedViews.vertical!, 600)
    triggerResize()
    await settle()

    expect(renderCalls.length - beforeWidening).toBe(3)
    expect(renderCalls.slice(-3).map(({ pageNum }) => pageNum)).toEqual(
      expect.arrayContaining([1, 2, 3]),
    )
    expect(currentView(wrapper).props('imageMap').get(4)).toBe(page4BeforeWidening)
    expect(currentView(wrapper).props('currentIndex')).toBe(1)
    wrapper.unmount()
  })

  test('尺寸批次变更后忽略过期 renderer 结果', async () => {
    renderBehavior = 'deferred'
    pdfDocument = createPdfDocument(1)
    mocks.getDocument.mockReturnValue({ promise: Promise.resolve(pdfDocument) })
    const wrapper = mountPage()
    await settle()
    expect(renderCalls).toHaveLength(1)
    expect(pendingRenders).toHaveLength(1)

    setClientWidth(mountedViews.vertical!, 600)
    triggerResize()
    await settle()
    expect(currentView(wrapper).props('imageMap').has(1)).toBe(false)

    pendingRenders[0].resolve()
    await settle()
    expect(renderCalls).toHaveLength(2)
    expect(mocks.revokeObjectURL).toHaveBeenCalledWith('blob:1')

    pendingRenders[1].resolve()
    await settle()
    expect(currentView(wrapper).props('imageMap').get(1)).toBe('blob:2')
    wrapper.unmount()
  })

  test('模式切换保持当前页和进度，并在所需像素宽度上升时只重渲染活动窗口', async () => {
    mocks.setRouteQuery?.({
      path: '/books/test.pdf',
      albumId: 'album-1',
      chapterId: 'chapter-1',
      page: '2',
    })
    viewWidths.vertical = 400
    viewWidths.horizontal = 900
    const wrapper = mountPage()
    await settle()
    const initialCount = renderCalls.length
    expect(currentView(wrapper).props('currentIndex')).toBe(1)

    wrapper.findComponent(ReaderBottomToolbarStub).vm.$emit('open-settings')
    await nextTick()
    wrapper.findComponent(ReaderSettingsPanelStub).vm.$emit('update:display-mode', false)
    await settle()

    expect(wrapper.findComponent(VerticalViewStub).exists()).toBe(false)
    expect(wrapper.findComponent(HorizontalViewStub).props('currentIndex')).toBe(1)
    expect(renderCalls.length - initialCount).toBe(3)
    expect(renderCalls.slice(-3).every(({ canvas }) => canvas.width === 1800)).toBe(true)
    expect(mocks.recordProgress).toHaveBeenLastCalledWith('album-1', 'chapter-1', 2, 3)
    wrapper.unmount()
  })
})
