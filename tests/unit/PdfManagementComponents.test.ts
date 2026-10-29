/* eslint-disable vue/one-component-per-file -- test-only Ionic fixtures */
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import type { LocalFileRecord, ExportTaskRecord } from '@/services/JmcomicTypes'
import { RuntimeError } from '@/runtime/errors'

const mocks = vi.hoisted(() => ({
  getLocalFiles: vi.fn(),
  refreshLocalFileAvailability: vi.fn(),
  getDownloadTasks: vi.fn(),
  getDownloadedResourceKeys: vi.fn(),
  getExportTasks: vi.fn(),
  getLocalFileManagementState: vi.fn(),
  getOfflineFolders: vi.fn(),
  getExportTask: vi.fn(),
  addExportProgressListener: vi.fn(),
  addStateInvalidatedListener: vi.fn(),
  inspectLocalFileForDeletion: vi.fn(),
  verifyLocalFile: vi.fn(),
  openLocalFile: vi.fn(),
  openLocalFileFolder: vi.fn(),
  deleteExportTask: vi.fn(),
  cancelExport: vi.fn(),
  retryExport: vi.fn(),
  pickFolder: vi.fn(),
  scanAndParse: vi.fn(),
  alertCreate: vi.fn(),
  routerPush: vi.fn(),
  showToast: vi.fn(),
  pdfProgressHandler: undefined as ((event: any) => void) | undefined,
  stateInvalidatedHandler: undefined as (() => void) | undefined,
}))

vi.mock('@ionic/vue', () => ({
  useBackButton: vi.fn(),
  IonContent: defineComponent({
    name: 'IonContent',
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  }),
  IonIcon: defineComponent({
    name: 'IonIcon',
    setup:
      (_, { attrs }) =>
      () =>
        h('span', attrs),
  }),
  IonPopover: defineComponent({
    name: 'IonPopover',
    inheritAttrs: false,
    setup:
      (_, { attrs, slots }) =>
      () =>
        h('div', attrs, slots.default?.()),
  }),
  IonSpinner: defineComponent({
    name: 'IonSpinner',
    setup: () => () => h('span', { class: 'ion-spinner' }),
  }),
  alertController: { create: mocks.alertCreate },
}))
vi.mock('@/services/AppAlertService', () => ({ createAppAlert: mocks.alertCreate }))
vi.mock('ionicons/icons', () => ({
  bookOutline: 'book',
  checkmarkCircleOutline: 'check',
  chevronDownOutline: 'chevron-down',
  closeCircleOutline: 'cancel',
  cloudUploadOutline: 'upload',
  copyOutline: 'copy',
  documentOutline: 'document',
  ellipsisVertical: 'more',
  folderOpenOutline: 'folder-open',
  imagesOutline: 'images',
  informationCircleOutline: 'information',
  listOutline: 'list',
  refreshOutline: 'refresh',
  removeCircleOutline: 'remove',
  trashOutline: 'trash',
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.routerPush }) }))
vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    getLocalFiles: mocks.getLocalFiles,
    refreshLocalFileAvailability: mocks.refreshLocalFileAvailability,
    getDownloadTasks: mocks.getDownloadTasks,
    getDownloadedResourceKeys: mocks.getDownloadedResourceKeys,
    getExportTasks: mocks.getExportTasks,
    getLocalFileManagementState: mocks.getLocalFileManagementState,
    acknowledgeLocalFileDatabaseReset: vi.fn(),
    getOfflineFolders: mocks.getOfflineFolders,
    getExportTask: mocks.getExportTask,
    addExportProgressListener: mocks.addExportProgressListener,
    addStateInvalidatedListener: mocks.addStateInvalidatedListener,
    inspectLocalFileForDeletion: mocks.inspectLocalFileForDeletion,
    openLocalFile: mocks.openLocalFile,
    openLocalFileFolder: mocks.openLocalFileFolder,
    removeLocalFileFromLibrary: vi.fn(),
    deleteLocalFile: vi.fn(),
    verifyLocalFile: mocks.verifyLocalFile,
    cancelExport: mocks.cancelExport,
    retryExport: mocks.retryExport,
    deleteExportTask: mocks.deleteExportTask,
    pickFolder: mocks.pickFolder,
  },
  sanitizeError: (error: unknown, fallback: string) =>
    error instanceof RuntimeError ? error.message : fallback,
  showToast: mocks.showToast,
}))
vi.mock('@/services/LocalFileImportService', () => ({
  LocalFileImportService: { scanAndParse: mocks.scanAndParse },
}))

