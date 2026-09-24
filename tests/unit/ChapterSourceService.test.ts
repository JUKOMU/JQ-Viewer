import { beforeEach, describe, expect, test, vi } from 'vitest'
import type { LocalFileRecord } from '@/services/JmcomicTypes'

const mocks = vi.hoisted(() => ({
  getDownloadTasks: vi.fn(),
  getLocalFiles: vi.fn(),
  getDownloadedPhoto: vi.fn(),
  verifyLocalFile: vi.fn(),
  getCbzInfo: vi.fn(),
  getPdfInfo: vi.fn(),
}))

vi.mock('@/services/JmcomicService', () => ({ JmcomicService: mocks }))

import { ChapterSourceService } from '@/services/ChapterSourceService'

function file(id: number, format: 'pdf' | 'cbz', updatedAt: number): LocalFileRecord {
  return {
    id,
    format,
    fileRef: `file:path:/book-${id}.${format}` as LocalFileRecord['fileRef'],
    displayPath: `/book-${id}.${format}`,
    fileName: `book-${id}.${format}`,
    sourceType: 'exported',
    ownership: 'app_created',
    chapterLinkStatus: 'multi_chapter',
    albumId: 'album-1',
    albumTitle: 'Album',
    coverUrl: '',
    authors: 'Author',
    chapterTitle: 'Chapter',
    chapterSortOrder: 1,
    chapters: [
      {
        sequence: 0,
        albumId: 'album-1',
        chapterId: 'chapter-1',
        chapterTitle: 'Chapter',
        sortOrder: 1,
        startPage: 6,
        endPage: 10,
        pageCount: 5,
      },
    ],
    createdAt: updatedAt,
    fileSize: 100,
    pageCount: 20,
    availability: 'available',
    verificationStatus: 'valid',
    updatedAt,
  }
}

describe('ChapterSourceService', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.getDownloadTasks.mockResolvedValue({ tasks: [], usedBytes: 0, availableBytes: 0 })
    mocks.getDownloadedPhoto.mockRejectedValue(new Error('missing'))
    mocks.getCbzInfo.mockResolvedValue({ pageCount: 20, coverPage: 1 })
    mocks.getPdfInfo.mockResolvedValue({ pageCount: 20 })
  })

  test('uses completed downloads before archive files', async () => {
    mocks.getDownloadTasks.mockResolvedValue({
      tasks: [
        {
          taskId: 'download-1',
          albumId: 'album-1',
          chapterId: 'chapter-1',
          albumTitle: 'Album',
          chapterTitle: 'Chapter',
          coverUrl: '',
          totalPages: 5,
          downloadedPages: 5,
          status: 'completed',
          createdAt: 1,
        },
      ],
      usedBytes: 0,
      availableBytes: 0,
    })
    mocks.getLocalFiles.mockResolvedValue({ files: [file(1, 'pdf', 1), file(2, 'cbz', 2)] })
    mocks.getDownloadedPhoto.mockResolvedValue({ images: [{ sortOrder: 1 }] })

    await expect(ChapterSourceService.resolve('album-1', 'chapter-1')).resolves.toMatchObject({
      kind: 'download',
    })
  })

  test('tries same-format files newest first before falling through to PDF', async () => {
    const newest = file(3, 'cbz', 30)
    const older = file(2, 'cbz', 20)
    const pdf = file(1, 'pdf', 10)
    mocks.getLocalFiles.mockImplementation(async (options: { formats?: string[] }) => ({
      files:
        options.formats?.length === 1
          ? options.formats[0] === 'cbz'
            ? [older, newest]
            : [pdf]
          : [pdf, older, newest],
    }))
    mocks.verifyLocalFile.mockImplementation(async (id: number) => {
      if (id === newest.id) throw new Error('corrupt')
      return id === older.id ? older : pdf
    })

    const resolved = await ChapterSourceService.resolve('album-1', 'chapter-1')

    expect(resolved).toMatchObject({ kind: 'cbz', file: { id: older.id } })
    expect(mocks.verifyLocalFile.mock.calls.map(([id]) => id)).toEqual([newest.id, older.id])
    expect(mocks.getPdfInfo).not.toHaveBeenCalled()
  })

  test('falls through to PDF after every CBZ candidate is invalid', async () => {
    const newest = file(3, 'cbz', 30)
    const older = file(2, 'cbz', 20)
    const pdf = file(1, 'pdf', 10)
    mocks.getLocalFiles.mockImplementation(async (options: { formats?: string[] }) => ({
      files:
        options.formats?.length === 1
          ? options.formats[0] === 'cbz'
            ? [newest, older]
            : [pdf]
          : [pdf, older, newest],
    }))
    mocks.verifyLocalFile.mockImplementation(async (id: number) => {
      if (id === pdf.id) return pdf
      throw new Error('invalid archive')
    })

    const resolved = await ChapterSourceService.resolve('album-1', 'chapter-1')

    expect(resolved).toMatchObject({ kind: 'pdf', file: { id: pdf.id } })
    expect(mocks.verifyLocalFile.mock.calls.map(([id]) => id)).toEqual([
      newest.id,
      older.id,
      pdf.id,
    ])
    expect(mocks.getPdfInfo).toHaveBeenCalledWith(pdf.fileRef)
  })

  test('falls back from a missing download to CBZ', async () => {
    const cbz = file(2, 'cbz', 20)
    mocks.getDownloadTasks.mockResolvedValue({
      tasks: [
        {
          taskId: 'download-1',
          albumId: 'album-1',
          chapterId: 'chapter-1',
          albumTitle: 'Album',
          chapterTitle: 'Chapter',
          coverUrl: '',
          totalPages: 5,
          downloadedPages: 5,
          status: 'completed',
          createdAt: 1,
        },
      ],
      usedBytes: 0,
      availableBytes: 0,
    })
    mocks.getLocalFiles.mockResolvedValue({ files: [cbz] })
    mocks.verifyLocalFile.mockResolvedValue(cbz)

    await expect(ChapterSourceService.resolve('album-1', 'chapter-1')).resolves.toMatchObject({
      kind: 'cbz',
      file: { id: cbz.id },
    })
  })

  test('maps merged local chapter pages into reader parameters', () => {
    const cbz = file(7, 'cbz', 1)
    const location = ChapterSourceService.readerLocation(
      { kind: 'cbz', file: cbz, chapter: cbz.chapters[0] },
      {
        albumId: 'album-1',
        albumTitle: 'Album',
        chapterId: 'chapter-1',
        chapterTitle: 'Chapter',
        authors: 'Author',
        coverUrl: '',
      },
      3,
    )

    expect(location).toMatchObject({
      path: '/cbz-reader',
      query: {
        fileId: '7',
        readingContext: 'chapter',
        chapterStartPage: '6',
        chapterPageCount: '5',
        page: '3',
      },
    })
  })

  test('direct file resolution never falls back to another file', async () => {
    const target = file(9, 'pdf', 1)
    mocks.getLocalFiles.mockResolvedValue({ files: [target] })
    mocks.verifyLocalFile.mockRejectedValue(new Error('missing'))

    await expect(ChapterSourceService.resolveFile(9)).resolves.toBeNull()
    expect(mocks.getLocalFiles).toHaveBeenCalledWith({ fileId: 9, limit: 1 })
  })
})
