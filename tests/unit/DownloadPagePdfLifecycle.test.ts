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
  checkFilesExist: vi.fn(),
  checkNotificationPermission: vi.fn(),
  exportPdfBatch: vi.fn(),
  buildExportPlan: vi.fn(),
  showToast: vi.fn(),
  pdfMountCount: 0,
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
    checkFilesExist: mocks.checkFilesExist,
    checkNotificationPermission: mocks.checkNotificationPermission,
    exportPdfBatch: mocks.exportPdfBatch,
  },
  sanitizeError: (_error: unknown, fallback: string) => fallback,
  showToast: mocks.showToast,
}))

vi.mock('@/services/PdfExportService', () => ({
  PdfExportService: {
    buildExportPlan: mocks.buildExportPlan,
    getExportPath: () => '/pdf',
  },
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
      mocks.pdfMountCount++
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
  default: defineComponent({ name: 'PdfExportBottomSheet', render: () => null }),
}))
vi.mock('@/components/download/DeleteChaptersBottomSheet.vue', () => ({
  default: defineComponent({ render: () => null }),
}))

import DownloadPage from '@/views/DownloadPage.vue'

describe('DownloadPage PDF keepAlive 生命周期', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.ionViewWillEnter = undefined
    mocks.pdfMountCount = 0
    mocks.route.query = { view: 'pdf' }
    mocks.getDownloadTasks.mockResolvedValue({ tasks: [], usedBytes: 0, availableBytes: 0 })
    mocks.getPdfManagementState.mockResolvedValue({ recoveryState: 'ready' })
    mocks.consumeLaunchRoute.mockResolvedValue({})
    mocks.addLaunchRouteListener.mockResolvedValue({ remove: vi.fn() })
    mocks.addDownloadProgressListener.mockResolvedValue({ remove: vi.fn() })
    mocks.refreshPdf.mockResolvedValue(undefined)
    mocks.checkFilesExist.mockResolvedValue({ existing: [] })
    mocks.checkNotificationPermission.mockResolvedValue({ granted: true })
    mocks.buildExportPlan.mockReturnValue({ tasks: [{}], outputDisplayPaths: [] })
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

  test('下载与 PDF 视图切换时不重新挂载 PDF 管理视图', async () => {
    const wrapper = mount(DownloadPage)
    await flushPromises()
    expect(mocks.pdfMountCount).toBe(1)

    await wrapper.findAll('.tab-btn')[0].trigger('click')
    await wrapper.findAll('.tab-btn')[1].trigger('click')

    expect(mocks.pdfMountCount).toBe(1)
    wrapper.unmount()
  })

  test.each([
    {
      results: [{ accepted: true }, { accepted: true }],
      message: 'PDF导出已开始，请查看通知',
      tone: 'success',
    },
    {
      results: [{ accepted: true }, { accepted: false }],
      message: '已开始 1 个，1 个未进入队列，请查看导出任务',
      tone: 'medium',
    },
    {
      results: [{ accepted: false }, { accepted: false }],
      message: 'PDF 导出未开始，请查看任务失败原因',
      tone: 'danger',
    },
  ])('按原生接受结果显示导出提示: $message', async ({ results, message, tone }) => {
    mocks.exportPdfBatch.mockResolvedValueOnce({ tasks: results })
    const wrapper = mount(DownloadPage)
    await flushPromises()

    wrapper.findComponent({ name: 'PdfExportBottomSheet' }).vm.$emit('confirm', {
      selectedChapters: [
        {
          albumId: 'album-1',
          chapterId: 'chapter-1',
          albumTitle: '测试漫画',
          chapterTitle: '第一话',
        },
      ],
      mode: 'chapter',
      useOriginal: true,
      compressionRatio: 1,
      editedPath: '/pdf/test.pdf',
      splitPages: 0,
    })
    await flushPromises()

    expect(mocks.showToast).toHaveBeenCalledWith(message, tone)
    wrapper.unmount()
  })
})
