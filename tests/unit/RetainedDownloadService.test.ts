import { beforeEach, describe, expect, test, vi } from 'vitest'
import type { DownloadTask, RetainedExportCandidate } from '@/services/JmcomicTypes'
import { mount } from '@vue/test-utils'
import DownloadTaskCard from '@/components/download/DownloadTaskCard.vue'
const native = vi.hoisted(() => ({
  refreshLocalFileAvailability: vi.fn(), getLocalFiles: vi.fn(),
  deleteLocalFile: vi.fn(), removeRetainedDownload: vi.fn(),
}))
vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: native, getImageUrl: () => '', sanitizeError: (_error: unknown, fallback: string) => fallback,
}))
import {
  isRetainedDownload, selectRetainedExport, verifyRetainedDownloads,
  retainedDeletionFiles, deleteRetainedDownloads, retainedFileStatusLabel,
} from '@/services/RetainedDownloadService'

const volume = (fileId: number, exportId = 'export-1', pages = 2): RetainedExportCandidate => ({
  fileId, exportId, fileRef: `file:path:/${fileId}.pdf` as never, format: 'pdf',
  startPage: 1, pageCount: pages, availability: 'available',
})
const task = (volumes?: RetainedExportCandidate[], chapterId = 'a'): DownloadTask => ({
  taskId: chapterId, albumId: 'album', chapterId, albumTitle: '', chapterTitle: '',
  coverUrl: '', totalPages: 2, downloadedPages: 2, status: 'completed', createdAt: 1,
  retainedExportCandidates: volumes,
})

beforeEach(() => {
  vi.resetAllMocks()
  native.getLocalFiles.mockResolvedValue({ files: [{ id: 1 }] })
  native.deleteLocalFile.mockResolvedValue({ result: 'deleted' })
  native.removeRetainedDownload.mockResolvedValue({ success: true })
})

describe('保留下载项的来源和操作', () => {
  test('普通原图下载不校验导出文件，也不提供移除身份', async () => {
    const ordinary = task()
    expect(isRetainedDownload(ordinary)).toBe(false)
    expect(await verifyRetainedDownloads([ordinary])).toEqual([ordinary])
    expect(native.refreshLocalFileAvailability).not.toHaveBeenCalled()
  })

  test('共享分卷独立校验一次，逐章保留缺失诊断', async () => {
    native.refreshLocalFileAvailability.mockResolvedValue({ files: [{ id: 1, availability: 'missing' }] })
    const result = await verifyRetainedDownloads([task([volume(1)]), task([volume(1)], 'b')])
    expect(native.refreshLocalFileAvailability).toHaveBeenCalledTimes(1)
    expect(native.refreshLocalFileAvailability).toHaveBeenCalledWith([1])
    for (const item of result) {
      expect(item.retainedExports).toEqual([])
      expect(retainedFileStatusLabel(item)).toBe('文件缺失')
      expect(isRetainedDownload(item)).toBe(true)
    }
  })

  test('文件库记录已删除仍保留格式、删除范围及缺失提示', async () => {
    const item = task([{ ...volume(1), fileId: null, availability: 'missing' }])
    const [result] = await verifyRetainedDownloads([item])
    expect(retainedFileStatusLabel(result)).toBe('文件缺失')
    expect(retainedDeletionFiles(result)[0].format).toBe('pdf')
    expect(native.refreshLocalFileAvailability).not.toHaveBeenCalled()
  })

  test('一个章节缺任一分卷即不可读，不跨任务拼接残卷', () => {
    const result = selectRetainedExport(task([
      volume(1, 'first', 1), { ...volume(2, 'first', 1), availability: 'missing' },
      volume(3, 'second', 1),
    ]))
    expect(result.retainedExports).toEqual([])
    expect(retainedDeletionFiles(result).map((v) => v.fileId)).toEqual([1, 2])
  })

  test('最新导出损坏时选择另一套完整来源', async () => {
    native.refreshLocalFileAvailability.mockResolvedValue({ files: [
      { id: 1, availability: 'invalid' }, { id: 2, availability: 'available' },
    ] })
    const [result] = await verifyRetainedDownloads([task([volume(1, 'new'), volume(2, 'old')])])
    expect(result.retainedExports?.map((v) => v.fileId)).toEqual([2])
    expect(result.retainedFileStatus).toBe('available')
    expect(retainedDeletionFiles(result).map((v) => v.fileId)).toEqual([2])
  })

  test('共享文件批量删除一次，只移除选中的记录', async () => {
    await deleteRetainedDownloads([task([volume(1)]), task([volume(1)], 'b')])
    expect(native.deleteLocalFile).toHaveBeenCalledTimes(1)
    expect(native.deleteLocalFile).toHaveBeenCalledWith(1)
    expect(native.removeRetainedDownload.mock.calls).toEqual([['album', 'a'], ['album', 'b']])
  })

  test('缺失文件的下载记录仍可主动删除', async () => {
    await deleteRetainedDownloads([task([{ ...volume(1), fileId: null, availability: 'missing' }])])
    expect(native.deleteLocalFile).not.toHaveBeenCalled()
    expect(native.removeRetainedDownload).toHaveBeenCalledWith('album', 'a')
  })

  test('删除文件失败保留下载记录，不能当作成功移除', async () => {
    native.deleteLocalFile.mockRejectedValue(new Error('permission denied'))
    await expect(deleteRetainedDownloads([task([volume(1)])])).rejects.toThrow('permission denied')
    expect(native.removeRetainedDownload).not.toHaveBeenCalled()
  })

  test.each(['unknown', 'invalid'] as const)('状态 %s 的卡片使用相应提示样式', (status) => {
    const item = { ...task([volume(1)]), retainedFileStatus: status }
    const wrapper = mount(DownloadTaskCard, {
      props: { task: item }, global: { stubs: { IonIcon: true } },
    })
    const label = wrapper.find(status === 'unknown' ? '.status-tag.verifying' : '.tag.failed')
    expect(label.text()).toBe(status === 'unknown' ? '待校验' : '文件损坏')
    wrapper.unmount()
  })

  test('多章节卡片汇总非首章异常，并保留导出格式', () => {
    const ordinary = task()
    const broken = selectRetainedExport(task([{ ...volume(1), availability: 'invalid' }], 'b'))
    const entries = [ordinary, broken].map((downloadTask) => ({
      source: 'download' as const, albumId: 'album', albumTitle: 'Album',
      chapterId: downloadTask.chapterId, chapterTitle: '', totalPages: 2, downloadTask,
    }))
    const wrapper = mount(DownloadTaskCard, {
      props: { task: ordinary, showProgress: false, downloadedChapters: entries as never },
      global: { stubs: { IonIcon: true } },
    })
    expect(wrapper.text()).toContain('部分章节文件异常（1/2）')
    expect(wrapper.text()).toContain('PDF')
    wrapper.unmount()
  })
})
