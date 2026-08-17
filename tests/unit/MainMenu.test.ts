import {nextTick, ref} from 'vue'
import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, test, vi} from 'vitest'
import MainMenu from '@/components/menu/MainMenu.vue'
import {
  isMenuNavigation,
  leftMenuGestureEnabled,
  leftMenuOpen,
  rightMenuOpen,
} from '@/composables/useSideMenuState'

const mocks = vi.hoisted(() => ({
  gestureConfig: undefined as Record<string, any> | undefined,
  routerPush: vi.fn(),
  route: { path: '/home' },
  getDownloadTasks: vi.fn(),
  getPdfExportTasks: vi.fn(),
  addDownloadProgressListener: vi.fn(),
  addPdfExportProgressListener: vi.fn(),
  downloadHandler: undefined as ((event: any) => void) | undefined,
  pdfHandler: undefined as ((event: any) => void) | undefined,
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({push: mocks.routerPush}),
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    getDownloadTasks: mocks.getDownloadTasks,
    getPdfExportTasks: mocks.getPdfExportTasks,
    addDownloadProgressListener: mocks.addDownloadProgressListener,
    addPdfExportProgressListener: mocks.addPdfExportProgressListener,
  },
}))

vi.mock('@/composables/useAuth', () => ({
  useAuth: () => ({userInfo: ref(null), isLoggedIn: ref(false)}),
}))

vi.mock('@ionic/vue', async () => {
  const {defineComponent: makeComponent, h: makeH} = await import('vue')
  const withSlot = (name: string, tag = 'div') =>
    makeComponent({
      name,
      inheritAttrs: false,
      setup(_, {attrs, slots}) {
        return () => makeH(tag, attrs, slots.default?.())
      },
    })

  return {
    createGesture: vi.fn((config: Record<string, any>) => {
      mocks.gestureConfig = config
      return {destroy: vi.fn(), enable: vi.fn()}
    }),
    IonContent: withSlot('IonContent'),
    IonHeader: withSlot('IonHeader'),
    IonIcon: withSlot('IonIcon'),
    IonItem: withSlot('IonItem', 'button'),
    IonLabel: withSlot('IonLabel'),
    IonList: withSlot('IonList'),
    IonSpinner: withSlot('IonSpinner'),
  }
})

const mountMenu = () => {
  const content = document.createElement('main')
  content.id = 'main-content'
  document.body.appendChild(content)
  return mount(MainMenu, {props: {contentId: 'main-content'}})
}

const getGesture = () => {
  expect(mocks.gestureConfig).toBeDefined()
  return mocks.gestureConfig!
}

beforeEach(() => {
  leftMenuOpen.value = false
  leftMenuGestureEnabled.value = true
  rightMenuOpen.value = false
  isMenuNavigation.value = false
  mocks.gestureConfig = undefined
  mocks.routerPush.mockClear()
  mocks.route.path = '/home'
  mocks.getDownloadTasks.mockReset()
  mocks.getPdfExportTasks.mockReset()
  mocks.addDownloadProgressListener.mockReset()
  mocks.addPdfExportProgressListener.mockReset()
  mocks.downloadHandler = undefined
  mocks.pdfHandler = undefined
  mocks.getDownloadTasks.mockResolvedValue({ tasks: [] })
  mocks.getPdfExportTasks.mockResolvedValue({ tasks: [] })
  mocks.addDownloadProgressListener.mockImplementation(async (handler: (event: any) => void) => {
    mocks.downloadHandler = handler
    return { remove: vi.fn() }
  })
  mocks.addPdfExportProgressListener.mockImplementation(async (handler: (event: any) => void) => {
    mocks.pdfHandler = handler
    return { remove: vi.fn() }
  })
})

afterEach(() => {
  document.getElementById('main-content')?.remove()
  leftMenuOpen.value = false
  leftMenuGestureEnabled.value = true
  rightMenuOpen.value = false
  isMenuNavigation.value = false
})

const downloadTask = (downloadedPages = 0, totalPages = 100) => ({
  taskId: 'album_chapter',
  albumId: 'album',
  chapterId: 'chapter',
  albumTitle: '漫画',
  chapterTitle: '第一话',
  coverUrl: '',
  totalPages,
  downloadedPages,
  status: 'downloading',
  createdAt: 1,
})

const downloadEvent = (downloadedPages: number, totalPages = 100, status = 'downloading') => ({
  taskId: 'album_chapter',
  albumId: 'album',
  chapterId: 'chapter',
  downloadedPages,
  totalPages,
  status,
  speed: 0,
})

const pdfEvent = (
  currentPage: number,
  totalPages = 100,
  status = 'running',
  snapshotRevision = currentPage + 1,
) => ({
  exportId: 'export-1',
  batchId: 'batch-1',
  status,
  phase: status,
  currentPage,
  totalPages,
  currentVolume: 1,
  totalVolumes: 1,
  snapshotRevision,
})

