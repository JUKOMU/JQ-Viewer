import { beforeEach, describe, expect, test, vi } from 'vitest'
import type { LocalFileParseItem } from '@/utils/importLocalFileParse'
import { asFileRef, asFolderRef } from '@/runtime/FileReferences'

const mocks = vi.hoisted(() => ({
  scanImportableFiles: vi.fn(),
  getCbzInfo: vi.fn(),
  checkFilesExist: vi.fn(),
  importLocalFiles: vi.fn(),
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    scanImportableFiles: mocks.scanImportableFiles,
    getCbzInfo: mocks.getCbzInfo,
    checkFilesExist: mocks.checkFilesExist,
    importLocalFiles: mocks.importLocalFiles,
  },
}))

import { LocalFileImportService } from '@/services/LocalFileImportService'

const file = (filePath: string): LocalFileParseItem => ({
  format: 'pdf',
  fileName: filePath.split('/').pop() ?? 'book.pdf',
  fileRef: asFileRef(filePath),
  displayPath: filePath,
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

describe('LocalFileImportService.scanAndParse', () => {
  test('同时扫描 PDF 和 CBZ，并读取 CBZ ComicInfo', async () => {
    mocks.scanImportableFiles.mockResolvedValue({
      files: [
        {
          format: 'cbz',
          ref: asFileRef('/books/999999.cbz'),
          fileName: '999999.cbz',
          displayPath: '/books/999999.cbz',
        },
      ],
    })
    mocks.getCbzInfo.mockResolvedValue({
      pageCount: 12,
      title: '第二话',
      series: '测试漫画',
      number: '2',
      authors: 'Alice',
      web: 'https://18comic.vip/album/123456',
      coverPage: 1,
    })
    const folder = asFolderRef('folder:path:/books')

    const result = await LocalFileImportService.scanAndParse(folder)

    expect(mocks.scanImportableFiles).toHaveBeenCalledWith(folder, ['pdf', 'cbz'])
    expect(mocks.getCbzInfo).toHaveBeenCalledWith(asFileRef('/books/999999.cbz'))
    expect(result.files[0]).toEqual(
      expect.objectContaining({
        format: 'cbz',
        extractedIds: ['123456'],
        chapterSortOrderHint: 2,
      }),
    )
  })
})

describe('LocalFileImportService.confirmImport', () => {
  test('全部文件缺失时返回汇总失败且不调用原生导入', async () => {
    mocks.checkFilesExist.mockResolvedValue({ existing: [] })

    const result = await LocalFileImportService.confirmImport([
      file('/pdf/a.pdf'),
      file('/pdf/b.pdf'),
    ])

    expect(result.errorCount).toBe(2)
    expect(result).toEqual({ imported: 0, skipped: 2, duplicateCount: 0, errorCount: 2 })
    expect(mocks.importLocalFiles).not.toHaveBeenCalled()
  })

  test('部分文件缺失时合并原生汇总数量', async () => {
    mocks.checkFilesExist.mockResolvedValue({ existing: ['/pdf/a.pdf'] })
    mocks.importLocalFiles.mockResolvedValue({
      imported: 1,
      skipped: 0,
      duplicateCount: 0,
      errorCount: 0,
      results: [
        {
          result: 'imported',
          file: { ref: asFileRef('/pdf/a.pdf'), fileName: 'a.pdf', displayPath: '/pdf/a.pdf' },
          id: 1,
        },
      ],
    })

    const result = await LocalFileImportService.confirmImport([
      file('/pdf/a.pdf'),
      file('/pdf/b.pdf'),
    ])

    expect(result).toEqual(expect.objectContaining({ imported: 1, skipped: 1, errorCount: 1 }))
    expect(result.results).toEqual([
      expect.objectContaining({
        result: 'imported',
        file: expect.objectContaining({ displayPath: '/pdf/a.pdf' }),
      }),
    ])
  })

  test('无法可靠对齐章节时不使用漫画 ID 伪造 chapterId', async () => {
    mocks.checkFilesExist.mockResolvedValue({ existing: ['/pdf/a.pdf'] })
    mocks.importLocalFiles.mockResolvedValue({
      imported: 1,
      skipped: 0,
      duplicateCount: 0,
      errorCount: 0,
      results: [
        {
          result: 'imported',
          file: { ref: asFileRef('/pdf/a.pdf'), fileName: 'a.pdf', displayPath: '/pdf/a.pdf' },
          id: 1,
        },
      ],
    })

    await LocalFileImportService.confirmImport([file('/pdf/a.pdf')])

    expect(mocks.importLocalFiles).toHaveBeenCalledWith([
      expect.objectContaining({
        albumId: '123456',
        chapterId: '',
        fileRef: asFileRef('/pdf/a.pdf'),
      }),
    ])
  })

  test('CBZ 缺少在线数据时使用 ComicInfo 元数据并保留格式', async () => {
    const cbz = file('/books/JM123456.cbz')
    cbz.format = 'cbz'
    cbz.albumDetail = null
    cbz.cbzInfo = {
      pageCount: 12,
      title: '第二话',
      series: '测试漫画',
      number: '2',
      authors: 'Alice',
      coverPage: 1,
    }
    mocks.checkFilesExist.mockResolvedValue({ existing: ['/books/JM123456.cbz'] })
    mocks.importLocalFiles.mockResolvedValue({
      imported: 1,
      skipped: 0,
      duplicateCount: 0,
      errorCount: 0,
    })

    await LocalFileImportService.confirmImport([cbz])

    expect(mocks.importLocalFiles).toHaveBeenCalledWith([
      expect.objectContaining({
        format: 'cbz',
        albumTitle: '测试漫画',
        authors: 'Alice',
      }),
    ])
  })

  test('扫描校验失败的 CBZ 不进入原生导入', async () => {
    const cbz = file('/books/JM123456.cbz')
    cbz.format = 'cbz'
    cbz.validationError = 'CBZ 无法打开'

    const result = await LocalFileImportService.confirmImport([cbz])

    expect(result).toEqual({ imported: 0, skipped: 1, duplicateCount: 0, errorCount: 0 })
    expect(mocks.checkFilesExist).not.toHaveBeenCalled()
    expect(mocks.importLocalFiles).not.toHaveBeenCalled()
  })
})
