/* eslint-disable vue/one-component-per-file -- test-only Ionic fixtures */
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { beforeEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  ionViewWillEnter: undefined as (() => void) | undefined,
  refreshPdf: vi.fn(),
  getDownloadTasks: vi.fn(),
  getPdfManagementState: vi.fn(),
  consumeLaunchRoute: vi.fn(),
  addLaunchRouteListener: vi.fn(),
  addDownloadProgressListener: vi.fn(),
  route: { query: { view: 'pdf' } } as { query: Record<string, string> },
}))

vi.mock('@ionic/vue', () => {
  const withSlot = (name: string, tag = 'div') =>
    defineComponent({
      name,
      setup(_, { slots }) {
        return () => h(tag, slots.default?.())
      },
    })
  return {
    alertController: { create: vi.fn() },
    toastController: { create: vi.fn() },
    IonAlert: withSlot('IonAlert'),
    IonContent: withSlot('IonContent', 'main'),
    IonHeader: withSlot('IonHeader', 'header'),
    IonIcon: withSlot('IonIcon', 'span'),
    IonPage: withSlot('IonPage'),
    IonPopover: withSlot('IonPopover'),
    IonRefresher: withSlot('IonRefresher'),
    IonRefresherContent: withSlot('IonRefresherContent'),
    IonToolbar: withSlot('IonToolbar'),
    onIonViewWillEnter: (hook: () => void) => {
      mocks.ionViewWillEnter = hook
    },
  }
})

vi.mock('ionicons/icons', () => ({
  bookOutline: 'book',
  closeCircleOutline: 'close',
  cloudDownloadOutline: 'download',
  documentLockOutline: 'pdf',
  informationCircleOutline: 'info',
  pauseOutline: 'pause',
  playOutline: 'play',
  refreshOutline: 'refresh',
  saveOutline: 'save',
  textOutline: 'text',
  timeOutline: 'time',
  trashOutline: 'trash',
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    getDownloadTasks: mocks.getDownloadTasks,
    getPdfManagementState: mocks.getPdfManagementState,
    consumeLaunchRoute: mocks.consumeLaunchRoute,
    addLaunchRouteListener: mocks.addLaunchRouteListener,
    addDownloadProgressListener: mocks.addDownloadProgressListener,
  },
  sanitizeError: (_error: unknown, fallback: string) => fallback,
  showToast: vi.fn(),
}))

vi.mock('@/services/OfflineDownloadService', () => ({
  OfflineDownloadService: {
    setAll: vi.fn(),
    getAll: vi.fn(() => []),
    updateProgress: vi.fn(),
    updateStatus: vi.fn(),
    removeTask: vi.fn(),
    addTask: vi.fn(),
  },
}))

vi.mock('@/components/download/PdfManagementView.vue', () => ({
  default: defineComponent({
    name: 'PdfManagementView',
    setup(_, { expose }) {
      expose({ refresh: mocks.refreshPdf })
      return () => h('div', { class: 'pdf-management-stub' })
    },
  }),
}))

vi.mock('@/components/common/MenuToggleButton.vue', () => ({
  default: defineComponent({ render: () => null }),
}))
vi.mock('@/components/download/DownloadTaskCard.vue', () => ({
  default: defineComponent({ render: () => null }),
}))
vi.mock('@/components/download/PdfExportBottomSheet.vue', () => ({
  default: defineComponent({ render: () => null }),
}))
vi.mock('@/components/download/DeleteChaptersBottomSheet.vue', () => ({
  default: defineComponent({ render: () => null }),
}))

import DownloadPage from '@/views/DownloadPage.vue'

describe('DownloadPage PDF keepAlive 生命周期', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.ionViewWillEnter = undefined
    mocks.route.query = { view: 'pdf' }
    mocks.getDownloadTasks.mockResolvedValue({ tasks: [], usedBytes: 0, availableBytes: 0 })
    mocks.getPdfManagementState.mockResolvedValue({ recoveryState: 'ready' })
    mocks.consumeLaunchRoute.mockResolvedValue({})
    mocks.addLaunchRouteListener.mockResolvedValue({ remove: vi.fn() })
    mocks.addDownloadProgressListener.mockResolvedValue({ remove: vi.fn() })
    mocks.refreshPdf.mockResolvedValue(undefined)
  })

  test('首次进入不重复刷新，keepAlive 重新进入时同步 PDF 子视图', async () => {
    const wrapper = mount(DownloadPage)
    await flushPromises()
    expect(wrapper.find('.pdf-management-stub').exists()).toBe(true)
    expect(mocks.ionViewWillEnter).toBeTypeOf('function')

    mocks.ionViewWillEnter!()
    await flushPromises()
    expect(mocks.refreshPdf).not.toHaveBeenCalled()

    mocks.ionViewWillEnter!()
    await flushPromises()
    expect(mocks.refreshPdf).toHaveBeenCalledOnce()
    wrapper.unmount()
  })
})
