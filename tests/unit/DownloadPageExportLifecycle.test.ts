/* eslint-disable vue/one-component-per-file -- test-only Ionic fixtures */
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, reactive } from 'vue'
import { beforeEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  ionViewWillEnter: undefined as (() => void) | undefined,
  refreshPdf: vi.fn(),
  cardRender: vi.fn(),
  getDownloadTasksPage: vi.fn(),
  clearDownloadTasks: vi.fn(),
  getLocalFileManagementState: vi.fn(),
  consumeLaunchRoute: vi.fn(),
  addLaunchRouteListener: vi.fn(),
  addDownloadProgressListener: vi.fn(),
  addStateInvalidatedListener: vi.fn(),
  checkFilesExist: vi.fn(),
  checkNotificationPermission: vi.fn(),
  exportBatch: vi.fn(),
  createAppAlert: vi.fn(),
  buildExportPlan: vi.fn(),
  showToast: vi.fn(),
  pdfMountCount: 0,
  downloadHandler: undefined as ((event: any) => void) | undefined,
  stateInvalidatedHandler: undefined as (() => void) | undefined,
  offlineSetAll: vi.fn(),
  offlineGetAll: vi.fn((): any[] => []),
  offlineGetCompletedGroups: vi.fn((): any[] => []),
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
    useBackButton: vi.fn(),
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
    IonSpinner: withSlot('IonSpinner', 'span'),
    IonToolbar: withSlot('IonToolbar'),
    onIonViewWillEnter: (hook: () => void) => {
      mocks.ionViewWillEnter = hook
    },
  }
})

  vi.mock('ionicons/icons', () => ({
  bookOutline: 'book',
  arrowDownOutline: 'arrow-down',
  arrowUpOutline: 'arrow-up',
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
  listOutline: 'list',
  chevronDownOutline: 'chevron-down',
  chevronUpOutline: 'chevron-up',
}))

vi.mock('@/services/AppAlertService', () => ({
  createAppAlert: mocks.createAppAlert,
}))

vi.mock('vue-router', () => ({
  useRoute: () => reactive(mocks.route),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    getDownloadTasksPage: mocks.getDownloadTasksPage,
    clearDownloadTasks: mocks.clearDownloadTasks,
    getLocalFileManagementState: mocks.getLocalFileManagementState,
    consumeLaunchRoute: mocks.consumeLaunchRoute,
    addLaunchRouteListener: mocks.addLaunchRouteListener,
    addDownloadProgressListener: mocks.addDownloadProgressListener,
    addStateInvalidatedListener: mocks.addStateInvalidatedListener,
    checkFilesExist: mocks.checkFilesExist,
    checkNotificationPermission: mocks.checkNotificationPermission,
    exportBatch: mocks.exportBatch,
  },
  sanitizeError: (_error: unknown, fallback: string) => fallback,
  showToast: mocks.showToast,
}))

vi.mock('@/services/ExportService', () => ({
  ExportService: {
    buildExportPlan: mocks.buildExportPlan,
    getExportPath: () => '/pdf',
    getExportFolder: () => ({ folderRef: 'folder:path:/pdf', displayPath: '/pdf' }),
  },
}))

vi.mock('@/services/OfflineDownloadService', () => ({
  OfflineDownloadService: {
    setAll: mocks.offlineSetAll,
    getAll: mocks.offlineGetAll,
    updateProgress: vi.fn(),
    updateStatus: vi.fn(),
    removeTask: vi.fn(),
    addTask: vi.fn(),
    getCompletedGroups: mocks.offlineGetCompletedGroups,
    setCompletedGroups: vi.fn(),
  },
}))

