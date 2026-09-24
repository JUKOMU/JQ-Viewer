import { beforeEach, describe, expect, test } from 'vitest'
import { createLocalReaderSession } from '@/services/LocalReaderSession'
import { ReadingProgressService } from '@/services/ReadingProgressService'

describe('LocalReaderSession', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  test('treats legacy reader parameters as chapter progress', () => {
    ReadingProgressService.recordChapter('album-a', 'chapter-a', 4, 12)

    const session = createLocalReaderSession({ albumId: 'album-a', chapterId: 'chapter-a' }, 12)

    expect(session.scope).toBe('chapter')
    expect(session.initialPage(undefined)).toBe(4)
    expect(session.physicalPage(4)).toBe(4)
  })

  test('maps logical chapter pages into a merged file', () => {
    const session = createLocalReaderSession(
      {
        fileId: '7',
        readingContext: 'chapter',
        albumId: 'album-a',
        chapterId: 'chapter-b',
        chapterStartPage: '6',
        chapterPageCount: '5',
      },
      20,
    )

    expect(session.scope).toBe('chapter')
    expect(session.totalPages).toBe(5)
    expect(session.physicalPage(3)).toBe(8)
  })

  test('keeps direct file progress separate from chapter progress', () => {
    ReadingProgressService.recordChapter('album-a', 'chapter-a', 4, 10)
    const session = createLocalReaderSession(
      {
        fileId: '7',
        readingContext: 'file',
        albumId: 'album-a',
        chapterId: 'chapter-a',
        chapterStartPage: '6',
        chapterPageCount: '10',
      },
      20,
    )

    expect(session.scope).toBe('file')
    expect(session.initialPage(undefined)).toBe(9)
    session.recordPage(12)
    expect(ReadingProgressService.getFile(7)?.page).toBe(12)
    expect(ReadingProgressService.getChapter('album-a', 'chapter-a')?.page).toBe(4)
  })
})