import ExportTaskCard from '@/components/download/ExportTaskCard.vue'
import LocalFileCard from '@/components/download/LocalFileCard.vue'
import LocalFileManagementView from '@/components/download/LocalFileManagementView.vue'

function mountManagement(options: {
  attachTo?: HTMLElement
  props?: { initialView?: 'files' | 'tasks'; overlayTarget?: HTMLElement }
} = {}) {
  return mount(LocalFileManagementView, {
    ...options,
    props: {
      ...options.props,
      overlayTarget: options.props?.overlayTarget ?? document.createElement('div'),
    },
  })
}

const file: LocalFileRecord = {
  id: 1,
  format: 'pdf',
  fileRef: 'content://provider/current.pdf' as LocalFileRecord['fileRef'],
  displayPath: 'content://provider/current.pdf',
  fileName: 'current.pdf',
  sourceType: 'imported',
  ownership: 'external_reference',
  chapterLinkStatus: 'resolved',
  albumId: 'album-1',
  albumTitle: '测试漫画',
  coverUrl: '',
  authors: '',
  chapterId: 'chapter-1',
  chapterTitle: '第一话',
  chapterSortOrder: 1,
  chapters: [
    {
      sequence: 0,
      albumId: 'album-1',
      chapterId: 'chapter-1',
      chapterTitle: '第一话',
      sortOrder: 1,
      startPage: 1,
      endPage: 12,
      pageCount: 12,
    },
  ],
  createdAt: 1,
  fileSize: 1024,
  pageCount: 12,
  availability: 'available',
  verificationStatus: 'valid',
  updatedAt: 1,
}
const task = (status: ExportTaskRecord['status']): ExportTaskRecord => ({
  exportId: 'export-1',
  batchId: 'batch-1',
  format: 'pdf',
  mode: 'chapter',
  albumId: 'album-1',
  albumTitle: '测试漫画',
  coverUrl: '',
  authors: '',
  chapterId: 'chapter-1',
  displayTitle: '第一话',
  displayPath: '/pdf/one.pdf',
  allowOverwrite: false,
  useOriginal: true,
  compressionRatio: 1,
  splitPages: 0,
  status,
  phase: status,
  currentPage: 0,
  totalPages: 12,
  currentVolume: 0,
  totalVolumes: 1,
  snapshotRevision: 1,
  cancelRequested: false,
  createdAt: 1,
  updatedAt: 1,
})

beforeEach(() => {
  vi.clearAllMocks()
  // jsdom 不提供 IntersectionObserver，列表自动加载测试使用最小可控替身。
  vi.stubGlobal('IntersectionObserver', class {
    observe() {}
    disconnect() {}
    unobserve() {}
  })
  mocks.getLocalFiles.mockResolvedValue({ files: [file], nextCursor: null })
  mocks.refreshLocalFileAvailability.mockResolvedValue({ files: [file] })
  mocks.getDownloadedResourceKeys.mockResolvedValue({ keys: ['album-1|chapter-1'] })
  mocks.getExportTasks.mockResolvedValue({ tasks: [task('completed')], nextCursor: null })
  mocks.getLocalFileManagementState.mockResolvedValue({ recoveryState: 'ready' })
  mocks.getOfflineFolders.mockResolvedValue({ folders: [] })
  mocks.getExportTask.mockResolvedValue(task('completed'))
  mocks.pdfProgressHandler = undefined
  mocks.stateInvalidatedHandler = undefined
  mocks.addExportProgressListener.mockImplementation(async (handler: (event: any) => void) => {
    mocks.pdfProgressHandler = handler
    return { remove: vi.fn() }
  })
  mocks.addStateInvalidatedListener.mockImplementation(async (handler: () => void) => {
    mocks.stateInvalidatedHandler = handler
    return { remove: vi.fn() }
  })
  mocks.inspectLocalFileForDeletion.mockResolvedValue(file)
  mocks.verifyLocalFile.mockResolvedValue(file)
  mocks.pickFolder.mockResolvedValue(null)
  mocks.scanAndParse.mockResolvedValue(undefined)
  mocks.alertCreate.mockResolvedValue({ present: vi.fn() })
})