vi.mock('@/components/download/LocalFileManagementView.vue', () => ({
  default: defineComponent({
    name: 'LocalFileManagementView',
    props: { overlayTarget: { type: Object, required: true } },
    emits: ['selection-change'],
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
  default: defineComponent({
    name: 'DownloadTaskCard',
    props: { task: { type: Object, required: false, default: () => ({}) } },
    render() { mocks.cardRender(this.task); return null },
  }),
}))
vi.mock('@/components/download/ExportBottomSheet.vue', () => ({
  default: defineComponent({ name: 'ExportBottomSheet', render: () => null }),
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
    mocks.downloadHandler = undefined
    mocks.stateInvalidatedHandler = undefined
    mocks.route.query = { view: 'pdf' }
    mocks.getDownloadTasksPage.mockResolvedValue({ items: [], nextCursor: null, previousCursor: null, totalCount: 0, usedBytes: 0, availableBytes: 0, totalBytes: 0 })
    mocks.getLocalFileManagementState.mockResolvedValue({ recoveryState: 'ready' })
    mocks.consumeLaunchRoute.mockResolvedValue({})
    mocks.addLaunchRouteListener.mockResolvedValue({ remove: vi.fn() })
    mocks.addDownloadProgressListener.mockImplementation(async (handler: (event: any) => void) => {
      mocks.downloadHandler = handler
      return { remove: vi.fn() }
    })
    mocks.addStateInvalidatedListener.mockImplementation(async (handler: () => void) => {
      mocks.stateInvalidatedHandler = handler
      return { remove: vi.fn() }
    })
    mocks.offlineGetAll.mockReturnValue([])
    mocks.offlineGetCompletedGroups.mockReturnValue([])
    mocks.refreshPdf.mockResolvedValue(undefined)
    mocks.checkFilesExist.mockResolvedValue({ existing: [] })
    mocks.checkNotificationPermission.mockResolvedValue({ granted: true })
    mocks.buildExportPlan.mockReturnValue({ tasks: [{}], outputDisplayPaths: [] })
    mocks.createAppAlert.mockResolvedValue({
      present: vi.fn(),
      onDidDismiss: vi.fn().mockResolvedValue({}),
    })
  })

  const taskAt = (id: number) => ({
    taskId: 'task-' + id, albumId: 'album-' + id, chapterId: 'chapter-' + id,
    albumTitle: 'Album ' + id, chapterTitle: 'Chapter', coverUrl: '',
    status: 'downloading', createdAt: id, totalPages: 100, downloadedPages: 0,
  })
  const emptyPage = () => ({ items: [], nextCursor: null, previousCursor: null,
    totalCount: 0, usedBytes: 0, availableBytes: 1000, totalBytes: 2000 })
  const groupAt = (id: number) => ({ ...taskAt(id), chapters: [{ ...taskAt(id), status: 'completed' }],
    type: 'single', totalSize: 1, completedAt: id })

  test('下载列表开启 Ionic 滚动事件以支持自动分页', async () => {
    mocks.route.query = { view: 'downloads' }
    const wrapper = mount(DownloadPage)
    await flushPromises()

    const content = wrapper.findAllComponents({ name: 'IonContent' })[0]
    expect(content.attributes('scroll-events')).toBe('true')
    wrapper.unmount()
  })

  test('三页窗口双向往返使用整个窗口的两端游标且不重复', async () => {
    mocks.getDownloadTasksPage.mockImplementation(async ({ status, cursor }: any) => {
      if (status !== 'active') return emptyPage()
      const [direction, value] = (cursor || 'next:0').split(':')
      const first = direction === 'previous' ? Number(value) - 50 : Number(value) + 1
      return { ...emptyPage(), items: Array.from({ length: 50 }, (_, offset) => taskAt(first + offset)),
        totalCount: 250, nextCursor: first + 49 < 250 ? 'next:' + (first + 49) : null,
        previousCursor: first > 1 ? 'previous:' + first : null }
    })
    const wrapper = mount(DownloadPage)
    await flushPromises()
    const state = (wrapper.vm as any).$.setupState
    for (let i = 0; i < 3; i++) await state.loadGroup('active')
    const group = state.downloadGroupStates.active
    expect(group.items).toHaveLength(150)
    expect(group.previousCursor).toBe('previous:51')
    expect(group.nextCursor).toBe('next:200')
    await state.loadGroup('active', 'previous')
    expect(group.items.map((item: any) => item.taskId)).toEqual(
      Array.from({ length: 150 }, (_, i) => 'task-' + (i + 1)),
    )
    expect(group.nextCursor).toBe('next:150')
    await state.loadGroup('active')
    expect(new Set(group.items.map((item: any) => item.taskId)).size).toBe(150)
    expect(group.items[0].taskId).toBe('task-51')
    wrapper.unmount()
  })

  test('折叠组保留窗口和数量，重新进入可展开，排序才重置其窗口', async () => {
    mocks.getDownloadTasksPage.mockImplementation(async ({ status }: any) => status === 'completed'
      ? { ...emptyPage(), items: [groupAt(1)], totalCount: 1 } : emptyPage())
    const wrapper = mount(DownloadPage)
    await flushPromises()
    const state = (wrapper.vm as any).$.setupState
    const group = state.downloadGroupStates.completed
    const pages = group.pages
    state.toggleGroup('completed')
    await flushPromises()
    expect(wrapper.findAll('.section-content')).toHaveLength(2)
    mocks.ionViewWillEnter!()
    await flushPromises()
    expect(group.pages).toBe(pages)
    expect(group.totalCount).toBe(1)
    expect(wrapper.text()).not.toContain('暂无下载内容')
    state.toggleGroup('completed')
    await flushPromises()
    expect(mocks.getDownloadTasksPage.mock.calls.filter(([query]) => query.status === 'completed')).toHaveLength(1)
    state.toggleGroup('completed')
    state.toggleSortDirection()
    await flushPromises()
    expect(group.collapsed).toBe(true)
    expect(group.totalCount).toBe(1)
    expect(mocks.getDownloadTasksPage.mock.calls.filter(([query]) => query.status === 'completed')).toHaveLength(2)
    wrapper.unmount()
  })

  test('存储概览按大小显示单位并区分下载占用和可用空间', async () => {
    mocks.route.query = { view: 'downloads' }
    mocks.getDownloadTasksPage.mockImplementation(async ({ status }: any) => ({
      ...emptyPage(),
      items: status === 'active' ? [taskAt(1)] : [],
      totalCount: status === 'active' ? 1 : 0,
      usedBytes: 20 * 1024 * 1024,
      availableBytes: 50 * 1024 * 1024,
      totalBytes: 100 * 1024 * 1024,
    }))

    const wrapper = mount(DownloadPage)
    await flushPromises()

    expect(wrapper.text()).toContain('20 MB/ 100 MB')
    expect(wrapper.text()).toContain('下载占用 20 MB')
    expect(wrapper.text()).toContain('可用 50 MB')
    expect(wrapper.findAll('.storage-progress-download')).toHaveLength(1)
    expect(wrapper.findAll('.storage-progress-available')).toHaveLength(1)
    expect(wrapper.find('.storage-progress-download').attributes('style')).toContain('width: 28.57142857142857%')
    expect(wrapper.find('.storage-progress-available').attributes('style')).toContain('width: 71.42857142857143%')
    wrapper.unmount()
  })

  test('明确的状态失效会刷新折叠组，而不是保留过期计数', async () => {
    let count = 1
    mocks.getDownloadTasksPage.mockImplementation(async ({ status }: any) => status === 'completed'
      ? { ...emptyPage(), items: Array.from({ length: count }, (_, i) => groupAt(i + 1)), totalCount: count }
      : emptyPage())
    const wrapper = mount(DownloadPage)
    await flushPromises()
    const state = (wrapper.vm as any).$.setupState
    state.toggleGroup('completed')
    count = 2
    mocks.stateInvalidatedHandler?.()
    await flushPromises()
    expect(state.downloadGroupStates.completed.collapsed).toBe(true)
    expect(state.downloadGroupStates.completed.totalCount).toBe(2)
    expect(state.downloadGroupStates.completed.items).toHaveLength(2)
    wrapper.unmount()
  })

  test('无关活动进度不重建已完成对象', async () => {
    mocks.getDownloadTasksPage.mockImplementation(async ({ status }: any) => ({ ...emptyPage(),
      items: status === 'active' ? [taskAt(1)] : status === 'completed' ? [groupAt(2), groupAt(3)] : [],
      totalCount: status === 'active' ? 1 : status === 'completed' ? 2 : 0 }))
    const wrapper = mount(DownloadPage)
    await flushPromises()
    const state = (wrapper.vm as any).$.setupState
    const before = [...state.downloadGroupStates.completed.items]
    const completedRenders = () => mocks.cardRender.mock.calls.filter(
      ([entry]) => ['album-2', 'album-3'].includes(entry.albumId),
    ).length
    const rendersBefore = completedRenders()
    mocks.downloadHandler?.({ taskId: 'task-1', albumId: 'album-1', status: 'downloading',
      totalPages: 100, downloadedPages: 10 })
    await flushPromises()
    expect(state.downloadGroupStates.active.items[0].downloadedPages).toBe(10)
    expect(state.downloadGroupStates.completed.items[0]).toBe(before[0])
    expect(state.downloadGroupStates.completed.items[1]).toBe(before[1])
    expect(completedRenders()).toBe(rendersBefore)
    state.selectedTask = state.groupEntry(state.downloadGroupStates.completed.items[0])
    state.isActionMenuOpen = true
    await flushPromises()
    expect(completedRenders()).toBeGreaterThan(rendersBefore)
    wrapper.unmount()
  })

  test('有限缓存展示失败后仍能重试首屏，不追加旧快照', async () => {
    mocks.offlineGetAll.mockReturnValue([taskAt(9)])
    let fail = true
    mocks.getDownloadTasksPage.mockImplementation(async ({ status }: any) => {
      if (status === 'active' && fail) throw new Error('offline')
      return status === 'active' ? { ...emptyPage(), items: [taskAt(1)], totalCount: 1 } : emptyPage()
    })
    const wrapper = mount(DownloadPage)
    await flushPromises()
    const state = (wrapper.vm as any).$.setupState
    expect(state.downloadGroupStates.active.error).toBe(true)
    expect(state.downloadGroupStates.active.items[0].taskId).toBe('task-9')
    fail = false
    await state.loadGroup('active')
    expect(state.downloadGroupStates.active.error).toBe(false)
    expect(state.downloadGroupStates.active.items.map((item: any) => item.taskId)).toEqual(['task-1'])
    wrapper.unmount()
  })

  test('部分清理失败刷新实际剩余记录并提示失败数量', async () => {
    mocks.getDownloadTasksPage.mockImplementation(async ({ status }: any) => status === 'failed'
      ? { ...emptyPage(), items: [{ ...taskAt(1), status: 'failed' }], totalCount: 1 } : emptyPage())
    mocks.clearDownloadTasks.mockResolvedValue({ deletedCount: 1, failedCount: 1 })
    const wrapper = mount(DownloadPage)
    await flushPromises()
    const state = (wrapper.vm as any).$.setupState
    await state.clearFailed()
    expect(state.downloadGroupStates.failed.totalCount).toBe(1)
    expect(mocks.showToast).toHaveBeenCalledWith('1 个任务清理失败，已保留记录，可重试', 'medium')
    wrapper.unmount()
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

  test('导出覆盖层位于内容区外，多选时锁定主视图切换', async () => {
    mocks.route.query = { view: 'export' }
    const wrapper = mount(DownloadPage)
    await flushPromises()
    const manager = wrapper.findComponent({ name: 'LocalFileManagementView' })
    const target = manager.props('overlayTarget') as HTMLElement
    const state = (wrapper.vm as any).$.setupState

    expect(target).toBe(wrapper.get('.export-overlay-host').element)
    expect(wrapper.findAllComponents({ name: 'IonContent' })).toHaveLength(2)
    expect(
      wrapper.findAllComponents({ name: 'IonContent' }).some((content) =>
        content.element.contains(target),
      ),
    ).toBe(false)

    manager.vm.$emit('selection-change', true)
    await flushPromises()
    expect(wrapper.findAll('.tab-btn').every((button) =>
      button.attributes('disabled') !== undefined,
    )).toBe(true)
    state.selectMainView('downloads')
    expect(state.activeMainView).toBe('export')

    reactive(mocks.route).query = { view: 'downloads' }
    await flushPromises()
    expect(target.style.display).toBe('none')
    reactive(mocks.route).query = { view: 'export' }
    await flushPromises()
    expect(target.style.display).not.toBe('none')

    manager.vm.$emit('selection-change', false)
    await flushPromises()
    await wrapper.findAll('.tab-btn')[0].trigger('click')
    expect(state.activeMainView).toBe('downloads')
    expect(target.style.display).toBe('none')
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

  test('复用下载页面时处理旧 view=pdf 与新 view=export 链接', async () => {
    mocks.route.query = { view: 'downloads' }
    const wrapper = mount(DownloadPage)
    await flushPromises()
    expect(wrapper.findAll('.tab-btn')[1].attributes('aria-selected')).toBe('false')

    reactive(mocks.route).query = { view: 'pdf' }
    await flushPromises()
    expect(wrapper.findAll('.tab-btn')[1].attributes('aria-selected')).toBe('true')

    reactive(mocks.route).query = { view: 'export' }
    await wrapper.findAll('.tab-btn')[0].trigger('click')
    mocks.ionViewWillEnter!()
    await flushPromises()
    expect(wrapper.findAll('.tab-btn')[1].attributes('aria-selected')).toBe('true')
    wrapper.unmount()
  })

  test('先注册下载监听，再读取初始权威快照', async () => {
    const wrapper = mount(DownloadPage)
    await flushPromises()

    expect(mocks.addDownloadProgressListener.mock.invocationCallOrder[0]).toBeLessThan(
      mocks.getDownloadTasksPage.mock.invocationCallOrder[0],
    )
    wrapper.unmount()
  })

  test('下载快照请求期间收到事件时丢弃旧响应并串行补读', async () => {
    let finishInitial:
      | ((value: any) => void)
      | undefined
    const currentTask = {
      taskId: 'album_chapter',
      albumId: 'album',
      chapterId: 'chapter',
      albumTitle: '漫画',
      chapterTitle: '第一话',
      coverUrl: '',
      totalPages: 100,
      downloadedPages: 20,
      status: 'downloading',
      createdAt: 1,
    }
    const emptyPage = { items: [], nextCursor: null, previousCursor: null, totalCount: 0, usedBytes: 0, availableBytes: 1000, totalBytes: 2000 }
    mocks.getDownloadTasksPage.mockImplementation((options: { status: string }) =>
      options.status === 'active'
        ? { items: [currentTask], nextCursor: null, previousCursor: null, totalCount: 1, usedBytes: 20, availableBytes: 1000, totalBytes: 2000 }
        : emptyPage,
    )
    mocks.getDownloadTasksPage
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishInitial = resolve
          }),
      )

    const wrapper = mount(DownloadPage)
    await vi.waitFor(() => expect(mocks.getDownloadTasksPage).toHaveBeenCalled())
    mocks.downloadHandler?.({
      taskId: currentTask.taskId,
      downloadedPages: 20,
      totalPages: 100,
      status: 'downloading',
    })
    finishInitial?.({ items: [{ ...currentTask, downloadedPages: 10 }], nextCursor: null, previousCursor: null, totalCount: 1, usedBytes: 10, availableBytes: 1000, totalBytes: 2000 })
    await flushPromises()

    expect(mocks.getDownloadTasksPage.mock.calls.length).toBeGreaterThanOrEqual(2)
    expect(mocks.offlineSetAll).toHaveBeenCalledOnce()
    expect(mocks.offlineSetAll).toHaveBeenCalledWith(expect.arrayContaining([expect.objectContaining({ taskId: currentTask.taskId })]))
    wrapper.unmount()
  })

  test('窗口外的连续进度事件只触发一次活动列表刷新', async () => {
    const wrapper = mount(DownloadPage)
    await flushPromises()
    const initialCalls = mocks.getDownloadTasksPage.mock.calls.length

    mocks.downloadHandler?.({ taskId: 'outside-window', status: 'downloading' })
    mocks.downloadHandler?.({ taskId: 'outside-window', status: 'downloading' })
    await flushPromises()

    expect(mocks.getDownloadTasksPage.mock.calls.length).toBe(initialCalls + 1)
    expect(mocks.getDownloadTasksPage).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'active' }))
    wrapper.unmount()
  })

  test('下载列表请求失败时显示有限缓存快照', async () => {
    const cachedTask = {
      taskId: 'cached-task',
      albumId: 'album',
      chapterId: 'chapter',
      albumTitle: '缓存本子',
      chapterTitle: '缓存章节',
      coverUrl: '',
      totalPages: 1,
      downloadedPages: 0,
      status: 'queued',
      createdAt: 1,
    }
    mocks.offlineGetAll.mockReturnValue([cachedTask])
    mocks.getDownloadTasksPage.mockRejectedValue(new Error('backend unavailable'))

    const wrapper = mount(DownloadPage)
    await flushPromises()

    expect(wrapper.text()).toContain('下载中 (1)')
    expect(wrapper.findAllComponents({ name: 'DownloadTaskCard' })[0].props('task')).toMatchObject({
      taskId: 'cached-task',
      chapterTitle: '缓存章节',
    })
    wrapper.unmount()
  })

  test.each([
    {
      results: [{ accepted: true }, { accepted: true }],
      message: 'PDF 导出已开始，请查看通知',
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
    mocks.exportBatch.mockResolvedValueOnce({ tasks: results })
    const wrapper = mount(DownloadPage)
    await flushPromises()

    wrapper.findComponent({ name: 'ExportBottomSheet' }).vm.$emit('confirm', {
      format: 'pdf',
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

  test('仅重提发生 PDF_OUTPUT_EXISTS 的原始任务并允许覆盖', async () => {
    const firstTask = { displayPath: '/pdf/first.pdf' }
    const conflictTask = { displayPath: '/pdf/conflict.pdf' }
    mocks.buildExportPlan.mockReturnValueOnce({
      tasks: [firstTask, conflictTask],
      outputDisplayPaths: [firstTask.displayPath, conflictTask.displayPath],
    })
    mocks.exportBatch
      .mockResolvedValueOnce({
        tasks: [
          { accepted: true },
          {
            accepted: false,
            errorCode: 'PDF_OUTPUT_EXISTS',
            errorMessage: '目标已存在',
            displayPath: conflictTask.displayPath,
          },
        ],
      })
      .mockResolvedValueOnce({ tasks: [{ accepted: true }] })
    mocks.createAppAlert.mockImplementation(async (options: any) => {
      await options.buttons[1].handler()
      return { present: vi.fn(), onDidDismiss: vi.fn().mockResolvedValue({}) }
    })

    const wrapper = mount(DownloadPage)
    await flushPromises()
    wrapper.findComponent({ name: 'ExportBottomSheet' }).vm.$emit('confirm', {
      format: 'pdf',
      selectedChapters: [
        { albumId: 'album-1', chapterId: 'chapter-1', albumTitle: '测试漫画', chapterTitle: '第一话' },
        { albumId: 'album-1', chapterId: 'chapter-2', albumTitle: '测试漫画', chapterTitle: '第二话' },
      ],
      mode: 'chapter',
      useOriginal: true,
      compressionRatio: 1,
      editedPath: '/pdf/test.pdf',
      splitPages: 0,
    })
    await flushPromises()

    expect(mocks.createAppAlert).toHaveBeenCalledOnce()
    expect(mocks.exportBatch).toHaveBeenCalledTimes(2)
    expect(mocks.exportBatch.mock.calls[0][0]).toEqual([
      expect.objectContaining({ allowOverwrite: false }),
      expect.objectContaining({ allowOverwrite: false }),
    ])
    expect(mocks.exportBatch.mock.calls[1][0]).toEqual([
      expect.objectContaining({ displayPath: conflictTask.displayPath, allowOverwrite: true }),
    ])
    expect(mocks.showToast).toHaveBeenCalledWith('PDF 导出已开始，请查看通知', 'success')
    wrapper.unmount()
  })

  test('取消覆盖时不重提冲突任务', async () => {
    const task = { displayPath: '/pdf/conflict.pdf' }
    mocks.buildExportPlan.mockReturnValueOnce({
      tasks: [task],
      outputDisplayPaths: [task.displayPath],
    })
    mocks.exportBatch.mockResolvedValueOnce({
      tasks: [{
        accepted: false,
        errorCode: 'PDF_OUTPUT_EXISTS',
        displayPath: task.displayPath,
      }],
    })
    const wrapper = mount(DownloadPage)
    await flushPromises()
    wrapper.findComponent({ name: 'ExportBottomSheet' }).vm.$emit('confirm', {
      format: 'pdf',
      selectedChapters: [
        { albumId: 'album-1', chapterId: 'chapter-1', albumTitle: '测试漫画', chapterTitle: '第一话' },
      ],
      mode: 'chapter',
      useOriginal: true,
      compressionRatio: 1,
      editedPath: '/pdf/test.pdf',
      splitPages: 0,
    })
    await flushPromises()

    expect(mocks.createAppAlert).toHaveBeenCalledOnce()
    expect(mocks.exportBatch).toHaveBeenCalledOnce()
    expect(mocks.showToast).toHaveBeenCalledWith('检测到已有同名 PDF，已取消覆盖', 'medium')
    wrapper.unmount()
  })

  test('混合结果取消覆盖时同时报告已开始、取消覆盖和其他失败', async () => {
    const acceptedTask = { displayPath: '/pdf/accepted.pdf' }
    const conflictTask = { displayPath: '/pdf/conflict.pdf' }
    const rejectedTask = { displayPath: '/pdf/rejected.pdf' }
    mocks.buildExportPlan.mockReturnValueOnce({
      tasks: [acceptedTask, conflictTask, rejectedTask],
      outputDisplayPaths: [
        acceptedTask.displayPath,
        conflictTask.displayPath,
        rejectedTask.displayPath,
      ],
    })
    mocks.exportBatch.mockResolvedValueOnce({
      tasks: [
        { accepted: true },
        {
          accepted: false,
          errorCode: 'PDF_OUTPUT_EXISTS',
          displayPath: conflictTask.displayPath,
        },
        {
          accepted: false,
          errorCode: 'PDF_EXPORT_FAILED',
          errorMessage: '导出失败',
          displayPath: rejectedTask.displayPath,
        },
      ],
    })

    const wrapper = mount(DownloadPage)
    await flushPromises()
    wrapper.findComponent({ name: 'ExportBottomSheet' }).vm.$emit('confirm', {
      format: 'pdf',
      selectedChapters: [
        { albumId: 'album-1', chapterId: 'chapter-1', albumTitle: '测试漫画', chapterTitle: '第一话' },
      ],
      mode: 'chapter',
      useOriginal: true,
      compressionRatio: 1,
      editedPath: '/pdf/test.pdf',
      splitPages: 0,
    })
    await flushPromises()

    expect(mocks.exportBatch).toHaveBeenCalledOnce()
    expect(mocks.showToast).toHaveBeenCalledWith(
      '已开始 1 个，1 个已取消覆盖，另有 1 个失败',
      'medium',
    )
    wrapper.unmount()
  })
})