const pdfTask = (currentPage = 0, totalPages = 100, status = 'running') => ({
  ...pdfEvent(currentPage, totalPages, status, 10),
  mode: 'chapter',
  albumId: 'album',
  albumTitle: '漫画',
  coverUrl: '',
  authors: '',
  displayTitle: '第一话',
  savePath: '/tmp/export.pdf',
  allowOverwrite: false,
  useOriginal: true,
  compressionRatio: 1,
  splitPages: 0,
  cancelRequested: false,
  createdAt: 1,
  updatedAt: 1,
})

describe('MainMenu 自定义左侧手势', () => {
  test('使用低识别阈值、严格横向角度并只在捕获后锁滚动', () => {
    const wrapper = mountMenu()
    const gesture = getGesture()

    expect(gesture.threshold).toBe(6)
    expect(gesture.maxAngle).toBe(30)
    expect(gesture.direction).toBe('x')
    expect(gesture.passive).toBe(true)
    expect(gesture.disableScroll).toBe(true)

    wrapper.unmount()
  })

  test('短距离或低速横滑达到低门槛后打开菜单', async () => {
    const wrapper = mountMenu()
    const gesture = getGesture()

    gesture.onStart()
    gesture.onMove({deltaX: 60})
    gesture.onEnd({velocityX: 0})
    await nextTick()

    expect(leftMenuOpen.value).toBe(true)
    expect(wrapper.find('.main-menu').classes()).toContain('interactive')

    wrapper.unmount()
  })

  test('快速轻扫可以用更小的移动距离打开菜单', async () => {
    const wrapper = mountMenu()
    const gesture = getGesture()

    gesture.onStart()
    gesture.onMove({deltaX: 8})
    gesture.onEnd({velocityX: 0.13})
    await nextTick()

    expect(leftMenuOpen.value).toBe(true)
    wrapper.unmount()
  })

  test('打开状态下向左移动较小幅度即可关闭', async () => {
    const wrapper = mountMenu()
    leftMenuOpen.value = true
    await nextTick()

    const gesture = getGesture()
    gesture.onStart()
    gesture.onMove({deltaX: -60})
    gesture.onEnd({velocityX: 0})
    await nextTick()

    expect(leftMenuOpen.value).toBe(false)
    wrapper.unmount()
  })

  test('详情页禁用全局开启手势，但打开后仍允许关闭', () => {
    const wrapper = mountMenu()
    const gesture = getGesture()

    leftMenuGestureEnabled.value = false
    expect(gesture.canStart({event: {target: document.body}})).toBe(false)

    leftMenuOpen.value = true
    expect(gesture.canStart({event: {target: document.body}})).toBe(true)
    wrapper.unmount()
  })

  test('右侧菜单打开或交互控件触摸时不启动左侧栏', () => {
    const wrapper = mountMenu()
    const gesture = getGesture()
    const range = document.createElement('input')

    rightMenuOpen.value = true
    expect(gesture.canStart({event: {target: document.body}})).toBe(false)
    rightMenuOpen.value = false
    expect(gesture.canStart({event: {target: range}})).toBe(false)

    wrapper.unmount()
  })
})

