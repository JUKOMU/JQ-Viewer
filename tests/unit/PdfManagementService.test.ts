import { beforeEach, describe, expect, test, vi } from 'vitest'
import type { LocalFileRecord, ExportTaskRecord } from '@/services/JmcomicTypes'

const mocks = vi.hoisted(() => ({
  getLocalFiles: vi.fn(),
  refreshLocalFileAvailability: vi.fn(),
  getExportTasks: vi.fn(),
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    getLocalFiles: mocks.getLocalFiles,
    refreshLocalFileAvailability: mocks.refreshLocalFileAvailability,
    getExportTasks: mocks.getExportTasks,
  },
}))

import {
  applyExportProgressEvent,
  mergeLocalFiles,
  LocalFileManagementService,
} from '@/services/LocalFileManagementService'

const file = (id: number): LocalFileRecord => ({
  id,
  format: 'pdf',
  fileRef: `/pdf/${id}.pdf` as LocalFileRecord['fileRef'],
  displayPath: `/pdf/${id}.pdf`,
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

const task = (revision: number): ExportTaskRecord => ({
  exportId: 'export-1',
  batchId: 'batch-1',
  format: 'pdf',
  mode: 'chapter',
  albumId: '1',
  albumTitle: '漫画',
  coverUrl: '',
  authors: '',
  chapterId: '1',
  displayTitle: '第一话',
  displayPath: '/pdf/1.pdf',
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

describe('LocalFileManagementService', () => {
  test('文件筛选与 cursor 下推，并立即返回数据库记录', async () => {
    mocks.getLocalFiles.mockResolvedValue({ files: [file(1)], nextCursor: 'next-1' })

    const result = await LocalFileManagementService.getFiles(
      { sourceType: 'imported', folderId: 'folder-1', query: '  标题  ' },
      'cursor-1',
    )

    expect(mocks.getLocalFiles).toHaveBeenCalledWith({
      sourceType: 'imported',
      folderId: 'folder-1',
      query: '标题',
      cursor: 'cursor-1',
      limit: 50,
    })
    expect(mocks.refreshLocalFileAvailability).not.toHaveBeenCalled()
    expect(result).toEqual({
      items: [file(1)],
      nextCursor: 'next-1',
    })
  })

  test('当前页状态可以在列表显示后单独刷新', async () => {
    const refreshed = { ...file(1), availability: 'missing' as const }
    mocks.refreshLocalFileAvailability.mockResolvedValue({ files: [refreshed] })

    await expect(LocalFileManagementService.refreshFiles([1])).resolves.toEqual([refreshed])
    expect(mocks.refreshLocalFileAvailability).toHaveBeenCalledWith([1])
  })

  test('追加文件按数据库 ID 去重并使用新快照', () => {
    expect(
      mergeLocalFiles([file(1), file(2)], [{ ...file(2), fileName: 'updated.pdf' }, file(3)]),
    ).toEqual([file(1), { ...file(2), fileName: 'updated.pdf' }, file(3)])
  })

  test('旧 revision 事件不会覆盖新任务快照', () => {
    const current = task(5)
    const result = applyExportProgressEvent([current], {
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
