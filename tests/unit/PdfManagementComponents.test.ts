/* eslint-disable vue/one-component-per-file -- test-only Ionic fixtures */
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import type { ImportedPdf, PdfExportTaskRecord } from '@/services/JmcomicTypes'
import { RuntimeError } from '@/runtime/errors'

const mocks = vi.hoisted(() => ({
  getPdfFiles: vi.fn(),
  refreshPdfFileAvailability: vi.fn(),
  getDownloadTasks: vi.fn(),
  getPdfExportTasks: vi.fn(),
  getPdfManagementState: vi.fn(),
  getOfflineFolders: vi.fn(),
  getPdfExportTask: vi.fn(),
  addPdfExportProgressListener: vi.fn(),
  inspectPdfFileForDeletion: vi.fn(),
  verifyPdfFile: vi.fn(),
  openPdf: vi.fn(),
  openPdfFolder: vi.fn(),
  deletePdfExportTask: vi.fn(),
  cancelPdfExport: vi.fn(),
  retryPdfExport: vi.fn(),
  pickFolder: vi.fn(),
  scanAndParse: vi.fn(),
  alertCreate: vi.fn(),
  routerPush: vi.fn(),
  showToast: vi.fn(),
}))

vi.mock('@ionic/vue', () => ({
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
  closeCircleOutline: 'cancel',
  cloudUploadOutline: 'upload',
  copyOutline: 'copy',
  documentOutline: 'document',
  ellipsisVertical: 'more',
  folderOpenOutline: 'folder-open',
  imagesOutline: 'images',
  informationCircleOutline: 'information',
  refreshOutline: 'refresh',
  removeCircleOutline: 'remove',
  trashOutline: 'trash',
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.routerPush }) }))
vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    getPdfFiles: mocks.getPdfFiles,
    refreshPdfFileAvailability: mocks.refreshPdfFileAvailability,
    getDownloadTasks: mocks.getDownloadTasks,
    getPdfExportTasks: mocks.getPdfExportTasks,
    getPdfManagementState: mocks.getPdfManagementState,
    acknowledgePdfDatabaseReset: vi.fn(),
    getOfflineFolders: mocks.getOfflineFolders,
    getPdfExportTask: mocks.getPdfExportTask,
    addPdfExportProgressListener: mocks.addPdfExportProgressListener,
    inspectPdfFileForDeletion: mocks.inspectPdfFileForDeletion,
    openPdf: mocks.openPdf,
    openPdfFolder: mocks.openPdfFolder,
    removePdfFromLibrary: vi.fn(),
    deletePdfFile: vi.fn(),
    verifyPdfFile: mocks.verifyPdfFile,
    cancelPdfExport: mocks.cancelPdfExport,
    retryPdfExport: mocks.retryPdfExport,
    deletePdfExportTask: mocks.deletePdfExportTask,
    pickFolder: mocks.pickFolder,
  },
  sanitizeError: (error: unknown, fallback: string) =>
    error instanceof RuntimeError ? error.message : fallback,
  showToast: mocks.showToast,
}))
vi.mock('@/services/PdfImportService', () => ({ PdfImportService: { scanAndParse: mocks.scanAndParse } }))

import PdfExportTaskCard from '@/components/download/PdfExportTaskCard.vue'
import PdfFileCard from '@/components/download/PdfFileCard.vue'
import PdfManagementView from '@/components/download/PdfManagementView.vue'

const file: ImportedPdf = {
  id: 1,
  fileRef: 'content://provider/current.pdf' as ImportedPdf['fileRef'],
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
  createdAt: 1,
  fileSize: 1024,
  pageCount: 12,
  availability: 'available',
  verificationStatus: 'valid',
  updatedAt: 1,
}
const task = (status: PdfExportTaskRecord['status']): PdfExportTaskRecord => ({
  exportId: 'export-1',
  batchId: 'batch-1',
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
  mocks.getPdfFiles.mockResolvedValue({ files: [file], nextCursor: null })
  mocks.refreshPdfFileAvailability.mockResolvedValue({ files: [file] })
  mocks.getDownloadTasks.mockResolvedValue({
    tasks: [{ albumId: 'album-1', chapterId: 'chapter-1', status: 'completed' }],
  })
  mocks.getPdfExportTasks.mockResolvedValue({ tasks: [task('completed')], nextCursor: null })
  mocks.getPdfManagementState.mockResolvedValue({ recoveryState: 'ready' })
  mocks.getOfflineFolders.mockResolvedValue({ folders: [] })
  mocks.getPdfExportTask.mockResolvedValue(task('completed'))
  mocks.addPdfExportProgressListener.mockResolvedValue({ remove: vi.fn() })
  mocks.inspectPdfFileForDeletion.mockResolvedValue(file)
  mocks.verifyPdfFile.mockResolvedValue(file)
  mocks.pickFolder.mockResolvedValue(null)
  mocks.scanAndParse.mockResolvedValue(undefined)
  mocks.alertCreate.mockResolvedValue({ present: vi.fn() })
})