describe('LocalFileCard', () => {
  test('使用独立的打开和更多按钮', () => {
    const wrapper = mount(LocalFileCard, {
      props: { file, hasImageResource: false, readable: true },
    })
    expect(wrapper.get('article').findAll('button')).toHaveLength(2)
    expect(wrapper.get('button[aria-label="阅读 PDF"]')).toBeTruthy()
    expect(wrapper.get('button[aria-label="更多操作"]')).toBeTruthy()
    expect(wrapper.get('.meta-row').text()).toMatch(/可用1\.0 KB$/)
  })
})

describe('ExportTaskCard', () => {
  test('取消中不再显示取消按钮', () => {
    const wrapper = mount(ExportTaskCard, { props: { task: task('cancelling') } })
    expect(wrapper.find('button[aria-label="取消 PDF 导出"]').exists()).toBe(false)
  })

  test('完成任务只提供删除记录操作', () => {
    const wrapper = mount(ExportTaskCard, { props: { task: task('completed') } })
    expect(wrapper.findAll('button')).toHaveLength(1)
    expect(wrapper.find('button[aria-label="删除 PDF 导出任务记录"]').exists()).toBe(true)
  })
})

describe('LocalFileManagementView', () => {
  test.each(['files', 'tasks'] as const)('%s 多选栏和进度层挂到页面目标，退出或卸载后移除', async (initialView) => {
    const target = document.createElement('div')
    const wrapper = mountManagement({ props: { initialView, overlayTarget: target } })
    await flushPromises()
    const state = (wrapper.vm as any).$.setupState
    const selectButton = wrapper.findAll('.manager-actions button').find((button) =>
      button.text() === '多选',
    )!

    await selectButton.trigger('click')
    await flushPromises()
    expect(target.querySelector('.multi-select-info')).not.toBeNull()
    expect(target.querySelector('.multi-select-actions')).not.toBeNull()
    expect(wrapper.find('.multi-select-overlay').exists()).toBe(false)
    expect(wrapper.emitted('selection-change')?.slice(-1)).toEqual([[true]])

    state.managementSelectionExecuting = true
    await flushPromises()
    expect(target.querySelector('.batch-progress-overlay')).not.toBeNull()
    expect(wrapper.find('.batch-progress-overlay').exists()).toBe(false)

    state.managementSelectionExecuting = false
    await flushPromises()
    target.querySelector<HTMLButtonElement>('.selection-toolbar > button')!.click()
    await flushPromises()
    expect(target.querySelector('.multi-select-overlay')).toBeNull()
    expect(target.querySelector('.batch-progress-overlay')).toBeNull()
    expect(wrapper.emitted('selection-change')?.slice(-1)).toEqual([[false]])

    await selectButton.trigger('click')
    await flushPromises()
    expect(target.querySelector('.multi-select-info')).not.toBeNull()
    wrapper.unmount()
    expect(target.querySelector('.multi-select-overlay')).toBeNull()
  })

  test('先显示卡片并在后台校验当前页', async () => {
    let finishRefresh: ((value: { files: LocalFileRecord[] }) => void) | undefined
    mocks.refreshLocalFileAvailability.mockReturnValue(
      new Promise((resolve) => {
        finishRefresh = resolve
      }),
    )

    const wrapper = mountManagement()
    await flushPromises()

    expect(wrapper.text()).toContain('测试漫画')
    expect(wrapper.get('.status').text()).toContain('校验中')

    finishRefresh?.({ files: [{ ...file, availability: 'missing' }] })
    await flushPromises()
    expect(wrapper.get('.status').text()).toBe('文件缺失')
    wrapper.unmount()
  })

  test('同章节存在已完成下载时显示图片和 PDF 资源', async () => {
    const wrapper = mountManagement()
    await flushPromises()

    expect(wrapper.get('.resource-icons').attributes('aria-label')).toBe('图片和 PDF')
    wrapper.unmount()
  })

  test('文件筛选默认排除 ZIP，ZIP 独立筛选且隐藏 PDF/CBZ 选项', async () => {
    const wrapper = mountManagement()
    await flushPromises()

    expect(mocks.getLocalFiles).toHaveBeenCalledWith(
      expect.objectContaining({ formats: ['pdf', 'cbz'], sourceType: undefined }),
    )
    expect(wrapper.find('[aria-label="文件格式筛选"]').exists()).toBe(true)
    expect(
      wrapper
        .findAll('[aria-label="文件格式筛选"] button')
        .find((button) => button.text() === '全部格式')
        ?.classes(),
    ).toContain('selected')

    const sourceButtons = wrapper.findAll('.file-filters .filter-buttons button')
    await sourceButtons[3].trigger('click')
    await flushPromises()

    expect(mocks.getLocalFiles).toHaveBeenLastCalledWith(
      expect.objectContaining({ formats: ['zip'], sourceType: 'exported' }),
    )
    expect(wrapper.find('[aria-label="文件格式筛选"]').exists()).toBe(false)
    wrapper.unmount()
  })

  test('格式选项分别筛选 CBZ 文件和 ZIP 任务', async () => {
    const wrapper = mountManagement({ attachTo: document.body })
    await flushPromises()

    const fileFormatButtons = wrapper.findAll('.file-filters [aria-label="文件格式筛选"] button')
    const cbzOption = fileFormatButtons.find((option) => option.text() === 'CBZ')
    expect(cbzOption).toBeDefined()
    await cbzOption!.trigger('click')
    expect(cbzOption!.classes()).toContain('selected')
    await flushPromises()
    expect(mocks.getLocalFiles).toHaveBeenLastCalledWith(
      expect.objectContaining({ formats: ['cbz'] }),
    )

    await wrapper.findAll('.subtabs button')[1].trigger('click')
    expect(wrapper.text().indexOf('任务状态')).toBeLessThan(wrapper.text().indexOf('任务格式'))
    const taskFormatButtons = wrapper.findAll(
      '[aria-label="导出任务格式筛选"] button',
    )
    const zipOption = taskFormatButtons.find((option) => option.text() === 'ZIP')
    expect(zipOption).toBeDefined()
    await zipOption!.trigger('click')
    expect(zipOption!.classes()).toContain('selected')
    await flushPromises()
    expect(mocks.getExportTasks).toHaveBeenLastCalledWith(
      expect.objectContaining({ format: 'zip' }),
    )
    wrapper.unmount()
  })

  test('ZIP 文件卡片支持校验但不提供阅读操作', async () => {
    const zipFile: LocalFileRecord = {
      ...file,
      format: 'zip',
      fileName: 'archive.zip',
      displayPath: '/exports/archive.zip',
      sourceType: 'exported',
      ownership: 'app_created',
    }
    mocks.getLocalFiles.mockResolvedValue({ files: [zipFile], nextCursor: null })
    const wrapper = mountManagement()
    await flushPromises()

    const infoButton = wrapper.get<HTMLButtonElement>('.open-btn')
    expect(infoButton.attributes('disabled')).toBeDefined()
    await infoButton.trigger('click')
    expect(mocks.routerPush).not.toHaveBeenCalled()

    await wrapper.get('button[aria-label="更多操作"]').trigger('click')
    expect(
      Array.from(document.body.querySelectorAll('.card-menu-item')).map(
        (button) => button.textContent,
      ),
    ).toEqual(['进入详情页', '校验', '复制路径', '打开文件夹', '移除', '删除', '多选'])
    wrapper.unmount()
  })

  test('物理删除前刷新资源并展示完整定位符和路径复用警告', async () => {
    const wrapper = mountManagement()
    await flushPromises()
    await wrapper.get('button[aria-label="更多操作"]').trigger('click')
    document.body.querySelector<HTMLButtonElement>('.card-menu-item--danger')?.click()
    await flushPromises()
    await flushPromises()

    expect(mocks.inspectLocalFileForDeletion).toHaveBeenCalledWith(1)
    expect(mocks.alertCreate).toHaveBeenCalledWith(
      expect.objectContaining({
        header: '确认删除实际 PDF 文件',
        message: expect.stringMatching(
          /content:\/\/provider\/current\.pdf[\s\S]*替换后的当前文件也会被删除/,
        ),
      }),
    )
    wrapper.unmount()
  })

  test('更多菜单按下载页样式提供八项文件操作', async () => {
    const wrapper = mountManagement()
    await flushPromises()
    await wrapper.get('button[aria-label="更多操作"]').trigger('click')

    expect(
      Array.from(document.body.querySelectorAll('.card-menu-item')).map(
        (button) => button.textContent,
      ),
    ).toEqual(['阅读', '进入详情页', '校验', '复制路径', '打开文件夹', '移除', '删除', '多选'])
    wrapper.unmount()
  })

  test('卡片和菜单阅读操作均进入应用内 PDF 阅读器', async () => {
    const expectedRoute = {
      path: '/pdf-reader',
      query: {
        fileRef: 'content://provider/current.pdf',
        title: 'current.pdf',
        albumId: 'album-1',
        albumTitle: '测试漫画',
        authors: '',
        coverUrl: '',
        fileId: '1',
        readingContext: 'file',
        chapterId: 'chapter-1',
        chapterTitle: '第一话',
        chapterStartPage: '1',
        chapterPageCount: '12',
      },
    }
    const wrapper = mountManagement()
    await flushPromises()

    await wrapper.get('button[aria-label="阅读 PDF"]').trigger('click')
    expect(mocks.routerPush).toHaveBeenLastCalledWith(expectedRoute)

    await wrapper.get('button[aria-label="更多操作"]').trigger('click')
    await document.body.querySelectorAll<HTMLButtonElement>('.card-menu-item')[0].click()
    await flushPromises()
    expect(mocks.routerPush).toHaveBeenLastCalledWith(expectedRoute)
    expect(mocks.routerPush).toHaveBeenCalledTimes(2)
    expect(mocks.openLocalFile).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  test('手动校验显示进行中状态并提示校验结果', async () => {
    let finishVerification: ((value: LocalFileRecord) => void) | undefined
    mocks.verifyLocalFile.mockReturnValue(
      new Promise((resolve) => {
        finishVerification = resolve
      }),
    )
    const wrapper = mountManagement()
    await flushPromises()

    await wrapper.get('button[aria-label="更多操作"]').trigger('click')
    document.body.querySelectorAll<HTMLButtonElement>('.card-menu-item')[2].click()
    await flushPromises()

    expect(wrapper.get('.status').text()).toContain('校验中')
    finishVerification?.({ ...file, availability: 'missing' })
    await flushPromises()

    expect(wrapper.get('.status').text()).toBe('文件缺失')
    expect(mocks.showToast).toHaveBeenCalledWith('校验完成：文件缺失', 'medium')
    wrapper.unmount()
  })

  test('详情和打开文件夹操作调用对应能力', async () => {
    const wrapper = mountManagement()
    await flushPromises()

    await wrapper.get('button[aria-label="更多操作"]').trigger('click')
    document.body.querySelectorAll<HTMLButtonElement>('.card-menu-item')[1].click()
    await flushPromises()
    expect(mocks.routerPush).toHaveBeenCalledWith('/album/album-1')

    await wrapper.get('button[aria-label="更多操作"]').trigger('click')
    document.body.querySelectorAll<HTMLButtonElement>('.card-menu-item')[4].click()
    await flushPromises()
    expect(mocks.openLocalFileFolder).toHaveBeenCalledWith('content://provider/current.pdf')
    wrapper.unmount()
  })

  test('删除任务记录的确认不再请求影响 token', async () => {
    const wrapper = mountManagement({ props: { initialView: 'tasks' } })
    await flushPromises()
    await wrapper.get('button[aria-label="删除 PDF 导出任务记录"]').trigger('click')

    expect(mocks.alertCreate).toHaveBeenCalledWith(
      expect.objectContaining({
        header: '确认删除任务记录',
        message: expect.stringContaining('最终 PDF 文件不会被删除'),
      }),
    )
    wrapper.unmount()
  })

  test('较早的文件筛选响应不会覆盖较新的结果', async () => {
    let finishInitial: ((value: { files: LocalFileRecord[]; nextCursor: null }) => void) | undefined
    const newerFile = { ...file, id: 2, fileName: 'newer.pdf', albumTitle: '新筛选结果' }
    mocks.getLocalFiles
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishInitial = resolve
          }),
      )
      .mockResolvedValueOnce({ files: [newerFile], nextCursor: null })
    mocks.refreshLocalFileAvailability.mockResolvedValue({ files: [] })

    const wrapper = mountManagement()
    await flushPromises()
    await wrapper.findAll('.filter-buttons button')[1].trigger('click')
    await flushPromises()
    finishInitial?.({ files: [file], nextCursor: null })
    await flushPromises()

    expect(wrapper.text()).toContain('新筛选结果')
    expect(wrapper.text()).not.toContain('测试漫画')
    wrapper.unmount()
  })

  test('较早的任务筛选响应不会覆盖较新的结果', async () => {
    let finishInitial:
      | ((value: { tasks: ExportTaskRecord[]; nextCursor: null }) => void)
      | undefined
    const oldTask = { ...task('completed'), displayTitle: '旧任务' }
    const newerTask = {
      ...task('failed'),
      exportId: 'export-2',
      displayTitle: '新筛选任务',
    }
    mocks.getExportTasks
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishInitial = resolve
          }),
      )
      .mockResolvedValueOnce({ tasks: [newerTask], nextCursor: null })

    const wrapper = mountManagement({ props: { initialView: 'tasks' } })
    await flushPromises()
    await wrapper.findAll('.task-filter-buttons button')[5].trigger('click')
    await flushPromises()
    finishInitial?.({ tasks: [oldTask], nextCursor: null })
    await flushPromises()

    expect(wrapper.text()).toContain('新筛选任务')
    expect(wrapper.text()).not.toContain('旧任务')
    wrapper.unmount()
  })

  test('任务查询期间收到事件时丢弃旧响应并串行补读', async () => {
    let finishInitial:
      | ((value: { tasks: ExportTaskRecord[]; nextCursor: null }) => void)
      | undefined
    const oldTask = { ...task('running'), currentPage: 1, snapshotRevision: 1 }
    const currentTask = { ...task('running'), currentPage: 8, snapshotRevision: 3 }
    mocks.getExportTasks
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishInitial = resolve
          }),
      )
      .mockResolvedValueOnce({ tasks: [currentTask], nextCursor: null })

    const wrapper = mountManagement({ props: { initialView: 'tasks' } })
    await vi.waitFor(() => expect(mocks.getExportTasks).toHaveBeenCalledOnce())
    mocks.pdfProgressHandler?.({
      ...currentTask,
      currentPage: 6,
      snapshotRevision: 2,
    })
    finishInitial?.({ tasks: [oldTask], nextCursor: null })
    await flushPromises()

    expect(mocks.getExportTasks).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('8/12 页')
    wrapper.unmount()
  })

  test('事件流重连后刷新管理状态、筛选页和下载资源索引', async () => {
    const wrapper = mountManagement()
    await flushPromises()
    expect(wrapper.text()).toContain('测试漫画')
    expect(wrapper.get('.resource-icons').attributes('aria-label')).toBe('图片和 PDF')

    const reloadedFile = { ...file, id: 2, albumTitle: '重连后的文件' }
    mocks.getLocalFiles.mockResolvedValue({ files: [reloadedFile], nextCursor: null })
    mocks.refreshLocalFileAvailability.mockResolvedValue({ files: [reloadedFile] })
    mocks.getExportTasks.mockResolvedValue({ tasks: [], nextCursor: null })
  mocks.getDownloadedResourceKeys.mockResolvedValue({ keys: [] })
    mocks.stateInvalidatedHandler?.()
    await flushPromises()

    expect(mocks.getLocalFileManagementState).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('重连后的文件')
    expect(wrapper.text()).not.toContain('测试漫画')
    expect(wrapper.get('.resource-icons').attributes('aria-label')).toBe('PDF')
    wrapper.unmount()
  })

  test('卸载后才完成注册的进度监听会立即移除', async () => {
    let finishRegistration: ((value: { remove: () => Promise<void> }) => void) | undefined
    const remove = vi.fn().mockResolvedValue(undefined)
    mocks.addExportProgressListener.mockReturnValue(
      new Promise((resolve) => {
        finishRegistration = resolve
      }),
    )

    const wrapper = mountManagement()
    await flushPromises()
    expect(mocks.addExportProgressListener).toHaveBeenCalledOnce()
    wrapper.unmount()
    finishRegistration?.({ remove })
    await flushPromises()

    expect(remove).toHaveBeenCalledOnce()
  })

  test('目录失效时清空旧扫描并重新选择目录', async () => {
    mocks.pickFolder
      .mockResolvedValueOnce({ ref: 'content://old-tree', displayPath: '/old' })
      .mockResolvedValueOnce({ ref: 'content://new-tree', displayPath: '/new' })
    mocks.scanAndParse
      .mockRejectedValueOnce(
        new RuntimeError('permission-denied', 'PDF 文件夹读取权限已失效，请重新选择文件夹'),
      )
      .mockResolvedValueOnce(undefined)

    const wrapper = mountManagement()
    await flushPromises()
    await wrapper.get('button[aria-label="导入文件"]').trigger('click')
    await flushPromises()

    expect(mocks.pickFolder).toHaveBeenCalledTimes(2)
    expect(mocks.scanAndParse).toHaveBeenNthCalledWith(1, 'content://old-tree')
    expect(mocks.scanAndParse).toHaveBeenNthCalledWith(2, 'content://new-tree')
    expect(mocks.routerPush).toHaveBeenCalledWith('/import-review')
    wrapper.unmount()
  })

  test('删除前记录已消失时刷新 PDF 文件列表', async () => {
    mocks.inspectLocalFileForDeletion.mockRejectedValueOnce(
      new RuntimeError('not-found', 'PDF 文件记录不存在'),
    )
    const wrapper = mountManagement()
    await flushPromises()
    const initialCalls = mocks.getLocalFiles.mock.calls.length

    await wrapper.get('button[aria-label="更多操作"]').trigger('click')
    document.body.querySelector<HTMLButtonElement>('.card-menu-item--danger')?.click()
    await flushPromises()

    expect(mocks.getLocalFiles.mock.calls.length).toBeGreaterThan(initialCalls)
    expect(mocks.alertCreate).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  test('重试冲突时刷新任务并保留服务端 message', async () => {
    const failedTask = task('failed')
    mocks.getExportTasks.mockResolvedValue({ tasks: [failedTask], nextCursor: null })
    mocks.retryExport.mockRejectedValueOnce(
      new RuntimeError('conflict', '相同章节已有任务正在运行'),
    )
    mocks.alertCreate.mockImplementationOnce(async (options: any) => {
      await options.buttons[1].handler()
      return { present: vi.fn() }
    })

    const wrapper = mountManagement({ props: { initialView: 'tasks' } })
    await flushPromises()
    const initialCalls = mocks.getExportTasks.mock.calls.length

    await wrapper.get('button[aria-label="重试整个 PDF 导出任务"]').trigger('click')
    await flushPromises()

    expect(mocks.getExportTasks.mock.calls.length).toBeGreaterThan(initialCalls)
    expect(mocks.showToast).toHaveBeenCalledWith('相同章节已有任务正在运行', 'medium')
    wrapper.unmount()
  })
})