describe('MainMenu 任务进度', () => {
  test('初始快照显示下载页数进度', async () => {
    mocks.getDownloadTasks.mockResolvedValue({ tasks: [downloadTask(40, 100)] })
    const wrapper = mountMenu()
    await flushPromises()

    expect(wrapper.find('.task-progress-copy').text()).toContain('下载40%')
    expect(wrapper.find('.task-progress-background').attributes('slot')).toBe('start')
    wrapper.unmount()
  })

  test('初始化查询期间收到的实时事件不会被旧快照覆盖', async () => {
    let resolveSnapshot: ((value: { tasks: ReturnType<typeof downloadTask>[] }) => void) | undefined
    mocks.getDownloadTasks.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveSnapshot = resolve
      }),
    )
    const wrapper = mountMenu()
    await vi.waitFor(() => expect(mocks.getDownloadTasks).toHaveBeenCalled())
    mocks.downloadHandler?.(downloadEvent(20))
    resolveSnapshot?.({ tasks: [downloadTask(10, 100)] })
    await flushPromises()

    expect(wrapper.find('.task-progress-copy').text()).toContain('下载20%')
    wrapper.unmount()
  })

  test('初始化查询期间收到的下载终态事件不会重新显示旧快照', async () => {
    let resolveSnapshot: ((value: { tasks: ReturnType<typeof downloadTask>[] }) => void) | undefined
    mocks.getDownloadTasks.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveSnapshot = resolve
      }),
    )
    const wrapper = mountMenu()
    await vi.waitFor(() => expect(mocks.getDownloadTasks).toHaveBeenCalled())
    mocks.downloadHandler?.(downloadEvent(100, 100, 'completed'))
    resolveSnapshot?.({ tasks: [downloadTask(10, 100)] })
    await flushPromises()

    expect(wrapper.find('.task-progress-copy').exists()).toBe(false)
    wrapper.unmount()
  })

  test('选中下载菜单项时隐藏进度背景和进度文字', async () => {
    mocks.route.path = '/download'
    mocks.getDownloadTasks.mockResolvedValue({ tasks: [downloadTask(40, 100)] })
    const wrapper = mountMenu()
    await flushPromises()

    expect(wrapper.find('.task-progress-background').exists()).toBe(false)
    expect(wrapper.find('.task-progress-copy').exists()).toBe(false)
    wrapper.unmount()
  })

  test('下载和 PDF 同时进行时显示两行百分比', async () => {
    const wrapper = mountMenu()
    await flushPromises()
    mocks.downloadHandler?.(downloadEvent(40))
    mocks.pdfHandler?.(pdfEvent(20))
    await nextTick()

    const progressText = wrapper.find('.task-progress-copy').text()
    expect(progressText).toContain('下载40%')
    expect(progressText).toContain('PDF20%')
    expect(wrapper.findAll('.task-progress-band')).toHaveLength(2)
    wrapper.unmount()
  })

  test('下载和 PDF 的真实进度达到 100% 后用加载动画替换百分比', async () => {
    const wrapper = mountMenu()
    await flushPromises()
    mocks.downloadHandler?.(downloadEvent(99))
    mocks.pdfHandler?.(pdfEvent(99))
    await nextTick()

    expect(wrapper.find('.task-progress-copy').text()).toContain('下载99%')
    expect(wrapper.find('.task-progress-copy').text()).toContain('PDF99%')
    expect(wrapper.findAll('.task-progress-spinner')).toHaveLength(0)

    mocks.downloadHandler?.(downloadEvent(100))
    mocks.pdfHandler?.(pdfEvent(100))
    await nextTick()

    expect(wrapper.findAll('.task-progress-spinner')).toHaveLength(2)
    expect(wrapper.find('.task-progress-copy').text()).not.toContain('100%')
    wrapper.unmount()
  })

  test('PDF 初始化按 queued、running、cancelling 分别完整分页查询', async () => {
    mocks.getPdfExportTasks.mockImplementation(
      async ({ status, cursor }: { status: string; cursor?: string }) => {
        if (status === 'queued' && !cursor) {
          return { tasks: [pdfTask(10, 100, 'queued')], nextCursor: 'queued-next' }
        }
        if (status === 'queued' && cursor === 'queued-next') {
          return { tasks: [pdfTask(20, 100, 'queued')] }
        }
        if (status === 'running') return { tasks: [pdfTask(30, 100, 'running')] }
        return { tasks: [pdfTask(40, 100, 'cancelling')] }
      },
    )
    const wrapper = mountMenu()
    await flushPromises()

    expect(mocks.getPdfExportTasks.mock.calls.map(([options]) => options)).toEqual([
      { status: 'queued', cursor: undefined, limit: 100 },
      { status: 'queued', cursor: 'queued-next', limit: 100 },
      { status: 'running', cursor: undefined, limit: 100 },
      { status: 'cancelling', cursor: undefined, limit: 100 },
    ])
    expect(wrapper.find('.task-progress-copy').text()).toContain('PDF40%')
    wrapper.unmount()
  })

  test('初始化查询期间收到的 PDF 终态事件不会重新显示旧快照', async () => {
    let resolveSnapshot:
      | ((value: { tasks: ReturnType<typeof pdfTask>[] }) => void)
      | undefined
    mocks.getPdfExportTasks.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveSnapshot = resolve
      }),
    )
    const wrapper = mountMenu()
    await vi.waitFor(() => expect(mocks.getPdfExportTasks).toHaveBeenCalled())
    mocks.pdfHandler?.(pdfEvent(100, 100, 'completed', 11))
    resolveSnapshot?.({ tasks: [pdfTask(10, 100, 'running')] })
    await flushPromises()

    expect(wrapper.find('.task-progress-copy').exists()).toBe(false)
    wrapper.unmount()
  })

  test('一个任务完成后，另一个进度带恢复占满下载项', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = mountMenu()
      await flushPromises()
      mocks.downloadHandler?.(downloadEvent(40))
      mocks.pdfHandler?.(pdfEvent(20))
      await nextTick()

      expect(wrapper.find('.download-progress-band').attributes('style')).toContain('height: 50%')
      mocks.pdfHandler?.(pdfEvent(100, 100, 'completed', 101))
      await nextTick()
      vi.advanceTimersByTime(221)
      await nextTick()

      expect(wrapper.find('.pdf-progress-band').exists()).toBe(false)
      expect(wrapper.find('.download-progress-band').attributes('style')).toContain('height: 100%')
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })
})
