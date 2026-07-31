/* eslint-disable vue/one-component-per-file -- test-only Ionic and keep-alive fixtures */
import {flushPromises, mount, type VueWrapper} from '@vue/test-utils'
import {KeepAlive, defineComponent, h, nextTick, ref} from 'vue'
import {afterEach, beforeEach, describe, expect, test, vi} from 'vitest'

const mocks = vi.hoisted(() => ({
  route: {
    params: {id: '123'},
    query: {},
  },
  setRouteId: undefined as undefined | ((id: string | undefined) => void),
  router: {
    back: vi.fn(),
    push: vi.fn(),
  },
  getAlbum: vi.fn(() => new Promise(() => {})),
  getPhoto: vi.fn(() => new Promise(() => {})),
  menuSwipeGesture: vi.fn(() => Promise.resolve()),
}))

vi.mock('vue-router', async () => {
  const {reactive} = await import('vue')
  const route = reactive<{params: {id?: string}; query: Record<string, string>}>(mocks.route)
  mocks.setRouteId = (id) => {
    route.params.id = id
  }
  return {
    useRoute: () => route,
    useRouter: () => mocks.router,
  }
})

vi.mock('@ionic/vue', async () => {
  const {defineComponent, h, onMounted, ref} = await import('vue')
  return {
    IonContent: defineComponent({
      name: 'IonContent',
      setup(_, {slots}) {
        const elementRef = ref<HTMLElement | null>(null)
        onMounted(() => {
          const element = elementRef.value as HTMLElement & {
            getScrollElement: () => Promise<HTMLElement | null>
          }
          element.getScrollElement = async () => element
        })
        return () => h('div', {ref: elementRef, class: 'ion-content-stub'}, slots.default?.())
      },
    }),
    IonPage: defineComponent({
      name: 'IonPage',
      setup(_, {slots}) {
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
  GlobalWorkerOptions: {workerSrc: ''},
  getDocument: vi.fn(),
}))

vi.mock('@/services/JmcomicService', () => ({
  getImageUrl: vi.fn(),
  sanitizeError: vi.fn((error: unknown, fallback: string) => String(error || fallback)),
  showToast: vi.fn(() => Promise.resolve()),
  JmcomicService: {
    getAlbum: mocks.getAlbum,
    getPhoto: mocks.getPhoto,
  },
}))

vi.mock('@/services/PdfReaderService', () => ({
  buildPdfDocumentParams: vi.fn(),
  fetchPdfArrayBuffer: vi.fn(),
}))

vi.mock('@/services/OfflineDownloadService', () => ({
  OfflineDownloadService: {},
}))

vi.mock('@/services/OfflineFavoriteService', () => ({
  OfflineFavoriteService: {},
}))

vi.mock('@/services/HistoryService', () => ({
  HistoryService: {recordBrowse: vi.fn()},
}))

vi.mock('@/composables/useAuth', async () => {
  const {ref} = await import('vue')
  return {useAuth: () => ({isLoggedIn: ref(false)})}
})

vi.mock('@/components/album/AlbumHeader.vue', () => ({
  default: {name: 'AlbumHeader', render: () => null},
}))
vi.mock('@/components/album/AlbumInfoTab.vue', () => ({
  default: {name: 'AlbumInfoTab', render: () => null},
}))
vi.mock('@/components/album/AlbumChaptersTab.vue', () => ({
  default: {name: 'AlbumChaptersTab', render: () => null},
}))
vi.mock('@/components/album/AlbumPreviewTab.vue', () => ({
  default: {name: 'AlbumPreviewTab', render: () => null},
}))
vi.mock('@/components/album/AlbumCommentsTab.vue', () => ({
  default: {name: 'AlbumCommentsTab', render: () => null},
}))
vi.mock('@/components/favorite/FavoriteFolderPicker.vue', () => ({
  default: {name: 'FavoriteFolderPicker', render: () => null},
}))

import AlbumDetailPage from '@/views/AlbumDetailPage.vue'

class ResizeObserverStub {
  observe() {}
  disconnect() {}
}

const settle = async () => {
  await flushPromises()
  await nextTick()
}

const clickTab = async (wrapper: VueWrapper, label: string) => {
  const button = wrapper.findAll('.tab-btn').find((item) => item.text().startsWith(label))
  if (!button) throw new Error(`找不到 tab: ${label}`)
  await button.trigger('click')
  await settle()
}

describe('AlbumDetailPage tab 状态', () => {
  beforeEach(() => {
    vi.stubGlobal('ResizeObserver', ResizeObserverStub)
    mocks.menuSwipeGesture.mockClear()
    mocks.setRouteId?.('123')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
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
            showDetail.value ? h(AlbumDetailPage, {key: 'detail'}) : h('div', {class: 'reader-stub'}),
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
})
