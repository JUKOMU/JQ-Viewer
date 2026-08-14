import { beforeEach, describe, expect, test, vi } from 'vitest'
import type { ImportedPdf, PdfExportTaskRecord } from '@/services/JmcomicTypes'

const mocks = vi.hoisted(() => ({
  getPdfFiles: vi.fn(),
  refreshPdfFileAvailability: vi.fn(),
  getPdfExportTasks: vi.fn(),
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    getPdfFiles: mocks.getPdfFiles,
    refreshPdfFileAvailability: mocks.refreshPdfFileAvailability,
    getPdfExportTasks: mocks.getPdfExportTasks,
  },
}))

import {
  applyPdfProgressEvent,
  mergePdfFiles,
  PdfManagementService,
} from '@/services/PdfManagementService'

const file = (id: number): ImportedPdf => ({
  id,
  filePath: `/pdf/${id}.pdf`,
  fileName: `${id}.pdf`,
  sourceType: 'imported',
  ownership: 'external_reference',
  chapterLinkStatus: 'resolved',
  albumId: '1',
  albumTitle: '漫画',
  coverUrl: '',
  authors: '',
  chapterId: String(id),
  chapterTitle: `第 ${id} 话`,
  chapterSortOrder: id,
  createdAt: id,
  fileSize: 10,
  pageCount: 1,
  availability: 'available',
  verificationStatus: 'valid',
  updatedAt: id,
})

const task = (revision: number): PdfExportTaskRecord => ({
  exportId: 'export-1',
  batchId: 'batch-1',
  mode: 'chapter',
  albumId: '1',
  albumTitle: '漫画',
  coverUrl: '',
  authors: '',
  chapterId: '1',
  displayTitle: '第一话',
  savePath: '/pdf/1.pdf',
  allowOverwrite: false,
  useOriginal: true,
  compressionRatio: 1,
  splitPages: 0,
  status: 'running',
  phase: 'writing',
  currentPage: revision,
  totalPages: 10,
  currentVolume: 1,
  totalVolumes: 1,
  snapshotRevision: revision,
  cancelRequested: false,
  createdAt: 1,
  updatedAt: 1,
})

beforeEach(() => vi.clearAllMocks())

describe('PdfManagementService', () => {
  test('文件筛选与 cursor 下推，并立即返回数据库记录', async () => {
    mocks.getPdfFiles.mockResolvedValue({ files: [file(1)], nextCursor: 'next-1' })

    const result = await PdfManagementService.getFiles(
      { sourceType: 'imported', folderId: 'folder-1', query: '  标题  ' },
      'cursor-1',
    )

    expect(mocks.getPdfFiles).toHaveBeenCalledWith({
      sourceType: 'imported',
      folderId: 'folder-1',
      query: '标题',
      cursor: 'cursor-1',
      limit: 50,
    })
    expect(mocks.refreshPdfFileAvailability).not.toHaveBeenCalled()
    expect(result).toEqual({
      items: [file(1)],
      nextCursor: 'next-1',
    })
  })

  test('当前页状态可以在列表显示后单独刷新', async () => {
    const refreshed = { ...file(1), availability: 'missing' as const }
    mocks.refreshPdfFileAvailability.mockResolvedValue({ files: [refreshed] })

    await expect(PdfManagementService.refreshFiles([1])).resolves.toEqual([refreshed])
    expect(mocks.refreshPdfFileAvailability).toHaveBeenCalledWith([1])
  })

  test('追加文件按数据库 ID 去重并使用新快照', () => {
    expect(
      mergePdfFiles([file(1), file(2)], [{ ...file(2), fileName: 'updated.pdf' }, file(3)]),
    ).toEqual([file(1), { ...file(2), fileName: 'updated.pdf' }, file(3)])
  })

  test('旧 revision 事件不会覆盖新任务快照', () => {
    const current = task(5)
    const result = applyPdfProgressEvent([current], {
      exportId: current.exportId,
      batchId: current.batchId,
      status: 'running',
      phase: 'writing',
      currentPage: 2,
      totalPages: 10,
      currentVolume: 1,
      totalVolumes: 1,
      snapshotRevision: 4,
    })

    expect(result).toEqual([current])
  })
})
