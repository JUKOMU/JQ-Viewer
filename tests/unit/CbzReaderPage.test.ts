/* eslint-disable vue/one-component-per-file -- test-only reader fixtures */
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, nextTick, type PropType } from 'vue'
import { beforeEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  route: {
    query: {
      fileRef: 'file:path:/books/test.cbz',
      title: '测试 CBZ',
      albumId: 'album-1',
      chapterId: 'chapter-1',
      page: '2',
    } as Record<string, string>,
  },
  router: { back: vi.fn(), push: vi.fn() },
  getCbzInfo: vi.fn(),
  cbzPageUrl: vi.fn(),
  showToast: vi.fn(),
  getInitialPage: vi.fn(),
  recordProgress: vi.fn(),
  recordBrowse: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => mocks.router,
}))

vi.mock('@ionic/vue', () => ({
  IonPage: defineComponent({
    name: 'IonPage',
    setup(_, { slots }) {
      return () => h('div', slots.default?.())
    },
  }),
}))

vi.mock('@/runtime/runtimeContext', () => ({
  getRuntime: () => ({
    services: {
      reader: {
        orientation: { available: false, reason: 'test' },
        brightness: { available: false, reason: 'test' },
        keepAwake: { available: false, reason: 'test' },
        fullscreen: { available: false, reason: 'test' },
        volumeKeys: { available: false, reason: 'test' },
        hostState: { available: false, reason: 'test' },
      },
    },
    resources: { cbzPageUrl: mocks.cbzPageUrl },
  }),
}))

vi.mock('@/services/JmcomicService', () => ({
  showToast: mocks.showToast,
  JmcomicService: { getCbzInfo: mocks.getCbzInfo },
}))

vi.mock('@/services/SettingsService', () => ({
  SettingsStore: {
    getReaderDisplayMode: () => 'vertical',
    getReaderPreloadPages: () => 1,
    getReaderScreenOrientation: () => 'auto',
    getReaderBrightness: () => -1,
    getReaderKeepScreenOn: () => false,
  },
}))

vi.mock('@/services/ReadingProgressService', () => ({
  ReadingProgressService: {
    getInitialPage: mocks.getInitialPage,
    record: mocks.recordProgress,
  },
}))

vi.mock('@/services/HistoryService', () => ({
  HistoryService: { recordBrowse: mocks.recordBrowse },
}))

const VerticalViewStub = defineComponent({
  name: 'VerticalScrollView',
  props: {
    imageMap: { type: Object as PropType<Map<number, string>>, required: true },
    totalCount: { type: Number, required: true },
    currentIndex: { type: Number, required: true },
  },
  emits: ['update:current-index', 'request-range', 'image-error'],
  setup(_, { expose }) {
    expose({ scrollToIndex: vi.fn() })
    return () => h('div', { class: 'vertical-view-stub' })
  },
})

const EmptyStub = defineComponent({
  setup() {
    return () => h('div')
  },
})

import CbzReaderPage from '@/views/CbzReaderPage.vue'

const mountPage = () =>
  mount(CbzReaderPage, {
    global: {
      stubs: {
        ReaderTopToolbar: EmptyStub,
        ReaderBottomToolbar: EmptyStub,
        ReaderSettingsPanel: EmptyStub,
        VerticalScrollView: VerticalViewStub,
        HorizontalPageView: EmptyStub,
      },
    },
  })

async function settle() {
  for (let index = 0; index < 4; index++) {
    await flushPromises()
    await nextTick()
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.route.query = {
    fileRef: 'file:path:/books/test.cbz',
    title: '测试 CBZ',
    albumId: 'album-1',
    chapterId: 'chapter-1',
    page: '2',
  }
  mocks.getCbzInfo.mockResolvedValue({ pageCount: 4, coverPage: 1 })
  mocks.cbzPageUrl.mockImplementation(({ page }: { page: number }) => `cbz:${page}`)
  mocks.getInitialPage.mockReturnValue(2)
  mocks.showToast.mockResolvedValue(undefined)
})

describe('CbzReaderPage', () => {
  test('恢复阅读进度并通过 ResourceResolver 建立页面窗口', async () => {
    const wrapper = mountPage()
    await settle()

    expect(mocks.getCbzInfo).toHaveBeenCalledWith('file:path:/books/test.cbz')
    expect(mocks.getInitialPage).toHaveBeenCalledWith('2', 'album-1', 'chapter-1', 4)
    const view = wrapper.findComponent(VerticalViewStub)
    expect(view.props('totalCount')).toBe(4)
    expect(view.props('currentIndex')).toBe(1)
    expect(Array.from((view.props('imageMap') as Map<number, string>).entries())).toEqual([
      [1, 'cbz:1'],
      [2, 'cbz:2'],
      [3, 'cbz:3'],
      [4, 'cbz:4'],
    ])
    expect(mocks.recordProgress).toHaveBeenCalledWith('album-1', 'chapter-1', 2, 4)
    expect(mocks.recordBrowse).toHaveBeenCalled()
    wrapper.unmount()
  })

  test('CBZ 打开失败时提示稳定错误并返回', async () => {
    mocks.getCbzInfo.mockRejectedValue(new Error('CBZ 已损坏'))

    const wrapper = mountPage()
    await settle()

    expect(mocks.showToast).toHaveBeenCalledWith('CBZ 已损坏', 'danger')
    expect(mocks.router.back).toHaveBeenCalled()
    wrapper.unmount()
  })
})