describe('PdfFileCard', () => {
  test('使用独立的打开和更多按钮', () => {
    const wrapper = mount(PdfFileCard, { props: { file, hasImageResource: false } })
    expect(wrapper.get('article').findAll('button')).toHaveLength(2)
    expect(wrapper.get('button[aria-label="打开 PDF"]')).toBeTruthy()
    expect(wrapper.get('button[aria-label="更多操作"]')).toBeTruthy()
    expect(wrapper.get('.meta-row').text()).toMatch(/可用1\.0 KB$/)
  })
})

describe('PdfExportTaskCard', () => {
  test('取消中不再显示取消按钮', () => {
    const wrapper = mount(PdfExportTaskCard, { props: { task: task('cancelling') } })
    expect(wrapper.find('button[aria-label="取消 PDF 导出"]').exists()).toBe(false)
  })

  test('完成任务只提供删除记录操作', () => {
    const wrapper = mount(PdfExportTaskCard, { props: { task: task('completed') } })
    expect(wrapper.findAll('button')).toHaveLength(1)
    expect(wrapper.find('button[aria-label="删除 PDF 导出任务记录"]').exists()).toBe(true)
  })
})

describe('PdfManagementView', () => {
  test('先显示卡片并在后台校验当前页', async () => {
    let finishRefresh: ((value: { files: ImportedPdf[] }) => void) | undefined
    mocks.refreshPdfFileAvailability.mockReturnValue(
      new Promise((resolve) => {
        finishRefresh = resolve
      }),
    )

    const wrapper = mount(PdfManagementView)
    await flushPromises()

    expect(wrapper.text()).toContain('测试漫画')
    expect(wrapper.get('.status').text()).toContain('校验中')

    finishRefresh?.({ files: [{ ...file, availability: 'missing' }] })
    await flushPromises()
    expect(wrapper.get('.status').text()).toBe('文件缺失')
    wrapper.unmount()
  })

  test('同章节存在已完成下载时显示图片和 PDF 资源', async () => {
    const wrapper = mount(PdfManagementView)
    await flushPromises()

    expect(wrapper.get('.resource-icons').attributes('aria-label')).toBe('图片和 PDF')
    wrapper.unmount()
  })

  test('物理删除前刷新资源并展示完整定位符和路径复用警告', async () => {
    const wrapper = mount(PdfManagementView)
    await flushPromises()
    await wrapper.get('button[aria-label="更多操作"]').trigger('click')
    document.body.querySelector<HTMLButtonElement>('.card-menu-item--danger')?.click()
    await flushPromises()
    await flushPromises()

    expect(mocks.inspectPdfFileForDeletion).toHaveBeenCalledWith(1)
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

  test('更多菜单按下载页样式提供七项文件操作', async () => {
    const wrapper = mount(PdfManagementView)
    await flushPromises()
    await wrapper.get('button[aria-label="更多操作"]').trigger('click')

    expect(
      Array.from(document.body.querySelectorAll('.card-menu-item')).map(
        (button) => button.textContent,
      ),
    ).toEqual(['阅读', '进入详情页', '校验', '复制路径', '打开文件夹', '移除', '删除'])
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
        chapterId: 'chapter-1',
        chapterTitle: '第一话',
      },
    }
    const wrapper = mount(PdfManagementView)
    await flushPromises()

    await wrapper.get('button[aria-label="打开 PDF"]').trigger('click')
    expect(mocks.routerPush).toHaveBeenLastCalledWith(expectedRoute)

    await wrapper.get('button[aria-label="更多操作"]').trigger('click')
    await document.body.querySelectorAll<HTMLButtonElement>('.card-menu-item')[0].click()
    await flushPromises()
    expect(mocks.routerPush).toHaveBeenLastCalledWith(expectedRoute)
    expect(mocks.routerPush).toHaveBeenCalledTimes(2)
    expect(mocks.openPdf).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  test('手动校验显示进行中状态并提示校验结果', async () => {
    let finishVerification: ((value: ImportedPdf) => void) | undefined
    mocks.verifyPdfFile.mockReturnValue(
      new Promise((resolve) => {
        finishVerification = resolve
      }),
    )
    const wrapper = mount(PdfManagementView)
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
    const wrapper = mount(PdfManagementView)
    await flushPromises()

    await wrapper.get('button[aria-label="更多操作"]').trigger('click')
    document.body.querySelectorAll<HTMLButtonElement>('.card-menu-item')[1].click()
    await flushPromises()
    expect(mocks.routerPush).toHaveBeenCalledWith('/album/album-1')

    await wrapper.get('button[aria-label="更多操作"]').trigger('click')
    document.body.querySelectorAll<HTMLButtonElement>('.card-menu-item')[4].click()
    await flushPromises()
    expect(mocks.openPdfFolder).toHaveBeenCalledWith('content://provider/current.pdf')
    wrapper.unmount()
  })

  test('删除任务记录的确认不再请求影响 token', async () => {
    const wrapper = mount(PdfManagementView, { props: { initialView: 'tasks' } })
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
    let finishInitial: ((value: { files: ImportedPdf[]; nextCursor: null }) => void) | undefined
    const newerFile = { ...file, id: 2, fileName: 'newer.pdf', albumTitle: '新筛选结果' }
    mocks.getPdfFiles
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishInitial = resolve
          }),
      )
      .mockResolvedValueOnce({ files: [newerFile], nextCursor: null })
    mocks.refreshPdfFileAvailability.mockResolvedValue({ files: [] })

    const wrapper = mount(PdfManagementView)
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
      | ((value: { tasks: PdfExportTaskRecord[]; nextCursor: null }) => void)
      | undefined
    const oldTask = { ...task('completed'), displayTitle: '旧任务' }
    const newerTask = {
      ...task('failed'),
      exportId: 'export-2',
      displayTitle: '新筛选任务',
    }
    mocks.getPdfExportTasks
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishInitial = resolve
          }),
      )
      .mockResolvedValueOnce({ tasks: [newerTask], nextCursor: null })

    const wrapper = mount(PdfManagementView, { props: { initialView: 'tasks' } })
    await flushPromises()
    await wrapper.findAll('.task-filter-buttons button')[5].trigger('click')
    await flushPromises()
    finishInitial?.({ tasks: [oldTask], nextCursor: null })
    await flushPromises()

    expect(wrapper.text()).toContain('新筛选任务')
    expect(wrapper.text()).not.toContain('旧任务')
    wrapper.unmount()
  })

  test('卸载后才完成注册的进度监听会立即移除', async () => {
    let finishRegistration: ((value: { remove: () => Promise<void> }) => void) | undefined
    const remove = vi.fn().mockResolvedValue(undefined)
    mocks.addPdfExportProgressListener.mockReturnValue(
      new Promise((resolve) => {
        finishRegistration = resolve
      }),
    )

    const wrapper = mount(PdfManagementView)
    await flushPromises()
    expect(mocks.addPdfExportProgressListener).toHaveBeenCalledOnce()
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
      .mockRejectedValueOnce(new RuntimeError('permission-denied', 'PDF 文件夹读取权限已失效，请重新选择文件夹'))
      .mockResolvedValueOnce(undefined)

    const wrapper = mount(PdfManagementView)
    await flushPromises()
    await wrapper.get('button[aria-label="导入 PDF"]').trigger('click')
    await flushPromises()

    expect(mocks.pickFolder).toHaveBeenCalledTimes(2)
    expect(mocks.scanAndParse).toHaveBeenNthCalledWith(1, 'content://old-tree')
    expect(mocks.scanAndParse).toHaveBeenNthCalledWith(2, 'content://new-tree')
    expect(mocks.routerPush).toHaveBeenCalledWith('/import-review')
    wrapper.unmount()
  })

  test('删除前记录已消失时刷新 PDF 文件列表', async () => {
    mocks.inspectPdfFileForDeletion.mockRejectedValueOnce(
      new RuntimeError('not-found', 'PDF 文件记录不存在'),
    )
    const wrapper = mount(PdfManagementView)
    await flushPromises()
    const initialCalls = mocks.getPdfFiles.mock.calls.length

    await wrapper.get('button[aria-label="更多操作"]').trigger('click')
    document.body.querySelector<HTMLButtonElement>('.card-menu-item--danger')?.click()
    await flushPromises()

    expect(mocks.getPdfFiles.mock.calls.length).toBeGreaterThan(initialCalls)
    expect(mocks.alertCreate).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  test('重试冲突时刷新任务并保留服务端 message', async () => {
    const failedTask = task('failed')
    mocks.getPdfExportTasks.mockResolvedValue({ tasks: [failedTask], nextCursor: null })
    mocks.retryPdfExport.mockRejectedValueOnce(
      new RuntimeError('conflict', '相同章节已有任务正在运行'),
    )
    mocks.alertCreate.mockImplementationOnce(async (options: any) => {
      await options.buttons[1].handler()
      return { present: vi.fn() }
    })

    const wrapper = mount(PdfManagementView, { props: { initialView: 'tasks' } })
    await flushPromises()
    const initialCalls = mocks.getPdfExportTasks.mock.calls.length

    await wrapper.get('button[aria-label="重试整个 PDF 导出任务"]').trigger('click')
    await flushPromises()

    expect(mocks.getPdfExportTasks.mock.calls.length).toBeGreaterThan(initialCalls)
    expect(mocks.showToast).toHaveBeenCalledWith('相同章节已有任务正在运行', 'medium')
    wrapper.unmount()
  })
})
