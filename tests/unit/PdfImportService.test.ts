import { beforeEach, describe, expect, test, vi } from 'vitest'
import type { PdfFileParseItem } from '@/utils/importPdfParse'

const mocks = vi.hoisted(() => ({
  checkFilesExist: vi.fn(),
  importPdfs: vi.fn(),
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    checkFilesExist: mocks.checkFilesExist,
    importPdfs: mocks.importPdfs,
  },
}))

import { PdfImportService } from '@/services/PdfImportService'

const file = (filePath: string): PdfFileParseItem => ({
  fileName: filePath.split('/').pop() ?? 'book.pdf',
  filePath,
  extractedIds: ['123456'],
  editedIds: ['123456'],
  idPositions: [],
  status: 'resolved',
  duplicateIds: [],
  albumDetail: {
    id: '123456',
    title: '测试漫画',
    image: '',
    authors: [],
    tags: [],
    description: '',
    likes: 0,
    views: 0,
    commentCount: 0,
    series: [],
    relatedWorks: [],
    isFavorite: false,
    isLiked: false,
    isSingleEpisode: false,
    photoMetas: [],
  },
})

beforeEach(() => {
  vi.clearAllMocks()
})

describe('PdfImportService.confirmImport', () => {
  test('全部文件缺失时返回汇总失败且不调用原生导入', async () => {
    mocks.checkFilesExist.mockResolvedValue({ existing: [] })

    const result = await PdfImportService.confirmImport([file('/pdf/a.pdf'), file('/pdf/b.pdf')])

    expect(result.errorCount).toBe(2)
    expect(result).toEqual({ imported: 0, skipped: 2, duplicateCount: 0, errorCount: 2 })
    expect(mocks.importPdfs).not.toHaveBeenCalled()
  })

  test('部分文件缺失时合并原生汇总数量', async () => {
    mocks.checkFilesExist.mockResolvedValue({ existing: ['/pdf/a.pdf'] })
    mocks.importPdfs.mockResolvedValue({
      imported: 1,
      skipped: 0,
      duplicateCount: 0,
      errorCount: 0,
      results: [{ result: 'imported', filePath: '/pdf/a.pdf', id: 1 }],
    })

    const result = await PdfImportService.confirmImport([file('/pdf/a.pdf'), file('/pdf/b.pdf')])

    expect(result).toEqual(
      expect.objectContaining({ imported: 1, skipped: 1, errorCount: 1 }),
    )
    expect(result.results).toEqual([
      expect.objectContaining({ result: 'imported', filePath: '/pdf/a.pdf' }),
    ])
  })

  test('无法可靠对齐章节时不使用漫画 ID 伪造 chapterId', async () => {
    mocks.checkFilesExist.mockResolvedValue({ existing: ['/pdf/a.pdf'] })
    mocks.importPdfs.mockResolvedValue({
      imported: 1,
      skipped: 0,
      duplicateCount: 0,
      errorCount: 0,
      results: [{ result: 'imported', filePath: '/pdf/a.pdf', id: 1 }],
    })

    await PdfImportService.confirmImport([file('/pdf/a.pdf')])

    expect(mocks.importPdfs).toHaveBeenCalledWith([
      expect.objectContaining({ albumId: '123456', chapterId: '' }),
    ])
  })
})