describe('导出管理三页窗口回归', () => {
  const fileAt = (id: number) => ({ ...file, id, albumId: 'album-' + id, chapterId: 'chapter-' + id })
  const taskAt = (id: number) => ({ ...task('completed'), exportId: 'export-' + id })
  const serveFiles = (records: LocalFileRecord[]) => ({ cursor }: any) => {
    const after = cursor ? Number(cursor.slice(1)) : 0
    const remaining = records.filter((entry) => entry.id > after)
    const items = remaining.slice(0, 50)
    return Promise.resolve({ files: items, nextCursor: remaining.length > 50 ? 'f' + items[items.length - 1].id : null })
  }

  test('第四页裁剪后可回看旧页，前后往返不重复且资源查询仅覆盖当前窗口', async () => {
    mocks.getLocalFiles.mockImplementation(serveFiles(Array.from({ length: 260 }, (_, i) => fileAt(i + 1))))
    const wrapper = mountManagement()
    await flushPromises()
    const state = (wrapper.vm as any).$.setupState
    for (let i = 0; i < 3; i++) await state.loadMoreFiles()
    expect(state.files.map((entry: any) => entry.id)).toEqual(Array.from({ length: 150 }, (_, i) => i + 51))
    expect(state.filePages).toHaveLength(3)
    await state.loadMoreFiles('previous')
    expect(state.files.map((entry: any) => entry.id)).toEqual(Array.from({ length: 150 }, (_, i) => i + 1))
    expect(state.fileCursor).toBe('f150')
    await state.loadMoreFiles()
    await flushPromises()
    expect(new Set(state.files.map((entry: any) => entry.id)).size).toBe(150)
    expect(mocks.getDownloadedResourceKeys).toHaveBeenLastCalledWith(
      Array.from({ length: 150 }, (_, i) => ({ albumId: 'album-' + (i + 51), chapterId: 'chapter-' + (i + 51) })),
    )
    wrapper.unmount()
  })

  test('删除窗口头部后按当前首游标补齐，不额外丢弃剩余首页', async () => {
    const records = Array.from({ length: 260 }, (_, i) => fileAt(i + 1))
    mocks.getLocalFiles.mockImplementation(serveFiles(records))
    const wrapper = mountManagement()
    await flushPromises()
    const state = (wrapper.vm as any).$.setupState
    for (let i = 0; i < 3; i++) await state.loadMoreFiles()
    records.splice(records.findIndex((entry) => entry.id === 51), 1)
    await state.removeFileAndFill(51)
    expect(state.files.map((entry: any) => entry.id)).toEqual(Array.from({ length: 150 }, (_, i) => i + 52))
    expect(state.fileCursor).toBe('f201')
    expect(state.filePages).toHaveLength(3)
    wrapper.unmount()
  })

  test('定位窗口外任务不复制旧记录，移出的末页仍可按游标读取', async () => {
    mocks.getExportTasks.mockImplementation(async ({ cursor }: any) => {
      const start = cursor ? Number(cursor.slice(1)) : 0
      return { tasks: Array.from({ length: 50 }, (_, i) => taskAt(start + i + 1)), nextCursor: 't' + (start + 50) }
    })
    const wrapper = mountManagement({ props: { initialView: 'tasks' } })
    await flushPromises()
    const state = (wrapper.vm as any).$.setupState
    await state.loadMoreTasks()
    await state.loadMoreTasks()
    mocks.getExportTask.mockResolvedValue(taskAt(999))
    await state.focusTask('export-999')
    expect(state.tasks).toHaveLength(101)
    expect(new Set(state.tasks.map((entry: any) => entry.exportId)).size).toBe(101)
    expect(state.taskCursor).toBe('t100')
    await state.loadMoreTasks()
    expect(state.tasks.map((entry: any) => entry.exportId)).toEqual(Array.from({ length: 150 }, (_, i) => 'export-' + (i + 1)))
    wrapper.unmount()
  })

  test('资源查询在文件加载后发起，较旧窗口响应不能覆盖新标记', async () => {
    let finishOld: ((result: { keys: string[] }) => void) | undefined
    mocks.getDownloadedResourceKeys.mockImplementationOnce(() => new Promise((resolve) => { finishOld = resolve }))
    const wrapper = mountManagement()
    await flushPromises()
    expect(mocks.getDownloadedResourceKeys).toHaveBeenCalledWith([{ albumId: 'album-1', chapterId: 'chapter-1' }])
    mocks.getLocalFiles.mockResolvedValue({ files: [fileAt(2)], nextCursor: null })
    mocks.getDownloadedResourceKeys.mockResolvedValue({ keys: ['album-2|chapter-2'] })
    const state = (wrapper.vm as any).$.setupState
    await state.requestFilesReload()
    await flushPromises()
    finishOld?.({ keys: ['album-1|chapter-1'] })
    await flushPromises()
    expect(Array.from(state.imageResourceKeys)).toEqual(['album-2|chapter-2'])
    wrapper.unmount()
  })

  test('较早定位响应不能覆盖新的目标，也不累计临时定位页', async () => {
    let finishOld: ((value: ExportTaskRecord) => void) | undefined
    const wrapper = mountManagement()
    await flushPromises()
    mocks.getExportTask.mockImplementationOnce(() => new Promise((resolve) => { finishOld = resolve }))
    mocks.getExportTask.mockResolvedValue(taskAt(3))
    const state = (wrapper.vm as any).$.setupState
    const old = state.focusTask('export-2')
    await state.focusTask('export-3')
    finishOld?.(taskAt(2))
    await old
    expect(state.highlightedExportId).toBe('export-3')
    expect(state.tasks.some((entry: any) => entry.exportId === 'export-2')).toBe(false)
    expect(state.taskPages.filter((page: any) => page.index === -1)).toHaveLength(1)
    wrapper.unmount()
  })
})
