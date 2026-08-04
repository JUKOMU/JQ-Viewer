import { flushPromises, shallowMount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  showToast: vi.fn(() => Promise.resolve()),
  getDownloadedPhoto: vi.fn(),
  getPhoto: vi.fn(),
  retryImage: vi.fn(),
  router: {
    back: vi.fn(),
    push: vi.fn(() => Promise.resolve()),
  },
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({
    name: 'ReaderPage',
    params: { albumId: 'album', chapterId: 'chapter' },
    query: {},
  }),
  useRouter: () => mocks.router,
}))

vi.mock('@/services/JmcomicService', () => ({
  getImageUrl: (photoId: string, sortOrder: number, type: string) =>
    `https://jqviewer.local/${type}/${photoId}/${sortOrder}`,
  showToast: mocks.showToast,
  JmcomicService: {
    getDownloadedPhoto: mocks.getDownloadedPhoto,
    getPhoto: mocks.getPhoto,
    getAlbum: vi.fn(() => new Promise(() => {})),
    preloadImages: vi.fn(() => Promise.resolve({ cached: [], pending: [] })),
    retryImage: mocks.retryImage,
    setReaderFullscreen: vi.fn(() => Promise.resolve()),
    setReaderState: vi.fn(() => Promise.resolve()),
    setReaderScreenOrientation: vi.fn(() => Promise.resolve()),
    setReaderBrightness: vi.fn(() => Promise.resolve()),
    setReaderKeepScreenOn: vi.fn(() => Promise.resolve()),
    addVolumeKeyListener: vi.fn(() => Promise.resolve({ remove: vi.fn() })),
    addImageReadyListener: vi.fn(() => Promise.resolve({ remove: vi.fn() })),
  },
}))

vi.mock('@/services/SettingsService', () => ({
  SettingsStore: {
    getReaderPreloadPages: () => 1,
    getReaderDisplayMode: () => 'horizontal',
    getReaderAutoShowToolbarAtEnd: () => false,
    getReaderScreenOrientation: () => 'auto',
    getReaderBrightness: () => -1,
    getReaderKeepScreenOn: () => false,
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

describe('ReaderPage 图片重试', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.getDownloadedPhoto.mockRejectedValue(new Error('not downloaded'))
    mocks.getPhoto.mockResolvedValue({
      id: 'chapter',
      title: 'Chapter',
      albumId: 'album',
      sortOrder: 1,
      author: '',
      tags: [],
      images: [
        {
          photoId: 'chapter',
          scrambleId: 'scramble',
          filename: 'page-2.png',
          url: 'https://example.invalid/page-2.png',
          queryParams: '',
          sortOrder: 2,
        },
      ],
    })
  })

  test('缺少目标图片映射时提示错误而不是静默返回', async () => {
    const wrapper = shallowMount(ReaderPage, {
      global: {
        stubs: {
          IonPage: { template: '<div><slot /></div>' },
        },
      },
    })
    await flushPromises()

    const horizontalView = wrapper.findComponent({ name: 'HorizontalPageView' })
    expect(horizontalView.exists()).toBe(true)

    horizontalView.vm.$emit('retry-image', 1)
    await nextTick()

    expect(mocks.showToast).toHaveBeenCalledWith('缺少图片信息，无法重试', 'danger')
    expect(mocks.retryImage).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  test('图片加载失败不会自动发起网络重试', async () => {
    mocks.getPhoto.mockResolvedValue({
      id: 'chapter',
      title: 'Chapter',
      albumId: 'album',
      sortOrder: 1,
      author: '',
      tags: [],
      images: [
        {
          photoId: 'chapter',
          scrambleId: 'scramble',
          filename: 'page-1.png',
          url: 'https://example.invalid/page-1.png',
          queryParams: '',
          sortOrder: 1,
        },
      ],
    })
    mocks.retryImage.mockResolvedValue({ success: true })
    const wrapper = shallowMount(ReaderPage, {
      global: {
        stubs: {
          IonPage: { template: '<div><slot /></div>' },
        },
      },
    })
    await flushPromises()

    const horizontalView = wrapper.findComponent({ name: 'HorizontalPageView' })
    horizontalView.vm.$emit('image-error', 1)
    await nextTick()
    expect(mocks.retryImage).not.toHaveBeenCalled()

    horizontalView.vm.$emit('retry-image', 1)
    await flushPromises()
    expect(mocks.retryImage).toHaveBeenCalledWith(
      'chapter',
      expect.objectContaining({ sortOrder: 1 }),
    )
    wrapper.unmount()
  })

  test('原生确认下载文件异常时提示重新下载并回退在线加载', async () => {
    mocks.getDownloadedPhoto.mockRejectedValue({ code: 'DOWNLOAD_FILES_INVALID' })
    const wrapper = shallowMount(ReaderPage, {
      global: {
        stubs: {
          IonPage: { template: '<div><slot /></div>' },
        },
      },
    })
    await flushPromises()

    expect(mocks.showToast).toHaveBeenCalledWith(
      '下载文件异常，请在下载管理中重新下载',
      'danger',
    )
    expect(mocks.getPhoto).toHaveBeenCalledWith('chapter')
    wrapper.unmount()
  })
})
